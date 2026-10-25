-- ============================================================================
-- 07_tablas_cola_erp_cierre.sql
-- Seccion 4 del diseno: cola de eventos, integracion con el ERP, cierre diario
-- y parametros (4 tablas).
--
-- Requiere haber ejecutado antes el script 06 (PEDIDO y PAGO).
-- Los JOBs de DBMS_SCHEDULER NO se crean aqui: llaman a procedimientos de los
-- paquetes (PKG_COLA, PKG_PEDIDO, ...) que todavia no existen. Van en un script
-- posterior, despues de los paquetes.
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1.
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Si falla a la mitad, ejecuta el bloque DESHACER del final (esta
--      comentado) y vuelve a correr el script completo.
--
-- DECISIONES DE DISENO
--   * COLA_EVENTO es el patron "outbox": el evento se inserta en la MISMA
--     transaccion que el hecho que lo origina (por ejemplo, el pago aprobado).
--     Si hay COMMIT existen los dos; si hay ROLLBACK no existe ninguno.
--   * Quien envia correos y llama al ERP es el backend, no la base de datos:
--     toma eventos mediante PKG_COLA y los marca como PROCESADO o con error.
--   * Idempotencia: CLAVE_IDEMPOTENCIA es unica, asi que el mismo evento no se
--     encola dos veces. Formato sugerido: TIPO:ENTIDAD:ID, por ejemplo
--     'SINCRONIZAR_ERP:PEDIDO:123'. Aun asi, el consumidor debe tolerar que un
--     evento se entregue dos veces (la entrega es "al menos una vez").
--   * La cola es generica: no tiene claves foraneas hacia pedidos o carritos.
--     El PAYLOAD referencia por id y el consumidor valida que exista.
--   * Reintentos: el paquete calcula PROXIMO_INTENTO con espera creciente
--     (1, 2, 4, 8 minutos). Al llegar al maximo el evento queda en ERROR y se
--     revisa a mano; no se reprocesa solo.
--   * FECHA_BLOQUEO: momento en que un consumidor tomo el evento (PROCESANDO).
--     Si el consumidor se cae, un JOB devuelve a PENDIENTE los eventos que
--     llevan demasiado tiempo en PROCESANDO.
--   * PEDIDO_ERP guarda el id que asigna el ERP SIN tocar PEDIDO, cuya foto es
--     inmutable. Su FECHA_CREACION es la fecha de sincronizacion. Que exista fila
--     significa "ya sincronizado": el JOB de revision busca pedidos pagados sin ella.
--   * CIERRE_DIARIO es de solo insercion. Guarda una fila por dia (hora de
--     Bogota) con el total de pedidos pagados y el total de pagos aprobados;
--     DIFERENCIA debe ser 0 y si no lo es, hay que investigar. Una correccion
--     se registra como ajuste, no editando el cierre.
--   * PARAMETRO: umbrales que se cambian sin recompilar. EXCEPCION A LAS
--     CONVENCIONES: la clave primaria es CLAVE (texto) y no un ID numerico,
--     porque es el identificador natural y nadie la referencia con una FK.
--     El VALOR es numerico a proposito: un valor mal escrito se rechaza al
--     guardarlo y no hace fallar a un JOB de madrugada. La unidad va en el
--     nombre de la clave (_MINUTOS, _HORAS, _DIAS).
--   * No hay tabla de notificaciones ni de log de JOBs: la fila procesada de
--     la cola ya dice a quien, cuando y con cuantos intentos, y Oracle registra
--     cada ejecucion en USER_SCHEDULER_JOB_RUN_DETAILS.
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

DECLARE
  v_existentes NUMBER;
BEGIN
  IF SYS_CONTEXT('USERENV', 'SESSION_USER') <> 'ECOMERS'
     OR SYS_CONTEXT('USERENV', 'CON_NAME') <> 'XEPDB1' THEN
    RAISE_APPLICATION_ERROR(-20001,
      'Conectate como ECOMERS al servicio XEPDB1 antes de ejecutar este script.');
  END IF;

  SELECT COUNT(*) INTO v_existentes
    FROM user_tables
   WHERE table_name IN ('PEDIDO', 'PAGO', 'ESTADO_PEDIDO');
  IF v_existentes < 3 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan tablas previas: ejecuta antes el script 06.');
  END IF;
END;
/

-- ============================================================================
-- COLA DE EVENTOS
-- ============================================================================

CREATE TABLE COLA_EVENTO (
  ID_COLA_EVENTO      NUMBER(12) GENERATED ALWAYS AS IDENTITY,
  TIPO_EVENTO         VARCHAR2(30)   NOT NULL,
  CLAVE_IDEMPOTENCIA  VARCHAR2(200)  NOT NULL,
  PAYLOAD             CLOB           NOT NULL,
  ESTADO              VARCHAR2(10)   DEFAULT 'PENDIENTE' NOT NULL,
  INTENTOS            NUMBER(3)      DEFAULT 0 NOT NULL,
  PROXIMO_INTENTO     TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
  FECHA_BLOQUEO       TIMESTAMP,                        -- cuando paso a PROCESANDO
  FECHA_PROCESADO     TIMESTAMP,                        -- cuando paso a PROCESADO
  ULTIMO_ERROR        VARCHAR2(4000),
  FECHA_CREACION      TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
  FECHA_MODIFICACION  TIMESTAMP,
  CONSTRAINT PK_COLA_EVENTO           PRIMARY KEY (ID_COLA_EVENTO),
  CONSTRAINT UQ_COLA_EVENTO_CLAVE     UNIQUE (CLAVE_IDEMPOTENCIA),
  CONSTRAINT CK_COLA_EVENTO_TIPO      CHECK (TIPO_EVENTO IN ('PAGO_NOTIFICADO', 'ENVIAR_CORREO', 'SINCRONIZAR_ERP')),
  CONSTRAINT CK_COLA_EVENTO_ESTADO    CHECK (ESTADO IN ('PENDIENTE', 'PROCESANDO', 'PROCESADO', 'ERROR')),
  CONSTRAINT CK_COLA_EVENTO_INTENTOS  CHECK (INTENTOS >= 0),
  CONSTRAINT CK_COLA_EVENTO_JSON      CHECK (PAYLOAD IS JSON),
  -- Coherencia entre el estado y las fechas / el error.
  CONSTRAINT CK_COLA_EVENTO_BLOQUEO   CHECK (ESTADO <> 'PROCESANDO' OR FECHA_BLOQUEO IS NOT NULL),
  CONSTRAINT CK_COLA_EVENTO_PROCESADO CHECK (ESTADO <> 'PROCESADO'  OR FECHA_PROCESADO IS NOT NULL),
  CONSTRAINT CK_COLA_EVENTO_ERROR     CHECK (ESTADO <> 'ERROR'      OR ULTIMO_ERROR IS NOT NULL)
);

-- Para el consumidor ("PENDIENTE con PROXIMO_INTENTO vencido") y para el JOB que
-- rescata los eventos atascados en PROCESANDO.
CREATE INDEX IX_COLA_EVENTO_ESTADO ON COLA_EVENTO (ESTADO, PROXIMO_INTENTO);

-- ============================================================================
-- INTEGRACION CON EL ERP
-- ============================================================================

CREATE TABLE PEDIDO_ERP (
  ID_PEDIDO           NUMBER(12)    NOT NULL,           -- la clave primaria es el propio pedido
  ID_ERP              VARCHAR2(100) NOT NULL,           -- id que asigno el ERP
  FECHA_CREACION      TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,   -- fecha de sincronizacion
  FECHA_MODIFICACION  TIMESTAMP,
  CONSTRAINT PK_PEDIDO_ERP        PRIMARY KEY (ID_PEDIDO),
  CONSTRAINT UQ_PEDIDO_ERP_ID_ERP UNIQUE (ID_ERP),
  CONSTRAINT FK_PEDIDO_ERP_PEDIDO FOREIGN KEY (ID_PEDIDO) REFERENCES PEDIDO (ID_PEDIDO)
);

-- ============================================================================
-- CIERRE DIARIO
-- ============================================================================

CREATE TABLE CIERRE_DIARIO (
  FECHA                  DATE          NOT NULL,        -- dia calendario (hora de Bogota), sin hora
  PEDIDOS_PAGADOS        NUMBER(10)    NOT NULL,
  SUBTOTAL               NUMBER(14,2)  NOT NULL,
  IMPUESTO               NUMBER(14,2)  NOT NULL,
  TOTAL_PAGADO           NUMBER(14,2)  NOT NULL,        -- suma de PEDIDO.TOTAL de los pedidos pagados ese dia
  TOTAL_PAGOS_APROBADOS  NUMBER(14,2)  NOT NULL,        -- suma de PAGO.MONTO de los pagos aprobados ese dia
  DIFERENCIA             NUMBER(14,2)  GENERATED ALWAYS AS (TOTAL_PAGADO - TOTAL_PAGOS_APROBADOS) VIRTUAL,
  FECHA_CREACION         TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT PK_CIERRE_DIARIO        PRIMARY KEY (FECHA),
  CONSTRAINT CK_CIERRE_DIARIO_FECHA  CHECK (FECHA = TRUNC(FECHA)),
  CONSTRAINT CK_CIERRE_DIARIO_MONTOS CHECK (PEDIDOS_PAGADOS >= 0 AND SUBTOTAL >= 0 AND IMPUESTO >= 0
                                             AND TOTAL_PAGOS_APROBADOS >= 0
                                             AND TOTAL_PAGADO = SUBTOTAL + IMPUESTO)
);

-- ============================================================================
-- PARAMETROS
-- ============================================================================

CREATE TABLE PARAMETRO (
  CLAVE               VARCHAR2(60)  NOT NULL,
  VALOR               NUMBER(10)    NOT NULL,
  DESCRIPCION         VARCHAR2(300) NOT NULL,
  FECHA_CREACION      TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  FECHA_MODIFICACION  TIMESTAMP,
  CONSTRAINT PK_PARAMETRO       PRIMARY KEY (CLAVE),
  CONSTRAINT CK_PARAMETRO_VALOR CHECK (VALOR > 0)
);

-- ============================================================================
-- COMENTARIOS
-- ============================================================================
COMMENT ON TABLE COLA_EVENTO  IS 'Cola de eventos asincronos (outbox): pagos notificados, correos y sincronizacion con el ERP.';
COMMENT ON TABLE PEDIDO_ERP   IS 'Id que asigno el ERP a cada pedido sincronizado; su existencia indica pedido ya sincronizado.';
COMMENT ON TABLE CIERRE_DIARIO IS 'Cierre de cada dia con pedidos pagados contra pagos aprobados; solo insercion.';
COMMENT ON TABLE PARAMETRO    IS 'Umbrales numericos configurables que usan los JOBs y los paquetes.';

COMMENT ON COLUMN COLA_EVENTO.CLAVE_IDEMPOTENCIA IS 'Identifica el evento de forma unica; evita encolarlo dos veces. Sugerido: TIPO:ENTIDAD:ID.';
COMMENT ON COLUMN COLA_EVENTO.PROXIMO_INTENTO    IS 'No se procesa antes de esta fecha; el paquete la aumenta en cada reintento.';
COMMENT ON COLUMN COLA_EVENTO.FECHA_BLOQUEO      IS 'Momento en que un consumidor tomo el evento (estado PROCESANDO).';
COMMENT ON COLUMN CIERRE_DIARIO.DIFERENCIA       IS 'TOTAL_PAGADO menos TOTAL_PAGOS_APROBADOS; debe ser 0.';

-- ============================================================================
-- TRIGGERS
-- ============================================================================

-- FECHA_MODIFICACION: mismo mecanismo que en los scripts anteriores.
BEGIN
  FOR t IN (SELECT column_value AS tabla
              FROM TABLE(SYS.ODCIVARCHAR2LIST('COLA_EVENTO', 'PEDIDO_ERP', 'PARAMETRO'))) LOOP
    EXECUTE IMMEDIATE
      'CREATE OR REPLACE TRIGGER TRG_' || t.tabla || '_FMOD '
      || 'BEFORE UPDATE ON ' || t.tabla || ' FOR EACH ROW '
      || 'BEGIN :NEW.FECHA_MODIFICACION := SYSTIMESTAMP; END;';
  END LOOP;
END;
/

-- Tabla de solo insercion: se rechaza cualquier UPDATE o DELETE.
CREATE OR REPLACE TRIGGER TRG_CIERRE_DIARIO_INMUT
BEFORE UPDATE OR DELETE ON CIERRE_DIARIO
BEGIN
  RAISE_APPLICATION_ERROR(-20010, 'CIERRE_DIARIO es de solo insercion: no se edita ni se borra.');
END;
/

-- ============================================================================
-- DATOS INICIALES
-- ============================================================================
INSERT INTO PARAMETRO (CLAVE, VALOR, DESCRIPCION)
VALUES ('PEDIDO_EXPIRACION_MINUTOS', 30,
        'Minutos que un pedido PENDIENTE_PAGO espera antes de expirar y liberar su stock.');
INSERT INTO PARAMETRO (CLAVE, VALOR, DESCRIPCION)
VALUES ('CARRITO_ABANDONO_HORAS', 24,
        'Horas sin actividad para considerar abandonado un carrito con productos.');
INSERT INTO PARAMETRO (CLAVE, VALOR, DESCRIPCION)
VALUES ('COLA_MAX_INTENTOS', 5,
        'Intentos maximos de un evento antes de pasar a ERROR para revision manual.');
INSERT INTO PARAMETRO (CLAVE, VALOR, DESCRIPCION)
VALUES ('COLA_RETENCION_DIAS', 90,
        'Dias que se conservan los eventos PROCESADO antes de purgarlos.');
INSERT INTO PARAMETRO (CLAVE, VALOR, DESCRIPCION)
VALUES ('COLA_PROCESANDO_MAX_MINUTOS', 10,
        'Minutos maximos en PROCESANDO antes de devolver el evento a PENDIENTE.');

COMMIT;

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT COUNT(*) AS tablas FROM user_tables;                                  -- 28

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';                                                    -- 0 filas

SELECT COUNT(*) AS triggers_habilitados
  FROM user_triggers
 WHERE status = 'ENABLED';                                                   -- 30

SELECT clave, valor FROM PARAMETRO ORDER BY clave;                           -- 5 filas

-- ============================================================================
-- PRUEBAS RAPIDAS (opcionales; no dejan datos porque terminan con ROLLBACK)
-- Ejecutalas con F5 despues del script. Esperado: tres lineas "OK".
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
BEGIN
  -- 1) La misma clave de idempotencia no entra dos veces.
  INSERT INTO COLA_EVENTO (TIPO_EVENTO, CLAVE_IDEMPOTENCIA, PAYLOAD)
  VALUES ('ENVIAR_CORREO', 'PRUEBA:1', '{"plantilla":"prueba"}');
  BEGIN
    INSERT INTO COLA_EVENTO (TIPO_EVENTO, CLAVE_IDEMPOTENCIA, PAYLOAD)
    VALUES ('ENVIAR_CORREO', 'PRUEBA:1', '{"plantilla":"prueba"}');
    DBMS_OUTPUT.PUT_LINE('1 FALLO: acepto la clave repetida');
  EXCEPTION WHEN DUP_VAL_ON_INDEX THEN
    DBMS_OUTPUT.PUT_LINE('1 OK: clave de idempotencia repetida rechazada');
  END;

  -- 2) Un evento PROCESANDO exige FECHA_BLOQUEO.
  BEGIN
    INSERT INTO COLA_EVENTO (TIPO_EVENTO, CLAVE_IDEMPOTENCIA, PAYLOAD, ESTADO)
    VALUES ('ENVIAR_CORREO', 'PRUEBA:2', '{"plantilla":"prueba"}', 'PROCESANDO');
    DBMS_OUTPUT.PUT_LINE('2 FALLO: acepto PROCESANDO sin FECHA_BLOQUEO');
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE = -2290 THEN
      DBMS_OUTPUT.PUT_LINE('2 OK: PROCESANDO sin FECHA_BLOQUEO rechazado');
    ELSE
      RAISE;
    END IF;
  END;

  -- 3) Un cierre ya insertado no se puede editar.
  INSERT INTO CIERRE_DIARIO (FECHA, PEDIDOS_PAGADOS, SUBTOTAL, IMPUESTO, TOTAL_PAGADO, TOTAL_PAGOS_APROBADOS)
  VALUES (DATE '2000-01-01', 0, 0, 0, 0, 0);
  BEGIN
    UPDATE CIERRE_DIARIO SET PEDIDOS_PAGADOS = 1 WHERE FECHA = DATE '2000-01-01';
    DBMS_OUTPUT.PUT_LINE('3 FALLO: permitio editar el cierre');
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE = -20010 THEN
      DBMS_OUTPUT.PUT_LINE('3 OK: cierre de solo insercion');
    ELSE
      RAISE;
    END IF;
  END;

  ROLLBACK;
EXCEPTION WHEN OTHERS THEN
  ROLLBACK;
  RAISE;
END;
/

-- ============================================================================
-- DESHACER (solo desarrollo): quita el comentario para volver al estado del 06.
-- ============================================================================
-- DROP TABLE PARAMETRO     CASCADE CONSTRAINTS PURGE;
-- DROP TABLE CIERRE_DIARIO CASCADE CONSTRAINTS PURGE;
-- DROP TABLE PEDIDO_ERP    CASCADE CONSTRAINTS PURGE;
-- DROP TABLE COLA_EVENTO   CASCADE CONSTRAINTS PURGE;
