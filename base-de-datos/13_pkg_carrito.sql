-- ============================================================================
-- 13_pkg_carrito.sql
-- Seccion 5 del diseno (parte 6): paquete PKG_CARRITO.
-- Operaciones del carrito de compras de un cliente.
--
-- Requiere haber ejecutado antes los scripts 03, 09 y 10.
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1 (SELECT USER FROM dual).
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Al final sale una linea "OK" por cada prueba y un resumen. Las pruebas
--      crean datos temporales y los deshacen con ROLLBACK: no quedan en la base.
--   4. Para deshacerlo: DROP PACKAGE PKG_CARRITO;
--
-- DECISIONES DE DISENO
--   * Es un paquete para el rol de clientes (el EXECUTE se da en el script final
--     de permisos). Todas las operaciones reciben el ID_CLIENTE y trabajan solo
--     sobre el carrito ABIERTO de ese cliente, y los productos se identifican por
--     variante, no por el ID de la fila del carrito. Asi no existe un parametro
--     con el que se pueda tocar el carrito de otro cliente. Que el ID_CLIENTE sea
--     el del comprador autenticado lo garantiza el backend: la base solo conoce
--     al usuario de aplicacion.
--   * obtener_carrito devuelve el carrito abierto y lo crea si no existe. Si dos
--     sesiones lo crean a la vez, el indice unico UX_CARRITO_ABIERTO deja pasar a
--     una y la otra relee el carrito que acaba de aparecer.
--   * Las operaciones que modifican el carrito primero lo BLOQUEAN
--     (SELECT ... FOR UPDATE sobre CARRITO). Asi no se mezclan con la conversion
--     del carrito en pedido (PKG_PEDIDO tambien lo bloquea): un producto agregado
--     "a la vez" que se confirma el pedido espera su turno y no se pierde ni se
--     cuela a medias.
--   * El carrito NO reserva stock ni copia precios: el stock se reserva al crear
--     el pedido y el precio sale de V_PRECIO_VIGENTE en ese momento. Por eso
--     agregar_item solo exige que la variante tenga precio vigente (activa).
--   * Cada operacion que modifica actualiza FECHA_ULTIMA_ACTIVIDAD (la usa el JOB
--     de carritos abandonados). No se reinicia AVISO_ABANDONO_ENVIADO: el aviso se
--     envia una sola vez por carrito.
--   * quitar_item y vaciar son idempotentes: repetirlos no da error.
--   * Cada procedimiento que escribe es atomico (SAVEPOINT + ROLLBACK TO).
--   * Errores (bloque -20140 a -20159); tambien puede propagar -20101 de
--     PKG_PRECIOS (variante inexistente o inactiva):
--       -20140 cliente inexistente o inactivo
--       -20141 cantidad invalida
--       -20143 la variante no esta en el carrito
--       -20144 no se pudo bloquear el carrito (reintentar)
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

PROMPT >> 13_pkg_carrito.sql: comprobando requisitos

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
   WHERE table_name IN ('CARRITO', 'CARRITO_ITEM', 'CLIENTE');
  IF v_existentes < 3 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan tablas previas: ejecuta antes el script 03.');
  END IF;

  SELECT COUNT(*) INTO v_existentes
    FROM user_objects
   WHERE object_name IN ('PKG_UTILIDADES', 'PKG_PRECIOS')
     AND object_type = 'PACKAGE BODY'
     AND status = 'VALID';
  IF v_existentes < 2 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan paquetes previos validos: ejecuta antes los scripts 09 y 10.');
  END IF;
END;
/

CREATE OR REPLACE PACKAGE PKG_CARRITO
  AUTHID DEFINER
AS
  -- ID del carrito ABIERTO del cliente; lo crea si no tiene.
  -- Error -20140 si el cliente no existe o esta inactivo.
  FUNCTION obtener_carrito(p_id_cliente IN NUMBER) RETURN NUMBER;

  -- Suma p_cantidad unidades de la variante al carrito (si ya estaba, incrementa).
  -- Errores: -20140, -20141 (la cantidad no es un entero mayor que cero),
  --          -20101 (la variante no existe o no esta activa), -20144.
  PROCEDURE agregar_item(
    p_id_cliente  IN NUMBER,
    p_id_variante IN NUMBER,
    p_cantidad    IN NUMBER
  );

  -- Fija la cantidad exacta de una variante que ya esta en el carrito.
  -- Errores: -20140, -20141, -20143 (la variante no esta en el carrito), -20144.
  PROCEDURE cambiar_cantidad(
    p_id_cliente  IN NUMBER,
    p_id_variante IN NUMBER,
    p_cantidad    IN NUMBER
  );

  -- Quita una variante del carrito. No da error si no estaba.
  PROCEDURE quitar_item(p_id_cliente IN NUMBER, p_id_variante IN NUMBER);

  -- Deja el carrito sin productos. No da error si ya estaba vacio.
  PROCEDURE vaciar(p_id_cliente IN NUMBER);
END PKG_CARRITO;
/
SHOW ERRORS PACKAGE PKG_CARRITO
PROMPT >> PKG_CARRITO: especificacion creada

CREATE OR REPLACE PACKAGE BODY PKG_CARRITO
AS
  -- ---------------------------------------------------------------------------
  -- Privados
  -- ---------------------------------------------------------------------------
  PROCEDURE validar_cliente(p_id_cliente IN NUMBER) IS
    v_activo CLIENTE.ACTIVO%TYPE;
  BEGIN
    SELECT ACTIVO
      INTO v_activo
      FROM CLIENTE
     WHERE ID_CLIENTE = p_id_cliente;
    IF v_activo <> 1 THEN
      RAISE_APPLICATION_ERROR(-20140, 'El cliente ' || p_id_cliente || ' esta inactivo.');
    END IF;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RAISE_APPLICATION_ERROR(-20140, 'El cliente ' || p_id_cliente || ' no existe.');
  END validar_cliente;

  PROCEDURE validar_cantidad(p_cantidad IN NUMBER) IS
  BEGIN
    IF p_cantidad IS NULL OR p_cantidad <= 0 OR p_cantidad <> TRUNC(p_cantidad) THEN
      RAISE_APPLICATION_ERROR(-20141, 'La cantidad debe ser un numero entero mayor que cero.');
    END IF;
  END validar_cantidad;

  -- Devuelve el carrito abierto del cliente ya bloqueado. Si el carrito se
  -- convierte en pedido justo entre buscarlo y bloquearlo, se reintenta una vez
  -- con el carrito nuevo.
  FUNCTION bloquear_carrito(p_id_cliente IN NUMBER) RETURN NUMBER IS
    v_id_carrito CARRITO.ID_CARRITO%TYPE;
  BEGIN
    FOR v_intento IN 1 .. 2 LOOP
      v_id_carrito := obtener_carrito(p_id_cliente);
      BEGIN
        SELECT ID_CARRITO
          INTO v_id_carrito
          FROM CARRITO
         WHERE ID_CARRITO = v_id_carrito
           AND ESTADO = 'ABIERTO'
           FOR UPDATE;
        RETURN v_id_carrito;
      EXCEPTION
        WHEN NO_DATA_FOUND THEN
          NULL;   -- se convirtio justo ahora: se vuelve a buscar
      END;
    END LOOP;
    RAISE_APPLICATION_ERROR(-20144,
      'No se pudo bloquear el carrito del cliente ' || p_id_cliente || '; intenta de nuevo.');
  END bloquear_carrito;

  PROCEDURE tocar_carrito(p_id_carrito IN NUMBER) IS
    v_ahora TIMESTAMP;
  BEGIN
    v_ahora := PKG_UTILIDADES.ahora;
    UPDATE CARRITO
       SET FECHA_ULTIMA_ACTIVIDAD = v_ahora
     WHERE ID_CARRITO = p_id_carrito;
  END tocar_carrito;

  -- ---------------------------------------------------------------------------
  -- Publicos
  -- ---------------------------------------------------------------------------
  FUNCTION obtener_carrito(p_id_cliente IN NUMBER) RETURN NUMBER IS
    v_id_carrito CARRITO.ID_CARRITO%TYPE;
  BEGIN
    validar_cliente(p_id_cliente);

    BEGIN
      SELECT ID_CARRITO
        INTO v_id_carrito
        FROM CARRITO
       WHERE ID_CLIENTE = p_id_cliente
         AND ESTADO = 'ABIERTO';
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        BEGIN
          INSERT INTO CARRITO (ID_CLIENTE)
          VALUES (p_id_cliente)
          RETURNING ID_CARRITO INTO v_id_carrito;
        EXCEPTION
          WHEN DUP_VAL_ON_INDEX THEN
            -- Otra sesion lo creo a la vez: se lee el que ya existe.
            SELECT ID_CARRITO
              INTO v_id_carrito
              FROM CARRITO
             WHERE ID_CLIENTE = p_id_cliente
               AND ESTADO = 'ABIERTO';
        END;
    END;

    RETURN v_id_carrito;
  END obtener_carrito;

  PROCEDURE agregar_item(
    p_id_cliente  IN NUMBER,
    p_id_variante IN NUMBER,
    p_cantidad    IN NUMBER
  ) IS
    v_precio     V_PRECIO_VIGENTE%ROWTYPE;
    v_id_carrito CARRITO.ID_CARRITO%TYPE;
  BEGIN
    SAVEPOINT sp_agregar_item;

    validar_cantidad(p_cantidad);
    -- Solo se agrega lo que tiene precio vigente (variante y producto activos).
    v_precio := PKG_PRECIOS.precio_vigente(p_id_variante);

    v_id_carrito := bloquear_carrito(p_id_cliente);

    MERGE INTO CARRITO_ITEM i
    USING (SELECT v_id_carrito AS ID_CARRITO, p_id_variante AS ID_VARIANTE FROM dual) x
       ON (i.ID_CARRITO = x.ID_CARRITO AND i.ID_VARIANTE = x.ID_VARIANTE)
     WHEN MATCHED THEN
       UPDATE SET i.CANTIDAD = i.CANTIDAD + p_cantidad
     WHEN NOT MATCHED THEN
       INSERT (ID_CARRITO, ID_VARIANTE, CANTIDAD)
       VALUES (x.ID_CARRITO, x.ID_VARIANTE, p_cantidad);

    tocar_carrito(v_id_carrito);
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_agregar_item;
      RAISE;
  END agregar_item;

  PROCEDURE cambiar_cantidad(
    p_id_cliente  IN NUMBER,
    p_id_variante IN NUMBER,
    p_cantidad    IN NUMBER
  ) IS
    v_id_carrito CARRITO.ID_CARRITO%TYPE;
  BEGIN
    SAVEPOINT sp_cambiar_cantidad;

    validar_cantidad(p_cantidad);
    v_id_carrito := bloquear_carrito(p_id_cliente);

    UPDATE CARRITO_ITEM
       SET CANTIDAD = p_cantidad
     WHERE ID_CARRITO = v_id_carrito
       AND ID_VARIANTE = p_id_variante;
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20143,
        'La variante ' || p_id_variante || ' no esta en el carrito.');
    END IF;

    tocar_carrito(v_id_carrito);
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_cambiar_cantidad;
      RAISE;
  END cambiar_cantidad;

  PROCEDURE quitar_item(p_id_cliente IN NUMBER, p_id_variante IN NUMBER) IS
    v_id_carrito CARRITO.ID_CARRITO%TYPE;
  BEGIN
    SAVEPOINT sp_quitar_item;

    v_id_carrito := bloquear_carrito(p_id_cliente);

    DELETE FROM CARRITO_ITEM
     WHERE ID_CARRITO = v_id_carrito
       AND ID_VARIANTE = p_id_variante;

    tocar_carrito(v_id_carrito);
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_quitar_item;
      RAISE;
  END quitar_item;

  PROCEDURE vaciar(p_id_cliente IN NUMBER) IS
    v_id_carrito CARRITO.ID_CARRITO%TYPE;
  BEGIN
    SAVEPOINT sp_vaciar;

    v_id_carrito := bloquear_carrito(p_id_cliente);

    DELETE FROM CARRITO_ITEM
     WHERE ID_CARRITO = v_id_carrito;

    tocar_carrito(v_id_carrito);
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_vaciar;
      RAISE;
  END vaciar;
END PKG_CARRITO;
/
SHOW ERRORS PACKAGE BODY PKG_CARRITO
PROMPT >> PKG_CARRITO: cuerpo creado

-- CREATE PACKAGE no se detiene ante errores de compilacion (solo avisa), asi que
-- se revisa USER_ERRORS de forma explicita.
DECLARE
  v_errores NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_errores
    FROM user_errors
   WHERE name = 'PKG_CARRITO';
  IF v_errores > 0 THEN
    RAISE_APPLICATION_ERROR(-20004,
      'PKG_CARRITO compilo con errores: consulta USER_ERRORS.');
  END IF;
END;
/
PROMPT >> PKG_CARRITO: paquete compilado sin errores

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT object_name, object_type, status
  FROM user_objects
 WHERE object_name = 'PKG_CARRITO'
 ORDER BY object_type;                                                       -- PACKAGE VALID, PACKAGE BODY VALID

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';                                                    -- 0 filas

-- ============================================================================
-- PRUEBAS RAPIDAS (terminan con ROLLBACK: no dejan datos).
-- Esperado: 16 lineas "OK" y al final "0 fallos".
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  v_pruebas             NUMBER := 0;
  v_fallos              NUMBER := 0;
  v_id_impuesto         NUMBER;
  v_id_tipo_doc         NUMBER;
  v_cliente             NUMBER;
  v_cliente_inactivo    NUMBER;
  v_categoria           NUMBER;
  v_producto            NUMBER;
  v_var_a               NUMBER;
  v_var_b               NUMBER;
  v_var_inactiva        NUMBER;
  v_carrito             NUMBER;
  v_carrito2            NUMBER;
  v_n                   NUMBER;
  v_fecha_vieja         TIMESTAMP;
  v_fecha_nueva         TIMESTAMP;

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

  -- Cantidad de una variante en un carrito (NULL si no esta).
  FUNCTION cantidad_item(p_id_carrito IN NUMBER, p_id_variante IN NUMBER) RETURN NUMBER IS
    v_cantidad NUMBER;
  BEGIN
    SELECT CANTIDAD INTO v_cantidad
      FROM CARRITO_ITEM
     WHERE ID_CARRITO = p_id_carrito AND ID_VARIANTE = p_id_variante;
    RETURN v_cantidad;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RETURN NULL;
  END cantidad_item;

  FUNCTION contar_items(p_id_carrito IN NUMBER) RETURN NUMBER IS
    v_cuenta NUMBER;
  BEGIN
    SELECT COUNT(*) INTO v_cuenta FROM CARRITO_ITEM WHERE ID_CARRITO = p_id_carrito;
    RETURN v_cuenta;
  END contar_items;

  FUNCTION estado_carrito(p_id_carrito IN NUMBER) RETURN VARCHAR2 IS
    v_estado CARRITO.ESTADO%TYPE;
  BEGIN
    SELECT ESTADO INTO v_estado FROM CARRITO WHERE ID_CARRITO = p_id_carrito;
    RETURN v_estado;
  END estado_carrito;
BEGIN
  SELECT ID_IMPUESTO       INTO v_id_impuesto FROM IMPUESTO       WHERE NOMBRE = 'IVA 19%';
  SELECT ID_TIPO_DOCUMENTO INTO v_id_tipo_doc FROM TIPO_DOCUMENTO WHERE CODIGO = 'CC';

  INSERT INTO CLIENTE (ID_TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRE, EMAIL)
    VALUES (v_id_tipo_doc, '9000000001', 'Cliente prueba carrito', 'prueba.carrito1@ejemplo.com')
    RETURNING ID_CLIENTE INTO v_cliente;
  INSERT INTO CLIENTE (ID_TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRE, EMAIL, ACTIVO)
    VALUES (v_id_tipo_doc, '9000000002', 'Cliente inactivo', 'prueba.carrito2@ejemplo.com', 0)
    RETURNING ID_CLIENTE INTO v_cliente_inactivo;

  INSERT INTO CATEGORIA (NOMBRE) VALUES ('CATEGORIA PRUEBA CARRITO')
    RETURNING ID_CATEGORIA INTO v_categoria;
  INSERT INTO PRODUCTO (ID_CATEGORIA, ID_IMPUESTO, NOMBRE)
    VALUES (v_categoria, v_id_impuesto, 'Producto prueba carrito')
    RETURNING ID_PRODUCTO INTO v_producto;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE)
    VALUES (v_producto, 'CAR-A', 'Variante A', 30000)
    RETURNING ID_VARIANTE INTO v_var_a;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE)
    VALUES (v_producto, 'CAR-B', 'Variante B', 40000)
    RETURNING ID_VARIANTE INTO v_var_b;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE, ACTIVO)
    VALUES (v_producto, 'CAR-INACTIVA', 'Variante inactiva', 50000, 0)
    RETURNING ID_VARIANTE INTO v_var_inactiva;

  -- Crear y reutilizar el carrito
  v_carrito := PKG_CARRITO.obtener_carrito(v_cliente);
  verificar('obtener_carrito crea un carrito ABIERTO', estado_carrito(v_carrito) = 'ABIERTO');
  verificar('pedirlo otra vez devuelve el mismo carrito',
            PKG_CARRITO.obtener_carrito(v_cliente) = v_carrito);

  -- Agregar y cambiar cantidades
  PKG_CARRITO.agregar_item(v_cliente, v_var_a, 2);
  verificar('agregar 2 unidades de A (cantidad 2)', cantidad_item(v_carrito, v_var_a) = 2);

  PKG_CARRITO.agregar_item(v_cliente, v_var_a, 3);
  verificar('agregar 3 mas de A suma a lo que habia (cantidad 5)', cantidad_item(v_carrito, v_var_a) = 5);

  PKG_CARRITO.cambiar_cantidad(v_cliente, v_var_a, 1);
  verificar('cambiar_cantidad fija el valor exacto (cantidad 1)', cantidad_item(v_carrito, v_var_a) = 1);

  -- Validaciones
  esperar_error('agregar una variante inactiva',
    'BEGIN PKG_CARRITO.agregar_item(' || v_cliente || ', ' || v_var_inactiva || ', 1); END;', -20101);
  esperar_error('agregar cantidad cero',
    'BEGIN PKG_CARRITO.agregar_item(' || v_cliente || ', ' || v_var_a || ', 0); END;', -20141);
  esperar_error('agregar cantidad con decimales',
    'BEGIN PKG_CARRITO.agregar_item(' || v_cliente || ', ' || v_var_a || ', 1.5); END;', -20141);
  esperar_error('cambiar la cantidad de una variante que no esta en el carrito',
    'BEGIN PKG_CARRITO.cambiar_cantidad(' || v_cliente || ', ' || v_var_b || ', 2); END;', -20143);
  esperar_error('cliente inexistente',
    'BEGIN PKG_CARRITO.agregar_item(-1, ' || v_var_a || ', 1); END;', -20140);
  esperar_error('cliente inactivo',
    'BEGIN PKG_CARRITO.agregar_item(' || v_cliente_inactivo || ', ' || v_var_a || ', 1); END;', -20140);

  -- Quitar y vaciar (idempotentes)
  PKG_CARRITO.quitar_item(v_cliente, v_var_a);
  PKG_CARRITO.quitar_item(v_cliente, v_var_a);
  verificar('quitar_item quita la variante y repetirlo no da error', cantidad_item(v_carrito, v_var_a) IS NULL);

  PKG_CARRITO.agregar_item(v_cliente, v_var_a, 1);
  PKG_CARRITO.agregar_item(v_cliente, v_var_b, 4);
  PKG_CARRITO.vaciar(v_cliente);
  verificar('vaciar deja el carrito sin productos', contar_items(v_carrito) = 0);

  -- Un solo carrito abierto por cliente
  SELECT COUNT(*) INTO v_n FROM CARRITO WHERE ID_CLIENTE = v_cliente AND ESTADO = 'ABIERTO';
  verificar('el cliente tiene un solo carrito ABIERTO', v_n = 1);

  -- Actividad del carrito
  UPDATE CARRITO
     SET FECHA_ULTIMA_ACTIVIDAD = PKG_UTILIDADES.ahora - INTERVAL '2' DAY
   WHERE ID_CARRITO = v_carrito;
  SELECT FECHA_ULTIMA_ACTIVIDAD INTO v_fecha_vieja FROM CARRITO WHERE ID_CARRITO = v_carrito;
  PKG_CARRITO.agregar_item(v_cliente, v_var_a, 1);
  SELECT FECHA_ULTIMA_ACTIVIDAD INTO v_fecha_nueva FROM CARRITO WHERE ID_CARRITO = v_carrito;
  verificar('agregar actualiza FECHA_ULTIMA_ACTIVIDAD', v_fecha_nueva > v_fecha_vieja + INTERVAL '1' DAY);

  -- Un carrito convertido en pedido ya no es el abierto
  UPDATE CARRITO SET ESTADO = 'CONVERTIDO' WHERE ID_CARRITO = v_carrito;
  v_carrito2 := PKG_CARRITO.obtener_carrito(v_cliente);
  verificar('tras convertir el carrito, obtener_carrito crea uno nuevo ABIERTO',
            v_carrito2 <> v_carrito AND estado_carrito(v_carrito2) = 'ABIERTO');

  DBMS_OUTPUT.PUT_LINE('Resultado: ' || v_pruebas || ' pruebas, ' || v_fallos || ' fallos');
  ROLLBACK;
EXCEPTION
  WHEN OTHERS THEN
    ROLLBACK;
    RAISE;
END;
/
