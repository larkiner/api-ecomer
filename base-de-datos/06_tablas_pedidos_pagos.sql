-- ============================================================================
-- 06_tablas_pedidos_pagos.sql
-- Seccion 3 del diseno: pedidos, reservas de stock y pagos (8 tablas).
--
-- Requiere haber ejecutado antes los scripts 03 y 04 (el 05, con los datos de
-- municipios, no es obligatorio para crear las tablas).
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1.
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Si falla a la mitad, ejecuta el bloque DESHACER del final (esta
--      comentado) y vuelve a correr el script completo.
--
-- DECISIONES DE DISENO
--   * PEDIDO guarda una "foto" fiscal: datos de facturacion y totales copiados al
--     momento de comprar. Un trigger impide modificarlos despues. Por eso el
--     paquete de pedidos debe calcular los totales ANTES de insertar la cabecera.
--   * Los totales de la cabecera repiten lo que suman las lineas (redundancia
--     deliberada). Un CHECK valida la aritmetica de cada fila; que la cabecera
--     coincida con la suma de sus lineas lo verifica el paquete.
--   * El estado actual vive en PEDIDO; PEDIDO_ESTADO_HIST lo escribe un trigger.
--   * RESERVA_STOCK no repite variante, cantidad ni expiracion: salen de
--     PEDIDO_ITEM y PEDIDO, para no duplicar datos.
--   * Un pedido puede tener varios PAGO (reintentos), pero solo uno APROBADO.
--   * Los indices unicos con CASE evitan el problema de las claves compuestas con
--     NULL: en Oracle (1, NULL) repetido SI viola una restriccion UNIQUE.
--   * El redondeo del impuesto por linea lo decide el paquete (politica contable).
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
   WHERE table_name IN ('CLIENTE', 'CARRITO', 'PROMOCION', 'VARIANTE',
                        'MOVIMIENTO_STOCK', 'MUNICIPIO');
  IF v_existentes < 6 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan tablas previas: ejecuta antes los scripts 03 y 04.');
  END IF;
END;
/

-- ============================================================================
-- PEDIDOS
-- ============================================================================

CREATE TABLE ESTADO_PEDIDO (
  ID_ESTADO_PEDIDO    NUMBER(12) GENERATED ALWAYS AS IDENTITY,
  CODIGO              VARCHAR2(20)  NOT NULL,
  NOMBRE              VARCHAR2(100) NOT NULL,
  ES_FINAL            NUMBER(1)     DEFAULT 0 NOT NULL,   -- 1 = no admite mas cambios en la v1
  ACTIVO              NUMBER(1)     DEFAULT 1 NOT NULL,
  FECHA_CREACION      TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  FECHA_MODIFICACION  TIMESTAMP,
  CONSTRAINT PK_ESTADO_PEDIDO        PRIMARY KEY (ID_ESTADO_PEDIDO),
  CONSTRAINT UQ_ESTADO_PEDIDO_CODIGO UNIQUE (CODIGO),
  CONSTRAINT CK_ESTADO_PEDIDO_FINAL  CHECK (ES_FINAL IN (0, 1)),
  CONSTRAINT CK_ESTADO_PEDIDO_ACTIVO CHECK (ACTIVO IN (0, 1))
);

CREATE TABLE PEDIDO (
  ID_PEDIDO               NUMBER(12) GENERATED ALWAYS AS IDENTITY,
  ID_PUBLICO              RAW(16)       DEFAULT SYS_GUID() NOT NULL,   -- identificador que se expone en la API
  ID_CLIENTE              NUMBER(12)    NOT NULL,
  ID_ESTADO_PEDIDO        NUMBER(12)    NOT NULL,
  MOTIVO_ESTADO           VARCHAR2(500),                  -- motivo del ultimo cambio de estado
  ID_CARRITO              NUMBER(12),                     -- carrito de origen (opcional)
  FECHA_EXPIRACION        TIMESTAMP     NOT NULL,         -- limite para pagar, despues se libera el stock
  -- Foto de facturacion, copiada al comprar. No hay FK a CLIENTE_DIRECCION a proposito.
  FACT_TIPO_DOCUMENTO     VARCHAR2(10)  NOT NULL,
  FACT_NUMERO_DOCUMENTO   VARCHAR2(30)  NOT NULL,
  FACT_NOMBRE             VARCHAR2(200) NOT NULL,
  FACT_EMAIL              VARCHAR2(254) NOT NULL,
  FACT_DIRECCION          VARCHAR2(250) NOT NULL,
  FACT_CODIGO_MUNICIPIO   VARCHAR2(5)   NOT NULL,
  -- Totales. SUBTOTAL ya tiene los descuentos aplicados y DESCUENTO_TOTAL es informativo.
  SUBTOTAL                NUMBER(14,2)  NOT NULL,
  DESCUENTO_TOTAL         NUMBER(14,2)  NOT NULL,
  IMPUESTO_TOTAL          NUMBER(14,2)  NOT NULL,
  TOTAL                   NUMBER(14,2)  NOT NULL,
  FECHA_CREACION          TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  FECHA_MODIFICACION      TIMESTAMP,
  CONSTRAINT PK_PEDIDO            PRIMARY KEY (ID_PEDIDO),
  CONSTRAINT UQ_PEDIDO_ID_PUBLICO UNIQUE (ID_PUBLICO),
  CONSTRAINT UQ_PEDIDO_CARRITO    UNIQUE (ID_CARRITO),    -- un carrito genera como maximo un pedido
  CONSTRAINT FK_PEDIDO_CLIENTE    FOREIGN KEY (ID_CLIENTE)            REFERENCES CLIENTE (ID_CLIENTE),
  CONSTRAINT FK_PEDIDO_ESTADO     FOREIGN KEY (ID_ESTADO_PEDIDO)      REFERENCES ESTADO_PEDIDO (ID_ESTADO_PEDIDO),
  CONSTRAINT FK_PEDIDO_CARRITO    FOREIGN KEY (ID_CARRITO)            REFERENCES CARRITO (ID_CARRITO),
  CONSTRAINT FK_PEDIDO_MUNICIPIO  FOREIGN KEY (FACT_CODIGO_MUNICIPIO) REFERENCES MUNICIPIO (CODIGO_DANE),
  CONSTRAINT CK_PEDIDO_MONTOS     CHECK (SUBTOTAL >= 0 AND DESCUENTO_TOTAL >= 0 AND IMPUESTO_TOTAL >= 0
                                         AND TOTAL = SUBTOTAL + IMPUESTO_TOTAL)
);

CREATE INDEX IX_PEDIDO_CLIENTE   ON PEDIDO (ID_CLIENTE, FECHA_CREACION);
-- Para el JOB que expira pedidos sin pagar.
CREATE INDEX IX_PEDIDO_EXPIRACION ON PEDIDO (ID_ESTADO_PEDIDO, FECHA_EXPIRACION);
CREATE INDEX IX_PEDIDO_MUNICIPIO ON PEDIDO (FACT_CODIGO_MUNICIPIO);

-- Lineas del pedido. Los datos copiados (SKU, nombre, precio, descuento, tasa) no
-- cambian aunque despues cambie el catalogo. Es de solo insercion.
CREATE TABLE PEDIDO_ITEM (
  ID_PEDIDO_ITEM    NUMBER(12) GENERATED ALWAYS AS IDENTITY,
  ID_PEDIDO         NUMBER(12)    NOT NULL,
  ID_VARIANTE       NUMBER(12)    NOT NULL,
  SKU               VARCHAR2(50)  NOT NULL,
  NOMBRE_ITEM       VARCHAR2(350) NOT NULL,     -- producto y variante, tal como se vendio
  CANTIDAD          NUMBER(10)    NOT NULL,
  PRECIO_UNITARIO   NUMBER(14,2)  NOT NULL,
  ID_PROMOCION      NUMBER(12),                 -- promocion aplicada (informativo)
  DESCUENTO_UNITARIO NUMBER(14,2) DEFAULT 0 NOT NULL,
  TASA_IMPUESTO     NUMBER(5,4)   NOT NULL,
  SUBTOTAL_LINEA    NUMBER(14,2)  NOT NULL,
  IMPUESTO_LINEA    NUMBER(14,2)  NOT NULL,
  TOTAL_LINEA       NUMBER(14,2)  NOT NULL,
  FECHA_CREACION    TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT PK_PEDIDO_ITEM           PRIMARY KEY (ID_PEDIDO_ITEM),
  CONSTRAINT FK_PEDIDO_ITEM_PEDIDO    FOREIGN KEY (ID_PEDIDO)    REFERENCES PEDIDO (ID_PEDIDO),
  CONSTRAINT FK_PEDIDO_ITEM_VARIANTE  FOREIGN KEY (ID_VARIANTE)  REFERENCES VARIANTE (ID_VARIANTE),
  CONSTRAINT FK_PEDIDO_ITEM_PROMOCION FOREIGN KEY (ID_PROMOCION) REFERENCES PROMOCION (ID_PROMOCION),
  CONSTRAINT UQ_PEDIDO_ITEM           UNIQUE (ID_PEDIDO, ID_VARIANTE),
  CONSTRAINT CK_PEDIDO_ITEM_CANTIDAD  CHECK (CANTIDAD > 0),
  CONSTRAINT CK_PEDIDO_ITEM_PRECIO    CHECK (PRECIO_UNITARIO >= 0),
  CONSTRAINT CK_PEDIDO_ITEM_DESCUENTO CHECK (DESCUENTO_UNITARIO >= 0 AND DESCUENTO_UNITARIO <= PRECIO_UNITARIO),
  CONSTRAINT CK_PEDIDO_ITEM_TASA      CHECK (TASA_IMPUESTO >= 0 AND TASA_IMPUESTO <= 1),
  CONSTRAINT CK_PEDIDO_ITEM_SUBTOTAL  CHECK (SUBTOTAL_LINEA = (PRECIO_UNITARIO - DESCUENTO_UNITARIO) * CANTIDAD),
  CONSTRAINT CK_PEDIDO_ITEM_IMPUESTO  CHECK (IMPUESTO_LINEA >= 0),
  CONSTRAINT CK_PEDIDO_ITEM_TOTAL     CHECK (TOTAL_LINEA = SUBTOTAL_LINEA + IMPUESTO_LINEA)
);

CREATE INDEX IX_PEDIDO_ITEM_VARIANTE  ON PEDIDO_ITEM (ID_VARIANTE);
CREATE INDEX IX_PEDIDO_ITEM_PROMOCION ON PEDIDO_ITEM (ID_PROMOCION);

-- Historial de estados. Solo insercion: lo escribe el trigger TRG_PEDIDO_ESTADO_HIST.
CREATE TABLE PEDIDO_ESTADO_HIST (
  ID_PEDIDO_ESTADO_HIST NUMBER(12) GENERATED ALWAYS AS IDENTITY,
  ID_PEDIDO             NUMBER(12)    NOT NULL,
  ID_ESTADO_ANTERIOR    NUMBER(12),                 -- NULL al crear el pedido
  ID_ESTADO_NUEVO       NUMBER(12)    NOT NULL,
  MOTIVO                VARCHAR2(500),
  USUARIO               VARCHAR2(128) DEFAULT SYS_CONTEXT('USERENV', 'SESSION_USER') NOT NULL,
  FECHA_CREACION        TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT PK_PEDIDO_ESTADO_HIST    PRIMARY KEY (ID_PEDIDO_ESTADO_HIST),
  CONSTRAINT FK_PED_EST_HIST_PEDIDO   FOREIGN KEY (ID_PEDIDO)          REFERENCES PEDIDO (ID_PEDIDO),
  CONSTRAINT FK_PED_EST_HIST_ANTERIOR FOREIGN KEY (ID_ESTADO_ANTERIOR) REFERENCES ESTADO_PEDIDO (ID_ESTADO_PEDIDO),
  CONSTRAINT FK_PED_EST_HIST_NUEVO    FOREIGN KEY (ID_ESTADO_NUEVO)    REFERENCES ESTADO_PEDIDO (ID_ESTADO_PEDIDO)
);

CREATE INDEX IX_PED_EST_HIST_PEDIDO   ON PEDIDO_ESTADO_HIST (ID_PEDIDO, FECHA_CREACION);
CREATE INDEX IX_PED_EST_HIST_ANTERIOR ON PEDIDO_ESTADO_HIST (ID_ESTADO_ANTERIOR);
CREATE INDEX IX_PED_EST_HIST_NUEVO    ON PEDIDO_ESTADO_HIST (ID_ESTADO_NUEVO);

-- Estado de la reserva de cada linea. La clave primaria es la propia linea: la
-- variante y la cantidad salen de PEDIDO_ITEM, y la expiracion de PEDIDO.
CREATE TABLE RESERVA_STOCK (
  ID_PEDIDO_ITEM      NUMBER(12) NOT NULL,
  ESTADO              VARCHAR2(10) DEFAULT 'ACTIVA' NOT NULL,
  FECHA_CREACION      TIMESTAMP    DEFAULT SYSTIMESTAMP NOT NULL,
  FECHA_MODIFICACION  TIMESTAMP,
  CONSTRAINT PK_RESERVA_STOCK        PRIMARY KEY (ID_PEDIDO_ITEM),
  CONSTRAINT FK_RESERVA_PEDIDO_ITEM  FOREIGN KEY (ID_PEDIDO_ITEM) REFERENCES PEDIDO_ITEM (ID_PEDIDO_ITEM),
  CONSTRAINT CK_RESERVA_STOCK_ESTADO CHECK (ESTADO IN ('ACTIVA', 'CONSUMIDA', 'LIBERADA'))
);

CREATE INDEX IX_RESERVA_STOCK_ESTADO ON RESERVA_STOCK (ESTADO);

-- Ahora que existe PEDIDO, se agrega la clave foranea pendiente del script 03.
ALTER TABLE MOVIMIENTO_STOCK ADD CONSTRAINT FK_MOV_STOCK_PEDIDO
  FOREIGN KEY (ID_PEDIDO) REFERENCES PEDIDO (ID_PEDIDO);

CREATE INDEX IX_MOV_STOCK_PEDIDO ON MOVIMIENTO_STOCK (ID_PEDIDO);

-- ============================================================================
-- PAGOS
-- ============================================================================

CREATE TABLE PASARELA_PAGO (
  ID_PASARELA_PAGO    NUMBER(12) GENERATED ALWAYS AS IDENTITY,
  CODIGO              VARCHAR2(30)  NOT NULL,
  NOMBRE              VARCHAR2(100) NOT NULL,
  ACTIVA              NUMBER(1)     DEFAULT 1 NOT NULL,
  FECHA_CREACION      TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  FECHA_MODIFICACION  TIMESTAMP,
  CONSTRAINT PK_PASARELA_PAGO        PRIMARY KEY (ID_PASARELA_PAGO),
  CONSTRAINT UQ_PASARELA_PAGO_CODIGO UNIQUE (CODIGO),
  CONSTRAINT CK_PASARELA_PAGO_ACTIVA CHECK (ACTIVA IN (0, 1))
);

-- Un intento de pago. Un pedido puede tener varios (reintentos tras un rechazo).
CREATE TABLE PAGO (
  ID_PAGO             NUMBER(12) GENERATED ALWAYS AS IDENTITY,
  ID_PUBLICO          RAW(16)       DEFAULT SYS_GUID() NOT NULL,   -- identificador que se expone en la API
  ID_PEDIDO           NUMBER(12)    NOT NULL,
  ID_PASARELA_PAGO    NUMBER(12)    NOT NULL,
  REFERENCIA_EXTERNA  VARCHAR2(100),                 -- id del pago en la pasarela (se conoce despues de crearlo)
  METODO_PAGO         VARCHAR2(30),                  -- informativo, tal como lo reporta la pasarela
  MONTO               NUMBER(14,2)  NOT NULL,
  MONEDA              VARCHAR2(3)   DEFAULT 'COP' NOT NULL,
  ESTADO              VARCHAR2(10)  DEFAULT 'PENDIENTE' NOT NULL,
  FECHA_CREACION      TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  FECHA_MODIFICACION  TIMESTAMP,
  CONSTRAINT PK_PAGO            PRIMARY KEY (ID_PAGO),
  CONSTRAINT UQ_PAGO_ID_PUBLICO UNIQUE (ID_PUBLICO),
  CONSTRAINT FK_PAGO_PEDIDO     FOREIGN KEY (ID_PEDIDO)        REFERENCES PEDIDO (ID_PEDIDO),
  CONSTRAINT FK_PAGO_PASARELA   FOREIGN KEY (ID_PASARELA_PAGO) REFERENCES PASARELA_PAGO (ID_PASARELA_PAGO),
  CONSTRAINT CK_PAGO_MONTO      CHECK (MONTO > 0),
  CONSTRAINT CK_PAGO_ESTADO     CHECK (ESTADO IN ('PENDIENTE', 'APROBADO', 'RECHAZADO', 'ANULADO'))
);

CREATE INDEX IX_PAGO_PEDIDO   ON PAGO (ID_PEDIDO);
CREATE INDEX IX_PAGO_PASARELA ON PAGO (ID_PASARELA_PAGO);

-- La referencia externa es unica por pasarela, pero puede ser NULL mientras tanto.
CREATE UNIQUE INDEX UX_PAGO_REF_EXTERNA ON PAGO (
  CASE WHEN REFERENCIA_EXTERNA IS NOT NULL THEN ID_PASARELA_PAGO END,
  REFERENCIA_EXTERNA
);

-- Como maximo un pago APROBADO por pedido.
CREATE UNIQUE INDEX UX_PAGO_APROBADO ON PAGO (
  CASE WHEN ESTADO = 'APROBADO' THEN ID_PEDIDO END
);

-- Todo lo que notifica la pasarela, con el mensaje crudo. Solo insercion.
CREATE TABLE PAGO_EVENTO (
  ID_PAGO_EVENTO      NUMBER(12) GENERATED ALWAYS AS IDENTITY,
  ID_PAGO             NUMBER(12)   NOT NULL,
  ID_EVENTO_EXTERNO   VARCHAR2(100),                -- id del evento en la pasarela (idempotencia)
  ESTADO_REPORTADO    VARCHAR2(30),
  PAYLOAD             CLOB         NOT NULL,
  FECHA_CREACION      TIMESTAMP    DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT PK_PAGO_EVENTO      PRIMARY KEY (ID_PAGO_EVENTO),
  CONSTRAINT FK_PAGO_EVENTO_PAGO FOREIGN KEY (ID_PAGO) REFERENCES PAGO (ID_PAGO),
  CONSTRAINT CK_PAGO_EVENTO_JSON CHECK (PAYLOAD IS JSON)
);

CREATE INDEX IX_PAGO_EVENTO_PAGO ON PAGO_EVENTO (ID_PAGO, FECHA_CREACION);

-- Un mismo evento externo no se registra dos veces para el mismo pago.
CREATE UNIQUE INDEX UX_PAGO_EVENTO_EXTERNO ON PAGO_EVENTO (
  CASE WHEN ID_EVENTO_EXTERNO IS NOT NULL THEN ID_PAGO END,
  ID_EVENTO_EXTERNO
);

-- ============================================================================
-- COMENTARIOS DE TABLA
-- ============================================================================
COMMENT ON TABLE ESTADO_PEDIDO       IS 'Catalogo de estados del pedido.';
COMMENT ON TABLE PEDIDO              IS 'Cabecera del pedido con la foto fiscal (facturacion y totales) fijada al comprar.';
COMMENT ON TABLE PEDIDO_ITEM         IS 'Lineas del pedido con precio, descuento e impuesto fijados al comprar; solo insercion.';
COMMENT ON TABLE PEDIDO_ESTADO_HIST  IS 'Historial de cambios de estado del pedido; solo insercion.';
COMMENT ON TABLE RESERVA_STOCK       IS 'Estado de la reserva de stock de cada linea del pedido.';
COMMENT ON TABLE PASARELA_PAGO       IS 'Catalogo de pasarelas de pago.';
COMMENT ON TABLE PAGO                IS 'Intento de pago de un pedido; como maximo uno aprobado por pedido.';
COMMENT ON TABLE PAGO_EVENTO         IS 'Notificaciones de la pasarela con el mensaje crudo en JSON; solo insercion.';

-- ============================================================================
-- TRIGGERS
-- ============================================================================

-- FECHA_MODIFICACION: mismo mecanismo que en los scripts 03 y 04.
BEGIN
  FOR t IN (SELECT column_value AS tabla
              FROM TABLE(SYS.ODCIVARCHAR2LIST(
                     'ESTADO_PEDIDO', 'PEDIDO', 'RESERVA_STOCK', 'PASARELA_PAGO', 'PAGO'))) LOOP
    EXECUTE IMMEDIATE
      'CREATE OR REPLACE TRIGGER TRG_' || t.tabla || '_FMOD '
      || 'BEFORE UPDATE ON ' || t.tabla || ' FOR EACH ROW '
      || 'BEGIN :NEW.FECHA_MODIFICACION := SYSTIMESTAMP; END;';
  END LOOP;
END;
/

-- Historial de estados: registra el estado inicial y cada cambio. El motivo sale
-- de PEDIDO.MOTIVO_ESTADO, que el paquete fija junto con el cambio de estado.
CREATE OR REPLACE TRIGGER TRG_PEDIDO_ESTADO_HIST
AFTER INSERT OR UPDATE OF ID_ESTADO_PEDIDO ON PEDIDO
FOR EACH ROW
WHEN (OLD.ID_ESTADO_PEDIDO IS NULL OR OLD.ID_ESTADO_PEDIDO <> NEW.ID_ESTADO_PEDIDO)
BEGIN
  INSERT INTO PEDIDO_ESTADO_HIST (ID_PEDIDO, ID_ESTADO_ANTERIOR, ID_ESTADO_NUEVO, MOTIVO, USUARIO)
  VALUES (:NEW.ID_PEDIDO, :OLD.ID_ESTADO_PEDIDO, :NEW.ID_ESTADO_PEDIDO, :NEW.MOTIVO_ESTADO,
          NVL(SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER'),
              SYS_CONTEXT('USERENV', 'SESSION_USER')));
END;
/

-- La foto del pedido no se modifica despues de crearlo.
CREATE OR REPLACE TRIGGER TRG_PEDIDO_FOTO_INMUT
BEFORE UPDATE OF ID_CLIENTE, ID_CARRITO, FACT_TIPO_DOCUMENTO, FACT_NUMERO_DOCUMENTO,
                 FACT_NOMBRE, FACT_EMAIL, FACT_DIRECCION, FACT_CODIGO_MUNICIPIO,
                 SUBTOTAL, DESCUENTO_TOTAL, IMPUESTO_TOTAL, TOTAL ON PEDIDO
FOR EACH ROW
BEGIN
  RAISE_APPLICATION_ERROR(-20011,
    'La foto del pedido (cliente, facturacion y totales) no se modifica una vez creado.');
END;
/

-- Tablas de solo insercion: se rechaza cualquier UPDATE o DELETE.
CREATE OR REPLACE TRIGGER TRG_PEDIDO_ITEM_INMUT
BEFORE UPDATE OR DELETE ON PEDIDO_ITEM
BEGIN
  RAISE_APPLICATION_ERROR(-20010, 'PEDIDO_ITEM es de solo insercion: no se edita ni se borra.');
END;
/

CREATE OR REPLACE TRIGGER TRG_PEDIDO_ESTADO_HIST_INMUT
BEFORE UPDATE OR DELETE ON PEDIDO_ESTADO_HIST
BEGIN
  RAISE_APPLICATION_ERROR(-20010, 'PEDIDO_ESTADO_HIST es de solo insercion: no se edita ni se borra.');
END;
/

CREATE OR REPLACE TRIGGER TRG_PAGO_EVENTO_INMUT
BEFORE UPDATE OR DELETE ON PAGO_EVENTO
BEGIN
  RAISE_APPLICATION_ERROR(-20010, 'PAGO_EVENTO es de solo insercion: no se edita ni se borra.');
END;
/

-- ============================================================================
-- DATOS INICIALES
-- ============================================================================
INSERT INTO ESTADO_PEDIDO (CODIGO, NOMBRE, ES_FINAL) VALUES ('PENDIENTE_PAGO', 'Pendiente de pago', 0);
INSERT INTO ESTADO_PEDIDO (CODIGO, NOMBRE, ES_FINAL) VALUES ('PAGADO',         'Pagado',            1);
INSERT INTO ESTADO_PEDIDO (CODIGO, NOMBRE, ES_FINAL) VALUES ('CANCELADO',      'Cancelado',         1);
INSERT INTO ESTADO_PEDIDO (CODIGO, NOMBRE, ES_FINAL) VALUES ('EXPIRADO',       'Expirado',          1);

COMMIT;

-- PASARELA_PAGO queda vacia a proposito: se inserta la pasarela cuando se defina.

-- Recompila lo que haya quedado invalido por los ALTER TABLE.
BEGIN
  DBMS_UTILITY.COMPILE_SCHEMA(schema => USER, compile_all => FALSE);
END;
/

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT COUNT(*) AS tablas FROM user_tables;                                  -- 24

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';                                                    -- 0 filas

SELECT COUNT(*) AS triggers_habilitados
  FROM user_triggers
 WHERE status = 'ENABLED';                                                   -- 26

SELECT codigo, es_final FROM ESTADO_PEDIDO ORDER BY id_estado_pedido;        -- 4 filas

SELECT constraint_name, status
  FROM user_constraints
 WHERE constraint_name = 'FK_MOV_STOCK_PEDIDO';                              -- 1 fila, ENABLED

-- ============================================================================
-- DESHACER (solo desarrollo): quita el comentario para volver al estado del 04.
-- ============================================================================
-- ALTER TABLE MOVIMIENTO_STOCK DROP CONSTRAINT FK_MOV_STOCK_PEDIDO;
-- DROP INDEX IX_MOV_STOCK_PEDIDO;
-- DROP TABLE PAGO_EVENTO         CASCADE CONSTRAINTS PURGE;
-- DROP TABLE PAGO                CASCADE CONSTRAINTS PURGE;
-- DROP TABLE PASARELA_PAGO       CASCADE CONSTRAINTS PURGE;
-- DROP TABLE RESERVA_STOCK       CASCADE CONSTRAINTS PURGE;
-- DROP TABLE PEDIDO_ESTADO_HIST  CASCADE CONSTRAINTS PURGE;
-- DROP TABLE PEDIDO_ITEM         CASCADE CONSTRAINTS PURGE;
-- DROP TABLE PEDIDO              CASCADE CONSTRAINTS PURGE;
-- DROP TABLE ESTADO_PEDIDO       CASCADE CONSTRAINTS PURGE;
