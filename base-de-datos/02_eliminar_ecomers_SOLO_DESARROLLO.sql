-- ============================================================================
-- 02_eliminar_ecomers_SOLO_DESARROLLO.sql
-- ATENCION: borra de forma IRREVERSIBLE el esquema ECOMERS con todas sus tablas
-- y datos, los usuarios de aplicacion, los roles y el tablespace.
-- Usalo solo en desarrollo, para volver a empezar desde cero.
--
-- Conectate como SYSTEM al servicio XEPDB1 y cierra antes las conexiones abiertas
-- de ECOMERS, ECOMERS_CLIENTE_APP, ECOMERS_EMPLEADO_APP y ECOMERS_INTEGRACION_APP
-- (si no, ORA-01940).
-- ============================================================================

WHENEVER SQLERROR EXIT SQL.SQLCODE

BEGIN
  IF SYS_CONTEXT('USERENV', 'CON_NAME') <> 'XEPDB1' THEN
    RAISE_APPLICATION_ERROR(-20001,
      'Conectate al servicio XEPDB1 antes de ejecutar este script.');
  END IF;
END;
/

-- El usuario y el rol de integracion (script 01b) pueden no existir: se borran
-- solo si estan.
BEGIN
  FOR u IN (SELECT username FROM dba_users WHERE username = 'ECOMERS_INTEGRACION_APP') LOOP
    EXECUTE IMMEDIATE 'DROP USER ECOMERS_INTEGRACION_APP';
  END LOOP;
  FOR r IN (SELECT role FROM dba_roles WHERE role = 'ROL_ECOMERS_INTEGRACION') LOOP
    EXECUTE IMMEDIATE 'DROP ROLE ROL_ECOMERS_INTEGRACION';
  END LOOP;
END;
/

DROP USER ECOMERS_CLIENTE_APP;
DROP USER ECOMERS_EMPLEADO_APP;
DROP USER ECOMERS CASCADE;
DROP ROLE ROL_ECOMERS_CLIENTE;
DROP ROLE ROL_ECOMERS_EMPLEADO;
DROP TABLESPACE ECOMERS_DATA INCLUDING CONTENTS AND DATAFILES;
