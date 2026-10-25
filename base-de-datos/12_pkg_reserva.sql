-- ============================================================================
-- 12_pkg_reserva.sql
-- Seccion 5 del diseno (parte 5): paquete PKG_RESERVA.
-- Reserva, libera y consume el stock de cada linea de un pedido.
--
-- Requiere haber ejecutado antes los scripts 06, 08, 09 y 11. Para crear pedidos
-- reales tambien hace falta cargar los municipios (script 05); las pruebas de
-- este script usan un municipio temporal.
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1.
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Al final sale una linea "OK" por cada prueba y un resumen. Las pruebas
--      crean datos temporales y los deshacen con ROLLBACK: no quedan en la base.
--   4. Para deshacerlo: DROP PACKAGE PKG_RESERVA;
--
-- DECISIONES DE DISENO
--   * Es un paquete INTERNO: solo lo llamara PKG_PEDIDO (que corre con permisos de
--     su duenio). Ningun rol de aplicacion recibe EXECUTE sobre el.
--   * reservar NO lee el disponible y despues escribe (eso deja pasar a dos
--     compradores a la vez). Hace un solo UPDATE condicional:
--         UPDATE STOCK SET CANTIDAD_RESERVADA = CANTIDAD_RESERVADA + :q
--          WHERE ID_VARIANTE = :v AND CANTIDAD_TOTAL - CANTIDAD_RESERVADA >= :q
--     El UPDATE bloquea la fila; si otra sesion la tiene bloqueada, espera y al
--     despertar reevalua la condicion. Si no actualiza filas, no alcanza el stock.
--     El CHECK CK_STOCK_CANTIDADES de la tabla es la red de seguridad.
--   * INTERBLOQUEOS: quien reserve varias lineas de un pedido (PKG_PEDIDO) debe
--     hacerlo en orden ascendente de ID_VARIANTE. Si dos pedidos reservaran las
--     mismas variantes en orden distinto, cada uno podria quedar esperando la
--     fila que tiene el otro.
--   * Este paquete no conoce el ciclo de vida del pedido: PKG_PEDIDO valida el
--     estado (PENDIENTE_PAGO, expirado, etc.) antes de llamarlo.
--   * liberar y consumir son ESTRICTOS: si la reserva no esta ACTIVA fallan con
--     -20124. El UPDATE condicional sobre RESERVA_STOCK (... AND ESTADO = 'ACTIVA')
--     es lo que impide liberar o consumir dos veces la misma linea y descontar
--     el stock dos veces. Es PKG_PEDIDO quien decide si un reintento es normal.
--   * Una linea sin fila en RESERVA_STOCK (variante sin stock controlado) se
--     ignora: reservar, liberar y consumir no hacen nada y no dan error.
--   * reservar reactiva una reserva LIBERADA. Hace falta para el caso "la pasarela
--     aprueba un pago de un pedido que ya expiro": si todavia hay stock, se
--     vuelve a reservar la misma linea. El kardex registra una nueva RESERVA.
--   * Cada procedimiento es atomico (SAVEPOINT + ROLLBACK TO): si falla, no deja
--     la reserva a medias aunque el llamador capture el error.
--   * Kardex: RESERVA suma a lo reservado; LIBERACION lo resta; CONSUMO resta de
--     lo reservado y del total (la unidad sale del almacen). V_CONCILIACION_STOCK
--     usa exactamente esas reglas.
--   * Errores (bloque -20120 a -20139, compartido con PKG_INVENTARIO):
--       -20122 stock insuficiente
--       -20124 la reserva ya existe o no esta ACTIVA
--       -20126 la linea de pedido no existe
--       -20127 inconsistencia: hay reserva pero no hay saldo de stock
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

PROMPT >> 12_pkg_reserva.sql: comprobando requisitos

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
   WHERE table_name IN ('RESERVA_STOCK', 'PEDIDO_ITEM', 'PEDIDO', 'STOCK', 'MOVIMIENTO_STOCK');
  IF v_existentes < 5 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan tablas previas: ejecuta antes los scripts 03 y 06.');
  END IF;

  SELECT COUNT(*) INTO v_existentes
    FROM user_objects
   WHERE object_name IN ('PKG_UTILIDADES', 'PKG_INVENTARIO')
     AND object_type = 'PACKAGE BODY'
     AND status = 'VALID';
  IF v_existentes < 2 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan paquetes previos validos: ejecuta antes los scripts 09 y 11.');
  END IF;
END;
/

CREATE OR REPLACE PACKAGE PKG_RESERVA
  AUTHID DEFINER
AS
  -- Reserva el stock de una linea de pedido. No hace nada si la variante no
  -- controla stock. Si la linea tenia una reserva LIBERADA, la reactiva.
  -- Errores: -20122 (stock insuficiente), -20124 (la linea ya tiene una reserva
  --          activa o consumida), -20126 (la linea no existe).
  PROCEDURE reservar(p_id_pedido_item IN NUMBER);

  -- Devuelve al disponible lo reservado por la linea (pedido expirado o cancelado).
  -- Errores: -20124 (la reserva no esta ACTIVA), -20126, -20127.
  PROCEDURE liberar(p_id_pedido_item IN NUMBER);

  -- Da por vendido lo reservado (pedido pagado): resta del total y de lo reservado.
  -- Errores: -20124 (la reserva no esta ACTIVA), -20126, -20127.
  PROCEDURE consumir(p_id_pedido_item IN NUMBER);
END PKG_RESERVA;
/
SHOW ERRORS PACKAGE PKG_RESERVA
PROMPT >> PKG_RESERVA: especificacion creada

CREATE OR REPLACE PACKAGE BODY PKG_RESERVA
AS
  -- Datos de la linea que interesan para mover stock.
  TYPE t_item IS RECORD (
    id_pedido    PEDIDO_ITEM.ID_PEDIDO%TYPE,
    id_variante  PEDIDO_ITEM.ID_VARIANTE%TYPE,
    cantidad     PEDIDO_ITEM.CANTIDAD%TYPE
  );

  -- ---------------------------------------------------------------------------
  -- Privados. Una funcion privada no se puede llamar dentro de una sentencia SQL:
  -- por eso su resultado se guarda primero en una variable.
  -- ---------------------------------------------------------------------------
  FUNCTION usuario_actual RETURN VARCHAR2 IS
  BEGIN
    RETURN NVL(SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER'),
               SYS_CONTEXT('USERENV', 'SESSION_USER'));
  END usuario_actual;

  FUNCTION leer_item(p_id_pedido_item IN NUMBER) RETURN t_item IS
    v_item t_item;
  BEGIN
    SELECT ID_PEDIDO, ID_VARIANTE, CANTIDAD
      INTO v_item
      FROM PEDIDO_ITEM
     WHERE ID_PEDIDO_ITEM = p_id_pedido_item;
    RETURN v_item;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RAISE_APPLICATION_ERROR(-20126,
        'La linea de pedido ' || p_id_pedido_item || ' no existe.');
  END leer_item;

  FUNCTION variante_controla_stock(p_id_variante IN NUMBER) RETURN BOOLEAN IS
    v_controla VARIANTE.CONTROLA_STOCK%TYPE;
  BEGIN
    SELECT CONTROLA_STOCK
      INTO v_controla
      FROM VARIANTE
     WHERE ID_VARIANTE = p_id_variante;
    RETURN v_controla = 1;
  END variante_controla_stock;

  FUNCTION existe_reserva(p_id_pedido_item IN NUMBER) RETURN BOOLEAN IS
    v_cuenta NUMBER;
  BEGIN
    SELECT COUNT(*)
      INTO v_cuenta
      FROM RESERVA_STOCK
     WHERE ID_PEDIDO_ITEM = p_id_pedido_item;
    RETURN v_cuenta > 0;
  END existe_reserva;

  PROCEDURE registrar_movimiento(p_item IN t_item, p_tipo IN VARCHAR2) IS
    v_usuario MOVIMIENTO_STOCK.USUARIO%TYPE;
  BEGIN
    v_usuario := usuario_actual;
    INSERT INTO MOVIMIENTO_STOCK (ID_VARIANTE, TIPO, CANTIDAD, ID_PEDIDO, USUARIO)
    VALUES (p_item.id_variante, p_tipo, p_item.cantidad, p_item.id_pedido, v_usuario);
  END registrar_movimiento;

  -- ---------------------------------------------------------------------------
  -- Publicos
  -- ---------------------------------------------------------------------------
  PROCEDURE reservar(p_id_pedido_item IN NUMBER) IS
    v_item t_item;
  BEGIN
    SAVEPOINT sp_reservar;

    v_item := leer_item(p_id_pedido_item);

    IF NOT variante_controla_stock(v_item.id_variante) THEN
      RETURN;
    END IF;

    -- 1) La reserva de la linea: reactiva una LIBERADA o crea una nueva. Si ya hay
    --    una ACTIVA o CONSUMIDA, el INSERT choca con la clave primaria.
    UPDATE RESERVA_STOCK
       SET ESTADO = 'ACTIVA'
     WHERE ID_PEDIDO_ITEM = p_id_pedido_item
       AND ESTADO = 'LIBERADA';
    IF SQL%ROWCOUNT = 0 THEN
      BEGIN
        INSERT INTO RESERVA_STOCK (ID_PEDIDO_ITEM, ESTADO)
        VALUES (p_id_pedido_item, 'ACTIVA');
      EXCEPTION
        WHEN DUP_VAL_ON_INDEX THEN
          RAISE_APPLICATION_ERROR(-20124,
            'La linea ' || p_id_pedido_item || ' ya tiene una reserva activa o consumida.');
      END;
    END IF;

    -- 2) Reservar en el saldo con un UPDATE condicional (ver el encabezado).
    UPDATE STOCK
       SET CANTIDAD_RESERVADA = CANTIDAD_RESERVADA + v_item.cantidad
     WHERE ID_VARIANTE = v_item.id_variante
       AND CANTIDAD_TOTAL - CANTIDAD_RESERVADA >= v_item.cantidad;
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20122,
        'Stock insuficiente para la variante ' || v_item.id_variante ||
        ' (se necesitan ' || v_item.cantidad || ').');
    END IF;

    -- 3) Kardex
    registrar_movimiento(v_item, 'RESERVA');
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_reservar;
      RAISE;
  END reservar;

  PROCEDURE liberar(p_id_pedido_item IN NUMBER) IS
    v_item t_item;
  BEGIN
    SAVEPOINT sp_liberar;

    v_item := leer_item(p_id_pedido_item);

    -- Sin fila de reserva (variante sin stock controlado): no hay nada que liberar.
    IF NOT existe_reserva(p_id_pedido_item) THEN
      RETURN;
    END IF;

    UPDATE RESERVA_STOCK
       SET ESTADO = 'LIBERADA'
     WHERE ID_PEDIDO_ITEM = p_id_pedido_item
       AND ESTADO = 'ACTIVA';
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20124,
        'La reserva de la linea ' || p_id_pedido_item || ' no esta activa.');
    END IF;

    UPDATE STOCK
       SET CANTIDAD_RESERVADA = CANTIDAD_RESERVADA - v_item.cantidad
     WHERE ID_VARIANTE = v_item.id_variante;
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20127,
        'La variante ' || v_item.id_variante || ' tiene una reserva pero no tiene saldo de stock.');
    END IF;

    registrar_movimiento(v_item, 'LIBERACION');
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_liberar;
      RAISE;
  END liberar;

  PROCEDURE consumir(p_id_pedido_item IN NUMBER) IS
    v_item t_item;
  BEGIN
    SAVEPOINT sp_consumir;

    v_item := leer_item(p_id_pedido_item);

    IF NOT existe_reserva(p_id_pedido_item) THEN
      RETURN;
    END IF;

    UPDATE RESERVA_STOCK
       SET ESTADO = 'CONSUMIDA'
     WHERE ID_PEDIDO_ITEM = p_id_pedido_item
       AND ESTADO = 'ACTIVA';
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20124,
        'La reserva de la linea ' || p_id_pedido_item || ' no esta activa.');
    END IF;

    -- Una sola sentencia: total y reservado bajan juntos, asi que el CHECK
    -- (reservado <= total) nunca se viola a mitad de camino.
    UPDATE STOCK
       SET CANTIDAD_TOTAL     = CANTIDAD_TOTAL     - v_item.cantidad,
           CANTIDAD_RESERVADA = CANTIDAD_RESERVADA - v_item.cantidad
     WHERE ID_VARIANTE = v_item.id_variante;
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20127,
        'La variante ' || v_item.id_variante || ' tiene una reserva pero no tiene saldo de stock.');
    END IF;

    registrar_movimiento(v_item, 'CONSUMO');
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_consumir;
      RAISE;
  END consumir;
END PKG_RESERVA;
/
SHOW ERRORS PACKAGE BODY PKG_RESERVA
PROMPT >> PKG_RESERVA: cuerpo creado

-- CREATE PACKAGE no se detiene ante errores de compilacion (solo avisa), asi que
-- se revisa USER_ERRORS de forma explicita.
DECLARE
  v_errores NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_errores
    FROM user_errors
   WHERE name = 'PKG_RESERVA';
  IF v_errores > 0 THEN
    RAISE_APPLICATION_ERROR(-20004,
      'PKG_RESERVA compilo con errores: consulta USER_ERRORS.');
  END IF;
END;
/

PROMPT >> PKG_RESERVA: paquete compilado sin errores

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT object_name, object_type, status
  FROM user_objects
 WHERE object_name = 'PKG_RESERVA'
 ORDER BY object_type;                                                       -- PACKAGE VALID, PACKAGE BODY VALID

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';                                                    -- 0 filas

-- ============================================================================
-- PRUEBAS RAPIDAS (terminan con ROLLBACK: no dejan datos).
-- Esperado: 14 lineas "OK" y al final "0 fallos".
-- Plan: variante con 10 unidades y un ajuste de -2 (total 8); linea 1 de 2
-- unidades; linea 2 de 100 unidades (no alcanza); linea 3 de una variante sin
-- stock controlado.
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  v_pruebas       NUMBER := 0;
  v_fallos        NUMBER := 0;
  v_id_impuesto   NUMBER;
  v_id_tipo_doc   NUMBER;
  v_id_estado     NUMBER;
  v_cliente       NUMBER;
  v_categoria     NUMBER;
  v_producto      NUMBER;
  v_var_a         NUMBER;   -- controla stock
  v_var_b         NUMBER;   -- no controla stock
  v_item1         NUMBER;
  v_item2         NUMBER;
  v_item3         NUMBER;
  v_total         NUMBER;
  v_reservada     NUMBER;
  v_n             NUMBER;
  v_m             NUMBER;

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

  PROCEDURE leer_stock(p_id_variante IN NUMBER) IS
  BEGIN
    SELECT CANTIDAD_TOTAL, CANTIDAD_RESERVADA
      INTO v_total, v_reservada
      FROM STOCK
     WHERE ID_VARIANTE = p_id_variante;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      v_total := NULL;
      v_reservada := NULL;
  END leer_stock;

  FUNCTION estado_reserva(p_id_pedido_item IN NUMBER) RETURN VARCHAR2 IS
    v_estado RESERVA_STOCK.ESTADO%TYPE;
  BEGIN
    SELECT ESTADO INTO v_estado FROM RESERVA_STOCK WHERE ID_PEDIDO_ITEM = p_id_pedido_item;
    RETURN v_estado;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RETURN NULL;
  END estado_reserva;

  -- Crea un pedido de una sola linea (precio unitario 50.000, IVA 19%) y devuelve
  -- el ID de la linea.
  FUNCTION nuevo_item(p_id_variante IN NUMBER, p_cantidad IN NUMBER) RETURN NUMBER IS
    v_sku       VARIANTE.SKU%TYPE;
    v_subtotal  NUMBER := 50000 * p_cantidad;
    v_iva       NUMBER := ROUND(50000 * p_cantidad * 0.19, 2);
    v_pedido    NUMBER;
    v_linea     NUMBER;
  BEGIN
    SELECT SKU INTO v_sku FROM VARIANTE WHERE ID_VARIANTE = p_id_variante;

    INSERT INTO PEDIDO (ID_CLIENTE, ID_ESTADO_PEDIDO, FECHA_EXPIRACION,
                        FACT_TIPO_DOCUMENTO, FACT_NUMERO_DOCUMENTO, FACT_NOMBRE, FACT_EMAIL,
                        FACT_DIRECCION, FACT_CODIGO_MUNICIPIO,
                        SUBTOTAL, DESCUENTO_TOTAL, IMPUESTO_TOTAL, TOTAL)
    VALUES (v_cliente, v_id_estado, PKG_UTILIDADES.ahora + INTERVAL '30' MINUTE,
            'CC', '9999999999', 'Cliente prueba reserva', 'prueba.reserva@ejemplo.com',
            'Calle 1 # 2-3', '00000',
            v_subtotal, 0, v_iva, v_subtotal + v_iva)
    RETURNING ID_PEDIDO INTO v_pedido;

    INSERT INTO PEDIDO_ITEM (ID_PEDIDO, ID_VARIANTE, SKU, NOMBRE_ITEM, CANTIDAD,
                             PRECIO_UNITARIO, DESCUENTO_UNITARIO, TASA_IMPUESTO,
                             SUBTOTAL_LINEA, IMPUESTO_LINEA, TOTAL_LINEA)
    VALUES (v_pedido, p_id_variante, v_sku, 'Item de prueba', p_cantidad,
            50000, 0, 0.19,
            v_subtotal, v_iva, v_subtotal + v_iva)
    RETURNING ID_PEDIDO_ITEM INTO v_linea;

    RETURN v_linea;
  END nuevo_item;
BEGIN
  -- Datos temporales: municipio, cliente, catalogo y dos variantes
  SELECT ID_IMPUESTO        INTO v_id_impuesto FROM IMPUESTO      WHERE NOMBRE = 'IVA 19%';
  SELECT ID_TIPO_DOCUMENTO  INTO v_id_tipo_doc FROM TIPO_DOCUMENTO WHERE CODIGO = 'CC';
  SELECT ID_ESTADO_PEDIDO   INTO v_id_estado   FROM ESTADO_PEDIDO  WHERE CODIGO = 'PENDIENTE_PAGO';

  INSERT INTO DEPARTAMENTO (CODIGO_DANE, NOMBRE) VALUES ('00', 'DEPARTAMENTO PRUEBA');
  INSERT INTO MUNICIPIO (CODIGO_DANE, CODIGO_DEPARTAMENTO, NOMBRE)
    VALUES ('00000', '00', 'MUNICIPIO PRUEBA');
  INSERT INTO CLIENTE (ID_TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRE, EMAIL)
    VALUES (v_id_tipo_doc, '9999999999', 'Cliente prueba reserva', 'prueba.reserva@ejemplo.com')
    RETURNING ID_CLIENTE INTO v_cliente;

  INSERT INTO CATEGORIA (NOMBRE) VALUES ('CATEGORIA PRUEBA RESERVA')
    RETURNING ID_CATEGORIA INTO v_categoria;
  INSERT INTO PRODUCTO (ID_CATEGORIA, ID_IMPUESTO, NOMBRE)
    VALUES (v_categoria, v_id_impuesto, 'Producto prueba reserva')
    RETURNING ID_PRODUCTO INTO v_producto;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE, CONTROLA_STOCK)
    VALUES (v_producto, 'RES-CON-STOCK', 'Con stock', 50000, 1)
    RETURNING ID_VARIANTE INTO v_var_a;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE, CONTROLA_STOCK)
    VALUES (v_producto, 'RES-SIN-STOCK', 'Sin control de stock', 50000, 0)
    RETURNING ID_VARIANTE INTO v_var_b;

  PKG_INVENTARIO.entrada(v_var_a, 10, 'prueba');
  PKG_INVENTARIO.ajuste(v_var_a, -2, 'merma de prueba');

  v_item1 := nuevo_item(v_var_a, 2);
  v_item2 := nuevo_item(v_var_a, 100);
  v_item3 := nuevo_item(v_var_b, 1);

  -- Reservar
  leer_stock(v_var_a);
  verificar('punto de partida: total 8, reservada 0', v_total = 8 AND v_reservada = 0);

  PKG_RESERVA.reservar(v_item1);
  leer_stock(v_var_a);
  verificar('reservar 2 unidades: reservada 2 y reserva ACTIVA',
            v_reservada = 2 AND estado_reserva(v_item1) = 'ACTIVA');

  esperar_error('reservar dos veces la misma linea',
    'BEGIN PKG_RESERVA.reservar(' || v_item1 || '); END;', -20124);
  leer_stock(v_var_a);
  verificar('la reserva duplicada no cambia el saldo (reservada 2)', v_reservada = 2);

  esperar_error('reservar 100 con 6 disponibles',
    'BEGIN PKG_RESERVA.reservar(' || v_item2 || '); END;', -20122);
  verificar('el rechazo por stock no deja una reserva huerfana', estado_reserva(v_item2) IS NULL);

  -- Liberar y reactivar
  PKG_RESERVA.liberar(v_item1);
  leer_stock(v_var_a);
  verificar('liberar: reservada 0 y reserva LIBERADA',
            v_reservada = 0 AND estado_reserva(v_item1) = 'LIBERADA');

  esperar_error('liberar dos veces la misma linea',
    'BEGIN PKG_RESERVA.liberar(' || v_item1 || '); END;', -20124);

  PKG_RESERVA.reservar(v_item1);
  leer_stock(v_var_a);
  verificar('reservar una linea liberada la reactiva (reservada 2, ACTIVA)',
            v_reservada = 2 AND estado_reserva(v_item1) = 'ACTIVA');

  -- Consumir
  PKG_RESERVA.consumir(v_item1);
  leer_stock(v_var_a);
  verificar('consumir: total 6, reservada 0 y reserva CONSUMIDA',
            v_total = 6 AND v_reservada = 0 AND estado_reserva(v_item1) = 'CONSUMIDA');

  esperar_error('consumir dos veces la misma linea',
    'BEGIN PKG_RESERVA.consumir(' || v_item1 || '); END;', -20124);

  -- Variante sin stock controlado: las tres operaciones se ignoran sin error
  PKG_RESERVA.reservar(v_item3);
  PKG_RESERVA.liberar(v_item3);
  PKG_RESERVA.consumir(v_item3);
  SELECT COUNT(*) INTO v_n FROM RESERVA_STOCK     WHERE ID_PEDIDO_ITEM = v_item3;
  SELECT COUNT(*) INTO v_m FROM MOVIMIENTO_STOCK  WHERE ID_VARIANTE = v_var_b;
  verificar('variante sin stock controlado: sin reserva ni kardex', v_n = 0 AND v_m = 0);

  esperar_error('linea de pedido inexistente',
    'BEGIN PKG_RESERVA.reservar(-1); END;', -20126);

  -- Total 6 = 10 - 2 - 2 y reservada 0 = 2 - 2 + 2 - 2, segun el kardex
  SELECT COUNT(*) INTO v_n FROM V_CONCILIACION_STOCK WHERE ID_VARIANTE = v_var_a;
  verificar('el saldo cuadra con el kardex (V_CONCILIACION_STOCK sin filas)', v_n = 0);

  DBMS_OUTPUT.PUT_LINE('Resultado: ' || v_pruebas || ' pruebas, ' || v_fallos || ' fallos');
  ROLLBACK;
EXCEPTION
  WHEN OTHERS THEN
    ROLLBACK;
    RAISE;
END;
/
