-- ============================================================================
-- 22_admin_catalogo.sql
-- BORRADOR PARA REVISAR: no se ha ejecutado contra ninguna base. Leelo completo,
-- ajustalo a tu criterio y ejecutalo tu. La API (carpeta "api") espera
-- exactamente estas firmas: ver docs/superpowers/specs/2026-10-21-admin-catalogo-permisos-design.md
--
-- Administracion, catalogo y permisos delegables:
--   * EMPLEADO.ROL               (columna nueva: ADMIN o TRABAJADOR)
--   * tablas PERMISO y EMPLEADO_PERMISO
--   * PKG_PERMISOS               (INTERNO, sin grants: comprobaciones compartidas)
--   * PKG_EMPLEADO               (se amplia: trabajadores y permisos, solo ADMIN)
--   * PKG_CATALOGO               (categorias, productos y variantes)
--
-- Requiere haber ejecutado antes los scripts 01, 03, 20 y 21.
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1 (SELECT USER FROM dual).
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Se puede volver a ejecutar: las columnas, tablas y datos se crean solo si
--      faltan, y los paquetes y triggers son CREATE OR REPLACE.
--   4. Al final salen las pruebas rapidas: una linea "OK" por cada una. Crean
--      datos temporales y los deshacen con ROLLBACK: no quedan en la base.
--   5. Para deshacerlo:
--        DROP PACKAGE PKG_CATALOGO;  DROP PACKAGE PKG_PERMISOS;
--        DROP TABLE EMPLEADO_PERMISO PURGE;  DROP TABLE PERMISO PURGE;
--        ALTER TABLE EMPLEADO DROP COLUMN ROL;
--        (y vuelve a ejecutar el script 21 para dejar PKG_EMPLEADO como estaba)
--
-- DECISIONES DE DISENO
--   * El ADMIN tiene todos los permisos sin filas en EMPLEADO_PERMISO: permisos_de
--     le devuelve todos los activos, asi un permiso nuevo le llega solo. Solo los
--     TRABAJADOR necesitan asignacion.
--   * PKG_PERMISOS es un paquete interno (como PKG_UTILIDADES y PKG_PRECIOS):
--     NO se le da EXECUTE a ningun rol. Lo usan PKG_EMPLEADO y PKG_CATALOGO.
--   * Toda rutina que actua recibe el ID del empleado que llama (lo saca la API de
--     su token) y verifica dentro de la base que existe, esta activo y tiene el
--     permiso: defensa en profundidad, igual que las demas comprobaciones.
--   * Un ADMIN solo gestiona TRABAJADORES: sobre otro admin (o sobre si mismo) da
--     -20322. Los admins se siembran a mano (ver el ejemplo al final).
--   * Las funciones asignar_permiso, quitar_permiso y cambiar_estado devuelven el
--     CORREO del trabajador: la API lo usa para invalidar sus sesiones sin otra
--     consulta. asignar_permiso y quitar_permiso son idempotentes.
--   * Nada se borra: categorias, productos, variantes y trabajadores solo se
--     activan o desactivan.
--   * Cada procedimiento que escribe es atomico (SAVEPOINT + ROLLBACK TO) y no
--     hace COMMIT: lo hace la API (OracleDb), como en el resto de paquetes.
--   * Errores (bloque -20320 a -20337; -20300 y -20304 ya existen en el 21):
--       -20320 el empleado no tiene el permiso
--       -20321 quien llama no es un admin activo
--       -20322 el objetivo no es un trabajador (es admin)
--       -20323 el trabajador no existe
--       -20324 el codigo de permiso no existe
--       -20325 el empleado que llama no existe o esta inactivo
--       -20330 la categoria no existe o esta inactiva
--       -20331 padre invalido (inexistente o crearia un ciclo)
--       -20332 ya hay una categoria con ese nombre bajo el mismo padre
--       -20333 el producto no existe
--       -20334 el impuesto no existe o esta inactivo
--       -20335 SKU repetido
--       -20336 la variante no existe
--       -20337 precio invalido (negativo o con mas de 2 decimales)
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

PROMPT >> 22_admin_catalogo.sql: comprobando requisitos

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
   WHERE table_name IN ('EMPLEADO', 'CLIENTE', 'CATEGORIA', 'PRODUCTO', 'VARIANTE', 'IMPUESTO');
  IF v_existentes < 6 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan tablas previas: ejecuta antes los scripts 03 y 21.');
  END IF;

  SELECT COUNT(*) INTO v_existentes
    FROM user_objects
   WHERE object_name = 'PKG_EMPLEADO'
     AND object_type = 'PACKAGE BODY'
     AND status = 'VALID';
  IF v_existentes < 1 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Falta PKG_EMPLEADO valido: ejecuta antes el script 21.');
  END IF;
END;
/

-- ----------------------------------------------------------------------------
-- 1. EMPLEADO.ROL
-- ----------------------------------------------------------------------------
DECLARE
  v_n NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_n
    FROM user_tab_columns
   WHERE table_name = 'EMPLEADO' AND column_name = 'ROL';
  IF v_n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE EMPLEADO ADD (ROL VARCHAR2(20) DEFAULT ''TRABAJADOR'' NOT NULL)';
  END IF;

  SELECT COUNT(*) INTO v_n
    FROM user_constraints
   WHERE table_name = 'EMPLEADO' AND constraint_name = 'CK_EMPLEADO_ROL';
  IF v_n = 0 THEN
    EXECUTE IMMEDIATE
      'ALTER TABLE EMPLEADO ADD CONSTRAINT CK_EMPLEADO_ROL CHECK (ROL IN (''ADMIN'', ''TRABAJADOR''))';
  END IF;
END;
/

COMMENT ON COLUMN EMPLEADO.ROL IS
  'ADMIN gestiona trabajadores y tiene todos los permisos; TRABAJADOR solo los que le asignen (EMPLEADO_PERMISO).';

PROMPT >> EMPLEADO.ROL lista

-- ----------------------------------------------------------------------------
-- 2. PERMISO y EMPLEADO_PERMISO
-- ----------------------------------------------------------------------------
DECLARE
  v_n NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_n FROM user_tables WHERE table_name = 'PERMISO';
  IF v_n = 0 THEN
    EXECUTE IMMEDIATE
      'CREATE TABLE PERMISO ('
      || ' ID_PERMISO          NUMBER(12) GENERATED ALWAYS AS IDENTITY,'
      || ' CODIGO              VARCHAR2(50)  NOT NULL,'
      || ' NOMBRE              VARCHAR2(100) NOT NULL,'
      || ' ACTIVO              NUMBER(1)     DEFAULT 1 NOT NULL,'
      || ' FECHA_CREACION      TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,'
      || ' FECHA_MODIFICACION  TIMESTAMP,'
      || ' CONSTRAINT PK_PERMISO        PRIMARY KEY (ID_PERMISO),'
      || ' CONSTRAINT UQ_PERMISO_CODIGO UNIQUE (CODIGO),'
      || ' CONSTRAINT CK_PERMISO_CODIGO CHECK (CODIGO = UPPER(TRIM(CODIGO))),'
      || ' CONSTRAINT CK_PERMISO_ACTIVO CHECK (ACTIVO IN (0, 1))'
      || ')';
  END IF;

  SELECT COUNT(*) INTO v_n FROM user_tables WHERE table_name = 'EMPLEADO_PERMISO';
  IF v_n = 0 THEN
    EXECUTE IMMEDIATE
      'CREATE TABLE EMPLEADO_PERMISO ('
      || ' ID_EMPLEADO       NUMBER(12) NOT NULL,'
      || ' ID_PERMISO        NUMBER(12) NOT NULL,'
      || ' ID_ADMIN_ASIGNO   NUMBER(12) NOT NULL,'
      || ' FECHA_ASIGNACION  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,'
      || ' CONSTRAINT PK_EMPLEADO_PERMISO  PRIMARY KEY (ID_EMPLEADO, ID_PERMISO),'
      || ' CONSTRAINT FK_EMP_PERM_EMPLEADO FOREIGN KEY (ID_EMPLEADO)     REFERENCES EMPLEADO (ID_EMPLEADO),'
      || ' CONSTRAINT FK_EMP_PERM_PERMISO  FOREIGN KEY (ID_PERMISO)      REFERENCES PERMISO (ID_PERMISO),'
      || ' CONSTRAINT FK_EMP_PERM_ADMIN    FOREIGN KEY (ID_ADMIN_ASIGNO) REFERENCES EMPLEADO (ID_EMPLEADO)'
      || ')';
    EXECUTE IMMEDIATE 'CREATE INDEX IX_EMP_PERM_PERMISO ON EMPLEADO_PERMISO (ID_PERMISO)';
    EXECUTE IMMEDIATE 'CREATE INDEX IX_EMP_PERM_ADMIN ON EMPLEADO_PERMISO (ID_ADMIN_ASIGNO)';
  END IF;
END;
/

COMMENT ON TABLE PERMISO IS 'Catalogo de permisos que un admin puede delegar a un trabajador.';
COMMENT ON TABLE EMPLEADO_PERMISO IS 'Permisos asignados a cada trabajador, y que admin se los dio.';

CREATE OR REPLACE TRIGGER TRG_PERMISO_FMOD
  BEFORE UPDATE ON PERMISO FOR EACH ROW
BEGIN
  :NEW.FECHA_MODIFICACION := SYSTIMESTAMP;
END;
/

-- Siembra (idempotente): un permiso nuevo en el futuro es una fila nueva aqui.
MERGE INTO PERMISO p
USING (SELECT 'CATEGORIAS' AS CODIGO, 'Gestionar categorias de productos' AS NOMBRE FROM dual
       UNION ALL
       SELECT 'PRODUCTOS', 'Gestionar productos y variantes' FROM dual) s
   ON (p.CODIGO = s.CODIGO)
 WHEN NOT MATCHED THEN
   INSERT (CODIGO, NOMBRE) VALUES (s.CODIGO, s.NOMBRE);
COMMIT;

PROMPT >> PERMISO y EMPLEADO_PERMISO listas

-- ----------------------------------------------------------------------------
-- 3. PKG_PERMISOS (INTERNO: no se le da EXECUTE a ningun rol)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE PACKAGE PKG_PERMISOS
  AUTHID DEFINER
AS
  -- Error -20325 si el empleado no existe o esta inactivo.
  PROCEDURE exigir_empleado_activo(p_id_empleado IN NUMBER);

  -- Error -20325 si no existe o esta inactivo; -20321 si no es ADMIN.
  PROCEDURE exigir_admin(p_id_empleado IN NUMBER);

  -- El ADMIN pasa siempre. Un TRABAJADOR necesita tener asignado el permiso
  -- (y que este activo): si no, -20320. Tambien -20325 si no existe o esta inactivo.
  PROCEDURE exigir_permiso(p_id_empleado IN NUMBER, p_codigo IN VARCHAR2);
END PKG_PERMISOS;
/
SHOW ERRORS PACKAGE PKG_PERMISOS
PROMPT >> PKG_PERMISOS: especificacion creada

CREATE OR REPLACE PACKAGE BODY PKG_PERMISOS
AS
  PROCEDURE leer_empleado(
    p_id_empleado IN  NUMBER,
    p_rol         OUT VARCHAR2,
    p_activo      OUT NUMBER
  ) IS
  BEGIN
    SELECT ROL, ACTIVO
      INTO p_rol, p_activo
      FROM EMPLEADO
     WHERE ID_EMPLEADO = p_id_empleado;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RAISE_APPLICATION_ERROR(-20325,
        'El empleado ' || p_id_empleado || ' no existe o esta inactivo.');
  END leer_empleado;

  PROCEDURE exigir_empleado_activo(p_id_empleado IN NUMBER) IS
    v_rol    EMPLEADO.ROL%TYPE;
    v_activo EMPLEADO.ACTIVO%TYPE;
  BEGIN
    leer_empleado(p_id_empleado, v_rol, v_activo);
    IF v_activo <> 1 THEN
      RAISE_APPLICATION_ERROR(-20325,
        'El empleado ' || p_id_empleado || ' no existe o esta inactivo.');
    END IF;
  END exigir_empleado_activo;

  PROCEDURE exigir_admin(p_id_empleado IN NUMBER) IS
    v_rol    EMPLEADO.ROL%TYPE;
    v_activo EMPLEADO.ACTIVO%TYPE;
  BEGIN
    leer_empleado(p_id_empleado, v_rol, v_activo);
    IF v_activo <> 1 THEN
      RAISE_APPLICATION_ERROR(-20325,
        'El empleado ' || p_id_empleado || ' no existe o esta inactivo.');
    END IF;
    IF v_rol <> 'ADMIN' THEN
      RAISE_APPLICATION_ERROR(-20321, 'Esta operacion es solo para administradores.');
    END IF;
  END exigir_admin;

  PROCEDURE exigir_permiso(p_id_empleado IN NUMBER, p_codigo IN VARCHAR2) IS
    v_rol    EMPLEADO.ROL%TYPE;
    v_activo EMPLEADO.ACTIVO%TYPE;
    v_tiene  NUMBER;
  BEGIN
    leer_empleado(p_id_empleado, v_rol, v_activo);
    IF v_activo <> 1 THEN
      RAISE_APPLICATION_ERROR(-20325,
        'El empleado ' || p_id_empleado || ' no existe o esta inactivo.');
    END IF;
    IF v_rol = 'ADMIN' THEN
      RETURN;
    END IF;

    SELECT COUNT(*)
      INTO v_tiene
      FROM EMPLEADO_PERMISO ep
      JOIN PERMISO p ON p.ID_PERMISO = ep.ID_PERMISO
     WHERE ep.ID_EMPLEADO = p_id_empleado
       AND p.CODIGO = UPPER(TRIM(p_codigo))
       AND p.ACTIVO = 1;
    IF v_tiene = 0 THEN
      RAISE_APPLICATION_ERROR(-20320,
        'El empleado ' || p_id_empleado || ' no tiene el permiso ' || UPPER(TRIM(p_codigo)) || '.');
    END IF;
  END exigir_permiso;
END PKG_PERMISOS;
/
SHOW ERRORS PACKAGE BODY PKG_PERMISOS
PROMPT >> PKG_PERMISOS: cuerpo creado

-- ----------------------------------------------------------------------------
-- 4. PKG_EMPLEADO (se amplia; conserva credencial_por_email y cambiar_password del 21)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE PACKAGE PKG_EMPLEADO
  AUTHID DEFINER
AS
  -- Credencial para el login: ID_EMPLEADO, EMAIL, PASSWORD_HASH, ACTIVO (0 o 1) y
  -- ROL (ADMIN o TRABAJADOR). Sin distinguir mayusculas. Cero filas si no existe.
  FUNCTION credencial_por_email(p_email IN VARCHAR2) RETURN SYS_REFCURSOR;

  -- Codigos de permiso del empleado (columna CODIGO). ADMIN: todos los activos.
  -- TRABAJADOR: los asignados que sigan activos. Cero filas si no existe.
  FUNCTION permisos_de(p_id_empleado IN NUMBER) RETURN SYS_REFCURSOR;

  -- Cambia el hash de un empleado. Error -20304 si el empleado no existe.
  PROCEDURE cambiar_password(
    p_id_empleado   IN NUMBER,
    p_password_hash IN VARCHAR2
  );

  -- Solo ADMIN. Crea un TRABAJADOR y devuelve su ID_EMPLEADO.
  -- Errores: -20321/-20325 (quien llama), -20300 (correo ya usado por un empleado o cliente).
  FUNCTION crear_trabajador(
    p_id_admin      IN NUMBER,
    p_nombre        IN VARCHAR2,
    p_email         IN VARCHAR2,
    p_password_hash IN VARCHAR2
  ) RETURN NUMBER;

  -- Solo ADMIN. Trabajadores (no admins): ID_EMPLEADO, NOMBRE, EMAIL, ACTIVO,
  -- FECHA_CREACION y PERMISOS (codigos activos separados por coma, o NULL).
  FUNCTION listar_trabajadores(p_id_admin IN NUMBER) RETURN SYS_REFCURSOR;

  -- Solo ADMIN. Activa (1) o desactiva (0) a un trabajador. Devuelve su correo.
  -- Errores: -20321/-20325, -20323 (no existe), -20322 (es admin).
  FUNCTION cambiar_estado(
    p_id_admin    IN NUMBER,
    p_id_empleado IN NUMBER,
    p_activo      IN NUMBER
  ) RETURN VARCHAR2;

  -- Solo ADMIN. Da un permiso a un trabajador (idempotente). Devuelve su correo.
  -- Errores: -20321/-20325, -20323, -20322, -20324 (codigo inexistente o inactivo).
  FUNCTION asignar_permiso(
    p_id_admin    IN NUMBER,
    p_id_empleado IN NUMBER,
    p_codigo      IN VARCHAR2
  ) RETURN VARCHAR2;

  -- Solo ADMIN. Quita un permiso a un trabajador (idempotente). Devuelve su correo.
  -- Mismos errores que asignar_permiso.
  FUNCTION quitar_permiso(
    p_id_admin    IN NUMBER,
    p_id_empleado IN NUMBER,
    p_codigo      IN VARCHAR2
  ) RETURN VARCHAR2;
END PKG_EMPLEADO;
/
SHOW ERRORS PACKAGE PKG_EMPLEADO
PROMPT >> PKG_EMPLEADO: especificacion creada

CREATE OR REPLACE PACKAGE BODY PKG_EMPLEADO
AS
  -- Correo del trabajador. -20323 si no existe, -20322 si es admin.
  FUNCTION exigir_trabajador(p_id_empleado IN NUMBER) RETURN VARCHAR2 IS
    v_rol   EMPLEADO.ROL%TYPE;
    v_email EMPLEADO.EMAIL%TYPE;
  BEGIN
    SELECT ROL, EMAIL
      INTO v_rol, v_email
      FROM EMPLEADO
     WHERE ID_EMPLEADO = p_id_empleado;
    IF v_rol <> 'TRABAJADOR' THEN
      RAISE_APPLICATION_ERROR(-20322,
        'El empleado ' || p_id_empleado || ' no es un trabajador: no se puede gestionar.');
    END IF;
    RETURN v_email;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RAISE_APPLICATION_ERROR(-20323, 'El trabajador ' || p_id_empleado || ' no existe.');
  END exigir_trabajador;

  FUNCTION credencial_por_email(p_email IN VARCHAR2) RETURN SYS_REFCURSOR IS
    v_cursor SYS_REFCURSOR;
  BEGIN
    OPEN v_cursor FOR
      SELECT ID_EMPLEADO, EMAIL, PASSWORD_HASH, ACTIVO, ROL
        FROM EMPLEADO
       WHERE UPPER(EMAIL) = UPPER(TRIM(p_email));
    RETURN v_cursor;
  END credencial_por_email;

  FUNCTION permisos_de(p_id_empleado IN NUMBER) RETURN SYS_REFCURSOR IS
    v_cursor SYS_REFCURSOR;
  BEGIN
    OPEN v_cursor FOR
      SELECT p.CODIGO
        FROM PERMISO p
       WHERE p.ACTIVO = 1
         AND ( (SELECT e.ROL FROM EMPLEADO e WHERE e.ID_EMPLEADO = p_id_empleado) = 'ADMIN'
               OR EXISTS (SELECT 1
                            FROM EMPLEADO_PERMISO ep
                           WHERE ep.ID_EMPLEADO = p_id_empleado
                             AND ep.ID_PERMISO = p.ID_PERMISO) )
       ORDER BY p.CODIGO;
    RETURN v_cursor;
  END permisos_de;

  PROCEDURE cambiar_password(
    p_id_empleado   IN NUMBER,
    p_password_hash IN VARCHAR2
  ) IS
  BEGIN
    SAVEPOINT sp_cambiar_password_empleado;

    UPDATE EMPLEADO
       SET PASSWORD_HASH = p_password_hash
     WHERE ID_EMPLEADO = p_id_empleado;
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20304, 'El empleado ' || p_id_empleado || ' no existe.');
    END IF;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_cambiar_password_empleado;
      RAISE;
  END cambiar_password;

  FUNCTION crear_trabajador(
    p_id_admin      IN NUMBER,
    p_nombre        IN VARCHAR2,
    p_email         IN VARCHAR2,
    p_password_hash IN VARCHAR2
  ) RETURN NUMBER IS
    v_email    EMPLEADO.EMAIL%TYPE := LOWER(TRIM(p_email));
    v_clientes NUMBER;
    v_id       EMPLEADO.ID_EMPLEADO%TYPE;
  BEGIN
    SAVEPOINT sp_crear_trabajador;

    PKG_PERMISOS.exigir_admin(p_id_admin);

    -- Un cliente con ese correo tambien cuenta como "ya registrado".
    SELECT COUNT(*)
      INTO v_clientes
      FROM CLIENTE
     WHERE UPPER(EMAIL) = UPPER(v_email);
    IF v_clientes > 0 THEN
      RAISE_APPLICATION_ERROR(-20300, 'Ya existe una cuenta con el correo ' || v_email || '.');
    END IF;

    BEGIN
      INSERT INTO EMPLEADO (NOMBRE, EMAIL, PASSWORD_HASH, ROL)
      VALUES (TRIM(p_nombre), v_email, p_password_hash, 'TRABAJADOR')
      RETURNING ID_EMPLEADO INTO v_id;
    EXCEPTION
      WHEN DUP_VAL_ON_INDEX THEN
        RAISE_APPLICATION_ERROR(-20300, 'Ya existe una cuenta con el correo ' || v_email || '.');
    END;

    RETURN v_id;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_crear_trabajador;
      RAISE;
  END crear_trabajador;

  FUNCTION listar_trabajadores(p_id_admin IN NUMBER) RETURN SYS_REFCURSOR IS
    v_cursor SYS_REFCURSOR;
  BEGIN
    PKG_PERMISOS.exigir_admin(p_id_admin);

    OPEN v_cursor FOR
      SELECT e.ID_EMPLEADO, e.NOMBRE, e.EMAIL, e.ACTIVO, e.FECHA_CREACION,
             (SELECT LISTAGG(p.CODIGO, ',') WITHIN GROUP (ORDER BY p.CODIGO)
                FROM EMPLEADO_PERMISO ep
                JOIN PERMISO p ON p.ID_PERMISO = ep.ID_PERMISO
               WHERE ep.ID_EMPLEADO = e.ID_EMPLEADO
                 AND p.ACTIVO = 1) AS PERMISOS
        FROM EMPLEADO e
       WHERE e.ROL = 'TRABAJADOR'
       ORDER BY e.NOMBRE, e.ID_EMPLEADO;
    RETURN v_cursor;
  END listar_trabajadores;

  FUNCTION cambiar_estado(
    p_id_admin    IN NUMBER,
    p_id_empleado IN NUMBER,
    p_activo      IN NUMBER
  ) RETURN VARCHAR2 IS
    v_email EMPLEADO.EMAIL%TYPE;
  BEGIN
    SAVEPOINT sp_cambiar_estado_trabajador;

    PKG_PERMISOS.exigir_admin(p_id_admin);
    v_email := exigir_trabajador(p_id_empleado);

    UPDATE EMPLEADO
       SET ACTIVO = p_activo
     WHERE ID_EMPLEADO = p_id_empleado;

    RETURN v_email;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_cambiar_estado_trabajador;
      RAISE;
  END cambiar_estado;

  FUNCTION asignar_permiso(
    p_id_admin    IN NUMBER,
    p_id_empleado IN NUMBER,
    p_codigo      IN VARCHAR2
  ) RETURN VARCHAR2 IS
    v_email      EMPLEADO.EMAIL%TYPE;
    v_id_permiso PERMISO.ID_PERMISO%TYPE;
  BEGIN
    SAVEPOINT sp_asignar_permiso;

    PKG_PERMISOS.exigir_admin(p_id_admin);
    v_email := exigir_trabajador(p_id_empleado);

    BEGIN
      SELECT ID_PERMISO
        INTO v_id_permiso
        FROM PERMISO
       WHERE CODIGO = UPPER(TRIM(p_codigo))
         AND ACTIVO = 1;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(-20324,
          'El permiso ' || p_codigo || ' no existe o esta inactivo.');
    END;

    MERGE INTO EMPLEADO_PERMISO ep
    USING (SELECT p_id_empleado AS ID_EMPLEADO, v_id_permiso AS ID_PERMISO FROM dual) x
       ON (ep.ID_EMPLEADO = x.ID_EMPLEADO AND ep.ID_PERMISO = x.ID_PERMISO)
     WHEN NOT MATCHED THEN
       INSERT (ID_EMPLEADO, ID_PERMISO, ID_ADMIN_ASIGNO)
       VALUES (x.ID_EMPLEADO, x.ID_PERMISO, p_id_admin);

    RETURN v_email;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_asignar_permiso;
      RAISE;
  END asignar_permiso;

  FUNCTION quitar_permiso(
    p_id_admin    IN NUMBER,
    p_id_empleado IN NUMBER,
    p_codigo      IN VARCHAR2
  ) RETURN VARCHAR2 IS
    v_email      EMPLEADO.EMAIL%TYPE;
    v_id_permiso PERMISO.ID_PERMISO%TYPE;
  BEGIN
    SAVEPOINT sp_quitar_permiso;

    PKG_PERMISOS.exigir_admin(p_id_admin);
    v_email := exigir_trabajador(p_id_empleado);

    -- Quitar un permiso ya desactivado en el catalogo si se permite: solo debe existir.
    BEGIN
      SELECT ID_PERMISO
        INTO v_id_permiso
        FROM PERMISO
       WHERE CODIGO = UPPER(TRIM(p_codigo));
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(-20324, 'El permiso ' || p_codigo || ' no existe.');
    END;

    DELETE FROM EMPLEADO_PERMISO
     WHERE ID_EMPLEADO = p_id_empleado
       AND ID_PERMISO = v_id_permiso;

    RETURN v_email;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_quitar_permiso;
      RAISE;
  END quitar_permiso;
END PKG_EMPLEADO;
/
SHOW ERRORS PACKAGE BODY PKG_EMPLEADO
PROMPT >> PKG_EMPLEADO: cuerpo creado

-- CREATE PACKAGE no se detiene ante errores de compilacion (solo avisa), asi que
-- se revisa USER_ERRORS de forma explicita.
DECLARE
  v_errores NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_errores
    FROM user_errors
   WHERE name IN ('PKG_PERMISOS', 'PKG_EMPLEADO');
  IF v_errores > 0 THEN
    RAISE_APPLICATION_ERROR(-20004,
      'PKG_PERMISOS o PKG_EMPLEADO compilaron con errores: consulta USER_ERRORS.');
  END IF;
END;
/
PROMPT >> PKG_PERMISOS y PKG_EMPLEADO compilados sin errores

-- ----------------------------------------------------------------------------
-- 5. PKG_CATALOGO
-- ----------------------------------------------------------------------------
CREATE OR REPLACE PACKAGE PKG_CATALOGO
  AUTHID DEFINER
AS
  -- ---- Lectura: cualquier empleado activo (-20325 si no). Incluyen inactivos. ----

  -- ID_CATEGORIA, ID_CATEGORIA_PADRE, NOMBRE, ACTIVO
  FUNCTION listar_categorias(p_id_empleado IN NUMBER) RETURN SYS_REFCURSOR;

  -- ID_IMPUESTO, NOMBRE, TASA, ACTIVO
  FUNCTION listar_impuestos(p_id_empleado IN NUMBER) RETURN SYS_REFCURSOR;

  -- Una fila por variante, paginado por PRODUCTO (p_pagina desde 0; p_tamano de 1 a 100):
  -- ID_PRODUCTO, ID_CATEGORIA, ID_IMPUESTO, PRODUCTO, DESCRIPCION, PRODUCTO_ACTIVO,
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
      SELECT ID_CATEGORIA, ID_CATEGORIA_PADRE, NOMBRE, ACTIVO
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
             pr.DESCRIPCION, pr.ACTIVO AS PRODUCTO_ACTIVO,
             v.ID_VARIANTE, v.SKU, v.NOMBRE AS VARIANTE, v.PRECIO_BASE,
             v.CONTROLA_STOCK, v.ACTIVO AS VARIANTE_ACTIVA
        FROM (SELECT ID_PRODUCTO, ID_CATEGORIA, ID_IMPUESTO, NOMBRE, DESCRIPCION, ACTIVO
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

-- ----------------------------------------------------------------------------
-- 6. Permisos (se pueden mover al script 20 para tenerlos todos juntos).
--    PKG_PERMISOS NO recibe ningun grant: es interno.
-- ----------------------------------------------------------------------------
GRANT EXECUTE ON PKG_CATALOGO TO ROL_ECOMERS_EMPLEADO;

PROMPT >> permisos otorgados

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT object_name, object_type, status
  FROM user_objects
 WHERE object_name IN ('PKG_PERMISOS', 'PKG_EMPLEADO', 'PKG_CATALOGO', 'PERMISO', 'EMPLEADO_PERMISO')
 ORDER BY object_name, object_type;     -- 3 PACKAGE VALID, 3 PACKAGE BODY VALID, 2 TABLE VALID

SELECT CODIGO, ACTIVO FROM PERMISO ORDER BY CODIGO;       -- CATEGORIAS 1, PRODUCTOS 1

SELECT COUNT(*) AS grants_pkg_permisos
  FROM user_tab_privs_made
 WHERE table_name = 'PKG_PERMISOS';     -- 0 (interno: nadie lo ejecuta)

SELECT table_name, grantee, privilege
  FROM user_tab_privs_made
 WHERE table_name IN ('PKG_CATALOGO', 'PKG_EMPLEADO')
 ORDER BY table_name;                   -- PKG_CATALOGO y PKG_EMPLEADO -> ROL_ECOMERS_EMPLEADO (EXECUTE)

SELECT object_name, object_type, status
  FROM user_objects
 WHERE status <> 'VALID';               -- 0 filas

-- ============================================================================
-- PRUEBAS RAPIDAS, PARTE A: trabajadores y permisos (terminan con ROLLBACK).
-- Esperado: 27 lineas "OK" y al final "0 fallos".
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  v_pruebas        NUMBER := 0;
  v_fallos         NUMBER := 0;
  v_admin          NUMBER;
  v_admin2         NUMBER;
  v_admin_inactivo NUMBER;
  v_trab           NUMBER;
  v_trab2          NUMBER;
  v_cursor         SYS_REFCURSOR;
  v_id             NUMBER;
  v_correo         VARCHAR2(254);
  v_hash           VARCHAR2(100);
  v_activo         NUMBER;
  v_rol            VARCHAR2(20);
  v_nombre         VARCHAR2(200);
  v_fecha          TIMESTAMP;
  v_permisos       VARCHAR2(4000);
  v_texto          VARCHAR2(4000);
  v_n              NUMBER;
  c_hash CONSTANT VARCHAR2(60) := '$2a$10$' || RPAD('a', 53, 'a');

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

  -- Permisos de un empleado como texto "A,B" (NULL si no tiene).
  FUNCTION permisos_texto(p_id IN NUMBER) RETURN VARCHAR2 IS
    v_c      SYS_REFCURSOR;
    v_codigo PERMISO.CODIGO%TYPE;
    v_res    VARCHAR2(4000);
  BEGIN
    v_c := PKG_EMPLEADO.permisos_de(p_id);
    LOOP
      FETCH v_c INTO v_codigo;
      EXIT WHEN v_c%NOTFOUND;
      v_res := v_res || CASE WHEN v_res IS NULL THEN NULL ELSE ',' END || v_codigo;
    END LOOP;
    CLOSE v_c;
    RETURN v_res;
  END permisos_texto;
BEGIN
  INSERT INTO EMPLEADO (NOMBRE, EMAIL, PASSWORD_HASH, ROL)
    VALUES ('Admin prueba', 'admin.prueba22@ejemplo.com', c_hash, 'ADMIN')
    RETURNING ID_EMPLEADO INTO v_admin;
  INSERT INTO EMPLEADO (NOMBRE, EMAIL, PASSWORD_HASH, ROL)
    VALUES ('Otro admin', 'admin2.prueba22@ejemplo.com', c_hash, 'ADMIN')
    RETURNING ID_EMPLEADO INTO v_admin2;
  INSERT INTO EMPLEADO (NOMBRE, EMAIL, PASSWORD_HASH, ROL, ACTIVO)
    VALUES ('Admin inactivo', 'admin.inactivo.prueba22@ejemplo.com', c_hash, 'ADMIN', 0)
    RETURNING ID_EMPLEADO INTO v_admin_inactivo;

  -- Crear trabajadores
  v_trab := PKG_EMPLEADO.crear_trabajador(v_admin, '  Ana Bodega ', '  Ana.Bodega22@Ejemplo.com ', c_hash);
  SELECT ROL, EMAIL, NOMBRE INTO v_rol, v_correo, v_nombre FROM EMPLEADO WHERE ID_EMPLEADO = v_trab;
  verificar('crear_trabajador crea un TRABAJADOR con correo en minusculas y nombre recortado',
            v_rol = 'TRABAJADOR' AND v_correo = 'ana.bodega22@ejemplo.com' AND v_nombre = 'Ana Bodega');

  esperar_error('crear_trabajador con un correo de otro empleado (otras mayusculas)',
    'DECLARE v NUMBER; BEGIN v := PKG_EMPLEADO.crear_trabajador(' || v_admin
    || ', ''X'', ''ANA.BODEGA22@ejemplo.com'', ''' || c_hash || '''); END;', -20300);

  INSERT INTO CLIENTE (ID_TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRE, EMAIL)
    VALUES ((SELECT ID_TIPO_DOCUMENTO FROM TIPO_DOCUMENTO WHERE CODIGO = 'CC'), '9220000001',
            'Cliente prueba', 'cliente.prueba22@ejemplo.com');
  esperar_error('crear_trabajador con el correo de un cliente',
    'DECLARE v NUMBER; BEGIN v := PKG_EMPLEADO.crear_trabajador(' || v_admin
    || ', ''X'', ''cliente.prueba22@ejemplo.com'', ''' || c_hash || '''); END;', -20300);

  esperar_error('un trabajador no puede crear trabajadores',
    'DECLARE v NUMBER; BEGIN v := PKG_EMPLEADO.crear_trabajador(' || v_trab
    || ', ''X'', ''x.prueba22@ejemplo.com'', ''' || c_hash || '''); END;', -20321);

  esperar_error('un admin inactivo no puede crear trabajadores',
    'DECLARE v NUMBER; BEGIN v := PKG_EMPLEADO.crear_trabajador(' || v_admin_inactivo
    || ', ''X'', ''y.prueba22@ejemplo.com'', ''' || c_hash || '''); END;', -20325);

  esperar_error('un empleado inexistente no puede crear trabajadores',
    'DECLARE v NUMBER; BEGIN v := PKG_EMPLEADO.crear_trabajador(-1, ''X'', ''z.prueba22@ejemplo.com'', '''
    || c_hash || '''); END;', -20325);

  v_trab2 := PKG_EMPLEADO.crear_trabajador(v_admin, 'Beto Ventas', 'beto.prueba22@ejemplo.com', c_hash);

  -- Permisos: asignar, idempotencia, listar
  v_correo := PKG_EMPLEADO.asignar_permiso(v_admin, v_trab, 'categorias');
  verificar('asignar_permiso devuelve el correo del trabajador', v_correo = 'ana.bodega22@ejemplo.com');
  v_correo := PKG_EMPLEADO.asignar_permiso(v_admin, v_trab, 'CATEGORIAS');
  SELECT COUNT(*) INTO v_n FROM EMPLEADO_PERMISO WHERE ID_EMPLEADO = v_trab;
  verificar('asignar_permiso es idempotente (una sola fila)', v_n = 1);

  verificar('permisos_de de un trabajador: solo los asignados', permisos_texto(v_trab) = 'CATEGORIAS');
  verificar('permisos_de de un trabajador sin permisos: ninguno', permisos_texto(v_trab2) IS NULL);

  SELECT COUNT(*) INTO v_n FROM PERMISO WHERE ACTIVO = 1;
  verificar('permisos_de de un admin: todos los activos (sin filas en EMPLEADO_PERMISO)',
            permisos_texto(v_admin) = 'CATEGORIAS,PRODUCTOS' AND v_n = 2);

  v_correo := PKG_EMPLEADO.asignar_permiso(v_admin, v_trab, 'PRODUCTOS');
  v_cursor := PKG_EMPLEADO.listar_trabajadores(v_admin);
  v_n := 0;
  LOOP
    FETCH v_cursor INTO v_id, v_nombre, v_correo, v_activo, v_fecha, v_permisos;
    EXIT WHEN v_cursor%NOTFOUND;
    IF v_id = v_trab THEN
      v_texto := v_permisos;
    END IF;
    IF v_id IN (v_admin, v_admin2, v_admin_inactivo) THEN
      v_n := v_n + 1;
    END IF;
  END LOOP;
  CLOSE v_cursor;
  verificar('listar_trabajadores trae los permisos separados por coma', v_texto = 'CATEGORIAS,PRODUCTOS');
  verificar('listar_trabajadores no incluye a los admins', v_n = 0);

  esperar_error('un trabajador no puede listar trabajadores',
    'DECLARE c SYS_REFCURSOR; BEGIN c := PKG_EMPLEADO.listar_trabajadores(' || v_trab || '); END;', -20321);

  -- Quitar permisos
  v_correo := PKG_EMPLEADO.quitar_permiso(v_admin, v_trab, 'PRODUCTOS');
  v_correo := PKG_EMPLEADO.quitar_permiso(v_admin, v_trab, 'PRODUCTOS');
  verificar('quitar_permiso es idempotente y deja los demas', permisos_texto(v_trab) = 'CATEGORIAS');

  esperar_error('asignar un permiso inexistente',
    'DECLARE v VARCHAR2(254); BEGIN v := PKG_EMPLEADO.asignar_permiso(' || v_admin || ', ' || v_trab
    || ', ''NO_EXISTE''); END;', -20324);

  -- PKG_PERMISOS
  PKG_PERMISOS.exigir_permiso(v_trab, 'CATEGORIAS');
  PKG_PERMISOS.exigir_permiso(v_admin, 'PRODUCTOS');
  esperar_error('exigir_permiso: el trabajador no tiene PRODUCTOS',
    'BEGIN PKG_PERMISOS.exigir_permiso(' || v_trab || ', ''PRODUCTOS''); END;', -20320);
  verificar('exigir_permiso: el trabajador con el permiso y el admin pasan', TRUE);

  -- Estado
  v_correo := PKG_EMPLEADO.cambiar_estado(v_admin, v_trab, 0);
  SELECT ACTIVO INTO v_activo FROM EMPLEADO WHERE ID_EMPLEADO = v_trab;
  verificar('cambiar_estado desactiva y devuelve el correo', v_activo = 0 AND v_correo = 'ana.bodega22@ejemplo.com');
  esperar_error('un trabajador desactivado ya no pasa exigir_permiso',
    'BEGIN PKG_PERMISOS.exigir_permiso(' || v_trab || ', ''CATEGORIAS''); END;', -20325);
  v_correo := PKG_EMPLEADO.cambiar_estado(v_admin, v_trab, 1);
  SELECT ACTIVO INTO v_activo FROM EMPLEADO WHERE ID_EMPLEADO = v_trab;
  verificar('cambiar_estado reactiva', v_activo = 1);

  -- Un admin solo gestiona trabajadores
  esperar_error('un admin no puede gestionar a otro admin',
    'DECLARE v VARCHAR2(254); BEGIN v := PKG_EMPLEADO.cambiar_estado(' || v_admin || ', ' || v_admin2
    || ', 0); END;', -20322);
  esperar_error('un admin no puede desactivarse a si mismo',
    'DECLARE v VARCHAR2(254); BEGIN v := PKG_EMPLEADO.cambiar_estado(' || v_admin || ', ' || v_admin
    || ', 0); END;', -20322);
  esperar_error('un admin no puede asignarse permisos a si mismo',
    'DECLARE v VARCHAR2(254); BEGIN v := PKG_EMPLEADO.asignar_permiso(' || v_admin || ', ' || v_admin
    || ', ''PRODUCTOS''); END;', -20322);
  esperar_error('gestionar un trabajador inexistente',
    'DECLARE v VARCHAR2(254); BEGIN v := PKG_EMPLEADO.cambiar_estado(' || v_admin
    || ', -1, 0); END;', -20323);

  -- Login
  v_cursor := PKG_EMPLEADO.credencial_por_email('ADMIN.prueba22@ejemplo.com');
  FETCH v_cursor INTO v_id, v_correo, v_hash, v_activo, v_rol;
  verificar('credencial_por_email devuelve el ROL', v_cursor%FOUND AND v_id = v_admin AND v_rol = 'ADMIN');
  CLOSE v_cursor;

  PKG_EMPLEADO.cambiar_password(v_trab, '$2a$10$' || RPAD('b', 53, 'b'));
  SELECT COUNT(*) INTO v_n FROM EMPLEADO WHERE ID_EMPLEADO = v_trab AND PASSWORD_HASH = '$2a$10$' || RPAD('b', 53, 'b');
  verificar('cambiar_password del empleado sigue funcionando', v_n = 1);

  ROLLBACK;
  DBMS_OUTPUT.PUT_LINE(v_pruebas || ' pruebas, ' || v_fallos || ' fallos');
  IF v_fallos > 0 THEN
    RAISE_APPLICATION_ERROR(-20005, v_fallos || ' pruebas fallaron: revisa las lineas FALLO.');
  END IF;
END;
/

-- ============================================================================
-- PRUEBAS RAPIDAS, PARTE B: categorias, productos y variantes (terminan con ROLLBACK).
-- Esperado: 39 lineas "OK" y al final "0 fallos".
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  TYPE t_fila IS RECORD (
    id_producto     NUMBER,
    id_categoria    NUMBER,
    id_impuesto     NUMBER,
    producto        VARCHAR2(200),
    descripcion     VARCHAR2(2000),
    producto_activo NUMBER,
    id_variante     NUMBER,
    sku             VARCHAR2(50),
    variante        VARCHAR2(150),
    precio_base     NUMBER,
    controla_stock  NUMBER,
    variante_activa NUMBER
  );

  v_pruebas      NUMBER := 0;
  v_fallos       NUMBER := 0;
  v_admin        NUMBER;
  v_trab_cat     NUMBER;   -- tiene CATEGORIAS
  v_trab_prod    NUMBER;   -- tiene PRODUCTOS
  v_trab_nada    NUMBER;   -- sin permisos
  v_inactivo     NUMBER;   -- trabajador desactivado con CATEGORIAS
  v_cat_a        NUMBER;
  v_cat_b        NUMBER;
  v_cat_c        NUMBER;
  v_cat_otra     NUMBER;
  v_cat_inactiva NUMBER;
  v_prod         NUMBER;
  v_prod2        NUMBER;
  v_var          NUMBER;
  v_impuesto     NUMBER;
  v_n            NUMBER;
  v_texto        VARCHAR2(4000);
  v_usuario      VARCHAR2(128);
  v_activo       NUMBER;
  v_sku          VARCHAR2(50);
  v_c1           SYS_REFCURSOR;
  v_f1           NUMBER;
  v_f2           NUMBER;
  v_f3           VARCHAR2(100);
  v_f4           NUMBER;
  v_f5           NUMBER;
  c_hash CONSTANT VARCHAR2(60) := '$2a$10$' || RPAD('a', 53, 'a');

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

  -- IDs de producto distintos de una pagina de listar_productos, como "id,id".
  FUNCTION ids_pagina(p_emp IN NUMBER, p_pag IN NUMBER, p_tam IN NUMBER) RETURN VARCHAR2 IS
    v_c   SYS_REFCURSOR;
    v_f   t_fila;
    v_res VARCHAR2(4000);
  BEGIN
    v_c := PKG_CATALOGO.listar_productos(p_emp, p_pag, p_tam);
    LOOP
      FETCH v_c INTO v_f;
      EXIT WHEN v_c%NOTFOUND;
      IF v_res IS NULL OR INSTR(',' || v_res || ',', ',' || v_f.id_producto || ',') = 0 THEN
        v_res := v_res || CASE WHEN v_res IS NULL THEN NULL ELSE ',' END || v_f.id_producto;
      END IF;
    END LOOP;
    CLOSE v_c;
    RETURN v_res;
  END ids_pagina;

  -- PRODUCTO_ACTIVO de un producto buscandolo en el listado (NULL si no sale).
  FUNCTION activo_en_listado(p_emp IN NUMBER, p_id IN NUMBER) RETURN NUMBER IS
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
          RETURN v_f.producto_activo;
        END IF;
      END LOOP;
      CLOSE v_c;
      EXIT WHEN NOT v_hay;
    END LOOP;
    RETURN NULL;
  END activo_en_listado;

  PROCEDURE nuevo_empleado(p_nombre IN VARCHAR2, p_correo IN VARCHAR2, p_rol IN VARCHAR2,
                           p_permiso IN VARCHAR2, p_activo IN NUMBER, p_id OUT NUMBER) IS
  BEGIN
    INSERT INTO EMPLEADO (NOMBRE, EMAIL, PASSWORD_HASH, ROL, ACTIVO)
      VALUES (p_nombre, p_correo, c_hash, p_rol, p_activo)
      RETURNING ID_EMPLEADO INTO p_id;
    IF p_permiso IS NOT NULL THEN
      INSERT INTO EMPLEADO_PERMISO (ID_EMPLEADO, ID_PERMISO, ID_ADMIN_ASIGNO)
        VALUES (p_id, (SELECT ID_PERMISO FROM PERMISO WHERE CODIGO = p_permiso), v_admin);
    END IF;
  END nuevo_empleado;
BEGIN
  nuevo_empleado('Admin catalogo', 'admin.cat.prueba22@ejemplo.com', 'ADMIN', NULL, 1, v_admin);
  nuevo_empleado('Trab categorias', 'trab.cat.prueba22@ejemplo.com', 'TRABAJADOR', 'CATEGORIAS', 1, v_trab_cat);
  nuevo_empleado('Trab productos', 'trab.prod.prueba22@ejemplo.com', 'TRABAJADOR', 'PRODUCTOS', 1, v_trab_prod);
  nuevo_empleado('Trab sin permisos', 'trab.nada.prueba22@ejemplo.com', 'TRABAJADOR', NULL, 1, v_trab_nada);
  nuevo_empleado('Trab inactivo', 'trab.inactivo.prueba22@ejemplo.com', 'TRABAJADOR', 'CATEGORIAS', 0, v_inactivo);
  SELECT ID_IMPUESTO INTO v_impuesto FROM IMPUESTO WHERE NOMBRE = 'IVA 19%';

  -- Categorias: permisos
  esperar_error('un trabajador con PRODUCTOS (sin CATEGORIAS) no crea categorias',
    'DECLARE v NUMBER; BEGIN v := PKG_CATALOGO.crear_categoria(' || v_trab_prod
    || ', ''Cat prueba22'', NULL); END;', -20320);
  esperar_error('un trabajador desactivado no crea categorias',
    'DECLARE v NUMBER; BEGIN v := PKG_CATALOGO.crear_categoria(' || v_inactivo
    || ', ''Cat prueba22'', NULL); END;', -20325);

  v_cat_a := PKG_CATALOGO.crear_categoria(v_trab_cat, '  Portatiles prueba22 ', NULL);
  SELECT NOMBRE INTO v_texto FROM CATEGORIA WHERE ID_CATEGORIA = v_cat_a;
  verificar('un trabajador con CATEGORIAS crea una categoria raiz (nombre recortado)',
            v_cat_a > 0 AND v_texto = 'Portatiles prueba22');

  v_cat_c := PKG_CATALOGO.crear_categoria(v_admin, 'Perifericos prueba22', NULL);
  verificar('el admin crea categorias sin filas en EMPLEADO_PERMISO', v_cat_c > 0);

  v_cat_b := PKG_CATALOGO.crear_categoria(v_trab_cat, 'Gamer prueba22', v_cat_a);
  SELECT ID_CATEGORIA_PADRE INTO v_n FROM CATEGORIA WHERE ID_CATEGORIA = v_cat_b;
  verificar('crear una categoria hija con padre valido', v_n = v_cat_a);

  esperar_error('crear con un padre inexistente',
    'DECLARE v NUMBER; BEGIN v := PKG_CATALOGO.crear_categoria(' || v_admin
    || ', ''Huerfana prueba22'', -1); END;', -20331);
  esperar_error('nombre repetido bajo el mismo padre (raiz)',
    'DECLARE v NUMBER; BEGIN v := PKG_CATALOGO.crear_categoria(' || v_admin
    || ', ''Portatiles prueba22'', NULL); END;', -20332);
  esperar_error('nombre repetido bajo el mismo padre (hija)',
    'DECLARE v NUMBER; BEGIN v := PKG_CATALOGO.crear_categoria(' || v_admin
    || ', ''Gamer prueba22'', ' || v_cat_a || '); END;', -20332);

  v_cat_otra := PKG_CATALOGO.crear_categoria(v_admin, 'Gamer prueba22', v_cat_c);
  verificar('el mismo nombre bajo otro padre si se permite', v_cat_otra > 0);

  -- Categorias: ciclos y edicion
  esperar_error('un ciclo A -> B -> A (A dentro de su propia hija)',
    'BEGIN PKG_CATALOGO.actualizar_categoria(' || v_admin || ', ' || v_cat_a || ', ''Portatiles prueba22'', '
    || v_cat_b || '); END;', -20331);
  esperar_error('una categoria no puede ser su propio padre',
    'BEGIN PKG_CATALOGO.actualizar_categoria(' || v_admin || ', ' || v_cat_a || ', ''Portatiles prueba22'', '
    || v_cat_a || '); END;', -20331);
  esperar_error('actualizar una categoria inexistente',
    'BEGIN PKG_CATALOGO.actualizar_categoria(' || v_admin || ', -1, ''X'', NULL); END;', -20330);

  PKG_CATALOGO.actualizar_categoria(v_trab_cat, v_cat_b, 'Gamer renombrada prueba22', v_cat_c);
  SELECT COUNT(*) INTO v_n FROM CATEGORIA
   WHERE ID_CATEGORIA = v_cat_b AND ID_CATEGORIA_PADRE = v_cat_c AND NOMBRE = 'Gamer renombrada prueba22';
  verificar('actualizar_categoria renombra y cambia de padre', v_n = 1);

  v_cat_inactiva := PKG_CATALOGO.crear_categoria(v_admin, 'Inactiva prueba22', NULL);
  PKG_CATALOGO.cambiar_estado_categoria(v_admin, v_cat_inactiva, 0);
  SELECT ACTIVO INTO v_activo FROM CATEGORIA WHERE ID_CATEGORIA = v_cat_inactiva;
  verificar('cambiar_estado_categoria desactiva', v_activo = 0);
  esperar_error('cambiar el estado de una categoria inexistente',
    'BEGIN PKG_CATALOGO.cambiar_estado_categoria(' || v_admin || ', -1, 0); END;', -20330);
  esperar_error('una categoria inactiva no sirve de padre',
    'DECLARE v NUMBER; BEGIN v := PKG_CATALOGO.crear_categoria(' || v_admin
    || ', ''Hija de inactiva'', ' || v_cat_inactiva || '); END;', -20331);

  -- Productos
  esperar_error('un trabajador con CATEGORIAS (sin PRODUCTOS) no crea productos',
    'DECLARE v NUMBER; BEGIN v := PKG_CATALOGO.crear_producto(' || v_trab_cat || ', ' || v_cat_a || ', '
    || v_impuesto || ', ''Portatil X'', NULL); END;', -20320);
  esperar_error('crear un producto en una categoria inactiva',
    'DECLARE v NUMBER; BEGIN v := PKG_CATALOGO.crear_producto(' || v_admin || ', ' || v_cat_inactiva || ', '
    || v_impuesto || ', ''Portatil X'', NULL); END;', -20330);
  esperar_error('crear un producto con un impuesto inexistente',
    'DECLARE v NUMBER; BEGIN v := PKG_CATALOGO.crear_producto(' || v_admin || ', ' || v_cat_a
    || ', -1, ''Portatil X'', NULL); END;', -20334);

  v_prod := PKG_CATALOGO.crear_producto(v_trab_prod, v_cat_a, v_impuesto, '  Portatil X prueba22 ', 'Descripcion');
  SELECT NOMBRE INTO v_texto FROM PRODUCTO WHERE ID_PRODUCTO = v_prod;
  verificar('un trabajador con PRODUCTOS crea un producto', v_prod > 0 AND v_texto = 'Portatil X prueba22');

  v_prod2 := PKG_CATALOGO.crear_producto(v_admin, v_cat_c, v_impuesto, 'Mouse prueba22', NULL);

  -- Variantes
  v_var := PKG_CATALOGO.agregar_variante(v_trab_prod, v_prod, '  lap-p22-001 ', 'Portatil 16GB', 2500000, 1);
  SELECT SKU INTO v_sku FROM VARIANTE WHERE ID_VARIANTE = v_var;
  verificar('agregar_variante normaliza el SKU a mayusculas y sin espacios', v_sku = 'LAP-P22-001');

  SELECT COUNT(*) INTO v_n FROM PRECIO_HIST WHERE ID_VARIANTE = v_var AND PRECIO_ANTERIOR IS NULL;
  verificar('el precio inicial queda en PRECIO_HIST por el trigger existente', v_n = 1);

  SELECT COUNT(*) INTO v_n FROM STOCK WHERE ID_VARIANTE = v_var;
  verificar('agregar_variante no crea fila de STOCK (la crea la primera entrada de inventario)', v_n = 0);

  esperar_error('SKU repetido (otras mayusculas)',
    'DECLARE v NUMBER; BEGIN v := PKG_CATALOGO.agregar_variante(' || v_admin || ', ' || v_prod2
    || ', ''lap-p22-001'', ''Otra'', 10, 0); END;', -20335);
  SELECT COUNT(*) INTO v_n FROM VARIANTE WHERE ID_PRODUCTO = v_prod2;
  verificar('un SKU repetido no deja la variante a medias', v_n = 0);

  esperar_error('precio negativo',
    'DECLARE v NUMBER; BEGIN v := PKG_CATALOGO.agregar_variante(' || v_admin || ', ' || v_prod2
    || ', ''MOU-P22-001'', ''Mouse'', -1, 0); END;', -20337);
  esperar_error('precio con 3 decimales',
    'DECLARE v NUMBER; BEGIN v := PKG_CATALOGO.agregar_variante(' || v_admin || ', ' || v_prod2
    || ', ''MOU-P22-001'', ''Mouse'', 10.123, 0); END;', -20337);
  esperar_error('agregar una variante a un producto inexistente',
    'DECLARE v NUMBER; BEGIN v := PKG_CATALOGO.agregar_variante(' || v_admin
    || ', -1, ''MOU-P22-002'', ''Mouse'', 10, 0); END;', -20333);

  -- Cambio de precio: queda en el historial con el autor fijado por la aplicacion
  DBMS_SESSION.SET_IDENTIFIER('emp:prueba22');
  PKG_CATALOGO.actualizar_variante(v_trab_prod, v_var, 'Portatil 16GB v2', 2600000.50);
  DBMS_SESSION.CLEAR_IDENTIFIER;
  SELECT COUNT(*) INTO v_n FROM PRECIO_HIST WHERE ID_VARIANTE = v_var;
  SELECT USUARIO INTO v_usuario FROM PRECIO_HIST WHERE ID_VARIANTE = v_var AND PRECIO_NUEVO = 2600000.50;
  verificar('actualizar_variante cambia el precio y el historial guarda al autor',
            v_n = 2 AND v_usuario = 'emp:prueba22');

  esperar_error('actualizar una variante inexistente',
    'BEGIN PKG_CATALOGO.actualizar_variante(' || v_admin || ', -1, ''X'', 10); END;', -20336);
  esperar_error('actualizar un producto inexistente',
    'BEGIN PKG_CATALOGO.actualizar_producto(' || v_admin || ', -1, ' || v_cat_a || ', ' || v_impuesto
    || ', ''X'', NULL); END;', -20333);

  -- Estado
  PKG_CATALOGO.cambiar_estado_variante(v_admin, v_var, 0);
  SELECT ACTIVO INTO v_activo FROM VARIANTE WHERE ID_VARIANTE = v_var;
  verificar('cambiar_estado_variante desactiva', v_activo = 0);
  esperar_error('cambiar el estado de una variante inexistente',
    'BEGIN PKG_CATALOGO.cambiar_estado_variante(' || v_admin || ', -1, 0); END;', -20336);

  PKG_CATALOGO.cambiar_estado_producto(v_admin, v_prod, 0);
  verificar('listar_productos incluye los productos inactivos', activo_en_listado(v_trab_nada, v_prod) = 0);
  esperar_error('cambiar el estado de un producto inexistente',
    'BEGIN PKG_CATALOGO.cambiar_estado_producto(' || v_admin || ', -1, 0); END;', -20333);

  -- Listas
  v_texto := ids_pagina(v_trab_nada, 0, 1);
  verificar('listar_productos pagina por producto (tamano 1 = un solo producto)',
            v_texto IS NOT NULL AND INSTR(v_texto, ',') = 0
            AND v_texto <> NVL(ids_pagina(v_trab_nada, 1, 1), 'x'));

  v_c1 := PKG_CATALOGO.listar_categorias(v_trab_nada);
  v_n := 0;
  LOOP
    FETCH v_c1 INTO v_f1, v_f2, v_f3, v_f4;
    EXIT WHEN v_c1%NOTFOUND;
    v_n := v_n + 1;
  END LOOP;
  CLOSE v_c1;
  verificar('un trabajador sin permisos puede listar categorias (con inactivas)', v_n >= 5);

  v_c1 := PKG_CATALOGO.listar_impuestos(v_trab_nada);
  FETCH v_c1 INTO v_f1, v_f3, v_f4, v_f5;
  verificar('un trabajador sin permisos puede listar impuestos', v_c1%FOUND);
  CLOSE v_c1;

  esperar_error('un trabajador desactivado no puede listar',
    'DECLARE c SYS_REFCURSOR; BEGIN c := PKG_CATALOGO.listar_categorias(' || v_inactivo || '); END;', -20325);

  ROLLBACK;
  DBMS_OUTPUT.PUT_LINE(v_pruebas || ' pruebas, ' || v_fallos || ' fallos');
  IF v_fallos > 0 THEN
    RAISE_APPLICATION_ERROR(-20005, v_fallos || ' pruebas fallaron: revisa las lineas FALLO.');
  END IF;
END;
/

-- ============================================================================
-- ALTA DEL PRIMER ADMIN (ejemplo; ajusta los datos y quita los "--")
-- Sin un admin nadie puede crear trabajadores ni delegar permisos desde la API.
-- El hash es un BCrypt de la contrasena inicial (60 caracteres, empieza por $2a$).
-- Pidele a Claude que te genere uno, o usa cualquier herramienta BCrypt (costo 10).
-- Puede cambiarla despues con PUT /api/v1/auth/password.
-- ============================================================================
-- INSERT INTO EMPLEADO (NOMBRE, EMAIL, PASSWORD_HASH, ROL)
-- VALUES ('Nombre Apellido', 'admin@ecomers.co', '<hash BCrypt de 60 caracteres>', 'ADMIN');
-- COMMIT;
