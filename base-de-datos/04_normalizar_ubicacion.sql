-- ============================================================================
-- 04_normalizar_ubicacion.sql
-- Normaliza la ubicacion de las direcciones: crea DEPARTAMENTO y MUNICIPIO
-- (codigos DANE / DIVIPOLA) y reemplaza las columnas de texto libre CIUDAD y
-- DEPARTAMENTO de CLIENTE_DIRECCION por una clave foranea a MUNICIPIO.
--
-- Corrige una dependencia transitiva (ciudad -> departamento) y deja los codigos
-- oficiales listos para facturacion electronica y reportes por region.
--
-- Los datos de DEPARTAMENTO y MUNICIPIO se cargan en 05_datos_divipola.sql.
--
-- EXCEPCION A LAS CONVENCIONES: estas dos tablas usan el codigo DANE como clave
-- primaria en lugar de un ID numerico, porque es un codigo oficial, estable y es
-- el que se necesita al facturar. Se guarda como VARCHAR2 (no CHAR) y un CHECK
-- obliga a que tenga exactamente 2 o 5 digitos.
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1.
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Solo funciona si CLIENTE_DIRECCION esta vacia (el script lo comprueba).
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

DECLARE
  v_filas NUMBER;
BEGIN
  IF SYS_CONTEXT('USERENV', 'SESSION_USER') <> 'ECOMERS'
     OR SYS_CONTEXT('USERENV', 'CON_NAME') <> 'XEPDB1' THEN
    RAISE_APPLICATION_ERROR(-20001,
      'Conectate como ECOMERS al servicio XEPDB1 antes de ejecutar este script.');
  END IF;

  SELECT COUNT(*) INTO v_filas FROM CLIENTE_DIRECCION;
  IF v_filas > 0 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'CLIENTE_DIRECCION ya tiene datos: migra esas direcciones a MUNICIPIO antes de continuar.');
  END IF;
END;
/

CREATE TABLE DEPARTAMENTO (
  CODIGO_DANE         VARCHAR2(2)   NOT NULL,
  NOMBRE              VARCHAR2(100) NOT NULL,
  ACTIVO              NUMBER(1)     DEFAULT 1 NOT NULL,
  FECHA_CREACION      TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  FECHA_MODIFICACION  TIMESTAMP,
  CONSTRAINT PK_DEPARTAMENTO        PRIMARY KEY (CODIGO_DANE),
  CONSTRAINT UQ_DEPARTAMENTO_NOMBRE UNIQUE (NOMBRE),
  CONSTRAINT CK_DEPARTAMENTO_CODIGO CHECK (REGEXP_LIKE(CODIGO_DANE, '^[0-9]{2}$')),
  CONSTRAINT CK_DEPARTAMENTO_ACTIVO CHECK (ACTIVO IN (0, 1))
);

CREATE TABLE MUNICIPIO (
  CODIGO_DANE          VARCHAR2(5)   NOT NULL,
  CODIGO_DEPARTAMENTO  VARCHAR2(2)   NOT NULL,
  NOMBRE               VARCHAR2(150) NOT NULL,
  ACTIVO               NUMBER(1)     DEFAULT 1 NOT NULL,
  FECHA_CREACION       TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  FECHA_MODIFICACION   TIMESTAMP,
  CONSTRAINT PK_MUNICIPIO              PRIMARY KEY (CODIGO_DANE),
  CONSTRAINT FK_MUNICIPIO_DEPARTAMENTO FOREIGN KEY (CODIGO_DEPARTAMENTO)
                                       REFERENCES DEPARTAMENTO (CODIGO_DANE),
  CONSTRAINT CK_MUNICIPIO_CODIGO       CHECK (REGEXP_LIKE(CODIGO_DANE, '^[0-9]{5}$')),
  -- Los dos primeros digitos del municipio son el codigo de su departamento.
  CONSTRAINT CK_MUNICIPIO_DEPTO        CHECK (SUBSTR(CODIGO_DANE, 1, 2) = CODIGO_DEPARTAMENTO),
  CONSTRAINT CK_MUNICIPIO_ACTIVO       CHECK (ACTIVO IN (0, 1))
);

CREATE INDEX IX_MUNICIPIO_DEPARTAMENTO ON MUNICIPIO (CODIGO_DEPARTAMENTO, NOMBRE);

-- CLIENTE_DIRECCION: se quitan las dos columnas de texto libre y se agrega la FK.
-- La nueva columna queda al final de la tabla (solo es cosmetico).
ALTER TABLE CLIENTE_DIRECCION DROP (CIUDAD, DEPARTAMENTO);

ALTER TABLE CLIENTE_DIRECCION ADD (CODIGO_MUNICIPIO VARCHAR2(5) NOT NULL);

ALTER TABLE CLIENTE_DIRECCION ADD CONSTRAINT FK_CLI_DIR_MUNICIPIO
  FOREIGN KEY (CODIGO_MUNICIPIO) REFERENCES MUNICIPIO (CODIGO_DANE);

CREATE INDEX IX_CLI_DIR_MUNICIPIO ON CLIENTE_DIRECCION (CODIGO_MUNICIPIO);

COMMENT ON TABLE DEPARTAMENTO IS 'Departamentos de Colombia (codigo DANE / DIVIPOLA).';
COMMENT ON TABLE MUNICIPIO    IS 'Municipios de Colombia (codigo DANE / DIVIPOLA).';
COMMENT ON TABLE CLIENTE_DIRECCION IS 'Direcciones de facturacion del cliente (ubicacion por codigo de municipio); una sola principal activa.';

-- FECHA_MODIFICACION: mismo mecanismo que en el script 03.
BEGIN
  FOR t IN (SELECT column_value AS tabla
              FROM TABLE(SYS.ODCIVARCHAR2LIST('DEPARTAMENTO', 'MUNICIPIO'))) LOOP
    EXECUTE IMMEDIATE
      'CREATE OR REPLACE TRIGGER TRG_' || t.tabla || '_FMOD '
      || 'BEFORE UPDATE ON ' || t.tabla || ' FOR EACH ROW '
      || 'BEGIN :NEW.FECHA_MODIFICACION := SYSTIMESTAMP; END;';
  END LOOP;
END;
/

-- El ALTER TABLE invalida el trigger de CLIENTE_DIRECCION: se recompila aqui.
ALTER TRIGGER TRG_CLIENTE_DIRECCION_FMOD COMPILE;

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT COUNT(*) AS tablas FROM user_tables;                                  -- 16

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';                                                    -- 0 filas

SELECT column_name, data_type, nullable
  FROM user_tab_columns
 WHERE table_name = 'CLIENTE_DIRECCION'
 ORDER BY column_id;                                                         -- sin CIUDAD ni DEPARTAMENTO; con CODIGO_MUNICIPIO NOT NULL

-- ============================================================================
-- DESHACER (solo desarrollo): quita el comentario para volver al estado del 03.
-- ============================================================================
-- ALTER TABLE CLIENTE_DIRECCION DROP COLUMN CODIGO_MUNICIPIO CASCADE CONSTRAINTS;
-- ALTER TABLE CLIENTE_DIRECCION ADD (CIUDAD VARCHAR2(100) NOT NULL, DEPARTAMENTO VARCHAR2(100) NOT NULL);
-- DROP TABLE MUNICIPIO    CASCADE CONSTRAINTS PURGE;
-- DROP TABLE DEPARTAMENTO CASCADE CONSTRAINTS PURGE;
