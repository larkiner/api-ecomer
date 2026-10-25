-- ============================================================================
-- 01b_crear_usuario_integracion.sql
-- Crea el tercer usuario de aplicacion: INTEGRACION.
--
-- POR QUE UN USUARIO APARTE
--   El usuario de la tienda (ECOMERS_CLIENTE_APP) atiende a compradores. Si con
--   sus credenciales se pudiera llamar al procedimiento que registra la
--   notificacion de una pasarela, quien las comprometa podria fabricar un pago
--   aprobado. Por eso el webhook de la pasarela y el consumidor de la cola
--   (correos y ERP) se conectan con ECOMERS_INTEGRACION_APP, que solo recibira
--   EXECUTE sobre PKG_PAGO y PKG_COLA cuando esos paquetes existan.
--
-- COMO EJECUTARLO
--   1. Conectate como SYSTEM al servicio XEPDB1 (igual que el script 01).
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. La clave queda escrita en este archivo en texto plano, igual que en el
--      01: guardala en la configuracion del backend y no subas el archivo a un
--      repositorio. En produccion se cambia con ALTER USER.
--   4. Para deshacerlo: el script 02 ya elimina este usuario y este rol.
-- ============================================================================

SET DEFINE ON
SET VERIFY OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

DEFINE v_clave_integracion = 'CAMBIAR_ANTES_DE_EJECUTAR'

-- Las claves no se guardan en el repositorio: cambia los DEFINE de arriba antes
-- de ejecutar. El harness de pruebas del backend los reemplaza solo.
BEGIN
  IF '&v_clave_integracion' = 'CAMBIAR_ANTES_DE_EJECUTAR' THEN
    RAISE_APPLICATION_ERROR(-20003,
      'Cambia las claves de los DEFINE antes de ejecutar este script.');
  END IF;
END;
/

DECLARE
  v_existe NUMBER;
BEGIN
  IF SYS_CONTEXT('USERENV', 'CON_NAME') <> 'XEPDB1' THEN
    RAISE_APPLICATION_ERROR(-20001,
      'Conectate al servicio XEPDB1 antes de ejecutar este script.');
  END IF;

  SELECT COUNT(*) INTO v_existe FROM dba_users WHERE username = 'ECOMERS';
  IF v_existe = 0 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'No existe el usuario ECOMERS: ejecuta antes el script 01.');
  END IF;
END;
/

CREATE ROLE ROL_ECOMERS_INTEGRACION;
GRANT CREATE SESSION TO ROL_ECOMERS_INTEGRACION;

-- Como los otros usuarios de aplicacion: no es duenio de nada, solo se conecta
-- y usa lo que su rol le permita.
CREATE USER ECOMERS_INTEGRACION_APP IDENTIFIED BY "&v_clave_integracion";
GRANT ROL_ECOMERS_INTEGRACION TO ECOMERS_INTEGRACION_APP;

UNDEFINE v_clave_integracion

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT username, account_status
  FROM dba_users
 WHERE username LIKE 'ECOMERS%'
 ORDER BY username;                                                          -- 4 filas, todas OPEN

SELECT grantee, granted_role
  FROM dba_role_privs
 WHERE grantee LIKE 'ECOMERS%'
 ORDER BY grantee;                                                           -- 3 filas (cliente, empleado, integracion)

SELECT grantee, privilege
  FROM dba_sys_privs
 WHERE grantee = 'ROL_ECOMERS_INTEGRACION';                                  -- CREATE SESSION
