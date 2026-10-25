-- ============================================================================
-- 11_pkg_inventario.sql
-- Seccion 5 del diseno (parte 4): paquete PKG_INVENTARIO.
-- Movimientos de stock que hace el back-office: entradas y ajustes.
-- Reservar, liberar y consumir stock de un pedido van en PKG_RESERVA (script 12).
--
-- Requiere haber ejecutado antes los scripts 03 y 08.
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1.
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Al final sale una linea "OK" por cada prueba y un resumen. Las pruebas
--      crean datos temporales y los deshacen con ROLLBACK: no quedan en la base.
--   4. Para deshacerlo: DROP PACKAGE PKG_INVENTARIO;
--
-- DECISIONES DE DISENO
--   * Se separa de PKG_RESERVA por quien lo llama. Oracle da EXECUTE por paquete,
--     no por procedimiento. Este paquete lo usara el rol de empleados (cargar y
--     corregir stock); si ahi estuvieran tambien reservar y consumir, un empleado
--     podria consumir stock de cualquier linea de pedido. Las reservas las llama
--     solo PKG_PEDIDO, que corre con permisos de su duenio.
--   * Cada procedimiento es atomico: abre un SAVEPOINT y, si algo falla, deshace
--     solo lo suyo y vuelve a lanzar el error. El llamador no queda con un
--     movimiento a medias aunque capture la excepcion.
--   * Toda operacion deja una fila en el kardex (MOVIMIENTO_STOCK) en la misma
--     transaccion que cambia STOCK. El usuario del kardex es el CLIENT_IDENTIFIER
--     (la persona que el backend identifique en la sesion) y, si no hay,
--     el usuario de la base: asi el kardex dice QUIEN, no solo "la aplicacion".
--   * ajuste exige observacion: es la unica forma de cambiar el stock sin que
--     entre mercancia, y debe poder auditarse el por que.
--   * Si un ajuste dejaria el total por debajo de lo reservado (o de cero), el
--     CHECK CK_STOCK_CANTIDADES de la tabla lo rechaza y aqui se traduce a -20123.
--   * Errores de este paquete (bloque -20120 a -20139, compartido con PKG_RESERVA):
--       -20120 variante inexistente, sin stock controlado o sin saldo registrado
--       -20121 cantidad invalida
--       -20123 el ajuste deja el total por debajo de lo reservado o de cero
--       -20125 el ajuste no trae observacion
--   * Sin GRANT todavia: los permisos a los roles se dan en el script final.
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

PROMPT >> 11_pkg_inventario.sql: comprobando requisitos

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
   WHERE table_name IN ('STOCK', 'MOVIMIENTO_STOCK', 'VARIANTE');
  IF v_existentes < 3 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan tablas previas: ejecuta antes el script 03.');
  END IF;

  SELECT COUNT(*) INTO v_existentes
    FROM user_views
   WHERE view_name = 'V_CONCILIACION_STOCK';
  IF v_existentes < 1 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Falta la vista V_CONCILIACION_STOCK: ejecuta antes el script 08.');
  END IF;
END;
/

CREATE OR REPLACE PACKAGE PKG_INVENTARIO
  AUTHID DEFINER
AS
  -- Suma p_cantidad unidades al stock de la variante (crea su fila en STOCK en la
  -- primera entrada) y registra una ENTRADA en el kardex.
  -- Errores: -20120 (variante inexistente o sin stock controlado),
  --          -20121 (la cantidad no es un entero mayor que cero).
  PROCEDURE entrada(
    p_id_variante  IN NUMBER,
    p_cantidad     IN NUMBER,
    p_observacion  IN VARCHAR2 DEFAULT NULL
  );

  -- Corrige el total de stock: cantidad positiva suma, negativa resta (mermas,
  -- conteos fisicos). Registra un AJUSTE en el kardex. La observacion es obligatoria.
  -- Errores: -20120 (variante inexistente, sin stock controlado o sin saldo; en el
  --          ultimo caso se usa entrada), -20121 (cantidad que no es un entero
  --          distinto de cero), -20123 (deja el total bajo lo reservado o bajo cero),
  --          -20125 (sin observacion).
  PROCEDURE ajuste(
    p_id_variante  IN NUMBER,
    p_cantidad     IN NUMBER,
    p_observacion  IN VARCHAR2
  );
END PKG_INVENTARIO;
/
SHOW ERRORS PACKAGE PKG_INVENTARIO
PROMPT >> PKG_INVENTARIO: especificacion creada

CREATE OR REPLACE PACKAGE BODY PKG_INVENTARIO
AS
  -- ---------------------------------------------------------------------------
  -- Privados. Una funcion privada no se puede llamar dentro de una sentencia SQL:
  -- por eso su resultado se guarda primero en una variable.
  -- ---------------------------------------------------------------------------
  FUNCTION usuario_actual RETURN VARCHAR2 IS
  BEGIN
    RETURN NVL(SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER'),
               SYS_CONTEXT('USERENV', 'SESSION_USER'));
  END usuario_actual;

  -- p_admite_negativa = TRUE solo para ajustes.
  PROCEDURE validar_cantidad(p_cantidad IN NUMBER, p_admite_negativa IN BOOLEAN) IS
  BEGIN
    IF p_cantidad IS NULL OR p_cantidad <> TRUNC(p_cantidad) THEN
      RAISE_APPLICATION_ERROR(-20121, 'La cantidad debe ser un numero entero.');
    ELSIF p_cantidad = 0 THEN
      RAISE_APPLICATION_ERROR(-20121, 'La cantidad no puede ser cero.');
    ELSIF p_cantidad < 0 AND NOT p_admite_negativa THEN
      RAISE_APPLICATION_ERROR(-20121, 'La cantidad debe ser mayor que cero.');
    END IF;
  END validar_cantidad;

  PROCEDURE validar_variante(p_id_variante IN NUMBER) IS
    v_controla VARIANTE.CONTROLA_STOCK%TYPE;
  BEGIN
    SELECT CONTROLA_STOCK
      INTO v_controla
      FROM VARIANTE
     WHERE ID_VARIANTE = p_id_variante;
    IF v_controla <> 1 THEN
      RAISE_APPLICATION_ERROR(-20120,
        'La variante ' || p_id_variante || ' no controla stock.');
    END IF;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RAISE_APPLICATION_ERROR(-20120, 'La variante ' || p_id_variante || ' no existe.');
  END validar_variante;

  PROCEDURE registrar_movimiento(
    p_id_variante  IN NUMBER,
    p_tipo         IN VARCHAR2,
    p_cantidad     IN NUMBER,
    p_observacion  IN VARCHAR2
  ) IS
    v_usuario MOVIMIENTO_STOCK.USUARIO%TYPE;
  BEGIN
    v_usuario := usuario_actual;
    INSERT INTO MOVIMIENTO_STOCK (ID_VARIANTE, TIPO, CANTIDAD, OBSERVACION, USUARIO)
    VALUES (p_id_variante, p_tipo, p_cantidad, p_observacion, v_usuario);
  END registrar_movimiento;

  -- ---------------------------------------------------------------------------
  -- Publicos
  -- ---------------------------------------------------------------------------
  PROCEDURE entrada(
    p_id_variante  IN NUMBER,
    p_cantidad     IN NUMBER,
    p_observacion  IN VARCHAR2 DEFAULT NULL
  ) IS
  BEGIN
    SAVEPOINT sp_entrada;

    validar_cantidad(p_cantidad, FALSE);
    validar_variante(p_id_variante);

    -- Primera entrada: crea el saldo. Las siguientes lo incrementan.
    MERGE INTO STOCK s
    USING (SELECT p_id_variante AS ID_VARIANTE FROM dual) x
       ON (s.ID_VARIANTE = x.ID_VARIANTE)
     WHEN MATCHED THEN
       UPDATE SET s.CANTIDAD_TOTAL = s.CANTIDAD_TOTAL + p_cantidad
     WHEN NOT MATCHED THEN
       INSERT (ID_VARIANTE, CANTIDAD_TOTAL)
       VALUES (x.ID_VARIANTE, p_cantidad);

    registrar_movimiento(p_id_variante, 'ENTRADA', p_cantidad, p_observacion);
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_entrada;
      RAISE;
  END entrada;

  PROCEDURE ajuste(
    p_id_variante  IN NUMBER,
    p_cantidad     IN NUMBER,
    p_observacion  IN VARCHAR2
  ) IS
    v_codigo  NUMBER;
    v_mensaje VARCHAR2(4000);
  BEGIN
    SAVEPOINT sp_ajuste;

    IF p_observacion IS NULL OR TRIM(p_observacion) IS NULL THEN
      RAISE_APPLICATION_ERROR(-20125, 'El ajuste de stock requiere una observacion.');
    END IF;
    validar_cantidad(p_cantidad, TRUE);
    validar_variante(p_id_variante);

    UPDATE STOCK
       SET CANTIDAD_TOTAL = CANTIDAD_TOTAL + p_cantidad
     WHERE ID_VARIANTE = p_id_variante;
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20120,
        'La variante ' || p_id_variante || ' no tiene stock registrado: usa entrada.');
    END IF;

    registrar_movimiento(p_id_variante, 'AJUSTE', p_cantidad, p_observacion);
  EXCEPTION
    WHEN OTHERS THEN
      -- Se guardan antes del ROLLBACK TO, que es otra sentencia.
      v_codigo  := SQLCODE;
      v_mensaje := SQLERRM;
      ROLLBACK TO SAVEPOINT sp_ajuste;
      IF v_codigo = -2290 AND INSTR(v_mensaje, 'CK_STOCK_CANTIDADES') > 0 THEN
        RAISE_APPLICATION_ERROR(-20123,
          'El ajuste dejaria el total de stock por debajo de lo reservado o de cero.');
      ELSE
        RAISE;
      END IF;
  END ajuste;
END PKG_INVENTARIO;
/
SHOW ERRORS PACKAGE BODY PKG_INVENTARIO
PROMPT >> PKG_INVENTARIO: cuerpo creado

-- CREATE PACKAGE no se detiene ante errores de compilacion (solo avisa), asi que
-- se revisa USER_ERRORS de forma explicita.
DECLARE
  v_errores NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_errores
    FROM user_errors
   WHERE name = 'PKG_INVENTARIO';
  IF v_errores > 0 THEN
    RAISE_APPLICATION_ERROR(-20004,
      'PKG_INVENTARIO compilo con errores: consulta USER_ERRORS.');
  END IF;
END;
/

PROMPT >> PKG_INVENTARIO: paquete compilado sin errores

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT object_name, object_type, status
  FROM user_objects
 WHERE object_name = 'PKG_INVENTARIO'
 ORDER BY object_type;                                                       -- PACKAGE VALID, PACKAGE BODY VALID

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';                                                    -- 0 filas

-- ============================================================================
-- PRUEBAS RAPIDAS (terminan con ROLLBACK: no dejan datos).
-- Esperado: 14 lineas "OK" y al final "0 fallos".
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  v_pruebas      NUMBER := 0;
  v_fallos       NUMBER := 0;
  v_id_impuesto  NUMBER;
  v_categoria    NUMBER;
  v_producto     NUMBER;
  v_var_a        NUMBER;   -- controla stock
  v_var_b        NUMBER;   -- no controla stock
  v_var_c        NUMBER;   -- controla stock, sin entrada todavia
  v_total        NUMBER;
  v_reservada    NUMBER;
  v_n            NUMBER;

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
BEGIN
  SELECT ID_IMPUESTO INTO v_id_impuesto FROM IMPUESTO WHERE NOMBRE = 'IVA 19%';

  INSERT INTO CATEGORIA (NOMBRE) VALUES ('CATEGORIA PRUEBA INVENTARIO')
    RETURNING ID_CATEGORIA INTO v_categoria;
  INSERT INTO PRODUCTO (ID_CATEGORIA, ID_IMPUESTO, NOMBRE)
    VALUES (v_categoria, v_id_impuesto, 'Producto prueba inventario')
    RETURNING ID_PRODUCTO INTO v_producto;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE, CONTROLA_STOCK)
    VALUES (v_producto, 'INV-CON-STOCK', 'Con stock', 50000, 1)
    RETURNING ID_VARIANTE INTO v_var_a;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE, CONTROLA_STOCK)
    VALUES (v_producto, 'INV-SIN-STOCK', 'Sin control de stock', 50000, 0)
    RETURNING ID_VARIANTE INTO v_var_b;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE, CONTROLA_STOCK)
    VALUES (v_producto, 'INV-SIN-ENTRADA', 'Con stock sin entrada', 50000, 1)
    RETURNING ID_VARIANTE INTO v_var_c;

  -- Entradas y ajustes correctos
  PKG_INVENTARIO.entrada(v_var_a, 10, 'prueba');
  leer_stock(v_var_a);
  verificar('la primera entrada crea el saldo (total 10, reservada 0)',
            v_total = 10 AND v_reservada = 0);

  SELECT COUNT(*) INTO v_n
    FROM MOVIMIENTO_STOCK
   WHERE ID_VARIANTE = v_var_a AND TIPO = 'ENTRADA' AND CANTIDAD = 10;
  verificar('la entrada queda en el kardex', v_n = 1);

  PKG_INVENTARIO.entrada(v_var_a, 5);
  leer_stock(v_var_a);
  verificar('una segunda entrada suma al saldo existente (total 15)', v_total = 15);

  PKG_INVENTARIO.ajuste(v_var_a, -3, 'merma');
  leer_stock(v_var_a);
  verificar('ajuste de -3 con observacion (total 12)', v_total = 12);

  -- Entradas y ajustes invalidos
  esperar_error('ajuste sin observacion',
    'BEGIN PKG_INVENTARIO.ajuste(' || v_var_a || ', -2, NULL); END;', -20125);
  esperar_error('ajuste de cantidad cero',
    'BEGIN PKG_INVENTARIO.ajuste(' || v_var_a || ', 0, ''x''); END;', -20121);
  esperar_error('entrada negativa',
    'BEGIN PKG_INVENTARIO.entrada(' || v_var_a || ', -1); END;', -20121);
  esperar_error('entrada con decimales',
    'BEGIN PKG_INVENTARIO.entrada(' || v_var_a || ', 1.5); END;', -20121);

  esperar_error('ajuste que deja el total negativo',
    'BEGIN PKG_INVENTARIO.ajuste(' || v_var_a || ', -100, ''x''); END;', -20123);
  leer_stock(v_var_a);
  verificar('el ajuste rechazado no cambia el saldo (total 12)', v_total = 12);

  esperar_error('entrada en variante sin stock controlado',
    'BEGIN PKG_INVENTARIO.entrada(' || v_var_b || ', 1); END;', -20120);
  esperar_error('entrada en variante inexistente',
    'BEGIN PKG_INVENTARIO.entrada(-1, 1); END;', -20120);
  esperar_error('ajuste en variante sin saldo registrado',
    'BEGIN PKG_INVENTARIO.ajuste(' || v_var_c || ', 1, ''x''); END;', -20120);

  -- El saldo debe cuadrar con el kardex: 10 + 5 - 3 = 12
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
