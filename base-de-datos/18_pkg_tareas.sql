-- ============================================================================
-- 18_pkg_tareas.sql
-- Seccion 5 del diseno (parte 11): paquete PKG_TAREAS.
-- Lo que ejecutan los JOBs de DBMS_SCHEDULER (se crean en el script 19).
--
-- Requiere haber ejecutado antes los scripts 09 a 17 (el 16 en su VERSION 2).
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1 (SELECT USER FROM dual).
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Al final sale una linea "OK" por cada prueba y un resumen. Las pruebas
--      crean datos temporales y los deshacen con ROLLBACK: no quedan en la base.
--   4. Para deshacerlo: DROP PACKAGE PKG_TAREAS;
--
-- QUE JOB LLAMA A QUE (script 19)
--   cada 30 s     procesar_cola                (rescata atascados + aplica pagos)
--   cada minuto   expirar_pedidos
--   cada hora     avisar_carritos_abandonados
--   cada hora     revisar_sincronizacion_erp
--   00:10 diario  cerrar_dias_pendientes + purgar_cola
--
-- DECISIONES DE DISENO
--   * INTERNO: solo lo ejecutan los JOBs (que corren como ECOMERS). Ningun rol de
--     aplicacion recibe EXECUTE sobre el.
--   * A DIFERENCIA DE LOS OTROS PAQUETES, ESTE SI HACE COMMIT: un JOB no tiene a
--     nadie que confirme por el. Confirma POR UNIDAD de trabajo (un pago, un
--     pedido, un carrito), asi los bloqueos duran poco y un fallo no deshace lo
--     que ya salio bien. Cada unidad va entre SAVEPOINT y ROLLBACK TO.
--   * p_confirmar (TRUE por defecto) existe solo para las pruebas: con FALSE no
--     hace COMMIT y la prueba puede terminar con ROLLBACK.
--   * Cada funcion devuelve cuantas unidades proceso (las pruebas lo usan).
--   * Que pasa si algo falla:
--       - un pago: lo maneja la cola (PKG_COLA.fallar: reintento o ERROR).
--       - un pedido ocupado (-20168) al expirar: se salta, el JOB lo vuelve a
--         intentar en un minuto. No cuenta como fallo.
--       - cualquier otro fallo: se sigue con el resto y AL FINAL se lanza -20210
--         con el ultimo error. Asi la ejecucion queda FAILED, con el mensaje, en
--         USER_SCHEDULER_JOB_RUN_DETAILS, y lo que salio bien ya esta confirmado.
--   * expirar_pedidos busca candidatos sin bloquear y PKG_PEDIDO_ESTADO.expirar
--     vuelve a decidir con el pedido bloqueado: si en medio llego el pago, no lo
--     expira.
--   * avisar_carritos_abandonados marca el aviso con un UPDATE condicional (sigue
--     ABIERTO, sin aviso y sin actividad nueva) y encola el correo en la misma
--     transaccion. El correo lleva nombre y email para que el backend no tenga
--     que consultar tablas.
--   * cerrar_dia es idempotente (si el dia ya esta cerrado devuelve 0) y solo
--     cierra dias terminados. cerrar_dias_pendientes cierra desde el ultimo dia
--     cerrado hasta ayer (maximo 31 dias): si el JOB no corrio una noche, el dia
--     siguiente se pone al dia solo. Una DIFERENCIA distinta de 0 en el cierre no
--     es un error del JOB: es la alarma de que pagos y pedidos no cuadran.
--   * revisar_sincronizacion_erp encola SINCRONIZAR_ERP para los pedidos pagados
--     sin fila en PEDIDO_ERP y sin evento. Si el evento esta PROCESADO pero no hay
--     fila (el consumidor uso completar en vez de completar_erp), falla con -20211.
--   * Errores (bloque -20200 a -20219, compartido con PKG_COLA):
--       -20210 la tarea termino con fallos (ver el mensaje)
--       -20211 hay pedidos con la sincronizacion PROCESADA pero sin PEDIDO_ERP
--       -20212 fecha de cierre invalida (solo dias ya terminados)
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

PROMPT >> 18_pkg_tareas.sql: comprobando requisitos

DECLARE
  v_existentes NUMBER;
BEGIN
  IF SYS_CONTEXT('USERENV', 'SESSION_USER') <> 'ECOMERS'
     OR SYS_CONTEXT('USERENV', 'CON_NAME') <> 'XEPDB1' THEN
    RAISE_APPLICATION_ERROR(-20001,
      'Conectate como ECOMERS al servicio XEPDB1 antes de ejecutar este script.');
  END IF;

  SELECT COUNT(*) INTO v_existentes
    FROM user_objects
   WHERE object_name IN ('PKG_UTILIDADES', 'PKG_PEDIDO_ESTADO', 'PKG_PEDIDO', 'PKG_COLA', 'PKG_PAGO')
     AND object_type = 'PACKAGE BODY'
     AND status = 'VALID';
  IF v_existentes < 5 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan paquetes previos validos: ejecuta antes los scripts 09 a 17.');
  END IF;

  SELECT COUNT(*) INTO v_existentes
    FROM user_procedures
   WHERE object_name = 'PKG_COLA'
     AND procedure_name = 'COMPLETAR_ERP';
  IF v_existentes < 1 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'PKG_COLA no tiene completar_erp: vuelve a ejecutar el script 16 (version 2).');
  END IF;
END;
/

CREATE OR REPLACE PACKAGE PKG_TAREAS
  AUTHID DEFINER
AS
  -- Rescata eventos atascados y aplica hasta p_limite notificaciones de pago.
  -- Devuelve cuantas se aplicaron bien.
  FUNCTION procesar_cola(p_limite IN NUMBER DEFAULT 50, p_confirmar IN BOOLEAN DEFAULT TRUE) RETURN NUMBER;

  -- Expira hasta p_limite pedidos PENDIENTE_PAGO vencidos. Devuelve cuantos expiro.
  FUNCTION expirar_pedidos(p_limite IN NUMBER DEFAULT 200, p_confirmar IN BOOLEAN DEFAULT TRUE) RETURN NUMBER;

  -- Marca y encola el aviso de hasta p_limite carritos abandonados. Devuelve cuantos.
  FUNCTION avisar_carritos_abandonados(p_limite IN NUMBER DEFAULT 200, p_confirmar IN BOOLEAN DEFAULT TRUE) RETURN NUMBER;

  -- Cierra un dia terminado (NULL = ayer). 1 si lo cerro, 0 si ya estaba cerrado.
  -- Error -20212.
  FUNCTION cerrar_dia(p_fecha IN DATE DEFAULT NULL, p_confirmar IN BOOLEAN DEFAULT TRUE) RETURN NUMBER;

  -- Cierra los dias sin cierre desde el ultimo cerrado hasta ayer (maximo 31).
  FUNCTION cerrar_dias_pendientes(p_confirmar IN BOOLEAN DEFAULT TRUE) RETURN NUMBER;

  -- Borra los eventos de la cola ya procesados y vencidos. Devuelve cuantos.
  FUNCTION purgar_cola(p_confirmar IN BOOLEAN DEFAULT TRUE) RETURN NUMBER;

  -- Encola la sincronizacion de pedidos pagados que no la tienen. Devuelve cuantos.
  -- Error -20211.
  FUNCTION revisar_sincronizacion_erp(p_limite IN NUMBER DEFAULT 500, p_confirmar IN BOOLEAN DEFAULT TRUE) RETURN NUMBER;
END PKG_TAREAS;
/
SHOW ERRORS PACKAGE PKG_TAREAS
PROMPT >> PKG_TAREAS: especificacion creada

CREATE OR REPLACE PACKAGE BODY PKG_TAREAS
AS
  TYPE t_evento IS RECORD (
    id_cola_evento     COLA_EVENTO.ID_COLA_EVENTO%TYPE,
    tipo_evento        COLA_EVENTO.TIPO_EVENTO%TYPE,
    clave_idempotencia COLA_EVENTO.CLAVE_IDEMPOTENCIA%TYPE,
    payload            COLA_EVENTO.PAYLOAD%TYPE,
    intentos           COLA_EVENTO.INTENTOS%TYPE
  );
  TYPE t_eventos IS TABLE OF t_evento;
  TYPE t_ids     IS TABLE OF NUMBER;

  e_pedido_ocupado EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_pedido_ocupado, -20168);

  PROCEDURE confirmar(p_confirmar IN BOOLEAN) IS
  BEGIN
    IF p_confirmar THEN
      COMMIT;
    END IF;
  END confirmar;

  -- Al final de una tarea: si hubo fallos, se informa con el ultimo error.
  PROCEDURE informar_fallos(p_tarea IN VARCHAR2, p_fallos IN NUMBER, p_ultimo_error IN VARCHAR2) IS
  BEGIN
    IF p_fallos > 0 THEN
      RAISE_APPLICATION_ERROR(-20210,
        p_tarea || ': ' || p_fallos || ' fallo(s). Ultimo error: ' || SUBSTR(p_ultimo_error, 1, 1500));
    END IF;
  END informar_fallos;

  -- ---------------------------------------------------------------------------
  -- Publicos
  -- ---------------------------------------------------------------------------
  FUNCTION procesar_cola(p_limite IN NUMBER DEFAULT 50, p_confirmar IN BOOLEAN DEFAULT TRUE) RETURN NUMBER IS
    c         SYS_REFCURSOR;
    v_eventos t_eventos;
    v_rescate NUMBER;
    v_ok      NUMBER := 0;
    v_r       VARCHAR2(20);
    v_error   VARCHAR2(4000);
  BEGIN
    v_rescate := PKG_COLA.rescatar_atascados;
    confirmar(p_confirmar);

    c := PKG_COLA.tomar_lote(PKG_COLA.c_pago_notificado, p_limite);
    FETCH c BULK COLLECT INTO v_eventos;
    CLOSE c;
    confirmar(p_confirmar);   -- deja los eventos en PROCESANDO y suelta los bloqueos

    FOR i IN 1 .. v_eventos.COUNT LOOP
      SAVEPOINT sp_evento;
      BEGIN
        v_r := PKG_PAGO.procesar_notificacion(v_eventos(i).payload);
        PKG_COLA.completar(v_eventos(i).id_cola_evento);
        v_ok := v_ok + 1;
      EXCEPTION
        WHEN OTHERS THEN
          v_error := SUBSTR(SQLERRM, 1, 4000);   -- antes del ROLLBACK, que lo borra
          ROLLBACK TO SAVEPOINT sp_evento;
          PKG_COLA.fallar(v_eventos(i).id_cola_evento, v_error);
      END;
      confirmar(p_confirmar);
    END LOOP;

    RETURN v_ok;
  END procesar_cola;

  FUNCTION expirar_pedidos(p_limite IN NUMBER DEFAULT 200, p_confirmar IN BOOLEAN DEFAULT TRUE) RETURN NUMBER IS
    v_ids    t_ids;
    v_ahora  TIMESTAMP;
    v_ok     NUMBER := 0;
    v_fallos NUMBER := 0;
    v_error  VARCHAR2(4000);
  BEGIN
    v_ahora := PKG_UTILIDADES.ahora;

    -- Candidatos sin bloquear: expirar() vuelve a decidir con el pedido bloqueado.
    SELECT p.ID_PEDIDO
      BULK COLLECT INTO v_ids
      FROM PEDIDO p
      JOIN ESTADO_PEDIDO e ON e.ID_ESTADO_PEDIDO = p.ID_ESTADO_PEDIDO
     WHERE e.CODIGO = 'PENDIENTE_PAGO'
       AND p.FECHA_EXPIRACION <= v_ahora
     ORDER BY p.FECHA_EXPIRACION
     FETCH FIRST p_limite ROWS ONLY;

    FOR i IN 1 .. v_ids.COUNT LOOP
      SAVEPOINT sp_pedido;
      BEGIN
        IF PKG_PEDIDO_ESTADO.expirar(v_ids(i)) THEN
          v_ok := v_ok + 1;
        END IF;
      EXCEPTION
        WHEN e_pedido_ocupado THEN
          ROLLBACK TO SAVEPOINT sp_pedido;   -- se reintenta en la proxima ejecucion
        WHEN OTHERS THEN
          v_error := 'Pedido ' || v_ids(i) || ': ' || SQLERRM;
          ROLLBACK TO SAVEPOINT sp_pedido;
          v_fallos := v_fallos + 1;
      END;
      confirmar(p_confirmar);
    END LOOP;

    informar_fallos('expirar_pedidos', v_fallos, v_error);
    RETURN v_ok;
  END expirar_pedidos;

  FUNCTION avisar_carritos_abandonados(p_limite IN NUMBER DEFAULT 200, p_confirmar IN BOOLEAN DEFAULT TRUE) RETURN NUMBER IS
    TYPE t_carrito IS RECORD (
      id_carrito  CARRITO.ID_CARRITO%TYPE,
      id_cliente  CARRITO.ID_CLIENTE%TYPE,
      nombre      CLIENTE.NOMBRE%TYPE,
      email       CLIENTE.EMAIL%TYPE,
      actividad   CARRITO.FECHA_ULTIMA_ACTIVIDAD%TYPE
    );
    TYPE t_carritos IS TABLE OF t_carrito;
    v_carritos t_carritos;
    v_payload  VARCHAR2(1000);
    v_id       NUMBER;
    v_ok       NUMBER := 0;
    v_fallos   NUMBER := 0;
    v_error    VARCHAR2(4000);
  BEGIN
    SELECT ID_CARRITO, ID_CLIENTE, NOMBRE, EMAIL, FECHA_ULTIMA_ACTIVIDAD
      BULK COLLECT INTO v_carritos
      FROM V_CARRITOS_ABANDONADOS
     ORDER BY FECHA_ULTIMA_ACTIVIDAD
     FETCH FIRST p_limite ROWS ONLY;

    FOR i IN 1 .. v_carritos.COUNT LOOP
      SAVEPOINT sp_carrito;
      BEGIN
        -- Condicional: si el cliente volvio a usar el carrito o ya se convirtio,
        -- no se avisa.
        UPDATE CARRITO
           SET AVISO_ABANDONO_ENVIADO = 1
         WHERE ID_CARRITO = v_carritos(i).id_carrito
           AND ESTADO = 'ABIERTO'
           AND AVISO_ABANDONO_ENVIADO = 0
           AND FECHA_ULTIMA_ACTIVIDAD = v_carritos(i).actividad;

        IF SQL%ROWCOUNT = 1 THEN
          SELECT JSON_OBJECT('plantilla'  VALUE 'CARRITO_ABANDONADO',
                             'id_carrito' VALUE v_carritos(i).id_carrito,
                             'id_cliente' VALUE v_carritos(i).id_cliente,
                             'nombre'     VALUE v_carritos(i).nombre,
                             'email'      VALUE v_carritos(i).email)
            INTO v_payload FROM dual;
          v_id := PKG_COLA.encolar(PKG_COLA.c_enviar_correo,
                                   'ENVIAR_CORREO:CARRITO_ABANDONADO:' || v_carritos(i).id_carrito,
                                   v_payload);
          v_ok := v_ok + 1;
        END IF;
      EXCEPTION
        WHEN OTHERS THEN
          v_error := 'Carrito ' || v_carritos(i).id_carrito || ': ' || SQLERRM;
          ROLLBACK TO SAVEPOINT sp_carrito;
          v_fallos := v_fallos + 1;
      END;
      confirmar(p_confirmar);
    END LOOP;

    informar_fallos('avisar_carritos_abandonados', v_fallos, v_error);
    RETURN v_ok;
  END avisar_carritos_abandonados;

  FUNCTION cerrar_dia(p_fecha IN DATE DEFAULT NULL, p_confirmar IN BOOLEAN DEFAULT TRUE) RETURN NUMBER IS
    v_fecha     DATE;
    v_existe    NUMBER;
    v_pedidos   NUMBER;
    v_subtotal  NUMBER;
    v_impuesto  NUMBER;
    v_total     NUMBER;
    v_aprobados NUMBER;
  BEGIN
    v_fecha := TRUNC(NVL(p_fecha, PKG_UTILIDADES.hoy - 1));
    IF v_fecha >= PKG_UTILIDADES.hoy THEN
      RAISE_APPLICATION_ERROR(-20212,
        'Solo se cierran dias terminados: ' || TO_CHAR(v_fecha, 'YYYY-MM-DD') || ' aun no termina.');
    END IF;

    SELECT COUNT(*) INTO v_existe FROM CIERRE_DIARIO WHERE FECHA = v_fecha;
    IF v_existe > 0 THEN
      RETURN 0;
    END IF;

    -- Pedidos que pasaron a PAGADO ese dia (misma regla que V_VENTAS_DIARIAS).
    SELECT NVL(SUM(PEDIDOS_PAGADOS), 0), NVL(SUM(SUBTOTAL), 0), NVL(SUM(IMPUESTO), 0), NVL(SUM(TOTAL), 0)
      INTO v_pedidos, v_subtotal, v_impuesto, v_total
      FROM V_VENTAS_DIARIAS
     WHERE FECHA = v_fecha;

    -- Pagos aprobados ese dia.
    SELECT NVL(SUM(MONTO), 0)
      INTO v_aprobados
      FROM PAGO
     WHERE ESTADO = 'APROBADO'
       AND FECHA_APROBACION >= v_fecha
       AND FECHA_APROBACION <  v_fecha + 1;

    BEGIN
      INSERT INTO CIERRE_DIARIO (FECHA, PEDIDOS_PAGADOS, SUBTOTAL, IMPUESTO, TOTAL_PAGADO, TOTAL_PAGOS_APROBADOS)
      VALUES (v_fecha, v_pedidos, v_subtotal, v_impuesto, v_total, v_aprobados);
    EXCEPTION
      WHEN DUP_VAL_ON_INDEX THEN
        RETURN 0;   -- otra ejecucion lo cerro al mismo tiempo
    END;

    confirmar(p_confirmar);
    RETURN 1;
  END cerrar_dia;

  FUNCTION cerrar_dias_pendientes(p_confirmar IN BOOLEAN DEFAULT TRUE) RETURN NUMBER IS
    v_ayer  DATE;
    v_desde DATE;
    v_n     NUMBER := 0;
  BEGIN
    v_ayer := PKG_UTILIDADES.hoy - 1;

    SELECT NVL(MAX(FECHA) + 1, v_ayer) INTO v_desde FROM CIERRE_DIARIO;
    v_desde := GREATEST(v_desde, v_ayer - 30);

    WHILE v_desde <= v_ayer LOOP
      v_n := v_n + cerrar_dia(v_desde, p_confirmar);
      v_desde := v_desde + 1;
    END LOOP;
    RETURN v_n;
  END cerrar_dias_pendientes;

  FUNCTION purgar_cola(p_confirmar IN BOOLEAN DEFAULT TRUE) RETURN NUMBER IS
    v_n NUMBER;
  BEGIN
    v_n := PKG_COLA.purgar_procesados;
    confirmar(p_confirmar);
    RETURN v_n;
  END purgar_cola;

  FUNCTION revisar_sincronizacion_erp(p_limite IN NUMBER DEFAULT 500, p_confirmar IN BOOLEAN DEFAULT TRUE) RETURN NUMBER IS
    v_ids        t_ids;
    v_estado     COLA_EVENTO.ESTADO%TYPE;
    v_id         NUMBER;
    v_payload    VARCHAR2(100);
    v_encolados  NUMBER := 0;
    v_anomalias  NUMBER := 0;
    v_ejemplo    NUMBER;
  BEGIN
    SELECT p.ID_PEDIDO
      BULK COLLECT INTO v_ids
      FROM PEDIDO p
      JOIN ESTADO_PEDIDO e ON e.ID_ESTADO_PEDIDO = p.ID_ESTADO_PEDIDO
     WHERE e.CODIGO = 'PAGADO'
       AND NOT EXISTS (SELECT 1 FROM PEDIDO_ERP x WHERE x.ID_PEDIDO = p.ID_PEDIDO)
     ORDER BY p.ID_PEDIDO
     FETCH FIRST p_limite ROWS ONLY;

    FOR i IN 1 .. v_ids.COUNT LOOP
      BEGIN
        SELECT ESTADO INTO v_estado
          FROM COLA_EVENTO
         WHERE CLAVE_IDEMPOTENCIA = 'SINCRONIZAR_ERP:PEDIDO:' || v_ids(i);
        IF v_estado = 'PROCESADO' THEN
          v_anomalias := v_anomalias + 1;
          v_ejemplo   := v_ids(i);
        END IF;
        -- PENDIENTE, PROCESANDO o ERROR: ya esta en la cola (ERROR se ve en V_COLA_PENDIENTE).
      EXCEPTION
        WHEN NO_DATA_FOUND THEN
          SELECT JSON_OBJECT('id_pedido' VALUE v_ids(i)) INTO v_payload FROM dual;
          v_id := PKG_COLA.encolar(PKG_COLA.c_sincronizar_erp,
                                   'SINCRONIZAR_ERP:PEDIDO:' || v_ids(i), v_payload);
          v_encolados := v_encolados + 1;
      END;
    END LOOP;
    confirmar(p_confirmar);

    IF v_anomalias > 0 THEN
      RAISE_APPLICATION_ERROR(-20211,
        v_anomalias || ' pedido(s) con la sincronizacion PROCESADA pero sin PEDIDO_ERP (por ejemplo, el ' ||
        v_ejemplo || '). El consumidor debe usar PKG_COLA.completar_erp.');
    END IF;
    RETURN v_encolados;
  END revisar_sincronizacion_erp;
END PKG_TAREAS;
/
SHOW ERRORS PACKAGE BODY PKG_TAREAS
PROMPT >> PKG_TAREAS: cuerpo creado

-- CREATE PACKAGE no se detiene ante errores de compilacion (solo avisa), asi que
-- se revisa USER_ERRORS de forma explicita.
DECLARE
  v_errores NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_errores
    FROM user_errors
   WHERE name = 'PKG_TAREAS';
  IF v_errores > 0 THEN
    RAISE_APPLICATION_ERROR(-20004,
      'PKG_TAREAS compilo con errores: consulta USER_ERRORS.');
  END IF;
END;
/
PROMPT >> PKG_TAREAS: paquete compilado sin errores

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT object_name, object_type, status
  FROM user_objects
 WHERE object_name = 'PKG_TAREAS'
 ORDER BY object_type;                                                       -- PACKAGE VALID, PACKAGE BODY VALID

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';                                                    -- 0 filas

-- ============================================================================
-- PRUEBAS RAPIDAS (con p_confirmar => FALSE y ROLLBACK al final: no dejan datos).
-- Esperado: 14 lineas "OK" y al final "0 fallos".
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  v_pruebas     NUMBER := 0;
  v_fallos      NUMBER := 0;
  v_id_impuesto NUMBER;
  v_id_tipo_doc NUMBER;
  v_c1          NUMBER;
  v_c2          NUMBER;
  v_categoria   NUMBER;
  v_producto    NUMBER;
  v_var_a       NUMBER;
  v_p1          NUMBER;
  v_p2          NUMBER;
  v_p3          NUMBER;
  v_pago        NUMBER;
  v_pub         VARCHAR2(32);
  v_monto       NUMBER;
  v_ev          NUMBER;
  v_n           NUMBER;
  v_carrito1    NUMBER;
  v_carrito2    NUMBER;
  v_estado      VARCHAR2(20);
  v_intentos    NUMBER;
  v_error       VARCHAR2(4000);
  v_texto       VARCHAR2(400);
  v_diferencia  NUMBER;

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

  FUNCTION estado_de(p_id_pedido IN NUMBER) RETURN VARCHAR2 IS
    v_codigo VARCHAR2(20);
  BEGIN
    SELECT e.CODIGO INTO v_codigo
      FROM PEDIDO p JOIN ESTADO_PEDIDO e ON e.ID_ESTADO_PEDIDO = p.ID_ESTADO_PEDIDO
     WHERE p.ID_PEDIDO = p_id_pedido;
    RETURN v_codigo;
  END estado_de;

  -- Lee el evento de la cola con esa clave (estado NULL si no existe).
  PROCEDURE leer_evento(p_clave IN VARCHAR2) IS
  BEGIN
    SELECT ESTADO, INTENTOS, ULTIMO_ERROR, PAYLOAD
      INTO v_estado, v_intentos, v_error, v_texto
      FROM COLA_EVENTO
     WHERE CLAVE_IDEMPOTENCIA = p_clave;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      v_estado := NULL;
  END leer_evento;

  -- Pedido con p_cant unidades de A (100.000 + IVA) y su notificacion de pago.
  FUNCTION pedido_con_pago(p_cant IN NUMBER, p_monto_reportado IN NUMBER) RETURN NUMBER IS
    v_pedido NUMBER;
  BEGIN
    PKG_CARRITO.agregar_item(v_c1, v_var_a, p_cant);
    v_pedido := PKG_PEDIDO.crear_desde_carrito(v_c1);
    PKG_PEDIDO.iniciar_pago(v_c1, v_pedido, 'PRUEBA', v_pago, v_pub, v_monto);
    v_ev := PKG_PAGO.registrar_notificacion('PRUEBA', v_pub, 'EXT-' || v_pedido, 'EV-' || v_pedido,
                                            'APROBADO', p_monto_reportado, 'PSE', '{}');
    RETURN v_pedido;
  END pedido_con_pago;
BEGIN
  -- Datos temporales
  SELECT ID_IMPUESTO       INTO v_id_impuesto FROM IMPUESTO       WHERE NOMBRE = 'IVA 19%';
  SELECT ID_TIPO_DOCUMENTO INTO v_id_tipo_doc FROM TIPO_DOCUMENTO WHERE CODIGO = 'CC';

  INSERT INTO DEPARTAMENTO (CODIGO_DANE, NOMBRE) VALUES ('00', 'DEPARTAMENTO PRUEBA');
  INSERT INTO MUNICIPIO (CODIGO_DANE, CODIGO_DEPARTAMENTO, NOMBRE)
    VALUES ('00000', '00', 'MUNICIPIO PRUEBA');
  INSERT INTO CLIENTE (ID_TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRE, EMAIL)
    VALUES (v_id_tipo_doc, '9000000051', 'Cliente tareas 1', 'prueba.tareas1@ejemplo.com')
    RETURNING ID_CLIENTE INTO v_c1;
  INSERT INTO CLIENTE (ID_TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRE, EMAIL)
    VALUES (v_id_tipo_doc, '9000000052', 'Cliente tareas 2', 'prueba.tareas2@ejemplo.com')
    RETURNING ID_CLIENTE INTO v_c2;
  INSERT INTO CLIENTE_DIRECCION (ID_CLIENTE, DIRECCION, ES_PRINCIPAL, CODIGO_MUNICIPIO)
    VALUES (v_c1, 'Calle 1 # 2-3', 1, '00000');
  INSERT INTO CATEGORIA (NOMBRE) VALUES ('CATEGORIA PRUEBA TAREAS')
    RETURNING ID_CATEGORIA INTO v_categoria;
  INSERT INTO PRODUCTO (ID_CATEGORIA, ID_IMPUESTO, NOMBRE)
    VALUES (v_categoria, v_id_impuesto, 'Producto prueba tareas')
    RETURNING ID_PRODUCTO INTO v_producto;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE, CONTROLA_STOCK)
    VALUES (v_producto, 'TAR-A', 'Variante A', 100000, 1)
    RETURNING ID_VARIANTE INTO v_var_a;
  PKG_INVENTARIO.entrada(v_var_a, 10, 'prueba');
  INSERT INTO PASARELA_PAGO (CODIGO, NOMBRE) VALUES ('PRUEBA', 'Pasarela de prueba');

  -- procesar_cola
  v_p1 := pedido_con_pago(2, 238000);
  v_n := PKG_TAREAS.procesar_cola(50, FALSE);
  leer_evento('PAGO_NOTIFICADO:PAGO_EVENTO:' || v_ev);
  verificar('procesar_cola aplica el pago: evento PROCESADO y pedido PAGADO',
            v_n >= 1 AND v_estado = 'PROCESADO' AND estado_de(v_p1) = 'PAGADO');

  v_p2 := pedido_con_pago(1, 1000);   -- la pasarela reporta un monto equivocado
  v_n := PKG_TAREAS.procesar_cola(50, FALSE);
  leer_evento('PAGO_NOTIFICADO:PAGO_EVENTO:' || v_ev);
  verificar('un pago que falla queda PENDIENTE para reintento, con el error (-20186)',
            v_estado = 'PENDIENTE' AND v_intentos = 1 AND v_error LIKE '%-20186%'
            AND estado_de(v_p2) = 'PENDIENTE_PAGO');

  -- expirar_pedidos
  UPDATE PEDIDO SET FECHA_EXPIRACION = PKG_UTILIDADES.ahora - INTERVAL '1' MINUTE
   WHERE ID_PEDIDO = v_p2;
  PKG_CARRITO.agregar_item(v_c1, v_var_a, 1);
  v_p3 := PKG_PEDIDO.crear_desde_carrito(v_c1);   -- vigente
  v_n := PKG_TAREAS.expirar_pedidos(200, FALSE);
  SELECT CANTIDAD_RESERVADA INTO v_monto FROM STOCK WHERE ID_VARIANTE = v_var_a;
  verificar('expirar_pedidos expira el vencido y libera su stock (reservada = 1, la del vigente)',
            v_n >= 1 AND estado_de(v_p2) = 'EXPIRADO' AND v_monto = 1);
  verificar('expirar_pedidos no toca un pedido vigente', estado_de(v_p3) = 'PENDIENTE_PAGO');

  -- avisar_carritos_abandonados
  v_carrito1 := PKG_CARRITO.obtener_carrito(v_c1);
  PKG_CARRITO.agregar_item(v_c1, v_var_a, 1);
  UPDATE CARRITO SET FECHA_ULTIMA_ACTIVIDAD = PKG_UTILIDADES.ahora - INTERVAL '2' DAY
   WHERE ID_CARRITO = v_carrito1;
  PKG_CARRITO.agregar_item(v_c2, v_var_a, 1);   -- carrito reciente
  v_carrito2 := PKG_CARRITO.obtener_carrito(v_c2);
  v_n := PKG_TAREAS.avisar_carritos_abandonados(200, FALSE);
  SELECT AVISO_ABANDONO_ENVIADO INTO v_monto FROM CARRITO WHERE ID_CARRITO = v_carrito1;
  leer_evento('ENVIAR_CORREO:CARRITO_ABANDONADO:' || v_carrito1);
  SELECT JSON_VALUE(v_texto, '$.email') INTO v_texto FROM dual;
  verificar('carrito abandonado: se marca el aviso y se encola el correo con el email',
            v_n >= 1 AND v_monto = 1 AND v_estado = 'PENDIENTE'
            AND v_texto = 'prueba.tareas1@ejemplo.com');
  SELECT AVISO_ABANDONO_ENVIADO INTO v_monto FROM CARRITO WHERE ID_CARRITO = v_carrito2;
  verificar('un carrito con actividad reciente no se avisa', v_monto = 0);
  v_n := PKG_TAREAS.avisar_carritos_abandonados(200, FALSE);
  SELECT COUNT(*) INTO v_monto FROM COLA_EVENTO
   WHERE CLAVE_IDEMPOTENCIA = 'ENVIAR_CORREO:CARRITO_ABANDONADO:' || v_carrito1;
  verificar('correr otra vez no vuelve a avisar el mismo carrito', v_monto = 1);

  -- Cierre diario (con un dia viejo para no chocar con cierres reales)
  esperar_error('cerrar el dia de hoy',
    'DECLARE n NUMBER; BEGIN n := PKG_TAREAS.cerrar_dia(TRUNC(SYSDATE), FALSE); END;', -20212);
  UPDATE PAGO SET FECHA_APROBACION = TIMESTAMP '2000-01-01 10:00:00'
   WHERE ID_PEDIDO = v_p1 AND ESTADO = 'APROBADO';
  v_n := PKG_TAREAS.cerrar_dia(DATE '2000-01-01', FALSE);
  SELECT TOTAL_PAGOS_APROBADOS, DIFERENCIA INTO v_monto, v_diferencia
    FROM CIERRE_DIARIO WHERE FECHA = DATE '2000-01-01';
  verificar('cerrar_dia suma los pagos aprobados del dia y la DIFERENCIA delata el descuadre',
            v_n = 1 AND v_monto = 238000 AND v_diferencia = -238000);
  verificar('cerrar otra vez el mismo dia devuelve 0 (ya estaba cerrado)',
            PKG_TAREAS.cerrar_dia(DATE '2000-01-01', FALSE) = 0);
  v_n := PKG_TAREAS.cerrar_dias_pendientes(FALSE);
  SELECT COUNT(*) INTO v_monto FROM CIERRE_DIARIO WHERE FECHA = PKG_UTILIDADES.hoy - 1;
  verificar('cerrar_dias_pendientes deja cerrado el dia de ayer', v_monto = 1);

  -- Sincronizacion con el ERP
  leer_evento('SINCRONIZAR_ERP:PEDIDO:' || v_p1);
  verificar('al pagar el pedido ya se encolo su sincronizacion', v_estado = 'PENDIENTE');
  DELETE FROM COLA_EVENTO WHERE CLAVE_IDEMPOTENCIA = 'SINCRONIZAR_ERP:PEDIDO:' || v_p1;
  v_n := PKG_TAREAS.revisar_sincronizacion_erp(500, FALSE);
  leer_evento('SINCRONIZAR_ERP:PEDIDO:' || v_p1);
  verificar('revisar_sincronizacion_erp encola la que falta', v_n >= 1 AND v_estado = 'PENDIENTE');
  UPDATE COLA_EVENTO SET ESTADO = 'PROCESADO', FECHA_PROCESADO = PKG_UTILIDADES.ahora
   WHERE CLAVE_IDEMPOTENCIA = 'SINCRONIZAR_ERP:PEDIDO:' || v_p1;
  esperar_error('evento PROCESADO sin PEDIDO_ERP',
    'DECLARE n NUMBER; BEGIN n := PKG_TAREAS.revisar_sincronizacion_erp(500, FALSE); END;', -20211);

  DBMS_OUTPUT.PUT_LINE('Resultado: ' || v_pruebas || ' pruebas, ' || v_fallos || ' fallos');
  ROLLBACK;
EXCEPTION
  WHEN OTHERS THEN
    ROLLBACK;
    RAISE;
END;
/
