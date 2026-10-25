-- ============================================================================
-- 01_crear_ecomers.sql
-- Crea el esquema ECOMERS en Oracle Database XE 21c.
--
-- En Oracle XE la base de datos ya existe (CDB "XE" y PDB "XEPDB1"). Lo que se
-- crea aqui es: un tablespace propio, el usuario duenio de las tablas y los
-- usuarios de aplicacion (uno por rol) que solo recibiran permisos por rol.
--
-- COMO EJECUTARLO
--   1. Conectate como SYSTEM al servicio XEPDB1 (NO al servicio XE).
--   2. Cambia las tres claves de abajo (no uses el simbolo ampersand en ellas).
--   3. Ejecuta como script completo (F5 en SQL Developer), no con Ctrl+Enter.
--   4. El script calcula solo la carpeta de los archivos de datos de tu PDB.
-- ============================================================================

SET DEFINE ON
WHENEVER SQLERROR EXIT SQL.SQLCODE

DEFINE v_clave_owner    = 'CAMBIAR_ANTES_DE_EJECUTAR'
DEFINE v_clave_cliente  = 'CAMBIAR_ANTES_DE_EJECUTAR'
DEFINE v_clave_empleado = 'CAMBIAR_ANTES_DE_EJECUTAR'

-- Las claves no se guardan en el repositorio: cambia los DEFINE de arriba antes
-- de ejecutar. El harness de pruebas del backend los reemplaza solo.
BEGIN
  IF '&v_clave_owner' = 'CAMBIAR_ANTES_DE_EJECUTAR'
     OR '&v_clave_cliente' = 'CAMBIAR_ANTES_DE_EJECUTAR'
     OR '&v_clave_empleado' = 'CAMBIAR_ANTES_DE_EJECUTAR' THEN
    RAISE_APPLICATION_ERROR(-20003,
      'Cambia las claves de los DEFINE antes de ejecutar este script.');
  END IF;
END;
/

-- Protege contra ejecutar el script en el contenedor equivocado.
BEGIN
  IF SYS_CONTEXT('USERENV', 'CON_NAME') <> 'XEPDB1' THEN
    RAISE_APPLICATION_ERROR(-20001,
      'Conectate al servicio XEPDB1 antes de ejecutar este script.');
  END IF;
END;
/

-- 1. Tablespace propio. Esta instancia no crea archivos de datos sola, asi que se
--    calcula la carpeta de los archivos de la PDB (sirve en Windows y Linux).
--    El limite de 8G queda por debajo de los 12 GB de datos de usuario de XE.
DECLARE
  v_archivo VARCHAR2(512);
  v_carpeta VARCHAR2(512);
BEGIN
  SELECT file_name
    INTO v_archivo
    FROM dba_data_files
   WHERE tablespace_name = 'SYSTEM'
     AND ROWNUM = 1;

  v_carpeta := SUBSTR(v_archivo, 1,
                      GREATEST(INSTR(v_archivo, '\', -1), INSTR(v_archivo, '/', -1)));

  EXECUTE IMMEDIATE
    'CREATE TABLESPACE ECOMERS_DATA DATAFILE ''' || v_carpeta || 'ECOMERS_DATA01.DBF'''
    || ' SIZE 200M AUTOEXTEND ON NEXT 50M MAXSIZE 8G'
    || ' EXTENT MANAGEMENT LOCAL SEGMENT SPACE MANAGEMENT AUTO';
END;
/

-- 2. Usuario duenio del esquema. Aqui se crearan tablas, vistas, paquetes y JOBs.
CREATE USER ECOMERS
  IDENTIFIED BY "&v_clave_owner"
  DEFAULT TABLESPACE ECOMERS_DATA
  QUOTA UNLIMITED ON ECOMERS_DATA;

-- Permisos explicitos y minimos (se evita el rol RESOURCE, que es muy amplio).
GRANT CREATE SESSION   TO ECOMERS;
GRANT CREATE TABLE     TO ECOMERS;
GRANT CREATE VIEW      TO ECOMERS;
GRANT CREATE SEQUENCE  TO ECOMERS;
GRANT CREATE PROCEDURE TO ECOMERS;  -- incluye paquetes y funciones
GRANT CREATE TRIGGER   TO ECOMERS;
GRANT CREATE JOB       TO ECOMERS;  -- necesario para DBMS_SCHEDULER

-- 3. Roles de aplicacion. Los EXECUTE sobre los paquetes se les asignan en
--    scripts posteriores, cuando los paquetes existan.
CREATE ROLE ROL_ECOMERS_CLIENTE;
CREATE ROLE ROL_ECOMERS_EMPLEADO;
GRANT CREATE SESSION TO ROL_ECOMERS_CLIENTE;
GRANT CREATE SESSION TO ROL_ECOMERS_EMPLEADO;

-- 4. Usuarios de aplicacion. No son duenios de nada: solo se conectan y usan
--    lo que su rol les permita.
CREATE USER ECOMERS_CLIENTE_APP  IDENTIFIED BY "&v_clave_cliente";
CREATE USER ECOMERS_EMPLEADO_APP IDENTIFIED BY "&v_clave_empleado";
GRANT ROL_ECOMERS_CLIENTE  TO ECOMERS_CLIENTE_APP;
GRANT ROL_ECOMERS_EMPLEADO TO ECOMERS_EMPLEADO_APP;

-- 5. Verificacion
SELECT username, default_tablespace, account_status
  FROM dba_users
 WHERE username LIKE 'ECOMERS%'
 ORDER BY username;

SELECT tablespace_name, status, contents
  FROM dba_tablespaces
 WHERE tablespace_name = 'ECOMERS_DATA';

SELECT grantee, privilege
  FROM dba_sys_privs
 WHERE grantee IN ('ECOMERS', 'ROL_ECOMERS_CLIENTE', 'ROL_ECOMERS_EMPLEADO')
 ORDER BY grantee, privilege;
