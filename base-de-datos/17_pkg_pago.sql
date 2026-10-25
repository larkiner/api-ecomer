-- ============================================================================
-- 17_pkg_pago.sql
-- Seccion 5 del diseno (parte 10): paquete PKG_PAGO.
-- Lado de la pasarela: registrar sus notificaciones y aplicarlas al pago y al
-- pedido.
--
-- Requiere haber ejecutado antes los scripts 14, 15 (VERSION 2, con
-- iniciar_pago) y 16. Las pruebas usan un municipio y una pasarela temporales.
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1 (SELECT USER FROM dual).
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Al final sale una linea "OK" por cada prueba y un resumen. Las pruebas
--      crean datos temporales y los deshacen con ROLLBACK: no quedan en la base.
--   4. Para deshacerlo: DROP PACKAGE PKG_PAGO;
--
-- FLUJO COMPLETO DE UN PAGO
--   1. Cliente:  PKG_PEDIDO.iniciar_pago  -> PAGO PENDIENTE + referencia (ID_PUBLICO).
--   2. Backend:  crea la transaccion en la pasarela con esa referencia.
--   3. Webhook:  el backend VERIFICA LA FIRMA de la pasarela, traduce su estado a
--                APROBADO / RECHAZADO / PENDIENTE / ANULADO y llama a
--                PKG_PAGO.registrar_notificacion. Responde 200 a la pasarela en
--                cuanto hace COMMIT: no espera a que se aplique.
--   4. JOB:      toma los eventos PAGO_NOTIFICADO de la cola y llama a
--                PKG_PAGO.procesar_notificacion, que actualiza PAGO y PEDIDO y
--                encola el correo y la sincronizacion con el ERP.
--
-- DECISIONES DE DISENO
--   * Paquete para el rol de INTEGRACION (y para PKG_TAREAS). El cliente nunca
--     recibe EXECUTE sobre el: iniciar_pago vive en PKG_PEDIDO por eso.
--   * registrar_notificacion es rapido e idempotente: guarda el mensaje crudo en
--     PAGO_EVENTO y encola PAGO_NOTIFICADO en la MISMA transaccion (outbox). Si
--     la pasarela reenvia el mismo evento (mismo ID_EVENTO_EXTERNO), devuelve el
--     evento ya registrado y no encola otra vez.
--   * procesar_notificacion es la parte lenta y la que puede chocar con otras
--     operaciones; por eso va en la cola, con reintentos. Bloquea primero el
--     PAGO y despues el PEDIDO (via PKG_PEDIDO_ESTADO). Nadie bloquea en el orden
--     contrario: iniciar_pago bloquea el pedido pero solo LEE e INSERTA pagos.
--   * Que hace procesar_notificacion segun el estado reportado:
--       APROBADO   valida que el monto sea exactamente el del pago, aprueba el
--                  pago y llama a PKG_PEDIDO_ESTADO.marcar_pagado. Devuelve el
--                  resultado de marcar_pagado. Si es SIN_STOCK o NO_PAGABLE, el
--                  pago queda APROBADO y el pedido no: aparece en
--                  V_CONCILIACION_PAGOS para devolver el dinero a mano.
--       RECHAZADO  pasa a RECHAZADO un pago PENDIENTE; en otro estado lo ignora
--                  (las notificaciones pueden llegar desordenadas y un rechazo
--                  viejo no debe deshacer una aprobacion).
--       ANULADO    anula un pago PENDIENTE. Si el pago ya estaba APROBADO falla
--                  (-20189): una devolucion es un proceso de negocio, no un
--                  cambio automatico.
--       PENDIENTE  no hace nada.
--   * Los casos que necesitan una persona (monto distinto, segundo pago aprobado
--     del mismo pedido, anulacion de un aprobado) se lanzan como error. El JOB
--     llama a PKG_COLA.fallar y, al agotar los reintentos, el evento queda en
--     ERROR con el motivo en ULTIMO_ERROR: se ven en V_COLA_PENDIENTE.
--   * "Solo un pago aprobado por pedido" lo garantiza el indice unico
--     UX_PAGO_APROBADO, no una consulta previa: dos aprobaciones simultaneas no
--     pueden pasar las dos.
--   * La referencia externa (id de la transaccion en la pasarela) se guarda con
--     la primera notificacion. Si despues llega otra distinta para el mismo pago,
--     se rechaza (-20182).
--   * Ningun procedimiento hace COMMIT: confirma quien lo llama.
--   * Errores (bloque -20180 a -20199):
--       -20180 no existe un pago con esa referencia para esa pasarela
--       -20181 estado reportado invalido
--       -20182 la referencia externa no coincide o ya la usa otro pago
--       -20183 el mensaje crudo (payload) no es JSON valido
--       -20184 una aprobacion debe traer el monto
--       -20185 el evento de cola no es valido (sin id o evento inexistente)
--       -20186 el monto aprobado no coincide con el del pago
--       -20187 el pedido ya tiene otro pago aprobado (devolver este)
--       -20188 el pago esta ocupado por otra operacion (reintentar)
--       -20189 la pasarela anulo un pago ya aprobado (revisar a mano)
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

PROMPT >> 17_pkg_pago.sql: comprobando requisitos

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
   WHERE object_name IN ('PKG_UTILIDADES', 'PKG_PEDIDO_ESTADO', 'PKG_PEDIDO', 'PKG_COLA')
     AND object_type = 'PACKAGE BODY'
     AND status = 'VALID';
  IF v_existentes < 4 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan paquetes previos validos: ejecuta antes los scripts 09 a 16.');
  END IF;

  -- PKG_PEDIDO debe ser la version 2 (con iniciar_pago).
  SELECT COUNT(*) INTO v_existentes
    FROM user_procedures
   WHERE object_name = 'PKG_PEDIDO'
     AND procedure_name = 'INICIAR_PAGO';
  IF v_existentes < 1 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'PKG_PEDIDO no tiene iniciar_pago: vuelve a ejecutar el script 15 (version 2).');
  END IF;
END;
/

CREATE OR REPLACE PACKAGE PKG_PAGO
  AUTHID DEFINER
AS
  -- Registra una notificacion de la pasarela y encola su procesamiento.
  -- Devuelve el ID_PAGO_EVENTO (el existente si el evento ya se habia recibido).
  --   p_referencia_pago     ID_PUBLICO del pago en hexadecimal (lo que se envio
  --                         a la pasarela en iniciar_pago).
  --   p_referencia_externa  id de la transaccion en la pasarela.
  --   p_id_evento_externo   id del evento en la pasarela (para no duplicar).
  --   p_estado              APROBADO, RECHAZADO, PENDIENTE o ANULADO, ya traducido
  --                         por el backend desde los codigos de la pasarela.
  --   p_monto               monto que reporta la pasarela (obligatorio si APROBADO).
  --   p_payload             mensaje crudo, en JSON, tal como llego.
  -- Errores: -20180, -20181, -20182, -20183, -20184, -20188.
  FUNCTION registrar_notificacion(
    p_codigo_pasarela    IN VARCHAR2,
    p_referencia_pago    IN VARCHAR2,
    p_referencia_externa IN VARCHAR2,
    p_id_evento_externo  IN VARCHAR2,
    p_estado             IN VARCHAR2,
    p_monto              IN NUMBER,
    p_metodo_pago        IN VARCHAR2,
    p_payload            IN CLOB
  ) RETURN NUMBER;

  -- Aplica un evento PAGO_NOTIFICADO de la cola (recibe su PAYLOAD). Lo llama el
  -- JOB. Devuelve lo que hizo: el resultado de PKG_PEDIDO_ESTADO.marcar_pagado
  -- (PAGADO, PAGADO_REACTIVADO, SIN_STOCK, NO_PAGABLE), o YA_APROBADO,
  -- RECHAZADO, ANULADO o IGNORADO.
  -- Errores: -20185, -20186, -20187, -20188, -20189.
  FUNCTION procesar_notificacion(p_payload_cola IN CLOB) RETURN VARCHAR2;
END PKG_PAGO;
/
SHOW ERRORS PACKAGE PKG_PAGO
PROMPT >> PKG_PAGO: especificacion creada

CREATE OR REPLACE PACKAGE BODY PKG_PAGO
AS
  e_check EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_check, -2290);        -- ORA-02290: viola un CHECK

  e_hex_invalido EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_hex_invalido, -1465); -- ORA-01465: hexadecimal invalido

  e_ocupado EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_ocupado, -30006);     -- se agoto el WAIT del FOR UPDATE

  -- Encola el correo de confirmacion y la sincronizacion con el ERP del pedido.
  PROCEDURE encolar_pedido_pagado(p_id_pedido IN NUMBER) IS
    v_payload VARCHAR2(200);
    v_id      NUMBER;
  BEGIN
    SELECT JSON_OBJECT('plantilla' VALUE 'PEDIDO_PAGADO', 'id_pedido' VALUE p_id_pedido)
      INTO v_payload FROM dual;
    v_id := PKG_COLA.encolar(PKG_COLA.c_enviar_correo,
                             'ENVIAR_CORREO:PEDIDO_PAGADO:' || p_id_pedido, v_payload);

    SELECT JSON_OBJECT('id_pedido' VALUE p_id_pedido) INTO v_payload FROM dual;
    v_id := PKG_COLA.encolar(PKG_COLA.c_sincronizar_erp,
                             'SINCRONIZAR_ERP:PEDIDO:' || p_id_pedido, v_payload);
  END encolar_pedido_pagado;

  -- ---------------------------------------------------------------------------
  -- Publicos
  -- ---------------------------------------------------------------------------
  FUNCTION registrar_notificacion(
    p_codigo_pasarela    IN VARCHAR2,
    p_referencia_pago    IN VARCHAR2,
    p_referencia_externa IN VARCHAR2,
    p_id_evento_externo  IN VARCHAR2,
    p_estado             IN VARCHAR2,
    p_monto              IN NUMBER,
    p_metodo_pago        IN VARCHAR2,
    p_payload            IN CLOB
  ) RETURN NUMBER IS
    v_id_pago     PAGO.ID_PAGO%TYPE;
    v_ref_actual  PAGO.REFERENCIA_EXTERNA%TYPE;
    v_id_evento   PAGO_EVENTO.ID_PAGO_EVENTO%TYPE;
    v_payload     VARCHAR2(400);
    v_id_cola     COLA_EVENTO.ID_COLA_EVENTO%TYPE;
  BEGIN
    SAVEPOINT sp_registrar_notificacion;

    IF p_estado IS NULL OR p_estado NOT IN ('APROBADO', 'RECHAZADO', 'PENDIENTE', 'ANULADO') THEN
      RAISE_APPLICATION_ERROR(-20181, 'Estado reportado invalido: ' || NVL(p_estado, '(vacio)') || '.');
    END IF;
    IF p_estado = 'APROBADO' AND p_monto IS NULL THEN
      RAISE_APPLICATION_ERROR(-20184, 'Una notificacion de pago aprobado debe traer el monto.');
    END IF;

    -- Pago al que se refiere la notificacion (bloqueado para fijar la referencia).
    BEGIN
      SELECT g.ID_PAGO, g.REFERENCIA_EXTERNA
        INTO v_id_pago, v_ref_actual
        FROM PAGO g
        JOIN PASARELA_PAGO s ON s.ID_PASARELA_PAGO = g.ID_PASARELA_PAGO
       WHERE g.ID_PUBLICO = HEXTORAW(p_referencia_pago)
         AND s.CODIGO = p_codigo_pasarela
         FOR UPDATE OF g.ID_PAGO WAIT 10;
    EXCEPTION
      WHEN NO_DATA_FOUND OR e_hex_invalido THEN
        RAISE_APPLICATION_ERROR(-20180,
          'No existe un pago con la referencia ' || p_referencia_pago ||
          ' para la pasarela ' || p_codigo_pasarela || '.');
      WHEN e_ocupado THEN
        RAISE_APPLICATION_ERROR(-20188, 'El pago esta ocupado por otra operacion; intenta de nuevo.');
    END;

    -- Referencia externa: se fija con la primera notificacion y no cambia.
    IF p_referencia_externa IS NOT NULL THEN
      IF v_ref_actual IS NULL THEN
        BEGIN
          UPDATE PAGO SET REFERENCIA_EXTERNA = p_referencia_externa WHERE ID_PAGO = v_id_pago;
        EXCEPTION
          WHEN DUP_VAL_ON_INDEX THEN
            RAISE_APPLICATION_ERROR(-20182,
              'La referencia externa ' || p_referencia_externa || ' ya pertenece a otro pago.');
        END;
      ELSIF v_ref_actual <> p_referencia_externa THEN
        RAISE_APPLICATION_ERROR(-20182,
          'El pago ya tiene la referencia externa ' || v_ref_actual ||
          ' y la notificacion trae ' || p_referencia_externa || '.');
      END IF;
    END IF;

    -- Mensaje crudo. Si el mismo evento ya llego, se devuelve y no se encola otra vez.
    BEGIN
      INSERT INTO PAGO_EVENTO (ID_PAGO, ID_EVENTO_EXTERNO, ESTADO_REPORTADO, PAYLOAD)
      VALUES (v_id_pago, p_id_evento_externo, p_estado, p_payload)
      RETURNING ID_PAGO_EVENTO INTO v_id_evento;
    EXCEPTION
      WHEN DUP_VAL_ON_INDEX THEN
        SELECT ID_PAGO_EVENTO INTO v_id_evento
          FROM PAGO_EVENTO
         WHERE ID_PAGO = v_id_pago
           AND ID_EVENTO_EXTERNO = p_id_evento_externo;
        RETURN v_id_evento;
      WHEN e_check THEN
        RAISE_APPLICATION_ERROR(-20183, 'El mensaje de la pasarela no es JSON valido.');
    END;

    -- Outbox: el procesamiento se encola en la misma transaccion.
    SELECT JSON_OBJECT('id_pago_evento' VALUE v_id_evento,
                       'monto'          VALUE p_monto,
                       'metodo'         VALUE p_metodo_pago)
      INTO v_payload FROM dual;
    v_id_cola := PKG_COLA.encolar(PKG_COLA.c_pago_notificado,
                                  'PAGO_NOTIFICADO:PAGO_EVENTO:' || v_id_evento, v_payload);

    RETURN v_id_evento;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_registrar_notificacion;
      RAISE;
  END registrar_notificacion;

  FUNCTION procesar_notificacion(p_payload_cola IN CLOB) RETURN VARCHAR2 IS
    v_id_evento  NUMBER;
    v_monto      NUMBER;
    v_metodo     VARCHAR2(30);
    v_id_pago    PAGO.ID_PAGO%TYPE;
    v_reportado  PAGO_EVENTO.ESTADO_REPORTADO%TYPE;
    v_estado     PAGO.ESTADO%TYPE;
    v_monto_pago PAGO.MONTO%TYPE;
    v_id_pedido  PAGO.ID_PEDIDO%TYPE;
    v_resultado  VARCHAR2(20);
  BEGIN
    SAVEPOINT sp_procesar_notificacion;

    SELECT JSON_VALUE(p_payload_cola, '$.id_pago_evento' RETURNING NUMBER),
           JSON_VALUE(p_payload_cola, '$.monto'          RETURNING NUMBER),
           JSON_VALUE(p_payload_cola, '$.metodo'         RETURNING VARCHAR2(30))
      INTO v_id_evento, v_monto, v_metodo
      FROM dual;

    BEGIN
      SELECT ID_PAGO, ESTADO_REPORTADO
        INTO v_id_pago, v_reportado
        FROM PAGO_EVENTO
       WHERE ID_PAGO_EVENTO = v_id_evento;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(-20185,
          'El evento de cola no tiene un id_pago_evento valido: ' || SUBSTR(p_payload_cola, 1, 200));
    END;

    -- Primero el PAGO; el PEDIDO lo bloquea despues PKG_PEDIDO_ESTADO.
    BEGIN
      SELECT ESTADO, MONTO, ID_PEDIDO
        INTO v_estado, v_monto_pago, v_id_pedido
        FROM PAGO
       WHERE ID_PAGO = v_id_pago
         FOR UPDATE WAIT 10;
    EXCEPTION
      WHEN e_ocupado THEN
        RAISE_APPLICATION_ERROR(-20188, 'El pago esta ocupado por otra operacion; intenta de nuevo.');
    END;

    IF v_reportado = 'APROBADO' THEN
      IF v_estado = 'APROBADO' THEN
        RETURN 'YA_APROBADO';
      END IF;
      IF v_monto IS NULL OR v_monto <> v_monto_pago THEN
        RAISE_APPLICATION_ERROR(-20186,
          'La pasarela aprobo ' || NVL(TO_CHAR(v_monto), '(sin monto)') || ' y el pago ' ||
          v_id_pago || ' es por ' || v_monto_pago || ': revisar antes de aprobar.');
      END IF;

      BEGIN
        UPDATE PAGO
           SET ESTADO           = 'APROBADO',
               FECHA_APROBACION = PKG_UTILIDADES.ahora,
               METODO_PAGO      = NVL(v_metodo, METODO_PAGO)
         WHERE ID_PAGO = v_id_pago;
      EXCEPTION
        WHEN DUP_VAL_ON_INDEX THEN   -- UX_PAGO_APROBADO
          RAISE_APPLICATION_ERROR(-20187,
            'El pedido ' || v_id_pedido || ' ya tiene otro pago aprobado: hay que devolver el pago ' ||
            v_id_pago || '.');
      END;

      v_resultado := PKG_PEDIDO_ESTADO.marcar_pagado(v_id_pedido);
      IF v_resultado IN (PKG_PEDIDO_ESTADO.c_pagado, PKG_PEDIDO_ESTADO.c_pagado_reactivado) THEN
        encolar_pedido_pagado(v_id_pedido);
      END IF;
      RETURN v_resultado;

    ELSIF v_reportado = 'RECHAZADO' THEN
      IF v_estado <> 'PENDIENTE' THEN
        RETURN 'IGNORADO';
      END IF;
      UPDATE PAGO SET ESTADO = 'RECHAZADO' WHERE ID_PAGO = v_id_pago;
      RETURN 'RECHAZADO';

    ELSIF v_reportado = 'ANULADO' THEN
      IF v_estado = 'APROBADO' THEN
        RAISE_APPLICATION_ERROR(-20189,
          'La pasarela anulo el pago aprobado ' || v_id_pago || ': revisar a mano.');
      ELSIF v_estado <> 'PENDIENTE' THEN
        RETURN 'IGNORADO';
      END IF;
      UPDATE PAGO SET ESTADO = 'ANULADO' WHERE ID_PAGO = v_id_pago;
      RETURN 'ANULADO';

    ELSE
      RETURN 'IGNORADO';   -- PENDIENTE: nada que hacer
    END IF;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_procesar_notificacion;
      RAISE;
  END procesar_notificacion;
END PKG_PAGO;
/
SHOW ERRORS PACKAGE BODY PKG_PAGO
PROMPT >> PKG_PAGO: cuerpo creado

-- CREATE PACKAGE no se detiene ante errores de compilacion (solo avisa), asi que
-- se revisa USER_ERRORS de forma explicita.
DECLARE
  v_errores NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_errores
    FROM user_errors
   WHERE name = 'PKG_PAGO';
  IF v_errores > 0 THEN
    RAISE_APPLICATION_ERROR(-20004,
      'PKG_PAGO compilo con errores: consulta USER_ERRORS.');
  END IF;
END;
/
PROMPT >> PKG_PAGO: paquete compilado sin errores

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT object_name, object_type, status
  FROM user_objects
 WHERE object_name = 'PKG_PAGO'
 ORDER BY object_type;                                                       -- PACKAGE VALID, PACKAGE BODY VALID

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';                                                    -- 0 filas

-- ============================================================================
-- PRUEBAS RAPIDAS (terminan con ROLLBACK: no dejan datos).
-- Recorren el flujo completo: carrito -> pedido -> pago -> notificacion -> JOB.
-- Esperado: 20 lineas "OK" y al final "0 fallos".
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  v_pruebas     NUMBER := 0;
  v_fallos      NUMBER := 0;
  v_id_impuesto NUMBER;
  v_id_tipo_doc NUMBER;
  v_cliente     NUMBER;
  v_categoria   NUMBER;
  v_producto    NUMBER;
  v_var_a       NUMBER;
  v_pasarela    NUMBER;
  v_p1          NUMBER;
  v_p2          NUMBER;
  v_pago1       NUMBER;
  v_pago2       NUMBER;
  v_pago3       NUMBER;
  v_pago_dup    NUMBER;
  v_pub1        VARCHAR2(32);
  v_pub2        VARCHAR2(32);
  v_pub3        VARCHAR2(32);
  v_pub_dup     VARCHAR2(32);
  v_monto       NUMBER;
  v_ev          NUMBER;
  v_ev2         NUMBER;
  v_resultado   VARCHAR2(20);
  v_n           NUMBER;
  v_texto       VARCHAR2(200);
  v_raw         RAW(16);

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

  -- Notificacion de prueba de la pasarela PRUEBA.
  FUNCTION notificar(p_pub IN VARCHAR2, p_ext IN VARCHAR2, p_evento IN VARCHAR2,
                     p_estado IN VARCHAR2, p_monto IN NUMBER) RETURN NUMBER IS
  BEGIN
    RETURN PKG_PAGO.registrar_notificacion('PRUEBA', p_pub, p_ext, p_evento, p_estado,
                                           p_monto, 'PSE', '{"origen":"prueba"}');
  END notificar;

  FUNCTION bloque_notificar(p_pub IN VARCHAR2, p_ext IN VARCHAR2, p_evento IN VARCHAR2,
                            p_estado IN VARCHAR2, p_monto IN VARCHAR2,
                            p_payload IN VARCHAR2 DEFAULT '{}') RETURN VARCHAR2 IS
  BEGIN
    RETURN 'DECLARE x NUMBER; BEGIN x := PKG_PAGO.registrar_notificacion(''PRUEBA'', '''
           || p_pub || ''', ''' || p_ext || ''', ''' || p_evento || ''', ''' || p_estado || ''', '
           || p_monto || ', NULL, ''' || p_payload || '''); END;';
  END bloque_notificar;

  -- PAYLOAD del evento PAGO_NOTIFICADO encolado para un PAGO_EVENTO.
  FUNCTION payload_cola(p_id_pago_evento IN NUMBER) RETURN CLOB IS
    v_payload CLOB;
  BEGIN
    SELECT PAYLOAD INTO v_payload
      FROM COLA_EVENTO
     WHERE CLAVE_IDEMPOTENCIA = 'PAGO_NOTIFICADO:PAGO_EVENTO:' || p_id_pago_evento;
    RETURN v_payload;
  END payload_cola;

  -- Procesa un evento esperando que falle con el codigo indicado.
  PROCEDURE esperar_error_procesar(p_nombre IN VARCHAR2, p_payload IN CLOB, p_codigo IN NUMBER) IS
    v_r VARCHAR2(20);
  BEGIN
    v_r := PKG_PAGO.procesar_notificacion(p_payload);
    verificar(p_nombre || ' (debia fallar con ' || p_codigo || ')', FALSE);
  EXCEPTION
    WHEN OTHERS THEN
      verificar(p_nombre || ' -> ' || p_codigo, SQLCODE = p_codigo);
  END esperar_error_procesar;

  FUNCTION estado_pago(p_id_pago IN NUMBER) RETURN VARCHAR2 IS
    v_estado PAGO.ESTADO%TYPE;
  BEGIN
    SELECT ESTADO INTO v_estado FROM PAGO WHERE ID_PAGO = p_id_pago;
    RETURN v_estado;
  END estado_pago;

  FUNCTION estado_de(p_id_pedido IN NUMBER) RETURN VARCHAR2 IS
    v_codigo VARCHAR2(20);
  BEGIN
    SELECT e.CODIGO INTO v_codigo
      FROM PEDIDO p JOIN ESTADO_PEDIDO e ON e.ID_ESTADO_PEDIDO = p.ID_ESTADO_PEDIDO
     WHERE p.ID_PEDIDO = p_id_pedido;
    RETURN v_codigo;
  END estado_de;

  -- Crea un pedido con p_cant unidades de A (100.000 + IVA 19%) desde el carrito.
  FUNCTION nuevo_pedido(p_cant IN NUMBER) RETURN NUMBER IS
  BEGIN
    PKG_CARRITO.agregar_item(v_cliente, v_var_a, p_cant);
    RETURN PKG_PEDIDO.crear_desde_carrito(v_cliente);
  END nuevo_pedido;
BEGIN
  -- Datos temporales
  SELECT ID_IMPUESTO       INTO v_id_impuesto FROM IMPUESTO       WHERE NOMBRE = 'IVA 19%';
  SELECT ID_TIPO_DOCUMENTO INTO v_id_tipo_doc FROM TIPO_DOCUMENTO WHERE CODIGO = 'CC';

  INSERT INTO DEPARTAMENTO (CODIGO_DANE, NOMBRE) VALUES ('00', 'DEPARTAMENTO PRUEBA');
  INSERT INTO MUNICIPIO (CODIGO_DANE, CODIGO_DEPARTAMENTO, NOMBRE)
    VALUES ('00000', '00', 'MUNICIPIO PRUEBA');
  INSERT INTO CLIENTE (ID_TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRE, EMAIL)
    VALUES (v_id_tipo_doc, '9000000031', 'Cliente prueba pago', 'prueba.pago@ejemplo.com')
    RETURNING ID_CLIENTE INTO v_cliente;
  INSERT INTO CLIENTE_DIRECCION (ID_CLIENTE, DIRECCION, ES_PRINCIPAL, CODIGO_MUNICIPIO)
    VALUES (v_cliente, 'Calle 1 # 2-3', 1, '00000');
  INSERT INTO CATEGORIA (NOMBRE) VALUES ('CATEGORIA PRUEBA PAGO')
    RETURNING ID_CATEGORIA INTO v_categoria;
  INSERT INTO PRODUCTO (ID_CATEGORIA, ID_IMPUESTO, NOMBRE)
    VALUES (v_categoria, v_id_impuesto, 'Producto prueba pago')
    RETURNING ID_PRODUCTO INTO v_producto;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE, CONTROLA_STOCK)
    VALUES (v_producto, 'PAG-A', 'Variante A', 100000, 1)
    RETURNING ID_VARIANTE INTO v_var_a;
  PKG_INVENTARIO.entrada(v_var_a, 10, 'prueba');
  INSERT INTO PASARELA_PAGO (CODIGO, NOMBRE) VALUES ('PRUEBA', 'Pasarela de prueba')
    RETURNING ID_PASARELA_PAGO INTO v_pasarela;

  -- Pedido 1: 2 unidades = 238.000
  v_p1 := nuevo_pedido(2);
  PKG_PEDIDO.iniciar_pago(v_cliente, v_p1, 'PRUEBA', v_pago1, v_pub1, v_monto);

  -- Registrar
  v_ev := notificar(v_pub1, 'EXT-1', 'EV-1', 'APROBADO', 238000);
  SELECT COUNT(*) INTO v_n FROM PAGO_EVENTO WHERE ID_PAGO = v_pago1;
  SELECT REFERENCIA_EXTERNA INTO v_texto FROM PAGO WHERE ID_PAGO = v_pago1;
  verificar('registrar guarda el evento y la referencia externa',
            v_n = 1 AND v_texto = 'EXT-1' AND estado_pago(v_pago1) = 'PENDIENTE');
  SELECT COUNT(*) INTO v_n FROM COLA_EVENTO
   WHERE CLAVE_IDEMPOTENCIA = 'PAGO_NOTIFICADO:PAGO_EVENTO:' || v_ev AND ESTADO = 'PENDIENTE';
  verificar('registrar encola PAGO_NOTIFICADO en la misma transaccion', v_n = 1);

  v_ev2 := notificar(v_pub1, 'EXT-1', 'EV-1', 'APROBADO', 238000);
  SELECT COUNT(*) INTO v_n FROM PAGO_EVENTO WHERE ID_PAGO = v_pago1;
  verificar('la misma notificacion repetida no duplica el evento ni la cola',
            v_ev2 = v_ev AND v_n = 1);

  esperar_error('estado reportado invalido',
    bloque_notificar(v_pub1, 'EXT-1', 'EV-X', 'APROVADO', '238000'), -20181);
  esperar_error('referencia de pago inexistente',
    bloque_notificar('00000000000000000000000000000000', 'EXT-9', 'EV-X', 'APROBADO', '1'), -20180);
  esperar_error('referencia de pago que no es hexadecimal',
    bloque_notificar('NO-ES-HEX', 'EXT-9', 'EV-X', 'APROBADO', '1'), -20180);
  esperar_error('otra referencia externa para el mismo pago',
    bloque_notificar(v_pub1, 'EXT-OTRA', 'EV-X', 'APROBADO', '238000'), -20182);
  esperar_error('aprobacion sin monto',
    bloque_notificar(v_pub1, 'EXT-1', 'EV-X', 'APROBADO', 'NULL'), -20184);
  esperar_error('mensaje crudo que no es JSON',
    bloque_notificar(v_pub1, 'EXT-1', 'EV-X', 'PENDIENTE', 'NULL', 'no es json'), -20183);

  -- Procesar (lo que hara el JOB)
  v_resultado := PKG_PAGO.procesar_notificacion(payload_cola(v_ev));
  SELECT COUNT(*) INTO v_n FROM PAGO
   WHERE ID_PAGO = v_pago1 AND ESTADO = 'APROBADO' AND FECHA_APROBACION IS NOT NULL AND METODO_PAGO = 'PSE';
  verificar('procesar la aprobacion: PAGADO, pago APROBADO con fecha y metodo, pedido PAGADO',
            v_resultado = 'PAGADO' AND v_n = 1 AND estado_de(v_p1) = 'PAGADO');
  SELECT COUNT(*) INTO v_n FROM COLA_EVENTO
   WHERE CLAVE_IDEMPOTENCIA IN ('ENVIAR_CORREO:PEDIDO_PAGADO:' || v_p1, 'SINCRONIZAR_ERP:PEDIDO:' || v_p1);
  verificar('al pagar se encolan el correo y la sincronizacion con el ERP', v_n = 2);
  verificar('procesar otra vez la misma aprobacion devuelve YA_APROBADO',
            PKG_PAGO.procesar_notificacion(payload_cola(v_ev)) = 'YA_APROBADO');
  SELECT COUNT(*) INTO v_n FROM V_CONCILIACION_PAGOS WHERE ID_PEDIDO = v_p1;
  verificar('el pedido pagado cuadra (no aparece en V_CONCILIACION_PAGOS)', v_n = 0);

  -- Pedido 2: 1 unidad = 119.000. Rechazo, monto distinto y pago de pedido cancelado.
  v_p2 := nuevo_pedido(1);
  PKG_PEDIDO.iniciar_pago(v_cliente, v_p2, 'PRUEBA', v_pago2, v_pub2, v_monto);
  v_ev := notificar(v_pub2, 'EXT-2', 'EV-2', 'RECHAZADO', NULL);
  v_resultado := PKG_PAGO.procesar_notificacion(payload_cola(v_ev));
  verificar('procesar un rechazo: pago RECHAZADO y el pedido sigue PENDIENTE_PAGO',
            v_resultado = 'RECHAZADO' AND estado_pago(v_pago2) = 'RECHAZADO'
            AND estado_de(v_p2) = 'PENDIENTE_PAGO');

  PKG_PEDIDO.iniciar_pago(v_cliente, v_p2, 'PRUEBA', v_pago3, v_pub3, v_monto);   -- reintento
  v_ev := notificar(v_pub3, 'EXT-3', 'EV-3', 'APROBADO', 1000);
  esperar_error_procesar('aprobacion por un monto distinto', payload_cola(v_ev), -20186);
  verificar('con monto distinto el pago sigue PENDIENTE', estado_pago(v_pago3) = 'PENDIENTE');

  PKG_PEDIDO.cancelar(v_cliente, v_p2, 'prueba');
  v_ev := notificar(v_pub3, 'EXT-3', 'EV-4', 'APROBADO', 119000);
  v_resultado := PKG_PAGO.procesar_notificacion(payload_cola(v_ev));
  SELECT COUNT(*) INTO v_n FROM V_CONCILIACION_PAGOS
   WHERE ID_PEDIDO = v_p2 AND INCONSISTENCIA = 'PAGO APROBADO CON PEDIDO CANCELADO';
  verificar('pago aprobado de un pedido cancelado: NO_PAGABLE y aparece en V_CONCILIACION_PAGOS',
            v_resultado = 'NO_PAGABLE' AND estado_pago(v_pago3) = 'APROBADO' AND v_n = 1);

  -- Segundo pago aprobado del pedido 1 (por ejemplo, un intento viejo que la
  -- pasarela aprobo tarde). Se crea a mano porque iniciar_pago ya no lo permite.
  INSERT INTO PAGO (ID_PEDIDO, ID_PASARELA_PAGO, MONTO)
    VALUES (v_p1, v_pasarela, 238000)
    RETURNING ID_PAGO, ID_PUBLICO INTO v_pago_dup, v_raw;
  v_pub_dup := RAWTOHEX(v_raw);
  v_ev := notificar(v_pub_dup, 'EXT-4', 'EV-5', 'APROBADO', 238000);
  esperar_error_procesar('segundo pago aprobado del mismo pedido', payload_cola(v_ev), -20187);

  -- Anulacion de un pago ya aprobado
  v_ev := notificar(v_pub1, 'EXT-1', 'EV-6', 'ANULADO', NULL);
  esperar_error_procesar('la pasarela anula un pago ya aprobado', payload_cola(v_ev), -20189);

  esperar_error_procesar('evento de cola sin id_pago_evento', '{}', -20185);

  DBMS_OUTPUT.PUT_LINE('Resultado: ' || v_pruebas || ' pruebas, ' || v_fallos || ' fallos');
  ROLLBACK;
EXCEPTION
  WHEN OTHERS THEN
    ROLLBACK;
    RAISE;
END;
/
