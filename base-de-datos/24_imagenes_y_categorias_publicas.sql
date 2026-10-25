-- ============================================================================
-- 24_imagenes_y_categorias_publicas.sql
-- BORRADOR PARA REVISAR: leelo completo, ajustalo a tu criterio y ejecutalo tu.
--
-- Imagen del producto y de la categoria, y categorias publicas para la tienda. La imagen
-- NO pasa por la API ni por la base: el navegador la sube directo a Supabase Storage y
-- aqui solo se guarda su URL publica.
--   * PRODUCTO.IMAGEN_URL        (columna nueva, opcional, solo https://, hasta 500)
--   * CATEGORIA.IMAGEN_URL       (igual que la del producto)
--   * V_CATALOGO                 (vista nueva: V_PRECIO_VIGENTE + la imagen y la categoria
--                                 del producto; es lo que lee el catalogo publico)
--   * V_CATEGORIA_PUBLICA        (vista nueva: solo las categorias ACTIVAS con su imagen;
--                                 es lo que lee la tienda para mostrar y filtrar por categoria)
--   * PKG_CATALOGO               (se reemplaza completo: nuevas rutinas fijar_imagen y
--                                 fijar_imagen_categoria; listar_productos y
--                                 listar_categorias devuelven IMAGEN_URL)
--
-- Requiere haber ejecutado antes los scripts 03, 08, 20, 21 y 22.
--
-- IMPORTANTE (orden de despliegue): la API nueva lee el catalogo publico de V_CATALOGO.
-- Ejecuta este script ANTES de arrancar esa version de la API; si no, el catalogo
-- publico responde 500 (ORA-00942: la vista no existe).
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1 (SELECT USER FROM dual).
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Se puede volver a ejecutar: las columnas se crean solo si faltan, y la vista y el
--      paquete son CREATE OR REPLACE. Si ya lo ejecutaste antes de que existiera la
--      imagen de categoria, ejecutalo otra vez: agrega CATEGORIA.IMAGEN_URL y reemplaza
--      el paquete, sin tocar tus datos.
--   4. Al final salen las pruebas rapidas: una linea "OK" por cada una. Crean datos
--      temporales y los deshacen con ROLLBACK: no quedan en la base.
--   5. Para deshacerlo:
--        DROP VIEW V_CATEGORIA_PUBLICA;
--        DROP VIEW V_CATALOGO;
--        ALTER TABLE PRODUCTO DROP COLUMN IMAGEN_URL;
--        ALTER TABLE CATEGORIA DROP COLUMN IMAGEN_URL;
--        (y vuelve a ejecutar el script 22 para dejar PKG_CATALOGO como estaba)
--
-- DECISIONES DE DISENO
--   * La imagen es del PRODUCTO (todas sus variantes comparten la foto). Una sola
--     imagen por producto; si luego se necesitan varias, seria una tabla aparte.
--   * Solo se guarda la URL. La API comprueba que sea de NUESTRO bucket de Supabase;
--     aqui la base solo exige lo minimo (https:// y hasta 500 caracteres), por
--     defensa en profundidad.
--   * V_PRECIO_VIGENTE NO se toca: es la unica fuente del calculo de precios y
--     PKG_PRECIOS la usa con %ROWTYPE. V_CATALOGO la envuelve y agrega la imagen. El
--     rol cliente solo puede leer vistas concretas, no PRODUCTO: por eso hace falta la
--     vista y su GRANT.
--   * fijar_imagen reemplaza la imagen (o la quita si p_imagen_url es NULL o vacio).
--     Requiere el permiso PRODUCTOS (el ADMIN pasa siempre). El archivo anterior queda
--     huerfano en Supabase: borrarlo es tarea aparte.
--   * La imagen de la CATEGORIA funciona igual, pero la fija quien tiene el permiso
--     CATEGORIAS (fijar_imagen_categoria). Una categoria tiene una sola imagen.
--   * La tienda NO lee la tabla CATEGORIA (el rol cliente solo lee vistas): por eso
--     V_CATEGORIA_PUBLICA, que oculta las inactivas y no expone nada mas que lo que se
--     muestra (id, padre, nombre e imagen). El filtro por categoria de la API incluye las
--     subcategorias recorriendo esa vista.
--   * Errores nuevos: -20338 la URL de la imagen es invalida.
--     Reusa -20320/-20325 (permiso), -20333 (el producto no existe) y -20330 (la
--     categoria no existe).
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

PROMPT >> 24_imagenes_y_categorias_publicas.sql: comprobando requisitos

DECLARE
  v_n NUMBER;
BEGIN
  IF SYS_CONTEXT('USERENV', 'SESSION_USER') <> 'ECOMERS'
     OR SYS_CONTEXT('USERENV', 'CON_NAME') <> 'XEPDB1' THEN
    RAISE_APPLICATION_ERROR(-20001,
      'Conectate como ECOMERS al servicio XEPDB1 antes de ejecutar este script.');
  END IF;

  SELECT COUNT(*) INTO v_n FROM user_tables WHERE table_name IN ('PRODUCTO', 'VARIANTE', 'CATEGORIA');
  IF v_n < 3 THEN
    RAISE_APPLICATION_ERROR(-20002, 'Faltan tablas previas: ejecuta antes el script 03.');
  END IF;

  SELECT COUNT(*) INTO v_n FROM user_views WHERE view_name = 'V_PRECIO_VIGENTE';
  IF v_n < 1 THEN
    RAISE_APPLICATION_ERROR(-20002, 'Falta la vista V_PRECIO_VIGENTE: ejecuta antes el script 08.');
  END IF;

  SELECT COUNT(*) INTO v_n
    FROM user_objects
   WHERE object_name IN ('PKG_CATALOGO', 'PKG_PERMISOS')
     AND object_type = 'PACKAGE BODY'
     AND status = 'VALID';
  IF v_n < 2 THEN
    RAISE_APPLICATION_ERROR(-20002, 'Faltan PKG_CATALOGO y PKG_PERMISOS validos: ejecuta antes el script 22.');
  END IF;
END;
/

-- ----------------------------------------------------------------------------
-- 1. PRODUCTO.IMAGEN_URL
-- ----------------------------------------------------------------------------
DECLARE
  v_n NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_n
    FROM user_tab_columns
   WHERE table_name = 'PRODUCTO' AND column_name = 'IMAGEN_URL';
  IF v_n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE PRODUCTO ADD (IMAGEN_URL VARCHAR2(500))';
  END IF;

  SELECT COUNT(*) INTO v_n
    FROM user_constraints
   WHERE table_name = 'PRODUCTO' AND constraint_name = 'CK_PRODUCTO_IMAGEN_URL';
  IF v_n = 0 THEN
    EXECUTE IMMEDIATE
      'ALTER TABLE PRODUCTO ADD CONSTRAINT CK_PRODUCTO_IMAGEN_URL '
      || 'CHECK (IMAGEN_URL IS NULL OR IMAGEN_URL LIKE ''https://%'')';
  END IF;
END;
/

COMMENT ON COLUMN PRODUCTO.IMAGEN_URL IS
  'URL publica de la imagen en Supabase Storage (la sube el navegador directo; aqui solo se guarda la URL).';

PROMPT >> PRODUCTO.IMAGEN_URL lista

-- ----------------------------------------------------------------------------
-- 1b. CATEGORIA.IMAGEN_URL
-- ----------------------------------------------------------------------------
DECLARE
  v_n NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_n
    FROM user_tab_columns
   WHERE table_name = 'CATEGORIA' AND column_name = 'IMAGEN_URL';
  IF v_n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE CATEGORIA ADD (IMAGEN_URL VARCHAR2(500))';
  END IF;

  SELECT COUNT(*) INTO v_n
    FROM user_constraints
   WHERE table_name = 'CATEGORIA' AND constraint_name = 'CK_CATEGORIA_IMAGEN_URL';
  IF v_n = 0 THEN
    EXECUTE IMMEDIATE
      'ALTER TABLE CATEGORIA ADD CONSTRAINT CK_CATEGORIA_IMAGEN_URL '
      || 'CHECK (IMAGEN_URL IS NULL OR IMAGEN_URL LIKE ''https://%'')';
  END IF;
END;
/

COMMENT ON COLUMN CATEGORIA.IMAGEN_URL IS
  'URL publica de la imagen de la categoria en Supabase Storage (la sube el navegador directo; aqui solo se guarda la URL).';

PROMPT >> CATEGORIA.IMAGEN_URL lista

-- ----------------------------------------------------------------------------
-- 2. V_CATALOGO: lo que lee el catalogo publico (V_PRECIO_VIGENTE + imagen)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_CATALOGO AS
SELECT pv.ID_VARIANTE,
       pv.SKU,
       pv.NOMBRE_ITEM,
       pv.CONTROLA_STOCK,
       pv.PRECIO_BASE,
       pv.ID_PROMOCION,
       pv.DESCUENTO_UNITARIO,
       pv.PRECIO_FINAL,
       pv.TASA_IMPUESTO,
       p.IMAGEN_URL,
       p.ID_CATEGORIA
  FROM V_PRECIO_VIGENTE pv
  JOIN VARIANTE v ON v.ID_VARIANTE = pv.ID_VARIANTE
  JOIN PRODUCTO p ON p.ID_PRODUCTO = v.ID_PRODUCTO;

COMMENT ON TABLE V_CATALOGO IS
  'Catalogo vendible: V_PRECIO_VIGENTE mas la imagen y la categoria del producto. La lee el rol cliente.';

GRANT SELECT ON V_CATALOGO TO ROL_ECOMERS_CLIENTE;

PROMPT >> V_CATALOGO lista y con permiso para el rol cliente

-- ----------------------------------------------------------------------------
-- 2b. V_CATEGORIA_PUBLICA: las categorias que ve la tienda (solo activas)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_CATEGORIA_PUBLICA AS
SELECT ID_CATEGORIA,
       ID_CATEGORIA_PADRE AS ID_PADRE,
       NOMBRE,
       IMAGEN_URL
  FROM CATEGORIA
 WHERE ACTIVO = 1;

COMMENT ON TABLE V_CATEGORIA_PUBLICA IS
  'Categorias activas con su imagen. La lee el rol cliente para mostrar y filtrar el catalogo.';

GRANT SELECT ON V_CATEGORIA_PUBLICA TO ROL_ECOMERS_CLIENTE;

PROMPT >> V_CATEGORIA_PUBLICA lista y con permiso para el rol cliente

-- ----------------------------------------------------------------------------
-- 3. PKG_CATALOGO (reemplazo completo del script 22, con las dos novedades)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE PACKAGE PKG_CATALOGO
  AUTHID DEFINER
AS
  -- ---- Lectura: cualquier empleado activo (-20325 si no). Incluyen inactivos. ----

  -- ID_CATEGORIA, ID_CATEGORIA_PADRE, NOMBRE, ACTIVO, IMAGEN_URL
  FUNCTION listar_categorias(p_id_empleado IN NUMBER) RETURN SYS_REFCURSOR;

  -- ID_IMPUESTO, NOMBRE, TASA, ACTIVO
  FUNCTION listar_impuestos(p_id_empleado IN NUMBER) RETURN SYS_REFCURSOR;

  -- Una fila por variante, paginado por PRODUCTO (p_pagina desde 0; p_tamano de 1 a 100):
  -- ID_PRODUCTO, ID_CATEGORIA, ID_IMPUESTO, PRODUCTO, DESCRIPCION, IMAGEN_URL, PRODUCTO_ACTIVO,
  -- ID_VARIANTE, SKU, VARIANTE, PRECIO_BASE, CONTROLA_STOCK, VARIANTE_ACTIVA.
  -- Un producto sin variantes sale con las columnas de variante en NULL.
  FUNCTION listar_productos(
    p_id_empleado IN NUMBER,
    p_pagina      IN NUMBER,
    p_tamano      IN NUMBER
  ) RETURN SYS_REFCURSOR;

  -- ---- Categorias: permiso CATEGORIAS ----

  -- Devuelve el ID. p_id_padre es opcional.
  -- Errores: -20320/-20325 (quien llama), -20331 (padre inexistente o inactivo),
  --          -20332 (nombre repetido bajo el mismo padre).
  FUNCTION crear_categoria(
    p_id_empleado IN NUMBER,
    p_nombre      IN VARCHAR2,
    p_id_padre    IN NUMBER
  ) RETURN NUMBER;

  -- Errores: los de crear_categoria, -20330 (la categoria no existe) y
  --          -20331 tambien si el nuevo padre crearia un ciclo (incluida ella misma).
  PROCEDURE actualizar_categoria(
    p_id_empleado  IN NUMBER,
    p_id_categoria IN NUMBER,
    p_nombre       IN VARCHAR2,
    p_id_padre     IN NUMBER
  );

  -- Activa (1) o desactiva (0). Error -20330 si no existe.
  PROCEDURE cambiar_estado_categoria(
    p_id_empleado  IN NUMBER,
    p_id_categoria IN NUMBER,
    p_activo       IN NUMBER
  );

  -- Fija la imagen de la categoria (reemplaza la anterior) o la quita si p_imagen_url es
  -- NULL o vacio. La URL debe empezar por https:// y tener hasta 500 caracteres. Que sea de
  -- NUESTRO bucket de Supabase lo comprueba la API.
  -- Errores: -20320/-20325 (permiso CATEGORIAS), -20330 (categoria), -20338 (URL invalida).
  PROCEDURE fijar_imagen_categoria(
    p_id_empleado  IN NUMBER,
    p_id_categoria IN NUMBER,
    p_imagen_url   IN VARCHAR2
  );

  -- ---- Productos y variantes: permiso PRODUCTOS ----

  -- Devuelve el ID. La categoria y el impuesto deben existir y estar activos.
  -- Errores: -20320/-20325, -20330 (categoria), -20334 (impuesto).
  FUNCTION crear_producto(
    p_id_empleado  IN NUMBER,
    p_id_categoria IN NUMBER,
    p_id_impuesto  IN NUMBER,
    p_nombre       IN VARCHAR2,
    p_descripcion  IN VARCHAR2
  ) RETURN NUMBER;

  -- Errores: los de crear_producto y -20333 (el producto no existe).
  PROCEDURE actualizar_producto(
    p_id_empleado  IN NUMBER,
    p_id_producto  IN NUMBER,
    p_id_categoria IN NUMBER,
    p_id_impuesto  IN NUMBER,
    p_nombre       IN VARCHAR2,
    p_descripcion  IN VARCHAR2
  );

  -- Activa (1) o desactiva (0). Error -20333 si no existe.
  PROCEDURE cambiar_estado_producto(
    p_id_empleado IN NUMBER,
    p_id_producto IN NUMBER,
    p_activo      IN NUMBER
  );

  -- Fija la imagen del producto (reemplaza la anterior) o la quita si p_imagen_url es NULL
  -- o vacio. La URL debe empezar por https:// y tener hasta 500 caracteres. Que sea de
  -- NUESTRO bucket de Supabase lo comprueba la API.
  -- Errores: -20320/-20325 (permiso PRODUCTOS), -20333 (producto), -20338 (URL invalida).
  PROCEDURE fijar_imagen(
    p_id_empleado IN NUMBER,
    p_id_producto IN NUMBER,
    p_imagen_url  IN VARCHAR2
  );

  -- Devuelve el ID de la variante. El SKU se normaliza (UPPER y sin espacios en los
  -- extremos). No crea fila de STOCK: el inventario la crea con la primera entrada.
  -- p_controla_stock: 1 o 0 (se fija aqui y no cambia despues).
  -- Errores: -20320/-20325, -20333 (producto), -20335 (SKU repetido), -20337 (precio).
  FUNCTION agregar_variante(
    p_id_empleado    IN NUMBER,
    p_id_producto    IN NUMBER,
    p_sku            IN VARCHAR2,
    p_nombre         IN VARCHAR2,
    p_precio_base    IN NUMBER,
    p_controla_stock IN NUMBER
  ) RETURN NUMBER;

  -- Cambia nombre y precio. El cambio de precio queda en PRECIO_HIST por el trigger
  -- (autor = CLIENT_IDENTIFIER). Errores: -20320/-20325, -20336, -20337.
  PROCEDURE actualizar_variante(
    p_id_empleado IN NUMBER,
    p_id_variante IN NUMBER,
    p_nombre      IN VARCHAR2,
    p_precio_base IN NUMBER
  );

  -- Activa (1) o desactiva (0). Error -20336 si no existe.
  PROCEDURE cambiar_estado_variante(
    p_id_empleado IN NUMBER,
    p_id_variante IN NUMBER,
    p_activo      IN NUMBER
  );
END PKG_CATALOGO;
/
SHOW ERRORS PACKAGE PKG_CATALOGO
PROMPT >> PKG_CATALOGO: especificacion creada

CREATE OR REPLACE PACKAGE BODY PKG_CATALOGO
AS
  c_permiso_categorias CONSTANT VARCHAR2(20) := 'CATEGORIAS';
  c_permiso_productos  CONSTANT VARCHAR2(20) := 'PRODUCTOS';

  -- ---------------------------------------------------------------------------
  -- Privados
  -- ---------------------------------------------------------------------------

  -- Una categoria usada como padre debe existir y estar activa.
  PROCEDURE validar_padre(p_id_padre IN NUMBER) IS
    v_n NUMBER;
  BEGIN
    IF p_id_padre IS NULL THEN
      RETURN;
    END IF;
    SELECT COUNT(*) INTO v_n
      FROM CATEGORIA
     WHERE ID_CATEGORIA = p_id_padre AND ACTIVO = 1;
    IF v_n = 0 THEN
      RAISE_APPLICATION_ERROR(-20331,
        'La categoria padre ' || p_id_padre || ' no existe o esta inactiva.');
    END IF;
  END validar_padre;

  -- La categoria de un producto debe existir y estar activa (-20330).
  PROCEDURE validar_categoria_producto(p_id_categoria IN NUMBER) IS
    v_n NUMBER;
  BEGIN
    SELECT COUNT(*) INTO v_n
      FROM CATEGORIA
     WHERE ID_CATEGORIA = p_id_categoria AND ACTIVO = 1;
    IF v_n = 0 THEN
      RAISE_APPLICATION_ERROR(-20330,
        'La categoria ' || p_id_categoria || ' no existe o esta inactiva.');
    END IF;
  END validar_categoria_producto;

  PROCEDURE validar_impuesto(p_id_impuesto IN NUMBER) IS
    v_n NUMBER;
  BEGIN
    SELECT COUNT(*) INTO v_n
      FROM IMPUESTO
     WHERE ID_IMPUESTO = p_id_impuesto AND ACTIVO = 1;
    IF v_n = 0 THEN
      RAISE_APPLICATION_ERROR(-20334,
        'El impuesto ' || p_id_impuesto || ' no existe o esta inactivo.');
    END IF;
  END validar_impuesto;

  -- Precio >= 0 y con a lo sumo 2 decimales (la columna es NUMBER(14,2)).
  PROCEDURE validar_precio(p_precio IN NUMBER) IS
  BEGIN
    IF p_precio IS NULL OR p_precio < 0 OR p_precio <> ROUND(p_precio, 2) THEN
      RAISE_APPLICATION_ERROR(-20337,
        'El precio debe ser mayor o igual a cero y tener como maximo 2 decimales.');
    END IF;
  END validar_precio;

  -- ---------------------------------------------------------------------------
  -- Lectura
  -- ---------------------------------------------------------------------------
  FUNCTION listar_categorias(p_id_empleado IN NUMBER) RETURN SYS_REFCURSOR IS
    v_cursor SYS_REFCURSOR;
  BEGIN
    PKG_PERMISOS.exigir_empleado_activo(p_id_empleado);
    OPEN v_cursor FOR
      SELECT ID_CATEGORIA, ID_CATEGORIA_PADRE, NOMBRE, ACTIVO, IMAGEN_URL
        FROM CATEGORIA
       ORDER BY NOMBRE, ID_CATEGORIA;
    RETURN v_cursor;
  END listar_categorias;

  FUNCTION listar_impuestos(p_id_empleado IN NUMBER) RETURN SYS_REFCURSOR IS
    v_cursor SYS_REFCURSOR;
  BEGIN
    PKG_PERMISOS.exigir_empleado_activo(p_id_empleado);
    OPEN v_cursor FOR
      SELECT ID_IMPUESTO, NOMBRE, TASA, ACTIVO
        FROM IMPUESTO
       ORDER BY NOMBRE, ID_IMPUESTO;
    RETURN v_cursor;
  END listar_impuestos;

  FUNCTION listar_productos(
    p_id_empleado IN NUMBER,
    p_pagina      IN NUMBER,
    p_tamano      IN NUMBER
  ) RETURN SYS_REFCURSOR IS
    v_cursor SYS_REFCURSOR;
    v_tamano NUMBER := GREATEST(1, LEAST(NVL(p_tamano, 20), 100));
    v_offset NUMBER := GREATEST(0, NVL(p_pagina, 0)) * GREATEST(1, LEAST(NVL(p_tamano, 20), 100));
  BEGIN
    PKG_PERMISOS.exigir_empleado_activo(p_id_empleado);
    OPEN v_cursor FOR
      SELECT pr.ID_PRODUCTO, pr.ID_CATEGORIA, pr.ID_IMPUESTO, pr.NOMBRE AS PRODUCTO,
             pr.DESCRIPCION, pr.IMAGEN_URL, pr.ACTIVO AS PRODUCTO_ACTIVO,
             v.ID_VARIANTE, v.SKU, v.NOMBRE AS VARIANTE, v.PRECIO_BASE,
             v.CONTROLA_STOCK, v.ACTIVO AS VARIANTE_ACTIVA
        FROM (SELECT ID_PRODUCTO, ID_CATEGORIA, ID_IMPUESTO, NOMBRE, DESCRIPCION, IMAGEN_URL, ACTIVO
                FROM PRODUCTO
               ORDER BY ID_PRODUCTO
              OFFSET v_offset ROWS FETCH NEXT v_tamano ROWS ONLY) pr
        LEFT JOIN VARIANTE v ON v.ID_PRODUCTO = pr.ID_PRODUCTO
       ORDER BY pr.ID_PRODUCTO, v.ID_VARIANTE;
    RETURN v_cursor;
  END listar_productos;

  -- ---------------------------------------------------------------------------
  -- Categorias
  -- ---------------------------------------------------------------------------
  FUNCTION crear_categoria(
    p_id_empleado IN NUMBER,
    p_nombre      IN VARCHAR2,
    p_id_padre    IN NUMBER
  ) RETURN NUMBER IS
    v_id CATEGORIA.ID_CATEGORIA%TYPE;
  BEGIN
    SAVEPOINT sp_crear_categoria;

    PKG_PERMISOS.exigir_permiso(p_id_empleado, c_permiso_categorias);
    validar_padre(p_id_padre);

    BEGIN
      INSERT INTO CATEGORIA (ID_CATEGORIA_PADRE, NOMBRE)
      VALUES (p_id_padre, TRIM(p_nombre))
      RETURNING ID_CATEGORIA INTO v_id;
    EXCEPTION
      WHEN DUP_VAL_ON_INDEX THEN
        RAISE_APPLICATION_ERROR(-20332,
          'Ya existe una categoria con el nombre ' || TRIM(p_nombre) || ' bajo ese padre.');
    END;

    RETURN v_id;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_crear_categoria;
      RAISE;
  END crear_categoria;

  PROCEDURE actualizar_categoria(
    p_id_empleado  IN NUMBER,
    p_id_categoria IN NUMBER,
    p_nombre       IN VARCHAR2,
    p_id_padre     IN NUMBER
  ) IS
    v_n NUMBER;
  BEGIN
    SAVEPOINT sp_actualizar_categoria;

    PKG_PERMISOS.exigir_permiso(p_id_empleado, c_permiso_categorias);

    SELECT COUNT(*) INTO v_n FROM CATEGORIA WHERE ID_CATEGORIA = p_id_categoria;
    IF v_n = 0 THEN
      RAISE_APPLICATION_ERROR(-20330, 'La categoria ' || p_id_categoria || ' no existe.');
    END IF;

    validar_padre(p_id_padre);
    IF p_id_padre IS NOT NULL THEN
      -- Si la categoria ya esta en la cadena de ancestros del nuevo padre (o es el
      -- propio padre), el cambio cerraria un ciclo. NOCYCLE protege de datos viejos.
      SELECT COUNT(*) INTO v_n
        FROM CATEGORIA
       WHERE ID_CATEGORIA = p_id_categoria
       START WITH ID_CATEGORIA = p_id_padre
       CONNECT BY NOCYCLE PRIOR ID_CATEGORIA_PADRE = ID_CATEGORIA;
      IF v_n > 0 THEN
        RAISE_APPLICATION_ERROR(-20331,
          'La categoria ' || p_id_categoria || ' no puede quedar dentro de ' || p_id_padre
          || ': se formaria un ciclo.');
      END IF;
    END IF;

    BEGIN
      UPDATE CATEGORIA
         SET NOMBRE = TRIM(p_nombre),
             ID_CATEGORIA_PADRE = p_id_padre
       WHERE ID_CATEGORIA = p_id_categoria;
    EXCEPTION
      WHEN DUP_VAL_ON_INDEX THEN
        RAISE_APPLICATION_ERROR(-20332,
          'Ya existe una categoria con el nombre ' || TRIM(p_nombre) || ' bajo ese padre.');
    END;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_actualizar_categoria;
      RAISE;
  END actualizar_categoria;

  PROCEDURE cambiar_estado_categoria(
    p_id_empleado  IN NUMBER,
    p_id_categoria IN NUMBER,
    p_activo       IN NUMBER
  ) IS
  BEGIN
    SAVEPOINT sp_estado_categoria;

    PKG_PERMISOS.exigir_permiso(p_id_empleado, c_permiso_categorias);
    UPDATE CATEGORIA SET ACTIVO = p_activo WHERE ID_CATEGORIA = p_id_categoria;
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20330, 'La categoria ' || p_id_categoria || ' no existe.');
    END IF;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_estado_categoria;
      RAISE;
  END cambiar_estado_categoria;

  PROCEDURE fijar_imagen_categoria(
    p_id_empleado  IN NUMBER,
    p_id_categoria IN NUMBER,
    p_imagen_url   IN VARCHAR2
  ) IS
    -- VARCHAR2(4000), no el tipo de la columna: una URL de mas de 500 caracteres debe dar
    -- -20338 y no un error de desbordamiento al asignarla.
    v_url VARCHAR2(4000) := TRIM(p_imagen_url);
  BEGIN
    SAVEPOINT sp_fijar_imagen_cat;

    PKG_PERMISOS.exigir_permiso(p_id_empleado, c_permiso_categorias);
    IF v_url IS NOT NULL AND (LENGTH(v_url) > 500 OR v_url NOT LIKE 'https://%') THEN
      RAISE_APPLICATION_ERROR(-20338,
        'La URL de la imagen debe empezar por https:// y tener hasta 500 caracteres.');
    END IF;

    UPDATE CATEGORIA SET IMAGEN_URL = v_url WHERE ID_CATEGORIA = p_id_categoria;
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20330, 'La categoria ' || p_id_categoria || ' no existe.');
    END IF;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_fijar_imagen_cat;
      RAISE;
  END fijar_imagen_categoria;

  -- ---------------------------------------------------------------------------
  -- Productos y variantes
  -- ---------------------------------------------------------------------------
  FUNCTION crear_producto(
    p_id_empleado  IN NUMBER,
    p_id_categoria IN NUMBER,
    p_id_impuesto  IN NUMBER,
    p_nombre       IN VARCHAR2,
    p_descripcion  IN VARCHAR2
  ) RETURN NUMBER IS
    v_id PRODUCTO.ID_PRODUCTO%TYPE;
  BEGIN
    SAVEPOINT sp_crear_producto;

    PKG_PERMISOS.exigir_permiso(p_id_empleado, c_permiso_productos);
    validar_categoria_producto(p_id_categoria);
    validar_impuesto(p_id_impuesto);

    INSERT INTO PRODUCTO (ID_CATEGORIA, ID_IMPUESTO, NOMBRE, DESCRIPCION)
    VALUES (p_id_categoria, p_id_impuesto, TRIM(p_nombre), TRIM(p_descripcion))
    RETURNING ID_PRODUCTO INTO v_id;

    RETURN v_id;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_crear_producto;
      RAISE;
  END crear_producto;

  PROCEDURE actualizar_producto(
    p_id_empleado  IN NUMBER,
    p_id_producto  IN NUMBER,
    p_id_categoria IN NUMBER,
    p_id_impuesto  IN NUMBER,
    p_nombre       IN VARCHAR2,
    p_descripcion  IN VARCHAR2
  ) IS
    v_n NUMBER;
  BEGIN
    SAVEPOINT sp_actualizar_producto;

    PKG_PERMISOS.exigir_permiso(p_id_empleado, c_permiso_productos);

    SELECT COUNT(*) INTO v_n FROM PRODUCTO WHERE ID_PRODUCTO = p_id_producto;
    IF v_n = 0 THEN
      RAISE_APPLICATION_ERROR(-20333, 'El producto ' || p_id_producto || ' no existe.');
    END IF;
    validar_categoria_producto(p_id_categoria);
    validar_impuesto(p_id_impuesto);

    UPDATE PRODUCTO
       SET ID_CATEGORIA = p_id_categoria,
           ID_IMPUESTO  = p_id_impuesto,
           NOMBRE       = TRIM(p_nombre),
           DESCRIPCION  = TRIM(p_descripcion)
     WHERE ID_PRODUCTO = p_id_producto;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_actualizar_producto;
      RAISE;
  END actualizar_producto;

  PROCEDURE cambiar_estado_producto(
    p_id_empleado IN NUMBER,
    p_id_producto IN NUMBER,
    p_activo      IN NUMBER
  ) IS
  BEGIN
    SAVEPOINT sp_estado_producto;

    PKG_PERMISOS.exigir_permiso(p_id_empleado, c_permiso_productos);
    UPDATE PRODUCTO SET ACTIVO = p_activo WHERE ID_PRODUCTO = p_id_producto;
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20333, 'El producto ' || p_id_producto || ' no existe.');
    END IF;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_estado_producto;
      RAISE;
  END cambiar_estado_producto;

  PROCEDURE fijar_imagen(
    p_id_empleado IN NUMBER,
    p_id_producto IN NUMBER,
    p_imagen_url  IN VARCHAR2
  ) IS
    -- VARCHAR2(4000), no el tipo de la columna: una URL de mas de 500 caracteres debe dar
    -- -20338 y no un error de desbordamiento al asignarla.
    v_url VARCHAR2(4000) := TRIM(p_imagen_url);
  BEGIN
    SAVEPOINT sp_fijar_imagen;

    PKG_PERMISOS.exigir_permiso(p_id_empleado, c_permiso_productos);
    IF v_url IS NOT NULL AND (LENGTH(v_url) > 500 OR v_url NOT LIKE 'https://%') THEN
      RAISE_APPLICATION_ERROR(-20338,
        'La URL de la imagen debe empezar por https:// y tener hasta 500 caracteres.');
    END IF;

    UPDATE PRODUCTO SET IMAGEN_URL = v_url WHERE ID_PRODUCTO = p_id_producto;
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20333, 'El producto ' || p_id_producto || ' no existe.');
    END IF;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_fijar_imagen;
      RAISE;
  END fijar_imagen;

  FUNCTION agregar_variante(
    p_id_empleado    IN NUMBER,
    p_id_producto    IN NUMBER,
    p_sku            IN VARCHAR2,
    p_nombre         IN VARCHAR2,
    p_precio_base    IN NUMBER,
    p_controla_stock IN NUMBER
  ) RETURN NUMBER IS
    v_n   NUMBER;
    v_sku VARIANTE.SKU%TYPE := UPPER(TRIM(p_sku));
    v_id  VARIANTE.ID_VARIANTE%TYPE;
  BEGIN
    SAVEPOINT sp_agregar_variante;

    PKG_PERMISOS.exigir_permiso(p_id_empleado, c_permiso_productos);

    SELECT COUNT(*) INTO v_n FROM PRODUCTO WHERE ID_PRODUCTO = p_id_producto;
    IF v_n = 0 THEN
      RAISE_APPLICATION_ERROR(-20333, 'El producto ' || p_id_producto || ' no existe.');
    END IF;
    validar_precio(p_precio_base);

    BEGIN
      INSERT INTO VARIANTE (ID_PRODUCTO, SKU, NOMBRE, PRECIO_BASE, CONTROLA_STOCK)
      VALUES (p_id_producto, v_sku, TRIM(p_nombre), p_precio_base, p_controla_stock)
      RETURNING ID_VARIANTE INTO v_id;
    EXCEPTION
      WHEN DUP_VAL_ON_INDEX THEN
        RAISE_APPLICATION_ERROR(-20335, 'Ya existe una variante con el SKU ' || v_sku || '.');
    END;

    RETURN v_id;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_agregar_variante;
      RAISE;
  END agregar_variante;

  PROCEDURE actualizar_variante(
    p_id_empleado IN NUMBER,
    p_id_variante IN NUMBER,
    p_nombre      IN VARCHAR2,
    p_precio_base IN NUMBER
  ) IS
  BEGIN
    SAVEPOINT sp_actualizar_variante;

    PKG_PERMISOS.exigir_permiso(p_id_empleado, c_permiso_productos);
    validar_precio(p_precio_base);

    UPDATE VARIANTE
       SET NOMBRE = TRIM(p_nombre),
           PRECIO_BASE = p_precio_base
     WHERE ID_VARIANTE = p_id_variante;
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20336, 'La variante ' || p_id_variante || ' no existe.');
    END IF;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_actualizar_variante;
      RAISE;
  END actualizar_variante;

  PROCEDURE cambiar_estado_variante(
    p_id_empleado IN NUMBER,
    p_id_variante IN NUMBER,
    p_activo      IN NUMBER
  ) IS
  BEGIN
    SAVEPOINT sp_estado_variante;

    PKG_PERMISOS.exigir_permiso(p_id_empleado, c_permiso_productos);
    UPDATE VARIANTE SET ACTIVO = p_activo WHERE ID_VARIANTE = p_id_variante;
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20336, 'La variante ' || p_id_variante || ' no existe.');
    END IF;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_estado_variante;
      RAISE;
  END cambiar_estado_variante;
END PKG_CATALOGO;
/
SHOW ERRORS PACKAGE BODY PKG_CATALOGO
PROMPT >> PKG_CATALOGO: cuerpo creado

DECLARE
  v_errores NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_errores
    FROM user_errors
   WHERE name = 'PKG_CATALOGO';
  IF v_errores > 0 THEN
    RAISE_APPLICATION_ERROR(-20004,
      'PKG_CATALOGO compilo con errores: consulta USER_ERRORS.');
  END IF;
END;
/
PROMPT >> PKG_CATALOGO compilado sin errores

-- (CREATE OR REPLACE conserva los permisos de PKG_CATALOGO: el EXECUTE del rol empleado sigue igual.)

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT object_name, object_type, status
  FROM user_objects
 WHERE object_name IN ('PKG_CATALOGO', 'V_CATALOGO', 'V_CATEGORIA_PUBLICA')
 ORDER BY object_name, object_type;     -- PACKAGE VALID, PACKAGE BODY VALID, 2 VIEW VALID

SELECT table_name, grantee, privilege
  FROM user_tab_privs_made
 WHERE table_name IN ('V_CATALOGO', 'V_CATEGORIA_PUBLICA');   -- 2 filas -> ROL_ECOMERS_CLIENTE (SELECT)

SELECT table_name, column_name, data_type, data_length, nullable
  FROM user_tab_columns
 WHERE table_name IN ('PRODUCTO', 'CATEGORIA') AND column_name = 'IMAGEN_URL';   -- 2 filas: VARCHAR2 500, nullable Y

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';               -- 0 filas

-- ============================================================================
-- PRUEBAS RAPIDAS (terminan con ROLLBACK: no dejan datos).
-- Esperado: 23 lineas "OK" y al final "0 fallos".
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  TYPE t_fila IS RECORD (
    id_producto     NUMBER,
    id_categoria    NUMBER,
    id_impuesto     NUMBER,
    producto        VARCHAR2(200),
    descripcion     VARCHAR2(2000),
    imagen_url      VARCHAR2(500),
    producto_activo NUMBER,
    id_variante     NUMBER,
    sku             VARCHAR2(50),
    variante        VARCHAR2(150),
    precio_base     NUMBER,
    controla_stock  NUMBER,
    variante_activa NUMBER
  );

  TYPE t_cat IS RECORD (
    id_categoria NUMBER,
    id_padre     NUMBER,
    nombre       VARCHAR2(100),
    activo       NUMBER,
    imagen_url   VARCHAR2(500)
  );

  v_pruebas   NUMBER := 0;
  v_fallos    NUMBER := 0;
  v_admin     NUMBER;
  v_trab_prod NUMBER;   -- tiene PRODUCTOS
  v_trab_cat  NUMBER;   -- solo CATEGORIAS
  v_impuesto  NUMBER;
  v_cat       NUMBER;
  v_prod      NUMBER;
  v_prod2     NUMBER;
  v_var       NUMBER;
  v_var2      NUMBER;
  v_texto     VARCHAR2(4000);
  v_n         NUMBER;
  v_n2        NUMBER;
  c_hash CONSTANT VARCHAR2(60) := '$2a$10$' || RPAD('a', 53, 'a');
  c_url  CONSTANT VARCHAR2(200) := 'https://abc.supabase.co/storage/v1/object/public/productos/productos/prueba24.png';

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

  PROCEDURE esperar_error(p_nombre IN VARCHAR2, p_bloque IN VARCHAR2, p_codigo IN NUMBER) IS
  BEGIN
    EXECUTE IMMEDIATE p_bloque;
    verificar(p_nombre || ' (debia fallar con ' || p_codigo || ')', FALSE);
  EXCEPTION
    WHEN OTHERS THEN
      verificar(p_nombre || ' -> ' || p_codigo, SQLCODE = p_codigo);
  END esperar_error;

  -- IMAGEN_URL de un producto buscandolo en el listado (NULL si no tiene o no sale).
  FUNCTION imagen_en_listado(p_emp IN NUMBER, p_id IN NUMBER) RETURN VARCHAR2 IS
    v_c   SYS_REFCURSOR;
    v_f   t_fila;
    v_hay BOOLEAN;
  BEGIN
    FOR v_pag IN 0 .. 500 LOOP
      v_c := PKG_CATALOGO.listar_productos(p_emp, v_pag, 100);
      v_hay := FALSE;
      LOOP
        FETCH v_c INTO v_f;
        EXIT WHEN v_c%NOTFOUND;
        v_hay := TRUE;
        IF v_f.id_producto = p_id THEN
          CLOSE v_c;
          RETURN v_f.imagen_url;
        END IF;
      END LOOP;
      CLOSE v_c;
      EXIT WHEN NOT v_hay;
    END LOOP;
    RETURN NULL;
  END imagen_en_listado;

  -- IMAGEN_URL de una categoria buscandola en el listado (NULL si no tiene o no sale).
  FUNCTION imagen_categoria_en_listado(p_emp IN NUMBER, p_id IN NUMBER) RETURN VARCHAR2 IS
    v_c   SYS_REFCURSOR;
    v_f   t_cat;
  BEGIN
    v_c := PKG_CATALOGO.listar_categorias(p_emp);
    LOOP
      FETCH v_c INTO v_f;
      EXIT WHEN v_c%NOTFOUND;
      IF v_f.id_categoria = p_id THEN
        CLOSE v_c;
        RETURN v_f.imagen_url;
      END IF;
    END LOOP;
    CLOSE v_c;
    RETURN NULL;
  END imagen_categoria_en_listado;
BEGIN
  INSERT INTO EMPLEADO (NOMBRE, EMAIL, PASSWORD_HASH, ROL)
    VALUES ('Admin imagen', 'admin.img.prueba24@ejemplo.com', c_hash, 'ADMIN') RETURNING ID_EMPLEADO INTO v_admin;
  INSERT INTO EMPLEADO (NOMBRE, EMAIL, PASSWORD_HASH, ROL)
    VALUES ('Trab productos', 'trab.prod.prueba24@ejemplo.com', c_hash, 'TRABAJADOR') RETURNING ID_EMPLEADO INTO v_trab_prod;
  INSERT INTO EMPLEADO (NOMBRE, EMAIL, PASSWORD_HASH, ROL)
    VALUES ('Trab categorias', 'trab.cat.prueba24@ejemplo.com', c_hash, 'TRABAJADOR') RETURNING ID_EMPLEADO INTO v_trab_cat;
  INSERT INTO EMPLEADO_PERMISO (ID_EMPLEADO, ID_PERMISO, ID_ADMIN_ASIGNO)
    VALUES (v_trab_prod, (SELECT ID_PERMISO FROM PERMISO WHERE CODIGO = 'PRODUCTOS'), v_admin);
  INSERT INTO EMPLEADO_PERMISO (ID_EMPLEADO, ID_PERMISO, ID_ADMIN_ASIGNO)
    VALUES (v_trab_cat, (SELECT ID_PERMISO FROM PERMISO WHERE CODIGO = 'CATEGORIAS'), v_admin);

  SELECT ID_IMPUESTO INTO v_impuesto FROM IMPUESTO WHERE NOMBRE = 'IVA 19%';
  v_cat   := PKG_CATALOGO.crear_categoria(v_admin, 'Categoria prueba24', NULL);
  v_prod  := PKG_CATALOGO.crear_producto(v_trab_prod, v_cat, v_impuesto, 'Producto con imagen prueba24', NULL);
  v_prod2 := PKG_CATALOGO.crear_producto(v_trab_prod, v_cat, v_impuesto, 'Producto sin imagen prueba24', NULL);
  v_var   := PKG_CATALOGO.agregar_variante(v_trab_prod, v_prod, 'IMG-P24-001', 'Con imagen', 1000, 0);
  v_var2  := PKG_CATALOGO.agregar_variante(v_trab_prod, v_prod2, 'IMG-P24-002', 'Sin imagen', 2000, 0);

  -- fijar_imagen
  PKG_CATALOGO.fijar_imagen(v_trab_prod, v_prod, '  ' || c_url || ' ');
  SELECT IMAGEN_URL INTO v_texto FROM PRODUCTO WHERE ID_PRODUCTO = v_prod;
  verificar('fijar_imagen guarda la URL recortada (permiso PRODUCTOS)', v_texto = c_url);

  esperar_error('un trabajador sin PRODUCTOS (solo CATEGORIAS) no fija imagenes',
    'BEGIN PKG_CATALOGO.fijar_imagen(' || v_trab_cat || ', ' || v_prod || ', ''' || c_url || '''); END;', -20320);

  PKG_CATALOGO.fijar_imagen(v_admin, v_prod2, c_url);
  PKG_CATALOGO.fijar_imagen(v_admin, v_prod2, NULL);
  SELECT COUNT(*) INTO v_n FROM PRODUCTO WHERE ID_PRODUCTO = v_prod2 AND IMAGEN_URL IS NULL;
  verificar('un NULL quita la imagen (el admin pasa siempre)', v_n = 1);

  PKG_CATALOGO.fijar_imagen(v_admin, v_prod2, c_url);
  PKG_CATALOGO.fijar_imagen(v_admin, v_prod2, '   ');
  SELECT COUNT(*) INTO v_n FROM PRODUCTO WHERE ID_PRODUCTO = v_prod2 AND IMAGEN_URL IS NULL;
  verificar('un texto en blanco tambien quita la imagen', v_n = 1);

  esperar_error('una URL http:// se rechaza',
    'BEGIN PKG_CATALOGO.fijar_imagen(' || v_admin || ', ' || v_prod || ', ''http://abc.supabase.co/a.png''); END;', -20338);
  esperar_error('una URL de mas de 500 caracteres se rechaza',
    'BEGIN PKG_CATALOGO.fijar_imagen(' || v_admin || ', ' || v_prod || ', ''https://' || RPAD('a', 500, 'a') || '''); END;', -20338);
  esperar_error('un producto inexistente',
    'BEGIN PKG_CATALOGO.fijar_imagen(' || v_admin || ', -1, ''' || c_url || '''); END;', -20333);

  SELECT IMAGEN_URL INTO v_texto FROM PRODUCTO WHERE ID_PRODUCTO = v_prod;
  verificar('una URL rechazada no cambia la imagen que ya tenia', v_texto = c_url);

  -- listar_productos
  verificar('listar_productos devuelve la IMAGEN_URL', imagen_en_listado(v_trab_cat, v_prod) = c_url);
  verificar('un producto sin imagen sale con IMAGEN_URL nula', imagen_en_listado(v_trab_cat, v_prod2) IS NULL);

  -- V_CATALOGO (lo que lee el catalogo publico)
  SELECT COUNT(*) INTO v_n FROM V_CATALOGO WHERE ID_VARIANTE = v_var AND IMAGEN_URL = c_url;
  SELECT COUNT(*) INTO v_n2 FROM V_CATALOGO WHERE ID_VARIANTE = v_var2 AND IMAGEN_URL IS NULL;
  verificar('V_CATALOGO trae la imagen del producto y NULL si no tiene', v_n = 1 AND v_n2 = 1);

  SELECT COUNT(*) INTO v_n FROM V_CATALOGO;
  SELECT COUNT(*) INTO v_n2 FROM V_PRECIO_VIGENTE;
  verificar('V_CATALOGO tiene exactamente las mismas filas que V_PRECIO_VIGENTE', v_n = v_n2);

  -- Restriccion de la columna
  esperar_error('la restriccion de la tabla rechaza una URL que no es https',
    'UPDATE PRODUCTO SET IMAGEN_URL = ''http://x.com/a.png'' WHERE ID_PRODUCTO = ' || v_prod, -2290);

  -- fijar_imagen_categoria
  PKG_CATALOGO.fijar_imagen_categoria(v_trab_cat, v_cat, '  ' || c_url || ' ');
  SELECT IMAGEN_URL INTO v_texto FROM CATEGORIA WHERE ID_CATEGORIA = v_cat;
  verificar('fijar_imagen_categoria guarda la URL recortada (permiso CATEGORIAS)', v_texto = c_url);

  esperar_error('un trabajador sin CATEGORIAS (solo PRODUCTOS) no fija imagenes de categoria',
    'BEGIN PKG_CATALOGO.fijar_imagen_categoria(' || v_trab_prod || ', ' || v_cat || ', ''' || c_url || '''); END;', -20320);

  verificar('listar_categorias devuelve la IMAGEN_URL', imagen_categoria_en_listado(v_trab_prod, v_cat) = c_url);

  PKG_CATALOGO.fijar_imagen_categoria(v_admin, v_cat, NULL);
  SELECT COUNT(*) INTO v_n FROM CATEGORIA WHERE ID_CATEGORIA = v_cat AND IMAGEN_URL IS NULL;
  verificar('un NULL quita la imagen de la categoria', v_n = 1);

  esperar_error('una URL http:// se rechaza en la categoria',
    'BEGIN PKG_CATALOGO.fijar_imagen_categoria(' || v_admin || ', ' || v_cat || ', ''http://abc.supabase.co/a.png''); END;', -20338);
  esperar_error('una categoria inexistente',
    'BEGIN PKG_CATALOGO.fijar_imagen_categoria(' || v_admin || ', -1, ''' || c_url || '''); END;', -20330);

  esperar_error('la restriccion de la tabla rechaza una URL de categoria que no es https',
    'UPDATE CATEGORIA SET IMAGEN_URL = ''http://x.com/a.png'' WHERE ID_CATEGORIA = ' || v_cat, -2290);

  -- V_CATEGORIA_PUBLICA y la categoria dentro de V_CATALOGO (lo que lee la tienda)
  PKG_CATALOGO.fijar_imagen_categoria(v_admin, v_cat, c_url);
  SELECT COUNT(*) INTO v_n
    FROM V_CATEGORIA_PUBLICA
   WHERE ID_CATEGORIA = v_cat AND ID_PADRE IS NULL AND IMAGEN_URL = c_url;
  verificar('V_CATEGORIA_PUBLICA muestra la categoria activa con su imagen', v_n = 1);

  SELECT COUNT(*) INTO v_n FROM V_CATALOGO WHERE ID_VARIANTE = v_var AND ID_CATEGORIA = v_cat;
  verificar('V_CATALOGO trae la categoria del producto', v_n = 1);

  PKG_CATALOGO.cambiar_estado_categoria(v_admin, v_cat, 0);
  SELECT COUNT(*) INTO v_n FROM V_CATEGORIA_PUBLICA WHERE ID_CATEGORIA = v_cat;
  verificar('una categoria inactiva no sale en V_CATEGORIA_PUBLICA', v_n = 0);

  ROLLBACK;
  DBMS_OUTPUT.PUT_LINE(v_pruebas || ' pruebas, ' || v_fallos || ' fallos');
  IF v_fallos > 0 THEN
    RAISE_APPLICATION_ERROR(-20005, v_fallos || ' pruebas fallaron: revisa las lineas FALLO.');
  END IF;
END;
/
