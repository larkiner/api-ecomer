-- ============================================================================
-- 16_pkg_cola.sql
-- Seccion 5 del diseno (parte 9): paquete PKG_COLA.
-- Operaciones de la cola de eventos COLA_EVENTO (patron outbox).
--
-- Va ANTES que PKG_PAGO porque PKG_PAGO encola en la misma transaccion en que
-- registra la notificacion de la pasarela.
-- Requiere haber ejecutado antes los scripts 07 y 09.
--
-- VERSION 2: agrega completar_erp. Si ya ejecutaste la version anterior, vuelve a
-- ejecutar este script completo (CREATE OR REPLACE; las pruebas no dejan datos).
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1 (SELECT USER FROM dual).
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Al final sale una linea "OK" por cada prueba y un resumen. Las pruebas
--      crean datos temporales y los deshacen con ROLLBACK: no quedan en la base.
--   4. Para deshacerlo: DROP PACKAGE PKG_COLA;
--
-- COMO LO USA UN CONSUMIDOR (backend o JOB)
--   1. c := PKG_COLA.tomar_lote('ENVIAR_CORREO', 10);  y COMMIT enseguida.
--      El COMMIT deja los eventos en PROCESANDO y libera los bloqueos; asi el
--      trabajo lento (enviar el correo, llamar al ERP) no retiene filas.
--   2. Por cada evento: hace el trabajo y llama a completar(id) o a
--      fallar(id, 'mensaje'), y COMMIT.
--
-- DECISIONES DE DISENO
--   * Idempotencia al encolar: si la CLAVE_IDEMPOTENCIA ya existe, encolar
--     devuelve el ID del evento existente y no da error. Asi el productor puede
--     reintentar sin duplicar eventos.
--   * Entrega "al menos una vez": un evento puede procesarse dos veces (por
--     ejemplo, si el consumidor se cae despues de enviar el correo y antes de
--     llamar a completar). El consumidor debe tolerarlo.
--   * tomar_lote usa FOR UPDATE SKIP LOCKED: varios consumidores a la vez nunca
--     toman el mismo evento; cada uno se salta las filas que otro tiene
--     bloqueadas. NO lleva ORDER BY a proposito: con ORDER BY, Oracle tendria
--     que ordenar (y bloquear) todos los candidatos antes de entregar el primero;
--     sin el, solo bloquea las filas que va leyendo hasta llegar al limite. El
--     indice (ESTADO, PROXIMO_INTENTO) hace que en la practica salgan primero los
--     mas antiguos, pero el orden no esta garantizado y no hace falta.
--   * INTENTOS se suma al TOMAR el evento, no al fallar. Asi cuenta tambien los
--     intentos en que el consumidor se cayo sin avisar.
--   * fallar reprograma con espera creciente: 1, 2, 4, 8 minutos
--     (2 ^ (INTENTOS - 1)). Al llegar a COLA_MAX_INTENTOS el evento pasa a ERROR
--     y se revisa a mano; reactivar lo devuelve a PENDIENTE con INTENTOS en 0.
--   * completar y fallar solo actuan sobre eventos PROCESANDO (UPDATE
--     condicional). Si el evento ya no lo esta (lo rescato el JOB o ya se
--     completo), fallan con -20204 en vez de pisar el estado.
--   * rescatar_atascados y purgar_procesados son para PKG_TAREAS (JOBs).
--   * completar_erp: el consumidor de SINCRONIZAR_ERP guarda el id que asigno el
--     ERP (PEDIDO_ERP) y completa el evento en UNA transaccion. Si lo hiciera en
--     dos pasos y se cayera en medio, el pedido quedaria sincronizado en el ERP
--     pero no en la base, y se enviaria otra vez. Es idempotente: recibir otra vez
--     el mismo id ERP para el mismo pedido no es error (entrega "al menos una vez").
--   * PAGO_NOTIFICADO lo consume el JOB de la base de datos, no el backend. El
--     rol de integracion podria tomarlo tecnicamente (el EXECUTE es por paquete),
--     pero ese rol ya puede registrar notificaciones de pago: el control real es
--     que el backend verifique la firma de la pasarela. Riesgo aceptado.
--   * Ningun procedimiento hace COMMIT: confirma quien lo llama.
--   * Errores (bloque -20200 a -20219, compartido con PKG_TAREAS):
--       -20200 tipo de evento invalido
--       -20201 el payload esta vacio o no es JSON valido
--       -20202 la clave de idempotencia esta vacia
--       -20203 limite de lote invalido (1 a 100)
--       -20204 el evento no existe o no esta PROCESANDO
--       -20205 el evento no existe o no esta en ERROR
--       -20206 el id ERP esta vacio
--       -20207 el pedido ya tiene otro id ERP, o ese id ERP ya es de otro pedido
--       -20208 el pedido no existe
--       -20209 el evento no es la sincronizacion de ese pedido
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

PROMPT >> 16_pkg_cola.sql: comprobando requisitos

DECLARE
  v_existentes NUMBER;
BEGIN
  IF SYS_CONTEXT('USERENV', 'SESSION_USER') <> 'ECOMERS'
     OR SYS_CONTEXT('USERENV', 'CON_NAME') <> 'XEPDB1' THEN
    RAISE_APPLICATION_ERROR(-20001,
      'Conectate como ECOMERS al servicio XEPDB1 antes de ejecutar este script.');
  END IF;

  SELECT COUNT(*) INTO v_existentes
    FROM PARAMETRO
   WHERE CLAVE IN ('COLA_MAX_INTENTOS', 'COLA_RETENCION_DIAS', 'COLA_PROCESANDO_MAX_MINUTOS');
  IF v_existentes < 3 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan parametros de la cola: revisa el script 07.');
  END IF;

  SELECT COUNT(*) INTO v_existentes
    FROM user_objects
   WHERE object_name = 'PKG_UTILIDADES'
     AND object_type = 'PACKAGE BODY'
     AND status = 'VALID';
  IF v_existentes < 1 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Falta PKG_UTILIDADES valido: ejecuta antes el script 09.');
  END IF;
END;
/

CREATE OR REPLACE PACKAGE PKG_COLA
  AUTHID DEFINER
AS
  c_pago_notificado CONSTANT VARCHAR2(30) := 'PAGO_NOTIFICADO';
  c_enviar_correo   CONSTANT VARCHAR2(30) := 'ENVIAR_CORREO';
  c_sincronizar_erp CONSTANT VARCHAR2(30) := 'SINCRONIZAR_ERP';

  -- Encola un evento y devuelve su ID. Si la clave ya existe, devuelve el ID del
  -- evento existente sin crear otro.
  -- Errores: -20200, -20201, -20202.
  FUNCTION encolar(
    p_tipo_evento IN VARCHAR2,
    p_clave       IN VARCHAR2,
    p_payload     IN CLOB
  ) RETURN NUMBER;

  -- Toma hasta p_limite eventos PENDIENTE del tipo cuyo PROXIMO_INTENTO ya llego,
  -- los pasa a PROCESANDO y los devuelve. Columnas del cursor:
  --   ID_COLA_EVENTO, TIPO_EVENTO, CLAVE_IDEMPOTENCIA, PAYLOAD, INTENTOS
  -- Quien llama debe hacer COMMIT enseguida.
  -- Errores: -20200, -20203.
  FUNCTION tomar_lote(p_tipo_evento IN VARCHAR2, p_limite IN NUMBER DEFAULT 10) RETURN SYS_REFCURSOR;

  -- El evento se proceso bien. Error -20204.
  PROCEDURE completar(p_id_cola_evento IN NUMBER);

  -- El evento fallo: se reprograma con espera creciente o pasa a ERROR.
  -- Error -20204.
  PROCEDURE fallar(p_id_cola_evento IN NUMBER, p_error IN VARCHAR2);

  -- Devuelve a PENDIENTE (con INTENTOS en 0) un evento en ERROR ya revisado.
  -- Error -20205.
  PROCEDURE reactivar(p_id_cola_evento IN NUMBER);

  -- Devuelve a PENDIENTE (o a ERROR si agoto los intentos) los eventos que llevan
  -- mas de COLA_PROCESANDO_MAX_MINUTOS en PROCESANDO. Devuelve cuantos rescato.
  FUNCTION rescatar_atascados RETURN NUMBER;

  -- Borra los eventos PROCESADO hace mas de COLA_RETENCION_DIAS. Devuelve cuantos.
  FUNCTION purgar_procesados RETURN NUMBER;

  -- Consumidor de SINCRONIZAR_ERP: registra el id que asigno el ERP al pedido y
  -- completa el evento, todo junto.
  -- Errores: -20204, -20206, -20207, -20208, -20209.
  PROCEDURE completar_erp(p_id_cola_evento IN NUMBER, p_id_pedido IN NUMBER, p_id_erp IN VARCHAR2);
END PKG_COLA;
/
SHOW ERRORS PACKAGE PKG_COLA
PROMPT >> PKG_COLA: especificacion creada

CREATE OR REPLACE PACKAGE BODY PKG_COLA
AS
  e_check EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_check, -2290);   -- ORA-02290: viola un CHECK

  e_sin_padre EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_sin_padre, -2291);   -- ORA-02291: no existe la fila padre (FK)

  -- ---------------------------------------------------------------------------
  -- Privados
  -- ---------------------------------------------------------------------------
  PROCEDURE validar_tipo(p_tipo_evento IN VARCHAR2) IS
  BEGIN
    IF p_tipo_evento IS NULL
       OR p_tipo_evento NOT IN (c_pago_notificado, c_enviar_correo, c_sincronizar_erp) THEN
      RAISE_APPLICATION_ERROR(-20200, 'Tipo de evento invalido: ' || NVL(p_tipo_evento, '(vacio)') || '.');
    END IF;
  END validar_tipo;

  -- ---------------------------------------------------------------------------
  -- Publicos
  -- ---------------------------------------------------------------------------
  FUNCTION encolar(
    p_tipo_evento IN VARCHAR2,
    p_clave       IN VARCHAR2,
    p_payload     IN CLOB
  ) RETURN NUMBER IS
    v_id COLA_EVENTO.ID_COLA_EVENTO%TYPE;
  BEGIN
    validar_tipo(p_tipo_evento);
    IF TRIM(p_clave) IS NULL THEN
      RAISE_APPLICATION_ERROR(-20202, 'La clave de idempotencia es obligatoria.');
    END IF;
    IF p_payload IS NULL OR DBMS_LOB.GETLENGTH(p_payload) = 0 THEN
      RAISE_APPLICATION_ERROR(-20201, 'El payload del evento esta vacio.');
    END IF;

    BEGIN
      INSERT INTO COLA_EVENTO (TIPO_EVENTO, CLAVE_IDEMPOTENCIA, PAYLOAD, PROXIMO_INTENTO)
      VALUES (p_tipo_evento, p_clave, p_payload, PKG_UTILIDADES.ahora)
      RETURNING ID_COLA_EVENTO INTO v_id;
    EXCEPTION
      WHEN DUP_VAL_ON_INDEX THEN
        -- Ya estaba encolado: se devuelve el existente.
        SELECT ID_COLA_EVENTO INTO v_id
          FROM COLA_EVENTO
         WHERE CLAVE_IDEMPOTENCIA = p_clave;
      WHEN e_check THEN
        -- El tipo ya se valido arriba: el unico CHECK que puede fallar es el JSON.
        RAISE_APPLICATION_ERROR(-20201, 'El payload del evento no es JSON valido.');
    END;

    RETURN v_id;
  END encolar;

  FUNCTION tomar_lote(p_tipo_evento IN VARCHAR2, p_limite IN NUMBER DEFAULT 10) RETURN SYS_REFCURSOR IS
    CURSOR c_candidatos(cp_ahora IN TIMESTAMP) IS
      SELECT ID_COLA_EVENTO
        FROM COLA_EVENTO
       WHERE TIPO_EVENTO = p_tipo_evento
         AND ESTADO = 'PENDIENTE'
         AND PROXIMO_INTENTO <= cp_ahora
         FOR UPDATE SKIP LOCKED;
    v_ahora  TIMESTAMP;
    v_ids    SYS.ODCINUMBERLIST;
    v_cursor SYS_REFCURSOR;
  BEGIN
    validar_tipo(p_tipo_evento);
    IF p_limite IS NULL OR p_limite < 1 OR p_limite > 100 OR p_limite <> TRUNC(p_limite) THEN
      RAISE_APPLICATION_ERROR(-20203, 'El limite del lote debe ser un entero entre 1 y 100.');
    END IF;

    v_ahora := PKG_UTILIDADES.ahora;

    OPEN c_candidatos(v_ahora);
    FETCH c_candidatos BULK COLLECT INTO v_ids LIMIT p_limite;
    CLOSE c_candidatos;

    IF v_ids.COUNT > 0 THEN
      UPDATE COLA_EVENTO
         SET ESTADO        = 'PROCESANDO',
             FECHA_BLOQUEO = v_ahora,
             INTENTOS      = INTENTOS + 1
       WHERE ID_COLA_EVENTO IN (SELECT COLUMN_VALUE FROM TABLE(v_ids));
    END IF;

    OPEN v_cursor FOR
      SELECT ID_COLA_EVENTO, TIPO_EVENTO, CLAVE_IDEMPOTENCIA, PAYLOAD, INTENTOS
        FROM COLA_EVENTO
       WHERE ID_COLA_EVENTO IN (SELECT COLUMN_VALUE FROM TABLE(v_ids))
       ORDER BY ID_COLA_EVENTO;
    RETURN v_cursor;
  END tomar_lote;

  PROCEDURE completar(p_id_cola_evento IN NUMBER) IS
    v_ahora TIMESTAMP;
  BEGIN
    v_ahora := PKG_UTILIDADES.ahora;
    UPDATE COLA_EVENTO
       SET ESTADO          = 'PROCESADO',
           FECHA_PROCESADO = v_ahora
     WHERE ID_COLA_EVENTO = p_id_cola_evento
       AND ESTADO = 'PROCESANDO';
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20204,
        'El evento ' || p_id_cola_evento || ' no existe o no esta PROCESANDO.');
    END IF;
  END completar;

  PROCEDURE fallar(p_id_cola_evento IN NUMBER, p_error IN VARCHAR2) IS
    v_ahora  TIMESTAMP;
    v_maximo NUMBER;
  BEGIN
    v_ahora  := PKG_UTILIDADES.ahora;
    v_maximo := PKG_UTILIDADES.valor_parametro('COLA_MAX_INTENTOS');

    UPDATE COLA_EVENTO
       SET ESTADO          = CASE WHEN INTENTOS >= v_maximo THEN 'ERROR' ELSE 'PENDIENTE' END,
           PROXIMO_INTENTO = CASE WHEN INTENTOS >= v_maximo THEN PROXIMO_INTENTO
                                  ELSE v_ahora + NUMTODSINTERVAL(POWER(2, GREATEST(INTENTOS - 1, 0)), 'MINUTE')
                             END,
           FECHA_BLOQUEO   = NULL,
           ULTIMO_ERROR    = NVL(SUBSTR(TRIM(p_error), 1, 4000), 'Sin detalle del error')
     WHERE ID_COLA_EVENTO = p_id_cola_evento
       AND ESTADO = 'PROCESANDO';
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20204,
        'El evento ' || p_id_cola_evento || ' no existe o no esta PROCESANDO.');
    END IF;
  END fallar;

  PROCEDURE reactivar(p_id_cola_evento IN NUMBER) IS
    v_ahora TIMESTAMP;
  BEGIN
    v_ahora := PKG_UTILIDADES.ahora;
    UPDATE COLA_EVENTO
       SET ESTADO          = 'PENDIENTE',
           INTENTOS        = 0,
           PROXIMO_INTENTO = v_ahora
     WHERE ID_COLA_EVENTO = p_id_cola_evento
       AND ESTADO = 'ERROR';
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20205,
        'El evento ' || p_id_cola_evento || ' no existe o no esta en ERROR.');
    END IF;
  END reactivar;

  FUNCTION rescatar_atascados RETURN NUMBER IS
    v_ahora   TIMESTAMP;
    v_maximo  NUMBER;
    v_minutos NUMBER;
  BEGIN
    v_ahora   := PKG_UTILIDADES.ahora;
    v_maximo  := PKG_UTILIDADES.valor_parametro('COLA_MAX_INTENTOS');
    v_minutos := PKG_UTILIDADES.valor_parametro('COLA_PROCESANDO_MAX_MINUTOS');

    UPDATE COLA_EVENTO
       SET ESTADO          = CASE WHEN INTENTOS >= v_maximo THEN 'ERROR' ELSE 'PENDIENTE' END,
           PROXIMO_INTENTO = v_ahora,
           FECHA_BLOQUEO   = NULL,
           ULTIMO_ERROR    = 'Rescatado: llevaba mas de ' || v_minutos || ' minutos en PROCESANDO'
     WHERE ESTADO = 'PROCESANDO'
       AND FECHA_BLOQUEO < v_ahora - NUMTODSINTERVAL(v_minutos, 'MINUTE');
    RETURN SQL%ROWCOUNT;
  END rescatar_atascados;

  FUNCTION purgar_procesados RETURN NUMBER IS
    v_ahora TIMESTAMP;
    v_dias  NUMBER;
  BEGIN
    v_ahora := PKG_UTILIDADES.ahora;
    v_dias  := PKG_UTILIDADES.valor_parametro('COLA_RETENCION_DIAS');

    DELETE FROM COLA_EVENTO
     WHERE ESTADO = 'PROCESADO'
       AND FECHA_PROCESADO < v_ahora - NUMTODSINTERVAL(v_dias, 'DAY');
    RETURN SQL%ROWCOUNT;
  END purgar_procesados;

  PROCEDURE completar_erp(p_id_cola_evento IN NUMBER, p_id_pedido IN NUMBER, p_id_erp IN VARCHAR2) IS
    v_clave  COLA_EVENTO.CLAVE_IDEMPOTENCIA%TYPE;
    v_actual PEDIDO_ERP.ID_ERP%TYPE;
  BEGIN
    SAVEPOINT sp_completar_erp;

    IF TRIM(p_id_erp) IS NULL THEN
      RAISE_APPLICATION_ERROR(-20206, 'El id que asigno el ERP es obligatorio.');
    END IF;

    BEGIN
      SELECT CLAVE_IDEMPOTENCIA INTO v_clave
        FROM COLA_EVENTO
       WHERE ID_COLA_EVENTO = p_id_cola_evento;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        v_clave := NULL;
    END;
    IF v_clave IS NULL OR v_clave <> 'SINCRONIZAR_ERP:PEDIDO:' || p_id_pedido THEN
      RAISE_APPLICATION_ERROR(-20209,
        'El evento ' || p_id_cola_evento || ' no es la sincronizacion del pedido ' || p_id_pedido || '.');
    END IF;

    BEGIN
      INSERT INTO PEDIDO_ERP (ID_PEDIDO, ID_ERP) VALUES (p_id_pedido, p_id_erp);
    EXCEPTION
      WHEN DUP_VAL_ON_INDEX THEN
        -- Ya estaba: es una reentrega si el id ERP es el mismo.
        BEGIN
          SELECT ID_ERP INTO v_actual FROM PEDIDO_ERP WHERE ID_PEDIDO = p_id_pedido;
        EXCEPTION
          WHEN NO_DATA_FOUND THEN
            v_actual := NULL;   -- el duplicado es el ID_ERP, que ya es de otro pedido
        END;
        IF v_actual IS NULL OR v_actual <> p_id_erp THEN
          RAISE_APPLICATION_ERROR(-20207,
            'Conflicto de id ERP para el pedido ' || p_id_pedido || ': ya hay ' ||
            NVL(v_actual, 'otro pedido con ' || p_id_erp) || '.');
        END IF;
      WHEN e_sin_padre THEN
        RAISE_APPLICATION_ERROR(-20208, 'El pedido ' || p_id_pedido || ' no existe.');
    END;

    completar(p_id_cola_evento);
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_completar_erp;
      RAISE;
  END completar_erp;
END PKG_COLA;
/
SHOW ERRORS PACKAGE BODY PKG_COLA
PROMPT >> PKG_COLA: cuerpo creado

-- CREATE PACKAGE no se detiene ante errores de compilacion (solo avisa), asi que
-- se revisa USER_ERRORS de forma explicita.
DECLARE
  v_errores NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_errores
    FROM user_errors
   WHERE name = 'PKG_COLA';
  IF v_errores > 0 THEN
    RAISE_APPLICATION_ERROR(-20004,
      'PKG_COLA compilo con errores: consulta USER_ERRORS.');
  END IF;
END;
/
PROMPT >> PKG_COLA: paquete compilado sin errores

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT object_name, object_type, status
  FROM user_objects
 WHERE object_name = 'PKG_COLA'
 ORDER BY object_type;                                                       -- PACKAGE VALID, PACKAGE BODY VALID

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';                                                    -- 0 filas

-- ============================================================================
-- PRUEBAS RAPIDAS (terminan con ROLLBACK: no dejan datos).
-- Esperado: 25 lineas "OK" y al final "0 fallos".
-- Suponen que la cola esta vacia (aun no hay aplicacion conectada).
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  v_pruebas   NUMBER := 0;
  v_fallos    NUMBER := 0;
  v_e1        NUMBER;
  v_e2        NUMBER;
  v_e3        NUMBER;
  v_e4        NUMBER;   -- otro tipo
  v_e5        NUMBER;   -- programado para el futuro
  v_id        NUMBER;
  v_lote1     SYS.ODCINUMBERLIST;
  v_lote2     SYS.ODCINUMBERLIST;
  v_n         NUMBER;
  v_maximo    NUMBER;
  v_minutos   NUMBER;
  v_dias      NUMBER;
  v_ok        BOOLEAN;
  v_cliente   NUMBER;
  v_pedido    NUMBER;
  v_erp       NUMBER;
  v_tipo_doc  NUMBER;
  v_estado_p  NUMBER;
  -- Fila leida de COLA_EVENTO
  v_estado    COLA_EVENTO.ESTADO%TYPE;
  v_intentos  COLA_EVENTO.INTENTOS%TYPE;
  v_proximo   COLA_EVENTO.PROXIMO_INTENTO%TYPE;
  v_bloqueo   COLA_EVENTO.FECHA_BLOQUEO%TYPE;
  v_procesado COLA_EVENTO.FECHA_PROCESADO%TYPE;
  v_error     COLA_EVENTO.ULTIMO_ERROR%TYPE;

  PROCEDURE verificar(p_nombre IN VARCHAR2, p_ok IN BOOLEAN) IS
  BEGIN
    v_pruebas := v_pruebas + 1;
    IF p_ok THEN
      DBMS_OUTPUT.PUT_LINE(v_pruebas || ' OK: ' || p_nombre);
    ELSE
      v_fallos := v_fallos + 1;
      DBMS_OUTPUT.PUT_LINE(v_pruebas || ' FALLO: ' || p_nombre);
    END IF;
  END verificar;

  -- Ejecuta un bloque PL/SQL y comprueba que falle con el codigo esperado.
  PROCEDURE esperar_error(p_nombre IN VARCHAR2, p_bloque IN VARCHAR2, p_codigo IN NUMBER) IS
  BEGIN
    EXECUTE IMMEDIATE p_bloque;
    verificar(p_nombre || ' (debia fallar con ' || p_codigo || ')', FALSE);
  EXCEPTION
    WHEN OTHERS THEN
      verificar(p_nombre || ' -> ' || p_codigo, SQLCODE = p_codigo);
  END esperar_error;

  PROCEDURE leer(p_id IN NUMBER) IS
  BEGIN
    SELECT ESTADO, INTENTOS, PROXIMO_INTENTO, FECHA_BLOQUEO, FECHA_PROCESADO, ULTIMO_ERROR
      INTO v_estado, v_intentos, v_proximo, v_bloqueo, v_procesado, v_error
      FROM COLA_EVENTO
     WHERE ID_COLA_EVENTO = p_id;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      v_estado := NULL;
  END leer;

  -- Llama a tomar_lote y devuelve los IDs que entrego el cursor.
  FUNCTION tomar(p_tipo IN VARCHAR2, p_limite IN NUMBER) RETURN SYS.ODCINUMBERLIST IS
    c          SYS_REFCURSOR;
    v_id_ev    NUMBER;
    v_tipo     VARCHAR2(30);
    v_clave    VARCHAR2(200);
    v_payload  CLOB;
    v_int      NUMBER;
    v_lista    SYS.ODCINUMBERLIST := SYS.ODCINUMBERLIST();
  BEGIN
    c := PKG_COLA.tomar_lote(p_tipo, p_limite);
    LOOP
      FETCH c INTO v_id_ev, v_tipo, v_clave, v_payload, v_int;
      EXIT WHEN c%NOTFOUND;
      v_lista.EXTEND;
      v_lista(v_lista.COUNT) := v_id_ev;
    END LOOP;
    CLOSE c;
    RETURN v_lista;
  END tomar;

  FUNCTION contiene(p_lista IN SYS.ODCINUMBERLIST, p_id IN NUMBER) RETURN BOOLEAN IS
  BEGIN
    FOR i IN 1 .. p_lista.COUNT LOOP
      IF p_lista(i) = p_id THEN
        RETURN TRUE;
      END IF;
    END LOOP;
    RETURN FALSE;
  END contiene;

  -- Pone un evento en PROCESANDO con los intentos indicados (para simular).
  PROCEDURE simular_procesando(p_id IN NUMBER, p_intentos IN NUMBER) IS
  BEGIN
    UPDATE COLA_EVENTO
       SET ESTADO = 'PROCESANDO', FECHA_BLOQUEO = PKG_UTILIDADES.ahora, INTENTOS = p_intentos
     WHERE ID_COLA_EVENTO = p_id;
  END simular_procesando;
BEGIN
  v_maximo  := PKG_UTILIDADES.valor_parametro('COLA_MAX_INTENTOS');
  v_minutos := PKG_UTILIDADES.valor_parametro('COLA_PROCESANDO_MAX_MINUTOS');
  v_dias    := PKG_UTILIDADES.valor_parametro('COLA_RETENCION_DIAS');

  -- Encolar
  v_e1 := PKG_COLA.encolar(PKG_COLA.c_enviar_correo, 'PRUEBA:CORREO:1', '{"para":"a@ejemplo.com"}');
  leer(v_e1);
  verificar('encolar crea el evento PENDIENTE con 0 intentos', v_estado = 'PENDIENTE' AND v_intentos = 0);

  v_id := PKG_COLA.encolar(PKG_COLA.c_enviar_correo, 'PRUEBA:CORREO:1', '{"para":"a@ejemplo.com"}');
  SELECT COUNT(*) INTO v_n FROM COLA_EVENTO WHERE CLAVE_IDEMPOTENCIA = 'PRUEBA:CORREO:1';
  verificar('encolar la misma clave devuelve el mismo evento sin duplicarlo', v_id = v_e1 AND v_n = 1);

  esperar_error('tipo de evento invalido',
    'DECLARE x NUMBER; BEGIN x := PKG_COLA.encolar(''OTRO'', ''PRUEBA:X'', ''{}''); END;', -20200);
  esperar_error('payload que no es JSON',
    'DECLARE x NUMBER; BEGIN x := PKG_COLA.encolar(''ENVIAR_CORREO'', ''PRUEBA:X'', ''no es json''); END;', -20201);
  esperar_error('clave vacia',
    'DECLARE x NUMBER; BEGIN x := PKG_COLA.encolar(''ENVIAR_CORREO'', ''  '', ''{}''); END;', -20202);
  esperar_error('limite de lote invalido',
    'DECLARE c SYS_REFCURSOR; BEGIN c := PKG_COLA.tomar_lote(''ENVIAR_CORREO'', 0); END;', -20203);

  v_e2 := PKG_COLA.encolar(PKG_COLA.c_enviar_correo,   'PRUEBA:CORREO:2', '{}');
  v_e3 := PKG_COLA.encolar(PKG_COLA.c_enviar_correo,   'PRUEBA:CORREO:3', '{}');
  v_e4 := PKG_COLA.encolar(PKG_COLA.c_sincronizar_erp, 'PRUEBA:ERP:1',    '{}');
  v_e5 := PKG_COLA.encolar(PKG_COLA.c_enviar_correo,   'PRUEBA:CORREO:5', '{}');
  UPDATE COLA_EVENTO SET PROXIMO_INTENTO = PKG_UTILIDADES.ahora + INTERVAL '1' HOUR
   WHERE ID_COLA_EVENTO = v_e5;

  -- Tomar
  v_lote1 := tomar(PKG_COLA.c_enviar_correo, 2);
  v_ok := v_lote1.COUNT = 2;
  FOR i IN 1 .. v_lote1.COUNT LOOP
    leer(v_lote1(i));
    v_ok := v_ok AND v_estado = 'PROCESANDO' AND v_bloqueo IS NOT NULL;
  END LOOP;
  verificar('tomar_lote con limite 2 entrega 2 eventos y los deja PROCESANDO', v_ok);

  v_lote2 := tomar(PKG_COLA.c_enviar_correo, 100);
  v_ok := TRUE;
  FOR i IN 1 .. v_lote2.COUNT LOOP
    v_ok := v_ok AND NOT contiene(v_lote1, v_lote2(i));
  END LOOP;
  verificar('un segundo lote no repite eventos ya tomados', v_ok);

  verificar('entre los dos lotes se tomaron los 3 correos disponibles',
            (contiene(v_lote1, v_e1) OR contiene(v_lote2, v_e1))
            AND (contiene(v_lote1, v_e2) OR contiene(v_lote2, v_e2))
            AND (contiene(v_lote1, v_e3) OR contiene(v_lote2, v_e3)));

  leer(v_e5);
  v_ok := v_estado = 'PENDIENTE';
  leer(v_e4);
  verificar('no se toman eventos futuros ni de otro tipo', v_ok AND v_estado = 'PENDIENTE');

  -- Completar
  PKG_COLA.completar(v_e1);
  leer(v_e1);
  verificar('completar deja el evento PROCESADO con fecha', v_estado = 'PROCESADO' AND v_procesado IS NOT NULL);
  esperar_error('completar dos veces',
    'BEGIN PKG_COLA.completar(' || v_e1 || '); END;', -20204);
  esperar_error('fallar un evento que no esta PROCESANDO',
    'BEGIN PKG_COLA.fallar(' || v_e4 || ', ''x''); END;', -20204);

  -- Fallar y reintentos
  PKG_COLA.fallar(v_e2, 'SMTP no responde');
  leer(v_e2);
  verificar('primer fallo: PENDIENTE de nuevo en 1 minuto, con el error guardado',
            v_estado = 'PENDIENTE' AND v_bloqueo IS NULL AND v_error = 'SMTP no responde'
            AND v_proximo BETWEEN PKG_UTILIDADES.ahora + NUMTODSINTERVAL(50, 'SECOND')
                              AND PKG_UTILIDADES.ahora + INTERVAL '1' MINUTE);

  simular_procesando(v_e2, 3);
  PKG_COLA.fallar(v_e2, 'SMTP no responde');
  leer(v_e2);
  verificar('tercer fallo: espera 4 minutos',
            v_estado = 'PENDIENTE'
            AND v_proximo BETWEEN PKG_UTILIDADES.ahora + NUMTODSINTERVAL(230, 'SECOND')
                              AND PKG_UTILIDADES.ahora + INTERVAL '4' MINUTE);

  simular_procesando(v_e2, v_maximo);
  PKG_COLA.fallar(v_e2, 'SMTP no responde');
  leer(v_e2);
  verificar('al agotar COLA_MAX_INTENTOS (' || v_maximo || ') el evento pasa a ERROR', v_estado = 'ERROR');

  PKG_COLA.reactivar(v_e2);
  leer(v_e2);
  verificar('reactivar devuelve el evento a PENDIENTE con 0 intentos',
            v_estado = 'PENDIENTE' AND v_intentos = 0);
  esperar_error('reactivar un evento que no esta en ERROR',
    'BEGIN PKG_COLA.reactivar(' || v_e2 || '); END;', -20205);

  -- Rescatar atascados
  UPDATE COLA_EVENTO
     SET FECHA_BLOQUEO = PKG_UTILIDADES.ahora - NUMTODSINTERVAL(v_minutos + 1, 'MINUTE')
   WHERE ID_COLA_EVENTO = v_e3;
  v_n := PKG_COLA.rescatar_atascados;
  leer(v_e3);
  verificar('rescatar_atascados devuelve a PENDIENTE lo que lleva demasiado en PROCESANDO',
            v_n >= 1 AND v_estado = 'PENDIENTE' AND v_error LIKE 'Rescatado%');

  -- Sincronizacion con el ERP (completar_erp). Pedido minimo de prueba.
  SELECT ID_TIPO_DOCUMENTO INTO v_tipo_doc FROM TIPO_DOCUMENTO WHERE CODIGO = 'CC';
  SELECT ID_ESTADO_PEDIDO  INTO v_estado_p FROM ESTADO_PEDIDO  WHERE CODIGO = 'PENDIENTE_PAGO';
  INSERT INTO DEPARTAMENTO (CODIGO_DANE, NOMBRE) VALUES ('00', 'DEPARTAMENTO PRUEBA');
  INSERT INTO MUNICIPIO (CODIGO_DANE, CODIGO_DEPARTAMENTO, NOMBRE)
    VALUES ('00000', '00', 'MUNICIPIO PRUEBA');
  INSERT INTO CLIENTE (ID_TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRE, EMAIL)
    VALUES (v_tipo_doc, '9000000041', 'Cliente prueba cola', 'prueba.cola@ejemplo.com')
    RETURNING ID_CLIENTE INTO v_cliente;
  INSERT INTO PEDIDO (ID_CLIENTE, ID_ESTADO_PEDIDO, FECHA_EXPIRACION,
                      FACT_TIPO_DOCUMENTO, FACT_NUMERO_DOCUMENTO, FACT_NOMBRE, FACT_EMAIL,
                      FACT_DIRECCION, FACT_CODIGO_MUNICIPIO,
                      SUBTOTAL, DESCUENTO_TOTAL, IMPUESTO_TOTAL, TOTAL)
  VALUES (v_cliente, v_estado_p, PKG_UTILIDADES.ahora + INTERVAL '30' MINUTE,
          'CC', '9000000041', 'Cliente prueba cola', 'prueba.cola@ejemplo.com',
          'Calle 1 # 2-3', '00000', 100, 0, 19, 119)
  RETURNING ID_PEDIDO INTO v_pedido;

  v_erp := PKG_COLA.encolar(PKG_COLA.c_sincronizar_erp, 'SINCRONIZAR_ERP:PEDIDO:' || v_pedido,
                            '{"id_pedido":' || v_pedido || '}');
  simular_procesando(v_erp, 1);
  PKG_COLA.completar_erp(v_erp, v_pedido, 'ERP-001');
  leer(v_erp);
  SELECT COUNT(*) INTO v_n FROM PEDIDO_ERP WHERE ID_PEDIDO = v_pedido AND ID_ERP = 'ERP-001';
  verificar('completar_erp guarda el id del ERP y completa el evento', v_n = 1 AND v_estado = 'PROCESADO');

  simular_procesando(v_erp, 2);   -- el mismo evento se entrega otra vez
  PKG_COLA.completar_erp(v_erp, v_pedido, 'ERP-001');
  leer(v_erp);
  verificar('reentrega con el mismo id ERP: no es error y vuelve a quedar PROCESADO', v_estado = 'PROCESADO');

  simular_procesando(v_erp, 3);
  esperar_error('otro id ERP para un pedido ya sincronizado',
    'BEGIN PKG_COLA.completar_erp(' || v_erp || ', ' || v_pedido || ', ''ERP-999''); END;', -20207);
  esperar_error('completar_erp con un evento que no es de ese pedido',
    'BEGIN PKG_COLA.completar_erp(' || v_e4 || ', ' || v_pedido || ', ''ERP-002''); END;', -20209);
  esperar_error('completar_erp sin id ERP',
    'BEGIN PKG_COLA.completar_erp(' || v_erp || ', ' || v_pedido || ', '' ''); END;', -20206);

  -- Purgar
  UPDATE COLA_EVENTO
     SET FECHA_PROCESADO = PKG_UTILIDADES.ahora - NUMTODSINTERVAL(v_dias + 1, 'DAY')
   WHERE ID_COLA_EVENTO = v_e1;
  v_n := PKG_COLA.purgar_procesados;
  leer(v_e1);
  verificar('purgar_procesados borra los PROCESADO mas viejos que la retencion',
            v_n >= 1 AND v_estado IS NULL);

  DBMS_OUTPUT.PUT_LINE('Resultado: ' || v_pruebas || ' pruebas, ' || v_fallos || ' fallos');
  ROLLBACK;
EXCEPTION
  WHEN OTHERS THEN
    ROLLBACK;
    RAISE;
END;
/
