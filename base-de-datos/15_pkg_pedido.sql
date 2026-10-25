-- ============================================================================
-- 15_pkg_pedido.sql
-- Seccion 5 del diseno (parte 8): paquete PKG_PEDIDO.
-- Lo que el cliente hace con sus pedidos: crearlo desde el carrito, iniciar el
-- pago y cancelarlo.
--
-- VERSION 2: agrega iniciar_pago. Si ya ejecutaste la version anterior, vuelve a
-- ejecutar este script completo (CREATE OR REPLACE; las pruebas no dejan datos).
--
-- Requiere haber ejecutado antes los scripts 09, 10, 12, 13 y 14. Para pedidos
-- reales hace falta el script 05 (municipios); las pruebas usan uno temporal.
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1 (SELECT USER FROM dual).
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Al final sale una linea "OK" por cada prueba y un resumen. Las pruebas
--      crean datos temporales y los deshacen con ROLLBACK: no quedan en la base.
--   4. Para deshacerlo: DROP PACKAGE PKG_PEDIDO;
--
-- DECISIONES DE DISENO
--   * Paquete para el rol de clientes. Crea pedidos, inicia sus pagos y cancela;
--     los cambios de estado los hace PKG_PEDIDO_ESTADO (interno). Todas las
--     operaciones reciben el ID_CLIENTE autenticado (lo garantiza el backend).
--   * Un pedido de otro cliente responde lo mismo que uno inexistente (-20160):
--     asi no se puede averiguar que IDs de pedido existen probando numeros.
--   * crear_desde_carrito, en este orden:
--       1. valida el cliente y la direccion de facturacion (la indicada o, si
--          viene NULL, la principal activa);
--       2. bloquea el carrito ABIERTO (FOR UPDATE WAIT 10), asi no cambia mientras
--          se convierte. Un doble clic no crea dos pedidos: la segunda llamada
--          espera, encuentra el carrito ya CONVERTIDO y falla con -20162 (y el
--          UNIQUE de PEDIDO.ID_CARRITO es la red de seguridad);
--       3. calcula TODAS las lineas con PKG_PRECIOS antes de insertar nada: el
--          trigger de la foto impide corregir los totales de la cabecera despues;
--       4. inserta la cabecera y las lineas, y reserva el stock linea por linea
--          en orden ascendente de ID_VARIANTE (evita interbloqueos);
--       5. comprueba que la cabecera cuadra con la suma de sus lineas;
--       6. marca el carrito CONVERTIDO.
--     Si algo falla (sin stock, variante desactivada...), se deshace todo: no
--     queda pedido, ni reservas, y el carrito sigue ABIERTO para corregirlo.
--   * Los errores de otros paquetes se dejan pasar tal cual (-20101 variante
--     inactiva, -20122 stock insuficiente) para que el backend muestre al
--     cliente que producto debe quitar o reducir.
--   * La expiracion sale del parametro PEDIDO_EXPIRACION_MINUTOS.
--   * iniciar_pago esta AQUI y no en PKG_PAGO por el mismo motivo de siempre: el
--     EXECUTE es por paquete y PKG_PAGO (rol de integracion) registra las
--     notificaciones de la pasarela. Si el rol de clientes tuviera PKG_PAGO, el
--     backend de la tienda podria fabricar un "pago aprobado".
--   * iniciar_pago crea el intento de pago (PAGO PENDIENTE) por el TOTAL del
--     pedido; el monto nunca lo manda el cliente. Bloquea el pedido para no
--     cruzarse con la expiracion. Si ya hay un intento PENDIENTE con la misma
--     pasarela lo devuelve (doble clic = un solo intento). Devuelve el ID_PUBLICO
--     en hexadecimal: es la referencia que el backend envia a la pasarela y la
--     que vuelve en la notificacion.
--   * Ningun procedimiento hace COMMIT: confirma el backend.
--   * Errores (bloque -20160 a -20179, compartido con PKG_PEDIDO_ESTADO):
--       -20160 el pedido no existe (o no es del cliente)
--       -20161 cliente inexistente o inactivo
--       -20162 el cliente no tiene un carrito abierto con productos
--       -20163 direccion de facturacion inexistente, inactiva o de otro cliente
--       -20166 el pedido ya vencio: no se puede iniciar el pago
--       -20167 pasarela de pago inexistente o inactiva
--       -20168 el carrito o el pedido esta ocupado por otra operacion (reintentar)
--       -20169 inconsistencia interna (la cabecera no cuadra con las lineas)
--     Y los de PKG_PEDIDO_ESTADO.cancelar (-20164, -20168).
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

PROMPT >> 15_pkg_pedido.sql: comprobando requisitos

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
   WHERE object_name IN ('PKG_UTILIDADES', 'PKG_PRECIOS', 'PKG_INVENTARIO', 'PKG_RESERVA',
                         'PKG_CARRITO', 'PKG_PEDIDO_ESTADO')
     AND object_type = 'PACKAGE BODY'
     AND status = 'VALID';
  IF v_existentes < 6 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan paquetes previos validos: ejecuta antes los scripts 09 a 14.');
  END IF;

  SELECT COUNT(*) INTO v_existentes
    FROM PARAMETRO
   WHERE CLAVE = 'PEDIDO_EXPIRACION_MINUTOS';
  IF v_existentes < 1 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Falta el parametro PEDIDO_EXPIRACION_MINUTOS: revisa el script 07.');
  END IF;
END;
/

CREATE OR REPLACE PACKAGE PKG_PEDIDO
  AUTHID DEFINER
AS
  -- Convierte el carrito ABIERTO del cliente en un pedido PENDIENTE_PAGO, con el
  -- stock reservado. Devuelve el ID_PEDIDO.
  -- p_id_direccion: direccion de facturacion; NULL = la principal del cliente.
  -- Errores: -20161, -20162, -20163, -20168, -20169, -20101, -20122.
  FUNCTION crear_desde_carrito(
    p_id_cliente   IN NUMBER,
    p_id_direccion IN NUMBER DEFAULT NULL
  ) RETURN NUMBER;

  -- El cliente cancela uno de sus pedidos PENDIENTE_PAGO; se libera el stock.
  -- No da error si ya estaba cancelado.
  -- Errores: -20160, -20164 (ya no esta pendiente de pago), -20168.
  PROCEDURE cancelar(
    p_id_cliente IN NUMBER,
    p_id_pedido  IN NUMBER,
    p_motivo     IN VARCHAR2 DEFAULT NULL
  );

  -- Crea (o devuelve, si ya existe uno PENDIENTE con esa pasarela) el intento de
  -- pago de un pedido PENDIENTE_PAGO del cliente, por el total del pedido.
  -- p_id_publico: referencia en hexadecimal (32 caracteres) para la pasarela.
  -- Errores: -20160, -20164 (no esta PENDIENTE_PAGO), -20166, -20167, -20168.
  PROCEDURE iniciar_pago(
    p_id_cliente      IN  NUMBER,
    p_id_pedido       IN  NUMBER,
    p_codigo_pasarela IN  VARCHAR2,
    p_id_pago         OUT NUMBER,
    p_id_publico      OUT VARCHAR2,
    p_monto           OUT NUMBER
  );
END PKG_PEDIDO;
/
SHOW ERRORS PACKAGE PKG_PEDIDO
PROMPT >> PKG_PEDIDO: especificacion creada

CREATE OR REPLACE PACKAGE BODY PKG_PEDIDO
AS
  TYPE t_lineas IS TABLE OF PKG_PRECIOS.t_linea INDEX BY PLS_INTEGER;

  e_ocupado EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_ocupado, -30006);     -- se agoto el WAIT del FOR UPDATE

  FUNCTION crear_desde_carrito(
    p_id_cliente   IN NUMBER,
    p_id_direccion IN NUMBER DEFAULT NULL
  ) RETURN NUMBER IS
    v_tipo_doc    TIPO_DOCUMENTO.CODIGO%TYPE;
    v_num_doc     CLIENTE.NUMERO_DOCUMENTO%TYPE;
    v_nombre      CLIENTE.NOMBRE%TYPE;
    v_email       CLIENTE.EMAIL%TYPE;
    v_activo      CLIENTE.ACTIVO%TYPE;
    v_direccion   CLIENTE_DIRECCION.DIRECCION%TYPE;
    v_municipio   CLIENTE_DIRECCION.CODIGO_MUNICIPIO%TYPE;
    v_id_carrito  CARRITO.ID_CARRITO%TYPE;
    v_id_estado   ESTADO_PEDIDO.ID_ESTADO_PEDIDO%TYPE;
    v_lineas      t_lineas;
    v_subtotal    NUMBER := 0;
    v_descuento   NUMBER := 0;
    v_impuesto    NUMBER := 0;
    v_total       NUMBER := 0;
    v_vence       TIMESTAMP;
    v_id_pedido   PEDIDO.ID_PEDIDO%TYPE;
    v_id_item     PEDIDO_ITEM.ID_PEDIDO_ITEM%TYPE;
    v_suma_lineas NUMBER;
    v_cuenta      NUMBER;
  BEGIN
    SAVEPOINT sp_crear_pedido;

    -- 1. Cliente y direccion de facturacion
    BEGIN
      SELECT t.CODIGO, c.NUMERO_DOCUMENTO, c.NOMBRE, c.EMAIL, c.ACTIVO
        INTO v_tipo_doc, v_num_doc, v_nombre, v_email, v_activo
        FROM CLIENTE c
        JOIN TIPO_DOCUMENTO t ON t.ID_TIPO_DOCUMENTO = c.ID_TIPO_DOCUMENTO
       WHERE c.ID_CLIENTE = p_id_cliente;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(-20161, 'El cliente ' || p_id_cliente || ' no existe.');
    END;
    IF v_activo <> 1 THEN
      RAISE_APPLICATION_ERROR(-20161, 'El cliente ' || p_id_cliente || ' esta inactivo.');
    END IF;

    BEGIN
      SELECT DIRECCION, CODIGO_MUNICIPIO
        INTO v_direccion, v_municipio
        FROM CLIENTE_DIRECCION
       WHERE ID_CLIENTE = p_id_cliente
         AND ACTIVO = 1
         AND (   ID_CLIENTE_DIRECCION = p_id_direccion
              OR (p_id_direccion IS NULL AND ES_PRINCIPAL = 1));
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(-20163,
          'El cliente no tiene esa direccion de facturacion activa (o no tiene direccion principal).');
    END;

    -- 2. Carrito bloqueado
    BEGIN
      SELECT ID_CARRITO
        INTO v_id_carrito
        FROM CARRITO
       WHERE ID_CLIENTE = p_id_cliente
         AND ESTADO = 'ABIERTO'
         FOR UPDATE WAIT 10;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(-20162, 'El cliente no tiene un carrito abierto con productos.');
      WHEN e_ocupado THEN
        RAISE_APPLICATION_ERROR(-20168, 'El carrito esta ocupado por otra operacion; intenta de nuevo.');
    END;

    -- 3. Todas las lineas calculadas antes de insertar
    FOR i IN (SELECT ID_VARIANTE, CANTIDAD
                FROM CARRITO_ITEM
               WHERE ID_CARRITO = v_id_carrito
               ORDER BY ID_VARIANTE) LOOP
      v_lineas(v_lineas.COUNT + 1) := PKG_PRECIOS.calcular_linea(i.ID_VARIANTE, i.CANTIDAD);
    END LOOP;

    IF v_lineas.COUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20162, 'El cliente no tiene un carrito abierto con productos.');
    END IF;

    FOR n IN 1 .. v_lineas.COUNT LOOP
      v_subtotal  := v_subtotal  + v_lineas(n).subtotal_linea;
      v_descuento := v_descuento + v_lineas(n).descuento_unitario * v_lineas(n).cantidad;
      v_impuesto  := v_impuesto  + v_lineas(n).impuesto_linea;
      v_total     := v_total     + v_lineas(n).total_linea;
    END LOOP;

    -- 4. Cabecera, lineas y reservas
    SELECT ID_ESTADO_PEDIDO INTO v_id_estado FROM ESTADO_PEDIDO WHERE CODIGO = 'PENDIENTE_PAGO';
    v_vence := PKG_UTILIDADES.ahora
               + NUMTODSINTERVAL(PKG_UTILIDADES.valor_parametro('PEDIDO_EXPIRACION_MINUTOS'), 'MINUTE');

    INSERT INTO PEDIDO (ID_CLIENTE, ID_ESTADO_PEDIDO, MOTIVO_ESTADO, ID_CARRITO, FECHA_EXPIRACION,
                        FACT_TIPO_DOCUMENTO, FACT_NUMERO_DOCUMENTO, FACT_NOMBRE, FACT_EMAIL,
                        FACT_DIRECCION, FACT_CODIGO_MUNICIPIO,
                        SUBTOTAL, DESCUENTO_TOTAL, IMPUESTO_TOTAL, TOTAL)
    VALUES (p_id_cliente, v_id_estado, 'Pedido creado desde el carrito', v_id_carrito, v_vence,
            v_tipo_doc, v_num_doc, v_nombre, v_email,
            v_direccion, v_municipio,
            v_subtotal, v_descuento, v_impuesto, v_total)
    RETURNING ID_PEDIDO INTO v_id_pedido;

    FOR n IN 1 .. v_lineas.COUNT LOOP   -- ya vienen en orden de ID_VARIANTE
      INSERT INTO PEDIDO_ITEM (ID_PEDIDO, ID_VARIANTE, SKU, NOMBRE_ITEM, CANTIDAD,
                               PRECIO_UNITARIO, ID_PROMOCION, DESCUENTO_UNITARIO, TASA_IMPUESTO,
                               SUBTOTAL_LINEA, IMPUESTO_LINEA, TOTAL_LINEA)
      VALUES (v_id_pedido, v_lineas(n).id_variante, v_lineas(n).sku, v_lineas(n).nombre_item,
              v_lineas(n).cantidad, v_lineas(n).precio_unitario, v_lineas(n).id_promocion,
              v_lineas(n).descuento_unitario, v_lineas(n).tasa_impuesto,
              v_lineas(n).subtotal_linea, v_lineas(n).impuesto_linea, v_lineas(n).total_linea)
      RETURNING ID_PEDIDO_ITEM INTO v_id_item;

      PKG_RESERVA.reservar(v_id_item);
    END LOOP;

    -- 5. La cabecera cuadra con sus lineas
    SELECT COUNT(*), SUM(TOTAL_LINEA)
      INTO v_cuenta, v_suma_lineas
      FROM PEDIDO_ITEM
     WHERE ID_PEDIDO = v_id_pedido;
    IF v_cuenta <> v_lineas.COUNT OR v_suma_lineas <> v_total THEN
      RAISE_APPLICATION_ERROR(-20169,
        'El total del pedido no cuadra con la suma de sus lineas.');
    END IF;

    -- 6. Carrito convertido
    UPDATE CARRITO
       SET ESTADO = 'CONVERTIDO'
     WHERE ID_CARRITO = v_id_carrito;

    RETURN v_id_pedido;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_crear_pedido;
      RAISE;
  END crear_desde_carrito;

  PROCEDURE cancelar(
    p_id_cliente IN NUMBER,
    p_id_pedido  IN NUMBER,
    p_motivo     IN VARCHAR2 DEFAULT NULL
  ) IS
    v_cuenta NUMBER;
  BEGIN
    SELECT COUNT(*)
      INTO v_cuenta
      FROM PEDIDO
     WHERE ID_PEDIDO = p_id_pedido
       AND ID_CLIENTE = p_id_cliente;
    IF v_cuenta = 0 THEN
      RAISE_APPLICATION_ERROR(-20160, 'El pedido ' || p_id_pedido || ' no existe.');
    END IF;

    PKG_PEDIDO_ESTADO.cancelar(
      p_id_pedido,
      'Cancelado por el cliente' || CASE WHEN TRIM(p_motivo) IS NOT NULL THEN ': ' || TRIM(p_motivo) END);
  END cancelar;

  PROCEDURE iniciar_pago(
    p_id_cliente      IN  NUMBER,
    p_id_pedido       IN  NUMBER,
    p_codigo_pasarela IN  VARCHAR2,
    p_id_pago         OUT NUMBER,
    p_id_publico      OUT VARCHAR2,
    p_monto           OUT NUMBER
  ) IS
    v_id_estado   PEDIDO.ID_ESTADO_PEDIDO%TYPE;
    v_vence       PEDIDO.FECHA_EXPIRACION%TYPE;
    v_total       PEDIDO.TOTAL%TYPE;
    v_estado      ESTADO_PEDIDO.CODIGO%TYPE;
    v_id_pasarela PASARELA_PAGO.ID_PASARELA_PAGO%TYPE;
    v_id_publico  PAGO.ID_PUBLICO%TYPE;
  BEGIN
    SAVEPOINT sp_iniciar_pago;

    -- Bloquea el pedido (si es del cliente) para no cruzarse con la expiracion.
    BEGIN
      SELECT ID_ESTADO_PEDIDO, FECHA_EXPIRACION, TOTAL
        INTO v_id_estado, v_vence, v_total
        FROM PEDIDO
       WHERE ID_PEDIDO = p_id_pedido
         AND ID_CLIENTE = p_id_cliente
         FOR UPDATE WAIT 10;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(-20160, 'El pedido ' || p_id_pedido || ' no existe.');
      WHEN e_ocupado THEN
        RAISE_APPLICATION_ERROR(-20168, 'El pedido esta ocupado por otra operacion; intenta de nuevo.');
    END;

    SELECT CODIGO INTO v_estado FROM ESTADO_PEDIDO WHERE ID_ESTADO_PEDIDO = v_id_estado;
    IF v_estado <> 'PENDIENTE_PAGO' THEN
      RAISE_APPLICATION_ERROR(-20164,
        'El pedido ' || p_id_pedido || ' esta ' || v_estado || ' y no admite pagos.');
    END IF;
    IF v_vence <= PKG_UTILIDADES.ahora THEN
      RAISE_APPLICATION_ERROR(-20166,
        'El pedido ' || p_id_pedido || ' ya vencio; crea uno nuevo desde el carrito.');
    END IF;

    BEGIN
      SELECT ID_PASARELA_PAGO
        INTO v_id_pasarela
        FROM PASARELA_PAGO
       WHERE CODIGO = p_codigo_pasarela
         AND ACTIVA = 1;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(-20167,
          'La pasarela ' || NVL(p_codigo_pasarela, '(vacia)') || ' no existe o no esta activa.');
    END;

    -- Doble clic: se reutiliza el intento PENDIENTE de la misma pasarela.
    BEGIN
      SELECT ID_PAGO, ID_PUBLICO, MONTO
        INTO p_id_pago, v_id_publico, p_monto
        FROM PAGO
       WHERE ID_PEDIDO = p_id_pedido
         AND ID_PASARELA_PAGO = v_id_pasarela
         AND ESTADO = 'PENDIENTE'
       ORDER BY ID_PAGO DESC
       FETCH FIRST 1 ROW ONLY;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        INSERT INTO PAGO (ID_PEDIDO, ID_PASARELA_PAGO, MONTO)
        VALUES (p_id_pedido, v_id_pasarela, v_total)
        RETURNING ID_PAGO, ID_PUBLICO, MONTO INTO p_id_pago, v_id_publico, p_monto;
    END;

    p_id_publico := RAWTOHEX(v_id_publico);
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_iniciar_pago;
      RAISE;
  END iniciar_pago;
END PKG_PEDIDO;
/
SHOW ERRORS PACKAGE BODY PKG_PEDIDO
PROMPT >> PKG_PEDIDO: cuerpo creado

-- CREATE PACKAGE no se detiene ante errores de compilacion (solo avisa), asi que
-- se revisa USER_ERRORS de forma explicita.
DECLARE
  v_errores NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_errores
    FROM user_errors
   WHERE name = 'PKG_PEDIDO';
  IF v_errores > 0 THEN
    RAISE_APPLICATION_ERROR(-20004,
      'PKG_PEDIDO compilo con errores: consulta USER_ERRORS.');
  END IF;
END;
/
PROMPT >> PKG_PEDIDO: paquete compilado sin errores

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT object_name, object_type, status
  FROM user_objects
 WHERE object_name = 'PKG_PEDIDO'
 ORDER BY object_type;                                                       -- PACKAGE VALID, PACKAGE BODY VALID

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';                                                    -- 0 filas

-- ============================================================================
-- PRUEBAS RAPIDAS (terminan con ROLLBACK: no dejan datos).
-- Esperado: 25 lineas "OK" y al final "0 fallos".
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  v_pruebas      NUMBER := 0;
  v_fallos       NUMBER := 0;
  v_id_impuesto  NUMBER;
  v_id_tipo_doc  NUMBER;
  v_c1           NUMBER;   -- cliente con direccion principal
  v_c2           NUMBER;   -- cliente sin direccion
  v_c3           NUMBER;   -- otro cliente con direccion
  v_dir1         NUMBER;
  v_dir3         NUMBER;
  v_categoria    NUMBER;
  v_producto     NUMBER;
  v_var_a        NUMBER;   -- 100.000, controla stock
  v_var_b        NUMBER;   -- 40.000, sin control de stock
  v_carrito      NUMBER;
  v_pedido       NUMBER;
  v_ped          PEDIDO%ROWTYPE;
  v_estado       VARCHAR2(20);
  v_reservada    NUMBER;
  v_n            NUMBER;
  v_minutos      NUMBER;
  v_pago         NUMBER;
  v_pago2        NUMBER;
  v_publico      VARCHAR2(32);
  v_publico2     VARCHAR2(32);
  v_monto        NUMBER;

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

  -- Bloque que llama a crear_desde_carrito para un cliente (y una direccion).
  FUNCTION bloque_crear(p_cliente IN NUMBER, p_direccion IN NUMBER DEFAULT NULL) RETURN VARCHAR2 IS
  BEGIN
    RETURN 'DECLARE x NUMBER; BEGIN x := PKG_PEDIDO.crear_desde_carrito('
           || p_cliente || ', ' || NVL(TO_CHAR(p_direccion), 'NULL') || '); END;';
  END bloque_crear;

  -- Bloque que llama a iniciar_pago.
  FUNCTION bloque_pago(p_cliente IN NUMBER, p_pedido IN NUMBER, p_pasarela IN VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    RETURN 'DECLARE a NUMBER; b VARCHAR2(32); c NUMBER; BEGIN PKG_PEDIDO.iniciar_pago('
           || p_cliente || ', ' || p_pedido || ', ''' || p_pasarela || ''', a, b, c); END;';
  END bloque_pago;

  FUNCTION reservada_a RETURN NUMBER IS
    v_cantidad NUMBER;
  BEGIN
    SELECT CANTIDAD_RESERVADA INTO v_cantidad FROM STOCK WHERE ID_VARIANTE = v_var_a;
    RETURN v_cantidad;
  END reservada_a;

  FUNCTION pedidos_de(p_cliente IN NUMBER) RETURN NUMBER IS
    v_cuenta NUMBER;
  BEGIN
    SELECT COUNT(*) INTO v_cuenta FROM PEDIDO WHERE ID_CLIENTE = p_cliente;
    RETURN v_cuenta;
  END pedidos_de;
BEGIN
  -- Datos temporales
  SELECT ID_IMPUESTO       INTO v_id_impuesto FROM IMPUESTO       WHERE NOMBRE = 'IVA 19%';
  SELECT ID_TIPO_DOCUMENTO INTO v_id_tipo_doc FROM TIPO_DOCUMENTO WHERE CODIGO = 'CC';
  v_minutos := PKG_UTILIDADES.valor_parametro('PEDIDO_EXPIRACION_MINUTOS');

  INSERT INTO DEPARTAMENTO (CODIGO_DANE, NOMBRE) VALUES ('00', 'DEPARTAMENTO PRUEBA');
  INSERT INTO MUNICIPIO (CODIGO_DANE, CODIGO_DEPARTAMENTO, NOMBRE)
    VALUES ('00000', '00', 'MUNICIPIO PRUEBA');

  INSERT INTO CLIENTE (ID_TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRE, EMAIL)
    VALUES (v_id_tipo_doc, '9000000021', 'Cliente pedido 1', 'prueba.pedido1@ejemplo.com')
    RETURNING ID_CLIENTE INTO v_c1;
  INSERT INTO CLIENTE (ID_TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRE, EMAIL)
    VALUES (v_id_tipo_doc, '9000000022', 'Cliente pedido 2', 'prueba.pedido2@ejemplo.com')
    RETURNING ID_CLIENTE INTO v_c2;
  INSERT INTO CLIENTE (ID_TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRE, EMAIL)
    VALUES (v_id_tipo_doc, '9000000023', 'Cliente pedido 3', 'prueba.pedido3@ejemplo.com')
    RETURNING ID_CLIENTE INTO v_c3;
  INSERT INTO CLIENTE_DIRECCION (ID_CLIENTE, DIRECCION, ES_PRINCIPAL, CODIGO_MUNICIPIO)
    VALUES (v_c1, 'Calle 10 # 20-30', 1, '00000')
    RETURNING ID_CLIENTE_DIRECCION INTO v_dir1;
  INSERT INTO CLIENTE_DIRECCION (ID_CLIENTE, DIRECCION, ES_PRINCIPAL, CODIGO_MUNICIPIO)
    VALUES (v_c3, 'Carrera 5 # 6-7', 1, '00000')
    RETURNING ID_CLIENTE_DIRECCION INTO v_dir3;

  INSERT INTO CATEGORIA (NOMBRE) VALUES ('CATEGORIA PRUEBA PEDIDO')
    RETURNING ID_CATEGORIA INTO v_categoria;
  INSERT INTO PRODUCTO (ID_CATEGORIA, ID_IMPUESTO, NOMBRE)
    VALUES (v_categoria, v_id_impuesto, 'Producto prueba pedido')
    RETURNING ID_PRODUCTO INTO v_producto;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE, CONTROLA_STOCK)
    VALUES (v_producto, 'PED-A', 'Variante A', 100000, 1)
    RETURNING ID_VARIANTE INTO v_var_a;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE, CONTROLA_STOCK)
    VALUES (v_producto, 'PED-B', 'Variante B', 40000, 0)
    RETURNING ID_VARIANTE INTO v_var_b;
  PKG_INVENTARIO.entrada(v_var_a, 10, 'prueba');
  INSERT INTO PASARELA_PAGO (CODIGO, NOMBRE) VALUES ('PRUEBA', 'Pasarela de prueba');

  -- Sin carrito o con carrito vacio
  esperar_error('crear sin carrito', bloque_crear(v_c1), -20162);
  v_carrito := PKG_CARRITO.obtener_carrito(v_c1);
  esperar_error('crear con el carrito vacio', bloque_crear(v_c1), -20162);

  -- Pedido correcto: 3 de A (300.000) y 2 de B (80.000), IVA 19%
  PKG_CARRITO.agregar_item(v_c1, v_var_b, 2);
  PKG_CARRITO.agregar_item(v_c1, v_var_a, 3);
  v_pedido := PKG_PEDIDO.crear_desde_carrito(v_c1);
  SELECT * INTO v_ped FROM PEDIDO WHERE ID_PEDIDO = v_pedido;
  SELECT e.CODIGO INTO v_estado FROM ESTADO_PEDIDO e WHERE e.ID_ESTADO_PEDIDO = v_ped.ID_ESTADO_PEDIDO;

  verificar('el pedido queda PENDIENTE_PAGO', v_estado = 'PENDIENTE_PAGO');
  verificar('totales: subtotal 380.000, IVA 72.200, total 452.200, descuento 0',
            v_ped.SUBTOTAL = 380000 AND v_ped.IMPUESTO_TOTAL = 72200
            AND v_ped.TOTAL = 452200 AND v_ped.DESCUENTO_TOTAL = 0);
  SELECT COUNT(*) INTO v_n FROM PEDIDO_ITEM WHERE ID_PEDIDO = v_pedido;
  verificar('el pedido tiene 2 lineas', v_n = 2);
  verificar('foto de facturacion copiada del cliente y su direccion principal',
            v_ped.FACT_TIPO_DOCUMENTO = 'CC' AND v_ped.FACT_NUMERO_DOCUMENTO = '9000000021'
            AND v_ped.FACT_DIRECCION = 'Calle 10 # 20-30' AND v_ped.FACT_CODIGO_MUNICIPIO = '00000');
  verificar('vence en PEDIDO_EXPIRACION_MINUTOS (' || v_minutos || ' min)',
            v_ped.FECHA_EXPIRACION BETWEEN PKG_UTILIDADES.ahora + NUMTODSINTERVAL(v_minutos - 1, 'MINUTE')
                                       AND PKG_UTILIDADES.ahora + NUMTODSINTERVAL(v_minutos, 'MINUTE'));
  verificar('se reservaron 3 unidades de A', reservada_a = 3);
  SELECT COUNT(*) INTO v_n FROM CARRITO
   WHERE ID_CARRITO = v_carrito AND ESTADO = 'CONVERTIDO';
  verificar('el carrito queda CONVERTIDO y el pedido apunta a el',
            v_n = 1 AND v_ped.ID_CARRITO = v_carrito);

  esperar_error('crear otra vez (doble clic)', bloque_crear(v_c1), -20162);

  -- Iniciar el pago
  PKG_PEDIDO.iniciar_pago(v_c1, v_pedido, 'PRUEBA', v_pago, v_publico, v_monto);
  SELECT COUNT(*) INTO v_n FROM PAGO
   WHERE ID_PAGO = v_pago AND ESTADO = 'PENDIENTE' AND MONTO = 452200
     AND RAWTOHEX(ID_PUBLICO) = v_publico;
  verificar('iniciar_pago crea un PAGO PENDIENTE por el total del pedido (452.200)',
            v_n = 1 AND v_monto = 452200 AND LENGTH(v_publico) = 32);
  PKG_PEDIDO.iniciar_pago(v_c1, v_pedido, 'PRUEBA', v_pago2, v_publico2, v_monto);
  SELECT COUNT(*) INTO v_n FROM PAGO WHERE ID_PEDIDO = v_pedido;
  verificar('iniciar_pago otra vez (doble clic) devuelve el mismo intento',
            v_pago2 = v_pago AND v_publico2 = v_publico AND v_n = 1);
  esperar_error('pasarela inexistente', bloque_pago(v_c1, v_pedido, 'NO_EXISTE'), -20167);
  esperar_error('pagar el pedido de otro cliente', bloque_pago(v_c3, v_pedido, 'PRUEBA'), -20160);
  UPDATE PEDIDO SET FECHA_EXPIRACION = PKG_UTILIDADES.ahora - INTERVAL '1' MINUTE
   WHERE ID_PEDIDO = v_pedido;
  esperar_error('pagar un pedido vencido', bloque_pago(v_c1, v_pedido, 'PRUEBA'), -20166);

  -- Stock insuficiente: no queda nada a medias
  PKG_CARRITO.agregar_item(v_c1, v_var_a, 8);   -- quedan 7 disponibles
  esperar_error('crear sin stock suficiente', bloque_crear(v_c1), -20122);
  SELECT COUNT(*) INTO v_n FROM CARRITO WHERE ID_CLIENTE = v_c1 AND ESTADO = 'ABIERTO';
  verificar('sin stock no deja pedido ni reserva y el carrito sigue ABIERTO',
            pedidos_de(v_c1) = 1 AND reservada_a = 3 AND v_n = 1);

  -- Variante desactivada despues de agregarla al carrito
  PKG_CARRITO.vaciar(v_c1);
  PKG_CARRITO.agregar_item(v_c1, v_var_b, 1);
  UPDATE VARIANTE SET ACTIVO = 0 WHERE ID_VARIANTE = v_var_b;
  esperar_error('crear con una variante desactivada', bloque_crear(v_c1), -20101);
  UPDATE VARIANTE SET ACTIVO = 1 WHERE ID_VARIANTE = v_var_b;

  -- Direccion de facturacion
  PKG_CARRITO.agregar_item(v_c2, v_var_b, 1);
  esperar_error('cliente sin direccion principal', bloque_crear(v_c2), -20163);
  esperar_error('direccion de otro cliente', bloque_crear(v_c1, v_dir3), -20163);

  -- Cancelar
  esperar_error('cancelar el pedido de otro cliente',
    'BEGIN PKG_PEDIDO.cancelar(' || v_c3 || ', ' || v_pedido || '); END;', -20160);

  PKG_PEDIDO.cancelar(v_c1, v_pedido, 'Me equivoque de producto');
  SELECT e.CODIGO, p.MOTIVO_ESTADO INTO v_estado, v_ped.MOTIVO_ESTADO
    FROM PEDIDO p JOIN ESTADO_PEDIDO e ON e.ID_ESTADO_PEDIDO = p.ID_ESTADO_PEDIDO
   WHERE p.ID_PEDIDO = v_pedido;
  verificar('el cliente cancela su pedido: CANCELADO y se libera el stock',
            v_estado = 'CANCELADO' AND reservada_a = 0);
  verificar('el motivo indica que cancelo el cliente',
            v_ped.MOTIVO_ESTADO = 'Cancelado por el cliente: Me equivoque de producto');
  esperar_error('pagar un pedido cancelado', bloque_pago(v_c1, v_pedido, 'PRUEBA'), -20164);

  -- Cliente inactivo
  UPDATE CLIENTE SET ACTIVO = 0 WHERE ID_CLIENTE = v_c3;
  esperar_error('cliente inactivo', bloque_crear(v_c3), -20161);

  DBMS_OUTPUT.PUT_LINE('Resultado: ' || v_pruebas || ' pruebas, ' || v_fallos || ' fallos');
  ROLLBACK;
EXCEPTION
  WHEN OTHERS THEN
    ROLLBACK;
    RAISE;
END;
/
