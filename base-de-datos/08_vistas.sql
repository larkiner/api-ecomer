-- ============================================================================
-- 08_vistas.sql
-- Seccion 5 del diseno (parte 1): un cambio pequeno en PAGO, un parametro nuevo
-- y las 10 vistas de consulta.
--
-- Requiere haber ejecutado antes los scripts 06 y 07.
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1.
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Si falla a la mitad, ejecuta el bloque DESHACER del final (esta
--      comentado) y vuelve a correr el script completo.
--
-- DECISIONES DE DISENO
--   * PAGO.FECHA_APROBACION: el cierre diario compara "pedidos pagados" con
--     "pagos aprobados" de un mismo dia y ambos lados necesitan una fecha propia.
--     El pedido ya la tiene (su cambio a PAGADO queda en PEDIDO_ESTADO_HIST); el
--     pago no, y FECHA_MODIFICACION no sirve porque cambia con cualquier UPDATE.
--     Un CHECK obliga a informarla cuando el estado es APROBADO.
--   * STOCK_BAJO_UNIDADES es un parametro global (valor inicial 5, editable) en
--     vez de una columna "stock minimo" por variante.
--   * Las vistas no llevan ORDER BY (Oracle no lo garantiza en una vista): quien
--     las consulta ordena.
--   * Las vistas CONCILIACION muestran solo lo que NO cuadra: vacia = todo bien.
--   * V_PRECIO_VIGENTE es la unica fuente del calculo de precio y promocion;
--     PKG_PRECIOS la lee en vez de repetir la formula.
--   * Hora: las columnas TIMESTAMP guardan la hora local del servidor de la BD
--     (al asignar SYSTIMESTAMP se descarta la zona horaria). Por eso las vistas
--     comparan contra CAST(SYSTIMESTAMP AS TIMESTAMP): comparar contra
--     SYSTIMESTAMP "a secas" interpretaria la columna en la zona de cada sesion
--     y daria resultados distintos segun quien consulte. Las vistas por dia
--     asumen que el servidor esta en hora de Colombia (-05:00); la verificacion
--     del final lo comprueba.
--   * Provisional, hasta definir la politica contable: el descuento porcentual
--     se redondea a 2 decimales por unidad (ROUND).
--   * Supuesto sobre el kardex: CONSUMO resta del saldo total y tambien de lo
--     reservado (la unidad reservada sale del almacen). PKG_INVENTARIO debe
--     respetarlo; V_CONCILIACION_STOCK lo verifica.
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
   WHERE table_name IN ('PAGO', 'PEDIDO_ESTADO_HIST', 'PARAMETRO', 'COLA_EVENTO', 'CIERRE_DIARIO');
  IF v_existentes < 5 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan tablas previas: ejecuta antes los scripts 06 y 07.');
  END IF;
END;
/

-- ============================================================================
-- CAMBIOS EN TABLAS
-- ============================================================================

ALTER TABLE PAGO ADD (FECHA_APROBACION TIMESTAMP);

ALTER TABLE PAGO ADD CONSTRAINT CK_PAGO_APROBACION
  CHECK (ESTADO <> 'APROBADO' OR FECHA_APROBACION IS NOT NULL);

-- Solo se indexan los pagos aprobados (los NULL no entran en un indice de una columna).
CREATE INDEX IX_PAGO_APROBACION ON PAGO (FECHA_APROBACION);

COMMENT ON COLUMN PAGO.FECHA_APROBACION IS 'Momento en que el pago paso a APROBADO; obligatoria en ese estado.';

INSERT INTO PARAMETRO (CLAVE, VALOR, DESCRIPCION)
VALUES ('STOCK_BAJO_UNIDADES', 5,
        'Una variante con stock controlado se considera en stock bajo si su disponible es menor o igual a este valor.');

COMMIT;

-- ============================================================================
-- VISTAS
-- ============================================================================

-- Precio con la promocion vigente y la tasa de impuesto de cada variante activa.
-- Una variante inactiva (o de un producto inactivo) no aparece: no tiene precio vigente.
CREATE OR REPLACE VIEW V_PRECIO_VIGENTE AS
SELECT x.ID_VARIANTE,
       x.SKU,
       x.NOMBRE_ITEM,
       x.CONTROLA_STOCK,
       x.PRECIO_BASE,
       x.ID_PROMOCION,
       x.DESCUENTO_UNITARIO,
       x.PRECIO_BASE - x.DESCUENTO_UNITARIO AS PRECIO_FINAL,
       x.TASA_IMPUESTO
  FROM (SELECT v.ID_VARIANTE,
               v.SKU,
               p.NOMBRE || ' - ' || v.NOMBRE AS NOMBRE_ITEM,
               v.CONTROLA_STOCK,
               v.PRECIO_BASE,
               pr.ID_PROMOCION,
               CASE
                 WHEN pr.ID_PROMOCION IS NULL            THEN 0
                 WHEN pr.TIPO_DESCUENTO = 'PORCENTAJE'   THEN ROUND(v.PRECIO_BASE * pr.VALOR / 100, 2)
                 ELSE LEAST(pr.VALOR, v.PRECIO_BASE)     -- un descuento en monto nunca supera el precio
               END AS DESCUENTO_UNITARIO,
               i.TASA AS TASA_IMPUESTO
          FROM VARIANTE v
          JOIN PRODUCTO p             ON p.ID_PRODUCTO   = v.ID_PRODUCTO
          JOIN IMPUESTO i             ON i.ID_IMPUESTO   = p.ID_IMPUESTO
          LEFT JOIN PROMOCION_VARIANTE pv ON pv.ID_VARIANTE  = v.ID_VARIANTE
          LEFT JOIN PROMOCION pr          ON pr.ID_PROMOCION = pv.ID_PROMOCION
                                         AND pr.ACTIVA = 1
         WHERE v.ACTIVO = 1
           AND p.ACTIVO = 1) x;

-- Variantes con stock controlado cuyo disponible llego al umbral del parametro.
CREATE OR REPLACE VIEW V_STOCK_BAJO AS
SELECT s.ID_VARIANTE,
       v.SKU,
       p.NOMBRE || ' - ' || v.NOMBRE AS NOMBRE_ITEM,
       s.CANTIDAD_TOTAL,
       s.CANTIDAD_RESERVADA,
       s.CANTIDAD_DISPONIBLE
  FROM STOCK s
  JOIN VARIANTE v ON v.ID_VARIANTE = s.ID_VARIANTE
  JOIN PRODUCTO p ON p.ID_PRODUCTO = v.ID_PRODUCTO
 WHERE v.ACTIVO = 1
   AND v.CONTROLA_STOCK = 1
   AND s.CANTIDAD_DISPONIBLE <= (SELECT VALOR FROM PARAMETRO WHERE CLAVE = 'STOCK_BAJO_UNIDADES');

-- Saldo de STOCK contra lo que suma el kardex. Vacia = cuadra.
--   total     = ENTRADA + AJUSTE (con signo) - CONSUMO
--   reservado = RESERVA - LIBERACION - CONSUMO
CREATE OR REPLACE VIEW V_CONCILIACION_STOCK AS
SELECT c.ID_VARIANTE,
       c.SKU,
       c.TOTAL_SALDO,
       c.TOTAL_KARDEX,
       c.TOTAL_SALDO - c.TOTAL_KARDEX         AS DIF_TOTAL,
       c.RESERVADA_SALDO,
       c.RESERVADA_KARDEX,
       c.RESERVADA_SALDO - c.RESERVADA_KARDEX AS DIF_RESERVADA
  FROM (SELECT s.ID_VARIANTE,
               v.SKU,
               s.CANTIDAD_TOTAL      AS TOTAL_SALDO,
               NVL(k.TOTAL_KARDEX, 0)     AS TOTAL_KARDEX,
               s.CANTIDAD_RESERVADA  AS RESERVADA_SALDO,
               NVL(k.RESERVADA_KARDEX, 0) AS RESERVADA_KARDEX
          FROM STOCK s
          JOIN VARIANTE v ON v.ID_VARIANTE = s.ID_VARIANTE
          LEFT JOIN (SELECT ID_VARIANTE,
                            SUM(CASE TIPO
                                  WHEN 'ENTRADA' THEN  CANTIDAD
                                  WHEN 'AJUSTE'  THEN  CANTIDAD
                                  WHEN 'CONSUMO' THEN -CANTIDAD
                                  ELSE 0
                                END) AS TOTAL_KARDEX,
                            SUM(CASE TIPO
                                  WHEN 'RESERVA'    THEN  CANTIDAD
                                  WHEN 'LIBERACION' THEN -CANTIDAD
                                  WHEN 'CONSUMO'    THEN -CANTIDAD
                                  ELSE 0
                                END) AS RESERVADA_KARDEX
                       FROM MOVIMIENTO_STOCK
                      GROUP BY ID_VARIANTE) k
                 ON k.ID_VARIANTE = s.ID_VARIANTE) c
 WHERE c.TOTAL_SALDO <> c.TOTAL_KARDEX
    OR c.RESERVADA_SALDO <> c.RESERVADA_KARDEX;

-- Pedidos esperando pago. MINUTOS_RESTANTES negativo = ya vencio y el JOB aun no lo expira.
CREATE OR REPLACE VIEW V_PEDIDOS_PENDIENTES AS
SELECT p.ID_PEDIDO,
       p.ID_PUBLICO,
       p.ID_CLIENTE,
       c.NOMBRE AS CLIENTE,
       c.EMAIL,
       p.TOTAL,
       p.FECHA_CREACION,
       p.FECHA_EXPIRACION,
       ROUND((CAST(p.FECHA_EXPIRACION AS DATE)
              - CAST(CAST(SYSTIMESTAMP AS TIMESTAMP) AS DATE)) * 1440) AS MINUTOS_RESTANTES
  FROM PEDIDO p
  JOIN ESTADO_PEDIDO e ON e.ID_ESTADO_PEDIDO = p.ID_ESTADO_PEDIDO
  JOIN CLIENTE c       ON c.ID_CLIENTE       = p.ID_CLIENTE
 WHERE e.CODIGO = 'PENDIENTE_PAGO';

-- Ventas por dia de pago: un pedido cuenta el dia en que paso a PAGADO.
CREATE OR REPLACE VIEW V_VENTAS_DIARIAS AS
SELECT TRUNC(h.FECHA_CREACION) AS FECHA,
       COUNT(*)                AS PEDIDOS_PAGADOS,
       SUM(p.SUBTOTAL)         AS SUBTOTAL,
       SUM(p.IMPUESTO_TOTAL)   AS IMPUESTO,
       SUM(p.TOTAL)            AS TOTAL
  FROM PEDIDO p
  JOIN PEDIDO_ESTADO_HIST h ON h.ID_PEDIDO = p.ID_PEDIDO
  JOIN ESTADO_PEDIDO e      ON e.ID_ESTADO_PEDIDO = h.ID_ESTADO_NUEVO
                           AND e.CODIGO = 'PAGADO'
 GROUP BY TRUNC(h.FECHA_CREACION);

-- Ventas por dia y variante (para filtrar por rango de fechas y sumar). Los
-- importes salen de las lineas del pedido, es decir, de lo que realmente se vendio.
CREATE OR REPLACE VIEW V_VENTAS_PRODUCTO AS
SELECT TRUNC(h.FECHA_CREACION)       AS FECHA,
       i.ID_VARIANTE,
       v.SKU,
       pr.NOMBRE || ' - ' || v.NOMBRE AS NOMBRE_ACTUAL,
       SUM(i.CANTIDAD)               AS UNIDADES,
       SUM(i.SUBTOTAL_LINEA)         AS SUBTOTAL,
       SUM(i.IMPUESTO_LINEA)         AS IMPUESTO,
       SUM(i.TOTAL_LINEA)            AS TOTAL
  FROM PEDIDO_ITEM i
  JOIN PEDIDO_ESTADO_HIST h ON h.ID_PEDIDO = i.ID_PEDIDO
  JOIN ESTADO_PEDIDO e      ON e.ID_ESTADO_PEDIDO = h.ID_ESTADO_NUEVO
                           AND e.CODIGO = 'PAGADO'
  JOIN VARIANTE v           ON v.ID_VARIANTE = i.ID_VARIANTE
  JOIN PRODUCTO pr          ON pr.ID_PRODUCTO = v.ID_PRODUCTO
 GROUP BY TRUNC(h.FECHA_CREACION), i.ID_VARIANTE, v.SKU, pr.NOMBRE, v.NOMBRE;

-- Compras acumuladas por cliente. Se ordena al consultar, por ejemplo:
--   SELECT * FROM V_TOP_CLIENTES ORDER BY TOTAL_COMPRADO DESC FETCH FIRST 10 ROWS ONLY;
CREATE OR REPLACE VIEW V_TOP_CLIENTES AS
SELECT c.ID_CLIENTE,
       c.NOMBRE,
       c.EMAIL,
       COUNT(*)                AS PEDIDOS_PAGADOS,
       SUM(p.TOTAL)            AS TOTAL_COMPRADO,
       MAX(h.FECHA_CREACION)   AS ULTIMA_COMPRA
  FROM PEDIDO p
  JOIN PEDIDO_ESTADO_HIST h ON h.ID_PEDIDO = p.ID_PEDIDO
  JOIN ESTADO_PEDIDO e      ON e.ID_ESTADO_PEDIDO = h.ID_ESTADO_NUEVO
                           AND e.CODIGO = 'PAGADO'
  JOIN CLIENTE c            ON c.ID_CLIENTE = p.ID_CLIENTE
 GROUP BY c.ID_CLIENTE, c.NOMBRE, c.EMAIL;

-- Pedidos y pagos que no cuadran. Vacia = todo bien. Cubre tres casos:
--   * pedido PAGADO sin pago APROBADO,
--   * pago APROBADO sobre un pedido que no esta PAGADO (por ejemplo, ya EXPIRADO),
--   * monto aprobado distinto del total del pedido.
-- Todo pago tiene pedido (FK), asi que partir de PEDIDO cubre los tres casos.
CREATE OR REPLACE VIEW V_CONCILIACION_PAGOS AS
SELECT p.ID_PEDIDO,
       p.ID_PUBLICO         AS ID_PUBLICO_PEDIDO,
       e.CODIGO             AS ESTADO_PEDIDO,
       p.TOTAL              AS TOTAL_PEDIDO,
       g.ID_PAGO,
       g.MONTO              AS MONTO_PAGO,
       g.FECHA_APROBACION,
       CASE
         WHEN g.ID_PAGO IS NULL    THEN 'PEDIDO PAGADO SIN PAGO APROBADO'
         WHEN e.CODIGO <> 'PAGADO' THEN 'PAGO APROBADO CON PEDIDO ' || e.CODIGO
         ELSE                           'MONTO DISTINTO AL TOTAL DEL PEDIDO'
       END AS INCONSISTENCIA
  FROM PEDIDO p
  JOIN ESTADO_PEDIDO e ON e.ID_ESTADO_PEDIDO = p.ID_ESTADO_PEDIDO
  LEFT JOIN PAGO g     ON g.ID_PEDIDO = p.ID_PEDIDO
                      AND g.ESTADO = 'APROBADO'
 WHERE (e.CODIGO = 'PAGADO' AND g.ID_PAGO IS NULL)
    OR (g.ID_PAGO IS NOT NULL AND (e.CODIGO <> 'PAGADO' OR g.MONTO <> p.TOTAL));

-- Carritos abiertos, con productos, sin actividad durante el umbral del
-- parametro y a los que aun no se les aviso. Es la lista de candidatos del JOB.
CREATE OR REPLACE VIEW V_CARRITOS_ABANDONADOS AS
SELECT c.ID_CARRITO,
       c.ID_CLIENTE,
       cl.NOMBRE,
       cl.EMAIL,
       c.FECHA_ULTIMA_ACTIVIDAD,
       COUNT(i.ID_CARRITO_ITEM) AS ITEMS
  FROM CARRITO c
  JOIN CLIENTE cl      ON cl.ID_CLIENTE  = c.ID_CLIENTE
  JOIN CARRITO_ITEM i  ON i.ID_CARRITO   = c.ID_CARRITO
 WHERE c.ESTADO = 'ABIERTO'
   AND c.AVISO_ABANDONO_ENVIADO = 0
   AND c.FECHA_ULTIMA_ACTIVIDAD
         < CAST(SYSTIMESTAMP AS TIMESTAMP)
           - NUMTODSINTERVAL((SELECT VALOR FROM PARAMETRO WHERE CLAVE = 'CARRITO_ABANDONO_HORAS'), 'HOUR')
 GROUP BY c.ID_CARRITO, c.ID_CLIENTE, cl.NOMBRE, cl.EMAIL, c.FECHA_ULTIMA_ACTIVIDAD;

-- Lo que la cola tiene sin resolver (no incluye el PAYLOAD). ATASCADO = 1 si lleva
-- en PROCESANDO mas del maximo del parametro: el JOB lo devolvera a PENDIENTE.
CREATE OR REPLACE VIEW V_COLA_PENDIENTE AS
SELECT ID_COLA_EVENTO,
       TIPO_EVENTO,
       CLAVE_IDEMPOTENCIA,
       ESTADO,
       INTENTOS,
       PROXIMO_INTENTO,
       FECHA_BLOQUEO,
       ULTIMO_ERROR,
       FECHA_CREACION,
       CASE
         WHEN ESTADO = 'PROCESANDO'
          AND FECHA_BLOQUEO < CAST(SYSTIMESTAMP AS TIMESTAMP)
              - NUMTODSINTERVAL((SELECT VALOR FROM PARAMETRO WHERE CLAVE = 'COLA_PROCESANDO_MAX_MINUTOS'), 'MINUTE')
         THEN 1 ELSE 0
       END AS ATASCADO
  FROM COLA_EVENTO
 WHERE ESTADO IN ('PENDIENTE', 'PROCESANDO', 'ERROR');

-- ============================================================================
-- COMENTARIOS
-- ============================================================================
COMMENT ON TABLE V_PRECIO_VIGENTE        IS 'Precio, promocion, descuento e impuesto vigentes de cada variante activa; fuente unica para PKG_PRECIOS.';
COMMENT ON TABLE V_STOCK_BAJO            IS 'Variantes con stock controlado cuyo disponible esta en el umbral STOCK_BAJO_UNIDADES o por debajo.';
COMMENT ON TABLE V_CONCILIACION_STOCK    IS 'Variantes cuyo saldo de STOCK no coincide con el kardex; vacia = cuadra.';
COMMENT ON TABLE V_PEDIDOS_PENDIENTES    IS 'Pedidos pendientes de pago con los minutos que les quedan antes de expirar.';
COMMENT ON TABLE V_VENTAS_DIARIAS        IS 'Pedidos pagados por dia de pago, con subtotal, impuesto y total.';
COMMENT ON TABLE V_VENTAS_PRODUCTO       IS 'Unidades e importes vendidos por dia y variante.';
COMMENT ON TABLE V_TOP_CLIENTES          IS 'Pedidos pagados, total comprado y ultima compra por cliente.';
COMMENT ON TABLE V_CONCILIACION_PAGOS    IS 'Pedidos y pagos que no cuadran entre si; vacia = todo bien.';
COMMENT ON TABLE V_CARRITOS_ABANDONADOS  IS 'Carritos con productos, sin actividad, a los que aun no se les aviso del abandono.';
COMMENT ON TABLE V_COLA_PENDIENTE        IS 'Eventos de la cola sin resolver (pendientes, en proceso o con error) y si estan atascados.';

-- ALTER TABLE PAGO deja invalidos los objetos que dependen de ella.
BEGIN
  DBMS_UTILITY.COMPILE_SCHEMA(schema => USER, compile_all => FALSE);
END;
/

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT COUNT(*) AS tablas FROM user_tables;                                  -- 28

SELECT COUNT(*) AS vistas FROM user_views;                                   -- 10

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';                                                    -- 0 filas

SELECT COUNT(*) AS triggers_habilitados
  FROM user_triggers
 WHERE status = 'ENABLED';                                                   -- 30

SELECT constraint_name, status
  FROM user_constraints
 WHERE constraint_name = 'CK_PAGO_APROBACION';                               -- 1 fila, ENABLED

SELECT COUNT(*) AS parametros FROM PARAMETRO;                                -- 6

-- Cada vista se ejecuta sin error (las tablas estan vacias).
SELECT 'V_PRECIO_VIGENTE'       AS vista, COUNT(*) AS filas FROM V_PRECIO_VIGENTE       UNION ALL
SELECT 'V_STOCK_BAJO',                    COUNT(*)          FROM V_STOCK_BAJO           UNION ALL
SELECT 'V_CONCILIACION_STOCK',            COUNT(*)          FROM V_CONCILIACION_STOCK   UNION ALL
SELECT 'V_PEDIDOS_PENDIENTES',            COUNT(*)          FROM V_PEDIDOS_PENDIENTES   UNION ALL
SELECT 'V_VENTAS_DIARIAS',                COUNT(*)          FROM V_VENTAS_DIARIAS       UNION ALL
SELECT 'V_VENTAS_PRODUCTO',               COUNT(*)          FROM V_VENTAS_PRODUCTO      UNION ALL
SELECT 'V_TOP_CLIENTES',                  COUNT(*)          FROM V_TOP_CLIENTES         UNION ALL
SELECT 'V_CONCILIACION_PAGOS',            COUNT(*)          FROM V_CONCILIACION_PAGOS   UNION ALL
SELECT 'V_CARRITOS_ABANDONADOS',          COUNT(*)          FROM V_CARRITOS_ABANDONADOS UNION ALL
SELECT 'V_COLA_PENDIENTE',                COUNT(*)          FROM V_COLA_PENDIENTE;      -- 10 filas, todas en 0

-- Zona horaria del servidor. Si no es -05:00, avisame: hay que ajustar las
-- vistas por dia y PKG_UTILIDADES antes de seguir.
SELECT TO_CHAR(SYSTIMESTAMP, 'TZH:TZM') AS zona_servidor,
       SESSIONTIMEZONE                  AS zona_sesion,
       DBTIMEZONE                       AS zona_bd
  FROM dual;                                                                 -- zona_servidor = -05:00

-- ============================================================================
-- DESHACER (solo desarrollo): quita el comentario para volver al estado del 07.
-- ============================================================================
-- DROP VIEW V_COLA_PENDIENTE;
-- DROP VIEW V_CARRITOS_ABANDONADOS;
-- DROP VIEW V_CONCILIACION_PAGOS;
-- DROP VIEW V_TOP_CLIENTES;
-- DROP VIEW V_VENTAS_PRODUCTO;
-- DROP VIEW V_VENTAS_DIARIAS;
-- DROP VIEW V_PEDIDOS_PENDIENTES;
-- DROP VIEW V_CONCILIACION_STOCK;
-- DROP VIEW V_STOCK_BAJO;
-- DROP VIEW V_PRECIO_VIGENTE;
-- DELETE FROM PARAMETRO WHERE CLAVE = 'STOCK_BAJO_UNIDADES';
-- COMMIT;
-- DROP INDEX IX_PAGO_APROBACION;
-- ALTER TABLE PAGO DROP CONSTRAINT CK_PAGO_APROBACION;
-- ALTER TABLE PAGO DROP COLUMN FECHA_APROBACION;
