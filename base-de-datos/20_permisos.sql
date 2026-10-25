-- ============================================================================
-- 20_permisos.sql
-- Seccion 5 del diseno (parte 13): permisos de los tres roles de aplicacion.
--
-- Requiere haber ejecutado antes los scripts 01, 01b y 08 a 19.
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1 (SELECT USER FROM dual). El
--      duenio de los objetos es quien puede dar permisos sobre ellos.
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Se puede volver a ejecutar: un GRANT repetido no da error.
--
-- QUE RECIBE CADA ROL
--   ROL_ECOMERS_CLIENTE      (backend de la tienda)
--     EXECUTE  PKG_CARRITO, PKG_PEDIDO
--     SELECT   V_PRECIO_VIGENTE (catalogo con precios)
--   ROL_ECOMERS_EMPLEADO     (backoffice)
--     EXECUTE  PKG_INVENTARIO
--     SELECT   las 10 vistas
--   ROL_ECOMERS_INTEGRACION  (webhook de la pasarela y consumidor de la cola)
--     EXECUTE  PKG_PAGO, PKG_COLA
--     SELECT   PEDIDO, PEDIDO_ITEM (para armar el correo y enviar el pedido al ERP)
--
--   Sin permisos para nadie (internos): PKG_UTILIDADES, PKG_PRECIOS,
--   PKG_RESERVA, PKG_PEDIDO_ESTADO y PKG_TAREAS. Ningun rol escribe en tablas.
--
-- DECISIONES DE DISENO
--   * Los roles solo EJECUTAN paquetes, y los paquetes corren con los permisos
--     de ECOMERS (AUTHID DEFINER). Asi toda escritura pasa por las validaciones
--     de los paquetes: nadie puede hacer un UPDATE directo sobre STOCK o PAGO.
--   * El reparto por paquete es lo que obligo a separar PKG_RESERVA,
--     PKG_PEDIDO_ESTADO y a poner iniciar_pago en PKG_PEDIDO: el EXECUTE se da
--     por paquete completo, no por procedimiento.
--   * Integracion lee PEDIDO y PEDIDO_ITEM directamente (solo lectura) porque el
--     correo de "pedido pagado" y la sincronizacion con el ERP necesitan el
--     detalle del pedido. Es un componente de backoffice: ya ve los pagos.
--   * No se crean sinonimos: el backend llama a ECOMERS.PKG_PEDIDO... o, al
--     abrir la conexion, ejecuta ALTER SESSION SET CURRENT_SCHEMA = ECOMERS.
--   * Los roles se otorgaron a los usuarios en 01 y 01b y quedan como roles por
--     defecto: estan activos en cuanto el usuario se conecta.
--   * PENDIENTE (no se inventa aqui): las consultas del cliente ("mis pedidos",
--     "mi carrito", stock disponible por producto) necesitan funciones que
--     filtren por ID_CLIENTE; se agregan a PKG_PEDIDO / PKG_CARRITO cuando se
--     definan las pantallas. Tambien falta definir con negocio que mas hace el
--     rol de empleados (por ejemplo, cancelar pedidos o reactivar eventos).
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

PROMPT >> 20_permisos.sql: comprobando requisitos

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
   WHERE object_name IN ('PKG_CARRITO', 'PKG_PEDIDO', 'PKG_INVENTARIO', 'PKG_PAGO', 'PKG_COLA')
     AND object_type = 'PACKAGE BODY'
     AND status = 'VALID';
  IF v_existentes < 5 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan paquetes validos: ejecuta antes los scripts 11 a 17.');
  END IF;
  -- Si falta un rol, el GRANT correspondiente falla (ORA-01919) y el script se
  -- detiene: revisa los scripts 01 y 01b.
END;
/

-- ----------------------------------------------------------------------------
-- Clientes (backend de la tienda)
-- ----------------------------------------------------------------------------
GRANT EXECUTE ON PKG_CARRITO      TO ROL_ECOMERS_CLIENTE;
GRANT EXECUTE ON PKG_PEDIDO       TO ROL_ECOMERS_CLIENTE;
GRANT SELECT  ON V_PRECIO_VIGENTE TO ROL_ECOMERS_CLIENTE;

-- ----------------------------------------------------------------------------
-- Empleados (backoffice)
-- ----------------------------------------------------------------------------
GRANT EXECUTE ON PKG_INVENTARIO          TO ROL_ECOMERS_EMPLEADO;
GRANT SELECT  ON V_PRECIO_VIGENTE        TO ROL_ECOMERS_EMPLEADO;
GRANT SELECT  ON V_STOCK_BAJO            TO ROL_ECOMERS_EMPLEADO;
GRANT SELECT  ON V_CONCILIACION_STOCK    TO ROL_ECOMERS_EMPLEADO;
GRANT SELECT  ON V_PEDIDOS_PENDIENTES    TO ROL_ECOMERS_EMPLEADO;
GRANT SELECT  ON V_VENTAS_DIARIAS        TO ROL_ECOMERS_EMPLEADO;
GRANT SELECT  ON V_VENTAS_PRODUCTO       TO ROL_ECOMERS_EMPLEADO;
GRANT SELECT  ON V_TOP_CLIENTES          TO ROL_ECOMERS_EMPLEADO;
GRANT SELECT  ON V_CONCILIACION_PAGOS    TO ROL_ECOMERS_EMPLEADO;
GRANT SELECT  ON V_CARRITOS_ABANDONADOS  TO ROL_ECOMERS_EMPLEADO;
GRANT SELECT  ON V_COLA_PENDIENTE        TO ROL_ECOMERS_EMPLEADO;

-- ----------------------------------------------------------------------------
-- Integracion (webhook de la pasarela y consumidor de la cola)
-- ----------------------------------------------------------------------------
GRANT EXECUTE ON PKG_PAGO    TO ROL_ECOMERS_INTEGRACION;
GRANT EXECUTE ON PKG_COLA    TO ROL_ECOMERS_INTEGRACION;
GRANT SELECT  ON PEDIDO      TO ROL_ECOMERS_INTEGRACION;
GRANT SELECT  ON PEDIDO_ITEM TO ROL_ECOMERS_INTEGRACION;

PROMPT >> permisos otorgados

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT grantee, privilege, table_name
  FROM user_tab_privs_made
 WHERE grantee LIKE 'ROL_ECOMERS%'
 ORDER BY grantee, privilege, table_name;                                    -- 18 filas

SELECT grantee, COUNT(*) AS permisos
  FROM user_tab_privs_made
 WHERE grantee LIKE 'ROL_ECOMERS%'
 GROUP BY grantee
 ORDER BY grantee;                                                           -- CLIENTE 3, EMPLEADO 11, INTEGRACION 4

-- Nada de lo interno ni de escritura debe estar otorgado.
SELECT grantee, privilege, table_name
  FROM user_tab_privs_made
 WHERE table_name IN ('PKG_UTILIDADES', 'PKG_PRECIOS', 'PKG_RESERVA', 'PKG_PEDIDO_ESTADO', 'PKG_TAREAS')
    OR privilege IN ('INSERT', 'UPDATE', 'DELETE');                          -- 0 filas

-- ============================================================================
-- PRUEBA MANUAL OPCIONAL (con la clave de ECOMERS_CLIENTE_APP):
--   conectate como ECOMERS_CLIENTE_APP a XEPDB1 y ejecuta:
--     SELECT COUNT(*) FROM ECOMERS.V_PRECIO_VIGENTE;          -- funciona
--     SELECT COUNT(*) FROM ECOMERS.PAGO;                      -- ORA-00942
--     EXEC ECOMERS.PKG_PEDIDO_ESTADO.cancelar(1, 'x');        -- PLS-00201
--   Ese ORA-00942 / PLS-00201 es lo que se espera: el rol no ve esos objetos.
-- ============================================================================
