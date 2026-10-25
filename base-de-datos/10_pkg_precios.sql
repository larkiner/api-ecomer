-- ============================================================================
-- 10_pkg_precios.sql
-- Seccion 5 del diseno (parte 3): paquete PKG_PRECIOS.
-- Calcula el precio vigente de una variante y los importes de una linea de pedido.
--
-- Requiere haber ejecutado antes los scripts 06, 08 y 09.
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1.
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Al final salen cinco lineas "OK" de pruebas. Las pruebas crean datos
--      temporales (categoria, producto, variantes, promocion) y los deshacen con
--      ROLLBACK: no quedan en la base.
--   4. Para deshacerlo: DROP PACKAGE PKG_PRECIOS;
--
-- DECISIONES DE DISENO
--   * El precio, el descuento y la tasa de impuesto salen de V_PRECIO_VIGENTE:
--     la formula vive en un solo lugar. Este paquete solo multiplica por la
--     cantidad y calcula el impuesto de la linea.
--   * calcular_linea devuelve un registro con los mismos nombres y tipos que las
--     columnas de PEDIDO_ITEM, para que PKG_PEDIDO lo inserte tal cual. Cumple los
--     CHECK de esa tabla:
--         SUBTOTAL_LINEA = (PRECIO_UNITARIO - DESCUENTO_UNITARIO) * CANTIDAD
--         TOTAL_LINEA    = SUBTOTAL_LINEA + IMPUESTO_LINEA
--   * El impuesto se calcula POR LINEA y se redondea a c_decimales_impuesto
--     decimales. PROVISIONAL: la politica de redondeo es una decision contable
--     sin confirmar. Cuando se defina, se cambia aqui y en ningun otro lugar.
--   * Una variante inactiva (o de un producto inactivo) no tiene precio vigente:
--     da error -20101. No se vende lo que no esta activo.
--   * Errores: -20101 variante sin precio vigente; -20102 cantidad invalida.
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

PROMPT >> 10_pkg_precios.sql: comprobando requisitos

DECLARE
  v_vistas NUMBER;
  v_pkg    NUMBER;
BEGIN
  IF SYS_CONTEXT('USERENV', 'SESSION_USER') <> 'ECOMERS'
     OR SYS_CONTEXT('USERENV', 'CON_NAME') <> 'XEPDB1' THEN
    RAISE_APPLICATION_ERROR(-20001,
      'Conectate como ECOMERS al servicio XEPDB1 antes de ejecutar este script.');
  END IF;

  SELECT COUNT(*) INTO v_vistas FROM user_views WHERE view_name = 'V_PRECIO_VIGENTE';
  SELECT COUNT(*) INTO v_pkg    FROM user_objects
   WHERE object_name = 'PKG_UTILIDADES' AND object_type = 'PACKAGE';
  IF v_vistas < 1 OR v_pkg < 1 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan objetos previos: ejecuta antes los scripts 08 y 09.');
  END IF;
END;
/

CREATE OR REPLACE PACKAGE PKG_PRECIOS
  AUTHID DEFINER
AS
  -- Decimales con que se redondea el impuesto de cada linea (provisional).
  c_decimales_impuesto CONSTANT PLS_INTEGER := 2;

  -- Una linea de pedido lista para insertar en PEDIDO_ITEM. El ultimo campo,
  -- controla_stock, no es columna de PEDIDO_ITEM: lo usa PKG_PEDIDO para saber si
  -- debe reservar stock.
  TYPE t_linea IS RECORD (
    id_variante         PEDIDO_ITEM.ID_VARIANTE%TYPE,
    sku                 PEDIDO_ITEM.SKU%TYPE,
    nombre_item         PEDIDO_ITEM.NOMBRE_ITEM%TYPE,
    cantidad            PEDIDO_ITEM.CANTIDAD%TYPE,
    precio_unitario     PEDIDO_ITEM.PRECIO_UNITARIO%TYPE,
    id_promocion        PEDIDO_ITEM.ID_PROMOCION%TYPE,
    descuento_unitario  PEDIDO_ITEM.DESCUENTO_UNITARIO%TYPE,
    tasa_impuesto       PEDIDO_ITEM.TASA_IMPUESTO%TYPE,
    subtotal_linea      PEDIDO_ITEM.SUBTOTAL_LINEA%TYPE,
    impuesto_linea      PEDIDO_ITEM.IMPUESTO_LINEA%TYPE,
    total_linea         PEDIDO_ITEM.TOTAL_LINEA%TYPE,
    controla_stock      VARIANTE.CONTROLA_STOCK%TYPE
  );

  -- Fila de V_PRECIO_VIGENTE de la variante.
  -- Error -20101 si la variante no existe o no esta activa.
  FUNCTION precio_vigente(p_id_variante IN NUMBER) RETURN V_PRECIO_VIGENTE%ROWTYPE;

  -- Importes de una linea: p_cantidad unidades de la variante al precio vigente.
  -- Error -20101 (sin precio vigente) o -20102 (cantidad que no es un entero > 0).
  FUNCTION calcular_linea(p_id_variante IN NUMBER, p_cantidad IN NUMBER) RETURN t_linea;
END PKG_PRECIOS;
/
SHOW ERRORS PACKAGE PKG_PRECIOS
PROMPT >> PKG_PRECIOS: especificacion creada

CREATE OR REPLACE PACKAGE BODY PKG_PRECIOS
AS
  FUNCTION precio_vigente(p_id_variante IN NUMBER) RETURN V_PRECIO_VIGENTE%ROWTYPE IS
    v_fila V_PRECIO_VIGENTE%ROWTYPE;
  BEGIN
    SELECT *
      INTO v_fila
      FROM V_PRECIO_VIGENTE
     WHERE ID_VARIANTE = p_id_variante;
    RETURN v_fila;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RAISE_APPLICATION_ERROR(-20101,
        'La variante ' || p_id_variante || ' no existe o no esta activa.');
  END precio_vigente;

  FUNCTION calcular_linea(p_id_variante IN NUMBER, p_cantidad IN NUMBER) RETURN t_linea IS
    v_precio V_PRECIO_VIGENTE%ROWTYPE;
    v_linea  t_linea;
  BEGIN
    IF p_cantidad IS NULL OR p_cantidad <= 0 OR p_cantidad <> TRUNC(p_cantidad) THEN
      RAISE_APPLICATION_ERROR(-20102, 'La cantidad debe ser un numero entero mayor que cero.');
    END IF;

    v_precio := precio_vigente(p_id_variante);

    v_linea.id_variante        := v_precio.ID_VARIANTE;
    v_linea.sku                := v_precio.SKU;
    v_linea.nombre_item        := v_precio.NOMBRE_ITEM;
    v_linea.cantidad           := p_cantidad;
    v_linea.precio_unitario    := v_precio.PRECIO_BASE;
    v_linea.id_promocion       := v_precio.ID_PROMOCION;
    v_linea.descuento_unitario := v_precio.DESCUENTO_UNITARIO;
    v_linea.tasa_impuesto      := v_precio.TASA_IMPUESTO;
    v_linea.controla_stock     := v_precio.CONTROLA_STOCK;

    v_linea.subtotal_linea := (v_linea.precio_unitario - v_linea.descuento_unitario) * v_linea.cantidad;
    v_linea.impuesto_linea := ROUND(v_linea.subtotal_linea * v_linea.tasa_impuesto, c_decimales_impuesto);
    v_linea.total_linea    := v_linea.subtotal_linea + v_linea.impuesto_linea;

    RETURN v_linea;
  END calcular_linea;
END PKG_PRECIOS;
/
SHOW ERRORS PACKAGE BODY PKG_PRECIOS
PROMPT >> PKG_PRECIOS: cuerpo creado

-- CREATE PACKAGE no se detiene ante errores de compilacion (solo avisa), asi que
-- se revisa USER_ERRORS de forma explicita.
DECLARE
  v_errores NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_errores
    FROM user_errors
   WHERE name = 'PKG_PRECIOS';
  IF v_errores > 0 THEN
    RAISE_APPLICATION_ERROR(-20004,
      'PKG_PRECIOS compilo con errores: consulta USER_ERRORS.');
  END IF;
END;
/

PROMPT >> PKG_PRECIOS: paquete compilado sin errores

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT object_name, object_type, status
  FROM user_objects
 WHERE object_name = 'PKG_PRECIOS'
 ORDER BY object_type;                                                       -- PACKAGE VALID, PACKAGE BODY VALID

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';                                                    -- 0 filas

-- ============================================================================
-- PRUEBAS RAPIDAS (terminan con ROLLBACK: no dejan datos). Esperado: cinco "OK".
-- Datos de prueba: variante activa de 100.000 con IVA 19% y promocion del 10%.
--   descuento = 10.000 | linea de 3 unidades: subtotal 270.000, impuesto 51.300, total 321.300
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  v_impuesto      NUMBER;
  v_categoria     NUMBER;
  v_producto      NUMBER;
  v_var_activa    NUMBER;
  v_var_inactiva  NUMBER;
  v_promocion     NUMBER;
  v_linea         PKG_PRECIOS.t_linea;
BEGIN
  SELECT ID_IMPUESTO INTO v_impuesto FROM IMPUESTO WHERE NOMBRE = 'IVA 19%';

  INSERT INTO CATEGORIA (NOMBRE) VALUES ('CATEGORIA PRUEBA')
    RETURNING ID_CATEGORIA INTO v_categoria;
  INSERT INTO PRODUCTO (ID_CATEGORIA, ID_IMPUESTO, NOMBRE)
    VALUES (v_categoria, v_impuesto, 'Producto prueba')
    RETURNING ID_PRODUCTO INTO v_producto;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE)
    VALUES (v_producto, 'PRUEBA-ACTIVA', 'Plan', 100000)
    RETURNING ID_VARIANTE INTO v_var_activa;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE, ACTIVO)
    VALUES (v_producto, 'PRUEBA-INACTIVA', 'Plan viejo', 50000, 0)
    RETURNING ID_VARIANTE INTO v_var_inactiva;
  INSERT INTO PROMOCION (NOMBRE, TIPO_DESCUENTO, VALOR)
    VALUES ('PROMO PRUEBA', 'PORCENTAJE', 10)
    RETURNING ID_PROMOCION INTO v_promocion;
  INSERT INTO PROMOCION_VARIANTE (ID_VARIANTE, ID_PROMOCION)
    VALUES (v_var_activa, v_promocion);

  -- 1) Promocion porcentual: 10% de 100.000, 3 unidades, IVA 19%.
  v_linea := PKG_PRECIOS.calcular_linea(v_var_activa, 3);
  IF v_linea.descuento_unitario = 10000
     AND v_linea.subtotal_linea = 270000
     AND v_linea.impuesto_linea = 51300
     AND v_linea.total_linea    = 321300 THEN
    DBMS_OUTPUT.PUT_LINE('1 OK: linea con promocion del 10% (total 321300)');
  ELSE
    DBMS_OUTPUT.PUT_LINE('1 FALLO: descuento=' || v_linea.descuento_unitario ||
                         ' subtotal=' || v_linea.subtotal_linea ||
                         ' impuesto=' || v_linea.impuesto_linea ||
                         ' total=' || v_linea.total_linea);
  END IF;

  -- 2) Descuento en monto mayor que el precio: nunca deja el precio negativo.
  UPDATE PROMOCION SET TIPO_DESCUENTO = 'MONTO', VALOR = 999999 WHERE ID_PROMOCION = v_promocion;
  v_linea := PKG_PRECIOS.calcular_linea(v_var_activa, 1);
  IF v_linea.descuento_unitario = 100000 AND v_linea.total_linea = 0 THEN
    DBMS_OUTPUT.PUT_LINE('2 OK: el descuento en monto se limita al precio (total 0)');
  ELSE
    DBMS_OUTPUT.PUT_LINE('2 FALLO: descuento=' || v_linea.descuento_unitario ||
                         ' total=' || v_linea.total_linea);
  END IF;

  -- 3) Una variante inactiva no tiene precio vigente.
  BEGIN
    v_linea := PKG_PRECIOS.calcular_linea(v_var_inactiva, 1);
    DBMS_OUTPUT.PUT_LINE('3 FALLO: calculo una linea con una variante inactiva');
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE = -20101 THEN
      DBMS_OUTPUT.PUT_LINE('3 OK: variante inactiva rechazada con -20101');
    ELSE
      RAISE;
    END IF;
  END;

  -- 4) Cantidad cero.
  BEGIN
    v_linea := PKG_PRECIOS.calcular_linea(v_var_activa, 0);
    DBMS_OUTPUT.PUT_LINE('4 FALLO: acepto cantidad 0');
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE = -20102 THEN
      DBMS_OUTPUT.PUT_LINE('4 OK: cantidad 0 rechazada con -20102');
    ELSE
      RAISE;
    END IF;
  END;

  -- 5) Cantidad con decimales.
  BEGIN
    v_linea := PKG_PRECIOS.calcular_linea(v_var_activa, 1.5);
    DBMS_OUTPUT.PUT_LINE('5 FALLO: acepto cantidad 1.5');
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE = -20102 THEN
      DBMS_OUTPUT.PUT_LINE('5 OK: cantidad 1.5 rechazada con -20102');
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
