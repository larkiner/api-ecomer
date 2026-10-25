-- ============================================================================
-- 14_pkg_pedido_estado.sql
-- Seccion 5 del diseno (parte 7): paquete PKG_PEDIDO_ESTADO.
-- Cambios de estado de un pedido ya creado: pagar, cancelar y expirar.
--
-- Requiere haber ejecutado antes los scripts 06, 09, 11 y 12. Las pruebas usan
-- un municipio temporal (no hace falta el script 05).
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1 (SELECT USER FROM dual).
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Al final sale una linea "OK" por cada prueba y un resumen. Las pruebas
--      crean datos temporales y los deshacen con ROLLBACK: no quedan en la base.
--   4. Para deshacerlo: DROP PACKAGE PKG_PEDIDO_ESTADO;
--
-- DECISIONES DE DISENO
--   * POR QUE ES UN PAQUETE APARTE DE PKG_PEDIDO: en Oracle el EXECUTE se da por
--     paquete completo, no por procedimiento. Si marcar_pagado estuviera en
--     PKG_PEDIDO (que recibe el rol de clientes), un cliente podria marcar su
--     pedido como pagado sin pagar. Por eso este paquete es INTERNO: lo llaman
--     PKG_PEDIDO (cancelar), PKG_PAGO (marcar_pagado) y PKG_TAREAS (expirar), y
--     ningun rol de aplicacion recibe EXECUTE sobre el. Es el mismo motivo por el
--     que PKG_RESERVA se separo de PKG_INVENTARIO.
--   * Es el UNICO codigo que cambia ID_ESTADO_PEDIDO. Toda transicion pasa por aqui.
--   * Cada operacion bloquea primero la fila del pedido (SELECT ... FOR UPDATE
--     WAIT 10) y decide segun el estado que lee YA BLOQUEADO. Asi un pago y la
--     expiracion del mismo pedido no se pisan: el segundo espera, relee el estado
--     y actua en consecuencia. Si espera mas de 10 s falla con -20168 y quien lo
--     llamo reintenta (la cola ya reintenta con espera creciente).
--   * Las lineas se recorren en orden ascendente de ID_VARIANTE (regla de
--     PKG_RESERVA para evitar interbloqueos).
--   * marcar_pagado se basa en el ESTADO, no en la fecha: si el pago se aprueba
--     cuando ya paso FECHA_EXPIRACION pero el JOB aun no expiro el pedido, la
--     reserva sigue activa y el pedido se paga normalmente.
--   * marcar_pagado devuelve un codigo en vez de fallar, porque todos estos casos
--     son normales y PKG_PAGO debe decidir que hacer con cada uno:
--       PAGADO             pedido PENDIENTE_PAGO: se consume la reserva.
--       YA_PAGADO          notificacion repetida: no hace nada (idempotente).
--       PAGADO_REACTIVADO  pedido EXPIRADO con stock disponible: se reserva de
--                          nuevo, se consume y pasa a PAGADO.
--       SIN_STOCK          pedido EXPIRADO sin stock suficiente: el pedido sigue
--                          EXPIRADO. El pago aprobado aparece en
--                          V_CONCILIACION_PAGOS para devolver el dinero a mano.
--       NO_PAGABLE         pedido CANCELADO: igual que SIN_STOCK, se revisa a mano.
--     OJO: el paso EXPIRADO -> PAGADO es la UNICA excepcion a ES_FINAL = 1, y es
--     la politica que sigue pendiente de validar con negocio. Si negocio dice que
--     no, basta con tratar EXPIRADO igual que CANCELADO en marcar_pagado.
--   * cancelar es idempotente (cancelar un pedido CANCELADO no hace nada) y
--     exige motivo. Solo se cancela un pedido PENDIENTE_PAGO. Si tenia un pago en
--     curso y la pasarela lo aprueba despues, marcar_pagado devuelve NO_PAGABLE.
--   * expirar devuelve TRUE si expiro el pedido y FALSE si no correspondia (ya no
--     estaba pendiente o aun no vencia). Asi el JOB puede reintentar sin miedo.
--   * Ningun procedimiento hace COMMIT: confirma quien lo llama. Cada uno es
--     atomico (SAVEPOINT + ROLLBACK TO).
--   * Errores (bloque -20160 a -20179, compartido con PKG_PEDIDO):
--       -20160 el pedido no existe
--       -20164 el estado del pedido no permite la operacion
--       -20165 falta el motivo
--       -20168 el pedido esta bloqueado por otra operacion (reintentar)
--       -20169 configuracion incompleta (falta un estado en ESTADO_PEDIDO)
--     Tambien puede propagar -20124 y -20127 de PKG_RESERVA (inconsistencias).
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

PROMPT >> 14_pkg_pedido_estado.sql: comprobando requisitos

DECLARE
  v_existentes NUMBER;
BEGIN
  IF SYS_CONTEXT('USERENV', 'SESSION_USER') <> 'ECOMERS'
     OR SYS_CONTEXT('USERENV', 'CON_NAME') <> 'XEPDB1' THEN
    RAISE_APPLICATION_ERROR(-20001,
      'Conectate como ECOMERS al servicio XEPDB1 antes de ejecutar este script.');
  END IF;

  SELECT COUNT(*) INTO v_existentes
    FROM ESTADO_PEDIDO
   WHERE CODIGO IN ('PENDIENTE_PAGO', 'PAGADO', 'CANCELADO', 'EXPIRADO');
  IF v_existentes < 4 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan estados en ESTADO_PEDIDO: revisa el script 06.');
  END IF;

  SELECT COUNT(*) INTO v_existentes
    FROM user_objects
   WHERE object_name IN ('PKG_UTILIDADES', 'PKG_INVENTARIO', 'PKG_RESERVA')
     AND object_type = 'PACKAGE BODY'
     AND status = 'VALID';
  IF v_existentes < 3 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan paquetes previos validos: ejecuta antes los scripts 09, 11 y 12.');
  END IF;
END;
/

CREATE OR REPLACE PACKAGE PKG_PEDIDO_ESTADO
  AUTHID DEFINER
AS
  -- Resultados de marcar_pagado (ver la cabecera del script).
  c_pagado            CONSTANT VARCHAR2(20) := 'PAGADO';
  c_ya_pagado         CONSTANT VARCHAR2(20) := 'YA_PAGADO';
  c_pagado_reactivado CONSTANT VARCHAR2(20) := 'PAGADO_REACTIVADO';
  c_sin_stock         CONSTANT VARCHAR2(20) := 'SIN_STOCK';
  c_no_pagable        CONSTANT VARCHAR2(20) := 'NO_PAGABLE';

  -- Aplica al pedido un pago ya aprobado. Lo llama PKG_PAGO.
  -- Errores: -20160, -20168, -20169.
  FUNCTION marcar_pagado(p_id_pedido IN NUMBER) RETURN VARCHAR2;

  -- Cancela un pedido PENDIENTE_PAGO y libera su stock. Lo llama PKG_PEDIDO.
  -- Errores: -20160, -20164 (no esta PENDIENTE_PAGO), -20165, -20168, -20169.
  PROCEDURE cancelar(p_id_pedido IN NUMBER, p_motivo IN VARCHAR2);

  -- Expira el pedido si sigue PENDIENTE_PAGO y ya vencio; libera su stock.
  -- TRUE si lo expiro, FALSE si no correspondia. Lo llama PKG_TAREAS.
  -- Errores: -20160, -20168, -20169.
  FUNCTION expirar(p_id_pedido IN NUMBER) RETURN BOOLEAN;
END PKG_PEDIDO_ESTADO;
/
SHOW ERRORS PACKAGE PKG_PEDIDO_ESTADO
PROMPT >> PKG_PEDIDO_ESTADO: especificacion creada

CREATE OR REPLACE PACKAGE BODY PKG_PEDIDO_ESTADO
AS
  e_sin_stock EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_sin_stock, -20122);   -- de PKG_RESERVA.reservar

  e_ocupado EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_ocupado, -30006);     -- se agoto el WAIT del FOR UPDATE

  -- ---------------------------------------------------------------------------
  -- Privados
  -- ---------------------------------------------------------------------------
  FUNCTION id_estado(p_codigo IN VARCHAR2) RETURN NUMBER IS
    v_id ESTADO_PEDIDO.ID_ESTADO_PEDIDO%TYPE;
  BEGIN
    SELECT ID_ESTADO_PEDIDO INTO v_id FROM ESTADO_PEDIDO WHERE CODIGO = p_codigo;
    RETURN v_id;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RAISE_APPLICATION_ERROR(-20169, 'Falta el estado ' || p_codigo || ' en ESTADO_PEDIDO.');
  END id_estado;

  -- Bloquea el pedido y devuelve su estado actual y su fecha de expiracion.
  PROCEDURE bloquear_pedido(
    p_id_pedido IN  NUMBER,
    p_estado    OUT VARCHAR2,
    p_vence     OUT TIMESTAMP
  ) IS
    v_id_estado PEDIDO.ID_ESTADO_PEDIDO%TYPE;
  BEGIN
    SELECT ID_ESTADO_PEDIDO, FECHA_EXPIRACION
      INTO v_id_estado, p_vence
      FROM PEDIDO
     WHERE ID_PEDIDO = p_id_pedido
       FOR UPDATE WAIT 10;

    SELECT CODIGO INTO p_estado FROM ESTADO_PEDIDO WHERE ID_ESTADO_PEDIDO = v_id_estado;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RAISE_APPLICATION_ERROR(-20160, 'El pedido ' || p_id_pedido || ' no existe.');
    WHEN e_ocupado THEN
      RAISE_APPLICATION_ERROR(-20168,
        'El pedido ' || p_id_pedido || ' esta ocupado por otra operacion; intenta de nuevo.');
  END bloquear_pedido;

  -- El trigger TRG_PEDIDO_ESTADO_HIST copia MOTIVO_ESTADO al historial.
  PROCEDURE cambiar_estado(p_id_pedido IN NUMBER, p_codigo IN VARCHAR2, p_motivo IN VARCHAR2) IS
    v_id_estado ESTADO_PEDIDO.ID_ESTADO_PEDIDO%TYPE;
  BEGIN
    v_id_estado := id_estado(p_codigo);
    UPDATE PEDIDO
       SET ID_ESTADO_PEDIDO = v_id_estado,
           MOTIVO_ESTADO    = SUBSTR(p_motivo, 1, 500)
     WHERE ID_PEDIDO = p_id_pedido;
  END cambiar_estado;

  -- Aplica PKG_RESERVA a todas las lineas, en orden ascendente de variante.
  PROCEDURE mover_reservas(p_id_pedido IN NUMBER, p_accion IN VARCHAR2) IS
  BEGIN
    FOR l IN (SELECT ID_PEDIDO_ITEM
                FROM PEDIDO_ITEM
               WHERE ID_PEDIDO = p_id_pedido
               ORDER BY ID_VARIANTE) LOOP
      CASE p_accion
        WHEN 'RESERVAR' THEN PKG_RESERVA.reservar(l.ID_PEDIDO_ITEM);
        WHEN 'LIBERAR'  THEN PKG_RESERVA.liberar(l.ID_PEDIDO_ITEM);
        WHEN 'CONSUMIR' THEN PKG_RESERVA.consumir(l.ID_PEDIDO_ITEM);
      END CASE;
    END LOOP;
  END mover_reservas;

  -- ---------------------------------------------------------------------------
  -- Publicos
  -- ---------------------------------------------------------------------------
  FUNCTION marcar_pagado(p_id_pedido IN NUMBER) RETURN VARCHAR2 IS
    v_estado ESTADO_PEDIDO.CODIGO%TYPE;
    v_vence  TIMESTAMP;
  BEGIN
    SAVEPOINT sp_marcar_pagado;

    bloquear_pedido(p_id_pedido, v_estado, v_vence);

    IF v_estado = 'PAGADO' THEN
      RETURN c_ya_pagado;

    ELSIF v_estado = 'PENDIENTE_PAGO' THEN
      mover_reservas(p_id_pedido, 'CONSUMIR');
      cambiar_estado(p_id_pedido, 'PAGADO', 'Pago aprobado');
      RETURN c_pagado;

    ELSIF v_estado = 'EXPIRADO' THEN
      -- Se intenta reservar todo de nuevo; si una linea no alcanza, se deshace
      -- lo reservado en las anteriores y el pedido queda como estaba.
      SAVEPOINT sp_reactivar;
      BEGIN
        mover_reservas(p_id_pedido, 'RESERVAR');
      EXCEPTION
        WHEN e_sin_stock THEN
          ROLLBACK TO SAVEPOINT sp_reactivar;
          RETURN c_sin_stock;
      END;
      mover_reservas(p_id_pedido, 'CONSUMIR');
      cambiar_estado(p_id_pedido, 'PAGADO',
                     'Pago aprobado despues de expirar; el stock se reservo de nuevo');
      RETURN c_pagado_reactivado;

    ELSE
      RETURN c_no_pagable;   -- CANCELADO u otro estado final
    END IF;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_marcar_pagado;
      RAISE;
  END marcar_pagado;

  PROCEDURE cancelar(p_id_pedido IN NUMBER, p_motivo IN VARCHAR2) IS
    v_estado ESTADO_PEDIDO.CODIGO%TYPE;
    v_vence  TIMESTAMP;
  BEGIN
    SAVEPOINT sp_cancelar;

    IF TRIM(p_motivo) IS NULL THEN
      RAISE_APPLICATION_ERROR(-20165, 'Para cancelar un pedido hay que indicar el motivo.');
    END IF;

    bloquear_pedido(p_id_pedido, v_estado, v_vence);

    IF v_estado = 'CANCELADO' THEN
      RETURN;   -- ya estaba cancelado: no se hace nada
    ELSIF v_estado <> 'PENDIENTE_PAGO' THEN
      RAISE_APPLICATION_ERROR(-20164,
        'El pedido ' || p_id_pedido || ' esta ' || v_estado || ' y no se puede cancelar.');
    END IF;

    mover_reservas(p_id_pedido, 'LIBERAR');
    cambiar_estado(p_id_pedido, 'CANCELADO', p_motivo);
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_cancelar;
      RAISE;
  END cancelar;

  FUNCTION expirar(p_id_pedido IN NUMBER) RETURN BOOLEAN IS
    v_estado ESTADO_PEDIDO.CODIGO%TYPE;
    v_vence  TIMESTAMP;
  BEGIN
    SAVEPOINT sp_expirar;

    bloquear_pedido(p_id_pedido, v_estado, v_vence);

    IF v_estado <> 'PENDIENTE_PAGO' OR v_vence > PKG_UTILIDADES.ahora THEN
      RETURN FALSE;
    END IF;

    mover_reservas(p_id_pedido, 'LIBERAR');
    cambiar_estado(p_id_pedido, 'EXPIRADO',
                   'No se pago antes de ' || TO_CHAR(v_vence, 'YYYY-MM-DD HH24:MI'));
    RETURN TRUE;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_expirar;
      RAISE;
  END expirar;
END PKG_PEDIDO_ESTADO;
/
SHOW ERRORS PACKAGE BODY PKG_PEDIDO_ESTADO
PROMPT >> PKG_PEDIDO_ESTADO: cuerpo creado

-- CREATE PACKAGE no se detiene ante errores de compilacion (solo avisa), asi que
-- se revisa USER_ERRORS de forma explicita.
DECLARE
  v_errores NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_errores
    FROM user_errors
   WHERE name = 'PKG_PEDIDO_ESTADO';
  IF v_errores > 0 THEN
    RAISE_APPLICATION_ERROR(-20004,
      'PKG_PEDIDO_ESTADO compilo con errores: consulta USER_ERRORS.');
  END IF;
END;
/
PROMPT >> PKG_PEDIDO_ESTADO: paquete compilado sin errores

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT object_name, object_type, status
  FROM user_objects
 WHERE object_name = 'PKG_PEDIDO_ESTADO'
 ORDER BY object_type;                                                       -- PACKAGE VALID, PACKAGE BODY VALID

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';                                                    -- 0 filas

-- ============================================================================
-- PRUEBAS RAPIDAS (terminan con ROLLBACK: no dejan datos).
-- Esperado: 20 lineas "OK" y al final "0 fallos".
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  v_pruebas      NUMBER := 0;
  v_fallos       NUMBER := 0;
  v_id_impuesto  NUMBER;
  v_id_tipo_doc  NUMBER;
  v_id_pendiente NUMBER;
  v_cliente      NUMBER;
  v_categoria    NUMBER;
  v_producto     NUMBER;
  v_var_a        NUMBER;   -- controla stock
  v_var_b        NUMBER;   -- no controla stock
  v_p1           NUMBER;
  v_p2           NUMBER;
  v_p3           NUMBER;
  v_p4           NUMBER;
  v_p5           NUMBER;
  v_resultado    VARCHAR2(20);
  v_total        NUMBER;
  v_reservada    NUMBER;
  v_n            NUMBER;
  v_motivo       PEDIDO_ESTADO_HIST.MOTIVO%TYPE;

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

  PROCEDURE leer_stock IS
  BEGIN
    SELECT CANTIDAD_TOTAL, CANTIDAD_RESERVADA
      INTO v_total, v_reservada
      FROM STOCK
     WHERE ID_VARIANTE = v_var_a;
  END leer_stock;

  FUNCTION estado_de(p_id_pedido IN NUMBER) RETURN VARCHAR2 IS
    v_codigo ESTADO_PEDIDO.CODIGO%TYPE;
  BEGIN
    SELECT e.CODIGO INTO v_codigo
      FROM PEDIDO p
      JOIN ESTADO_PEDIDO e ON e.ID_ESTADO_PEDIDO = p.ID_ESTADO_PEDIDO
     WHERE p.ID_PEDIDO = p_id_pedido;
    RETURN v_codigo;
  END estado_de;

  -- Estado de la reserva de la linea de la variante A del pedido.
  FUNCTION reserva_a(p_id_pedido IN NUMBER) RETURN VARCHAR2 IS
    v_estado RESERVA_STOCK.ESTADO%TYPE;
  BEGIN
    SELECT r.ESTADO INTO v_estado
      FROM RESERVA_STOCK r
      JOIN PEDIDO_ITEM i ON i.ID_PEDIDO_ITEM = r.ID_PEDIDO_ITEM
     WHERE i.ID_PEDIDO = p_id_pedido
       AND i.ID_VARIANTE = v_var_a;
    RETURN v_estado;
  END reserva_a;

  -- Crea un pedido PENDIENTE_PAGO con dos lineas (p_cant_a unidades de A y una de
  -- B, a 50.000 con IVA 19%) y reserva su stock en orden de variante.
  FUNCTION nuevo_pedido(p_cant_a IN NUMBER) RETURN NUMBER IS
    v_sub_a  NUMBER := 50000 * p_cant_a;
    v_iva_a  NUMBER := ROUND(50000 * p_cant_a * 0.19, 2);
    v_sub_b  NUMBER := 50000;
    v_iva_b  NUMBER := 9500;
    v_pedido NUMBER;
    v_item_a NUMBER;
    v_item_b NUMBER;
  BEGIN
    INSERT INTO PEDIDO (ID_CLIENTE, ID_ESTADO_PEDIDO, FECHA_EXPIRACION,
                        FACT_TIPO_DOCUMENTO, FACT_NUMERO_DOCUMENTO, FACT_NOMBRE, FACT_EMAIL,
                        FACT_DIRECCION, FACT_CODIGO_MUNICIPIO,
                        SUBTOTAL, DESCUENTO_TOTAL, IMPUESTO_TOTAL, TOTAL)
    VALUES (v_cliente, v_id_pendiente, PKG_UTILIDADES.ahora + INTERVAL '30' MINUTE,
            'CC', '9000000010', 'Cliente prueba estados', 'prueba.estados@ejemplo.com',
            'Calle 1 # 2-3', '00000',
            v_sub_a + v_sub_b, 0, v_iva_a + v_iva_b, v_sub_a + v_sub_b + v_iva_a + v_iva_b)
    RETURNING ID_PEDIDO INTO v_pedido;

    INSERT INTO PEDIDO_ITEM (ID_PEDIDO, ID_VARIANTE, SKU, NOMBRE_ITEM, CANTIDAD,
                             PRECIO_UNITARIO, DESCUENTO_UNITARIO, TASA_IMPUESTO,
                             SUBTOTAL_LINEA, IMPUESTO_LINEA, TOTAL_LINEA)
    VALUES (v_pedido, v_var_a, 'EST-A', 'Item A', p_cant_a,
            50000, 0, 0.19, v_sub_a, v_iva_a, v_sub_a + v_iva_a)
    RETURNING ID_PEDIDO_ITEM INTO v_item_a;

    INSERT INTO PEDIDO_ITEM (ID_PEDIDO, ID_VARIANTE, SKU, NOMBRE_ITEM, CANTIDAD,
                             PRECIO_UNITARIO, DESCUENTO_UNITARIO, TASA_IMPUESTO,
                             SUBTOTAL_LINEA, IMPUESTO_LINEA, TOTAL_LINEA)
    VALUES (v_pedido, v_var_b, 'EST-B', 'Item B', 1,
            50000, 0, 0.19, v_sub_b, v_iva_b, v_sub_b + v_iva_b)
    RETURNING ID_PEDIDO_ITEM INTO v_item_b;

    PKG_RESERVA.reservar(v_item_a);   -- v_var_a < v_var_b: orden ascendente
    PKG_RESERVA.reservar(v_item_b);   -- B no controla stock: no hace nada
    RETURN v_pedido;
  END nuevo_pedido;

  PROCEDURE vencer(p_id_pedido IN NUMBER) IS
  BEGIN
    UPDATE PEDIDO
       SET FECHA_EXPIRACION = PKG_UTILIDADES.ahora - INTERVAL '1' MINUTE
     WHERE ID_PEDIDO = p_id_pedido;
  END vencer;
BEGIN
  -- Datos temporales
  SELECT ID_IMPUESTO       INTO v_id_impuesto  FROM IMPUESTO       WHERE NOMBRE = 'IVA 19%';
  SELECT ID_TIPO_DOCUMENTO INTO v_id_tipo_doc  FROM TIPO_DOCUMENTO WHERE CODIGO = 'CC';
  SELECT ID_ESTADO_PEDIDO  INTO v_id_pendiente FROM ESTADO_PEDIDO  WHERE CODIGO = 'PENDIENTE_PAGO';

  INSERT INTO DEPARTAMENTO (CODIGO_DANE, NOMBRE) VALUES ('00', 'DEPARTAMENTO PRUEBA');
  INSERT INTO MUNICIPIO (CODIGO_DANE, CODIGO_DEPARTAMENTO, NOMBRE)
    VALUES ('00000', '00', 'MUNICIPIO PRUEBA');
  INSERT INTO CLIENTE (ID_TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRE, EMAIL)
    VALUES (v_id_tipo_doc, '9000000010', 'Cliente prueba estados', 'prueba.estados@ejemplo.com')
    RETURNING ID_CLIENTE INTO v_cliente;
  INSERT INTO CATEGORIA (NOMBRE) VALUES ('CATEGORIA PRUEBA ESTADOS')
    RETURNING ID_CATEGORIA INTO v_categoria;
  INSERT INTO PRODUCTO (ID_CATEGORIA, ID_IMPUESTO, NOMBRE)
    VALUES (v_categoria, v_id_impuesto, 'Producto prueba estados')
    RETURNING ID_PRODUCTO INTO v_producto;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE, CONTROLA_STOCK)
    VALUES (v_producto, 'EST-A', 'Con stock', 50000, 1)
    RETURNING ID_VARIANTE INTO v_var_a;
  INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE, CONTROLA_STOCK)
    VALUES (v_producto, 'EST-B', 'Sin control de stock', 50000, 0)
    RETURNING ID_VARIANTE INTO v_var_b;

  PKG_INVENTARIO.entrada(v_var_a, 10, 'prueba');

  -- Pagar
  v_p1 := nuevo_pedido(2);
  leer_stock;
  verificar('pedido 1 creado: total 10, reservada 2', v_total = 10 AND v_reservada = 2);

  v_resultado := PKG_PEDIDO_ESTADO.marcar_pagado(v_p1);
  leer_stock;
  verificar('marcar_pagado devuelve PAGADO y el pedido queda PAGADO',
            v_resultado = PKG_PEDIDO_ESTADO.c_pagado AND estado_de(v_p1) = 'PAGADO');
  verificar('pagar consume la reserva: total 8, reservada 0, reserva CONSUMIDA',
            v_total = 8 AND v_reservada = 0 AND reserva_a(v_p1) = 'CONSUMIDA');

  v_resultado := PKG_PEDIDO_ESTADO.marcar_pagado(v_p1);
  leer_stock;
  verificar('notificacion repetida: YA_PAGADO y el stock no cambia (total 8)',
            v_resultado = PKG_PEDIDO_ESTADO.c_ya_pagado AND v_total = 8);

  SELECT COUNT(*) INTO v_n FROM PEDIDO_ESTADO_HIST WHERE ID_PEDIDO = v_p1;
  verificar('historial del pedido 1: creacion y PAGADO (2 filas)', v_n = 2);

  -- Cancelar
  v_p2 := nuevo_pedido(3);
  PKG_PEDIDO_ESTADO.cancelar(v_p2, 'Prueba de cancelacion');
  leer_stock;
  verificar('cancelar: pedido CANCELADO, reservada 0, reserva LIBERADA',
            estado_de(v_p2) = 'CANCELADO' AND v_reservada = 0 AND reserva_a(v_p2) = 'LIBERADA');

  PKG_PEDIDO_ESTADO.cancelar(v_p2, 'Segundo intento');
  SELECT COUNT(*) INTO v_n FROM PEDIDO_ESTADO_HIST WHERE ID_PEDIDO = v_p2;
  verificar('cancelar otra vez no da error ni agrega historial (2 filas)', v_n = 2);

  SELECT MOTIVO INTO v_motivo FROM PEDIDO_ESTADO_HIST
   WHERE ID_PEDIDO = v_p2 AND ID_ESTADO_ANTERIOR IS NOT NULL;
  verificar('el historial guarda el motivo de la cancelacion',
            v_motivo = 'Prueba de cancelacion');

  v_resultado := PKG_PEDIDO_ESTADO.marcar_pagado(v_p2);
  verificar('pagar un pedido cancelado devuelve NO_PAGABLE y sigue CANCELADO',
            v_resultado = PKG_PEDIDO_ESTADO.c_no_pagable AND estado_de(v_p2) = 'CANCELADO');

  esperar_error('cancelar un pedido pagado',
    'BEGIN PKG_PEDIDO_ESTADO.cancelar(' || v_p1 || ', ''x''); END;', -20164);
  esperar_error('cancelar sin motivo',
    'BEGIN PKG_PEDIDO_ESTADO.cancelar(' || v_p2 || ', ''  ''); END;', -20165);
  esperar_error('pedido inexistente',
    'DECLARE r VARCHAR2(20); BEGIN r := PKG_PEDIDO_ESTADO.marcar_pagado(-1); END;', -20160);

  -- Expirar
  v_p3 := nuevo_pedido(4);
  verificar('expirar un pedido que aun no vence devuelve FALSE',
            NOT PKG_PEDIDO_ESTADO.expirar(v_p3) AND estado_de(v_p3) = 'PENDIENTE_PAGO');

  vencer(v_p3);
  verificar('expirar un pedido vencido devuelve TRUE', PKG_PEDIDO_ESTADO.expirar(v_p3));
  leer_stock;
  verificar('expirar: pedido EXPIRADO, reservada 0, reserva LIBERADA',
            estado_de(v_p3) = 'EXPIRADO' AND v_reservada = 0 AND reserva_a(v_p3) = 'LIBERADA');
  verificar('expirar otra vez devuelve FALSE', NOT PKG_PEDIDO_ESTADO.expirar(v_p3));

  -- Pago aprobado despues de expirar, con stock
  v_resultado := PKG_PEDIDO_ESTADO.marcar_pagado(v_p3);
  leer_stock;
  verificar('pago tardio con stock: PAGADO_REACTIVADO, pedido PAGADO, total 4, reservada 0',
            v_resultado = PKG_PEDIDO_ESTADO.c_pagado_reactivado
            AND estado_de(v_p3) = 'PAGADO' AND v_total = 4 AND v_reservada = 0
            AND reserva_a(v_p3) = 'CONSUMIDA');

  -- Pago aprobado despues de expirar, sin stock
  v_p4 := nuevo_pedido(3);
  vencer(v_p4);
  IF PKG_PEDIDO_ESTADO.expirar(v_p4) THEN NULL; END IF;
  v_p5 := nuevo_pedido(4);   -- otro comprador se lleva las 4 unidades que quedan
  v_resultado := PKG_PEDIDO_ESTADO.marcar_pagado(v_p4);
  leer_stock;
  verificar('pago tardio sin stock: SIN_STOCK y el pedido sigue EXPIRADO',
            v_resultado = PKG_PEDIDO_ESTADO.c_sin_stock AND estado_de(v_p4) = 'EXPIRADO');
  verificar('sin stock no toca nada: reservada 4 (la del otro pedido), reserva LIBERADA',
            v_reservada = 4 AND reserva_a(v_p4) = 'LIBERADA' AND reserva_a(v_p5) = 'ACTIVA');

  -- Kardex
  SELECT COUNT(*) INTO v_n FROM V_CONCILIACION_STOCK WHERE ID_VARIANTE = v_var_a;
  verificar('saldo de STOCK y kardex cuadran (V_CONCILIACION_STOCK sin filas)', v_n = 0);

  DBMS_OUTPUT.PUT_LINE('Resultado: ' || v_pruebas || ' pruebas, ' || v_fallos || ' fallos');
  ROLLBACK;
EXCEPTION
  WHEN OTHERS THEN
    ROLLBACK;
    RAISE;
END;
/
