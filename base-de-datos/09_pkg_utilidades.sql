-- ============================================================================
-- 09_pkg_utilidades.sql
-- Seccion 5 del diseno (parte 2): paquete PKG_UTILIDADES.
-- Da a los demas paquetes la hora actual del servidor y la lectura de parametros.
--
-- Requiere haber ejecutado antes los scripts 07 y 08.
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1.
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Al final salen tres lineas "OK" de pruebas que no dejan datos.
--   4. Para deshacerlo: DROP PACKAGE PKG_UTILIDADES;
--
-- DECISIONES DE DISENO
--   * AUTHID DEFINER (el valor por defecto, escrito a proposito): el paquete se
--     ejecuta con los permisos de su duenio ECOMERS. Por eso los roles de la
--     aplicacion nunca necesitan permisos sobre las tablas.
--   * ahora() devuelve CAST(SYSTIMESTAMP AS TIMESTAMP): la misma convencion con la
--     que se llenan las columnas TIMESTAMP (hora local del servidor, sin zona).
--     Todo el codigo debe usar ahora() y no SYSTIMESTAMP a secas.
--   * No existe una funcion "dia_bogota": el servidor esta en -05:00 (este script
--     lo comprueba y se detiene si no es asi), asi que el dia es TRUNC de la hora
--     del servidor. Un envoltorio aparentaria una flexibilidad que las vistas por
--     dia (script 08) tampoco tienen. Si algun dia cambia la zona del servidor hay
--     que revisar esas vistas y este paquete.
--   * valor_parametro() falla con error si la clave no existe. Devolver NULL haria que
--     un JOB comparara contra NULL y no hiciera nada, sin avisar.
--   * valor_parametro() no usa cache: se llama poco (JOBs y creacion de pedidos) y asi
--     un cambio en la tabla PARAMETRO se aplica de inmediato.
--   * La funcion NO se llama "parametro" a proposito: dentro de un paquete, un
--     nombre de subprograma tiene prioridad sobre el de una tabla en el codigo
--     PL/SQL, y PARAMETRO.VALOR%TYPE dejaba de resolver a la tabla (PLS-00302).
--     Regla: no nombrar subprogramas como las tablas que ese mismo paquete usa.
--
-- CODIGOS DE ERROR DE LOS PAQUETES (cada paquete tiene un bloque de 20 codigos;
-- el backend puede traducirlos a mensajes). Los -20001 a -20011 ya se usan en
-- los scripts y los triggers.
--   -20100 a -20119  PKG_UTILIDADES y PKG_PRECIOS
--   -20120 a -20139  PKG_INVENTARIO
--   -20140 a -20159  PKG_CARRITO
--   -20160 a -20179  PKG_PEDIDO
--   -20180 a -20199  PKG_PAGO
--   -20200 a -20219  PKG_COLA y PKG_TAREAS
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
   WHERE table_name = 'PARAMETRO';
  IF v_existentes < 1 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Falta la tabla PARAMETRO: ejecuta antes los scripts 07 y 08.');
  END IF;

  IF TO_CHAR(SYSTIMESTAMP, 'TZH:TZM') <> '-05:00' THEN
    RAISE_APPLICATION_ERROR(-20003,
      'El servidor de la BD no esta en hora de Colombia (-05:00): es ' ||
      TO_CHAR(SYSTIMESTAMP, 'TZH:TZM') ||
      '. Avisa antes de continuar: hay que ajustar las vistas por dia y este paquete.');
  END IF;
END;
/

CREATE OR REPLACE PACKAGE PKG_UTILIDADES
  AUTHID DEFINER
AS
  -- Hora actual del servidor, sin zona horaria (igual que las columnas TIMESTAMP).
  FUNCTION ahora RETURN TIMESTAMP;

  -- Dia de hoy, sin hora.
  FUNCTION hoy RETURN DATE;

  -- Valor de un parametro de la tabla PARAMETRO.
  -- Error -20100 si la clave no existe.
  FUNCTION valor_parametro(p_clave IN VARCHAR2) RETURN NUMBER;
END PKG_UTILIDADES;
/

CREATE OR REPLACE PACKAGE BODY PKG_UTILIDADES
AS
  FUNCTION ahora RETURN TIMESTAMP IS
  BEGIN
    RETURN CAST(SYSTIMESTAMP AS TIMESTAMP);
  END ahora;

  FUNCTION hoy RETURN DATE IS
  BEGIN
    RETURN TRUNC(CAST(SYSTIMESTAMP AS TIMESTAMP));
  END hoy;

  FUNCTION valor_parametro(p_clave IN VARCHAR2) RETURN NUMBER IS
    v_valor PARAMETRO.VALOR%TYPE;
  BEGIN
    SELECT VALOR
      INTO v_valor
      FROM PARAMETRO
     WHERE CLAVE = p_clave;
    RETURN v_valor;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RAISE_APPLICATION_ERROR(-20100, 'No existe el parametro ' || p_clave || '.');
  END valor_parametro;
END PKG_UTILIDADES;
/

-- CREATE PACKAGE no se detiene ante errores de compilacion (solo avisa), asi que
-- se revisa USER_ERRORS de forma explicita.
DECLARE
  v_errores NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_errores
    FROM user_errors
   WHERE name = 'PKG_UTILIDADES';
  IF v_errores > 0 THEN
    RAISE_APPLICATION_ERROR(-20004,
      'PKG_UTILIDADES compilo con errores: consulta USER_ERRORS.');
  END IF;
END;
/

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT object_name, object_type, status
  FROM user_objects
 WHERE object_name = 'PKG_UTILIDADES'
 ORDER BY object_type;                                                       -- PACKAGE VALID, PACKAGE BODY VALID

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';                                                    -- 0 filas

-- ============================================================================
-- PRUEBAS RAPIDAS (no dejan datos). Esperado: tres lineas "OK".
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  v_directo  NUMBER;
  v_paquete  NUMBER;
  v_ignorar  NUMBER;
BEGIN
  -- 1) El paquete devuelve lo mismo que la tabla.
  SELECT VALOR INTO v_directo FROM PARAMETRO WHERE CLAVE = 'COLA_MAX_INTENTOS';
  v_paquete := PKG_UTILIDADES.valor_parametro('COLA_MAX_INTENTOS');
  IF v_paquete = v_directo THEN
    DBMS_OUTPUT.PUT_LINE('1 OK: valor_parametro() devuelve el valor de la tabla (' || v_paquete || ')');
  ELSE
    DBMS_OUTPUT.PUT_LINE('1 FALLO: valor_parametro() devolvio ' || v_paquete || ' y la tabla ' || v_directo);
  END IF;

  -- 2) Una clave inexistente da error, no NULL.
  BEGIN
    v_ignorar := PKG_UTILIDADES.valor_parametro('CLAVE_QUE_NO_EXISTE');
    DBMS_OUTPUT.PUT_LINE('2 FALLO: no fallo con una clave inexistente');
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE = -20100 THEN
      DBMS_OUTPUT.PUT_LINE('2 OK: clave inexistente rechazada con -20100');
    ELSE
      RAISE;
    END IF;
  END;

  -- 3) hoy() es el dia de ahora().
  IF PKG_UTILIDADES.hoy <= PKG_UTILIDADES.ahora
     AND PKG_UTILIDADES.ahora < PKG_UTILIDADES.hoy + 1 THEN
    DBMS_OUTPUT.PUT_LINE('3 OK: hoy() y ahora() son coherentes');
  ELSE
    DBMS_OUTPUT.PUT_LINE('3 FALLO: hoy() y ahora() no coinciden');
  END IF;
END;
/
