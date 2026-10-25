-- ============================================================================
-- 21_auth_cuentas.sql
-- BORRADOR PARA REVISAR: no se ha ejecutado contra ninguna base. Leelo completo,
-- ajustalo a tu criterio y ejecutalo tu. La API (carpeta "api") espera
-- exactamente estas firmas: contrato en base-de-datos/auth-contrato-base-de-datos.md.
--
-- Cuentas para el login propio de la API: contrasenas de clientes y empleados.
--   * CLIENTE.PASSWORD_HASH      (columna nueva)
--   * tabla EMPLEADO             (nueva)
--   * PKG_CLIENTE                (registrar, credencial_por_email, cambiar_password)
--   * PKG_EMPLEADO               (credencial_por_email, cambiar_password)
--
-- Requiere haber ejecutado antes los scripts 01 y 03 (y 20, para los roles).
--
-- COMO EJECUTARLO
--   1. Conectate como ECOMERS al servicio XEPDB1 (SELECT USER FROM dual).
--   2. Ejecuta como script completo (F5 en SQL Developer).
--   3. Se puede volver a ejecutar: la columna y la tabla se crean solo si faltan,
--      y los paquetes y triggers son CREATE OR REPLACE.
--   4. Al final salen las pruebas rapidas: una linea "OK" por cada una. Crean
--      datos temporales y los deshacen con ROLLBACK: no quedan en la base.
--   5. Para deshacerlo:
--        DROP PACKAGE PKG_CLIENTE;  DROP PACKAGE PKG_EMPLEADO;  DROP TABLE EMPLEADO PURGE;
--        ALTER TABLE CLIENTE DROP CONSTRAINT CK_CLIENTE_PASSWORD_HASH;
--        ALTER TABLE CLIENTE DROP COLUMN PASSWORD_HASH;
--
-- DECISIONES DE DISENO
--   * La base guarda SOLO el hash BCrypt (60 caracteres, empieza por $2): la
--     contrasena en claro nunca llega a Oracle. El hash lo calcula la API. Un CHECK
--     impide guardar por error una contrasena en claro.
--   * Esto cambia lo que decia el script 03 ("la autenticacion vive fuera de la
--     BD"): ahora la BD guarda el hash. Si prefieres otra tabla (por ejemplo
--     CLIENTE_CREDENCIAL), solo hay que mantener las firmas de los paquetes.
--   * PASSWORD_HASH queda NULL-able para no romper los clientes que ya existen.
--     Un cliente sin hash no puede iniciar sesion: credencial_por_email no lo
--     devuelve. registrar siempre lo guarda. Cuando todos los clientes tengan
--     hash, se puede hacer NOT NULL.
--   * Los correos son unicos sin distinguir mayusculas (UX_CLIENTE_EMAIL y
--     UX_EMPLEADO_EMAIL), y ademas registrar rechaza un correo que ya sea de un
--     empleado: asi el login (que busca primero entre clientes) no confunde
--     cuentas. La API guarda los correos en minusculas.
--   * registrar NO consulta "existe?" antes de insertar: deja que los indices
--     unicos decidan y traduce su error. Asi dos registros simultaneos no se
--     cuelan (no hay carrera entre consultar y guardar).
--   * Los empleados los da de alta el administrador de la base (ver el ejemplo
--     comentado al final); la API no crea empleados.
--   * Cada procedimiento que escribe es atomico (SAVEPOINT + ROLLBACK TO) y no
--     hace COMMIT: lo hace la API (OracleDb), como en el resto de paquetes.
--   * Errores (bloque -20300 a -20319):
--       -20300 el correo ya esta registrado
--       -20301 el documento (tipo y numero) ya esta registrado
--       -20302 el tipo de documento no existe o esta inactivo
--       -20304 la cuenta no existe (cambiar_password)
-- ============================================================================

SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

PROMPT >> 21_auth_cuentas.sql: comprobando requisitos

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
   WHERE table_name IN ('CLIENTE', 'TIPO_DOCUMENTO');
  IF v_existentes < 2 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'Faltan tablas previas: ejecuta antes el script 03.');
  END IF;
END;
/

-- ----------------------------------------------------------------------------
-- 1. CLIENTE.PASSWORD_HASH
-- ----------------------------------------------------------------------------
DECLARE
  v_n NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_n
    FROM user_tab_columns
   WHERE table_name = 'CLIENTE' AND column_name = 'PASSWORD_HASH';
  IF v_n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE CLIENTE ADD (PASSWORD_HASH VARCHAR2(100))';
  END IF;

  SELECT COUNT(*) INTO v_n
    FROM user_constraints
   WHERE table_name = 'CLIENTE' AND constraint_name = 'CK_CLIENTE_PASSWORD_HASH';
  IF v_n = 0 THEN
    EXECUTE IMMEDIATE
      'ALTER TABLE CLIENTE ADD CONSTRAINT CK_CLIENTE_PASSWORD_HASH '
      || 'CHECK (PASSWORD_HASH IS NULL OR (LENGTH(PASSWORD_HASH) = 60 AND PASSWORD_HASH LIKE ''$2%''))';
  END IF;
END;
/

COMMENT ON COLUMN CLIENTE.PASSWORD_HASH IS
  'Hash BCrypt de la contrasena (60 caracteres). La calcula la API; nunca se guarda en claro.';

PROMPT >> CLIENTE.PASSWORD_HASH lista

-- ----------------------------------------------------------------------------
-- 2. EMPLEADO
-- ----------------------------------------------------------------------------
DECLARE
  v_n NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_n FROM user_tables WHERE table_name = 'EMPLEADO';
  IF v_n = 0 THEN
    EXECUTE IMMEDIATE
      'CREATE TABLE EMPLEADO ('
      || ' ID_EMPLEADO         NUMBER(12) GENERATED ALWAYS AS IDENTITY,'
      || ' NOMBRE              VARCHAR2(200) NOT NULL,'
      || ' EMAIL               VARCHAR2(254) NOT NULL,'
      || ' PASSWORD_HASH       VARCHAR2(100) NOT NULL,'
      || ' ACTIVO              NUMBER(1)     DEFAULT 1 NOT NULL,'
      || ' FECHA_CREACION      TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,'
      || ' FECHA_MODIFICACION  TIMESTAMP,'
      || ' CONSTRAINT PK_EMPLEADO               PRIMARY KEY (ID_EMPLEADO),'
      || ' CONSTRAINT CK_EMPLEADO_EMAIL         CHECK (INSTR(EMAIL, ''@'') > 1),'
      || ' CONSTRAINT CK_EMPLEADO_ACTIVO        CHECK (ACTIVO IN (0, 1)),'
      || ' CONSTRAINT CK_EMPLEADO_PASSWORD_HASH CHECK (LENGTH(PASSWORD_HASH) = 60 AND PASSWORD_HASH LIKE ''$2%'')'
      || ')';
    -- Correo unico sin distinguir mayusculas.
    EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX UX_EMPLEADO_EMAIL ON EMPLEADO (UPPER(EMAIL))';
  END IF;
END;
/

COMMENT ON TABLE EMPLEADO IS 'Empleados del backoffice que inician sesion en la API. Los da de alta el administrador de la base.';

CREATE OR REPLACE TRIGGER TRG_EMPLEADO_FMOD
  BEFORE UPDATE ON EMPLEADO FOR EACH ROW
BEGIN
  :NEW.FECHA_MODIFICACION := SYSTIMESTAMP;
END;
/

PROMPT >> EMPLEADO lista

-- ----------------------------------------------------------------------------
-- 3. PKG_CLIENTE
-- ----------------------------------------------------------------------------
CREATE OR REPLACE PACKAGE PKG_CLIENTE
  AUTHID DEFINER
AS
  -- Crea un cliente con su hash BCrypt y devuelve su ID_CLIENTE.
  -- p_tipo_documento es el CODIGO (CC, CE, NIT, PAS), sin distinguir mayusculas.
  -- El correo se guarda en minusculas.
  -- Errores: -20300 (correo repetido, tambien si es de un empleado),
  --          -20301 (documento repetido), -20302 (tipo de documento invalido).
  FUNCTION registrar(
    p_nombre           IN VARCHAR2,
    p_email            IN VARCHAR2,
    p_tipo_documento   IN VARCHAR2,
    p_numero_documento IN VARCHAR2,
    p_telefono         IN VARCHAR2,
    p_password_hash    IN VARCHAR2
  ) RETURN NUMBER;

  -- Credencial para el login: ID_CLIENTE, EMAIL, PASSWORD_HASH y ACTIVO (0 o 1).
  -- Sin distinguir mayusculas. Cero filas si el correo no existe o si el cliente
  -- todavia no tiene contrasena.
  FUNCTION credencial_por_email(p_email IN VARCHAR2) RETURN SYS_REFCURSOR;

  -- Cambia el hash de un cliente. Error -20304 si el cliente no existe.
  PROCEDURE cambiar_password(
    p_id_cliente    IN NUMBER,
    p_password_hash IN VARCHAR2
  );
END PKG_CLIENTE;
/
SHOW ERRORS PACKAGE PKG_CLIENTE
PROMPT >> PKG_CLIENTE: especificacion creada

CREATE OR REPLACE PACKAGE BODY PKG_CLIENTE
AS
  FUNCTION registrar(
    p_nombre           IN VARCHAR2,
    p_email            IN VARCHAR2,
    p_tipo_documento   IN VARCHAR2,
    p_numero_documento IN VARCHAR2,
    p_telefono         IN VARCHAR2,
    p_password_hash    IN VARCHAR2
  ) RETURN NUMBER IS
    v_email       CLIENTE.EMAIL%TYPE := LOWER(TRIM(p_email));
    v_id_tipo_doc TIPO_DOCUMENTO.ID_TIPO_DOCUMENTO%TYPE;
    v_id_cliente  CLIENTE.ID_CLIENTE%TYPE;
    v_empleados   NUMBER;
  BEGIN
    SAVEPOINT sp_registrar_cliente;

    BEGIN
      SELECT ID_TIPO_DOCUMENTO
        INTO v_id_tipo_doc
        FROM TIPO_DOCUMENTO
       WHERE CODIGO = UPPER(TRIM(p_tipo_documento))
         AND ACTIVO = 1;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(-20302,
          'El tipo de documento ' || p_tipo_documento || ' no existe o esta inactivo.');
    END;

    -- Un empleado con ese correo tambien cuenta como "ya registrado".
    SELECT COUNT(*)
      INTO v_empleados
      FROM EMPLEADO
     WHERE UPPER(EMAIL) = UPPER(v_email);
    IF v_empleados > 0 THEN
      RAISE_APPLICATION_ERROR(-20300, 'Ya existe una cuenta con el correo ' || v_email || '.');
    END IF;

    BEGIN
      INSERT INTO CLIENTE (ID_TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRE, EMAIL, TELEFONO, PASSWORD_HASH)
      VALUES (v_id_tipo_doc, TRIM(p_numero_documento), TRIM(p_nombre), v_email, p_telefono, p_password_hash)
      RETURNING ID_CLIENTE INTO v_id_cliente;
    EXCEPTION
      WHEN DUP_VAL_ON_INDEX THEN
        -- El indice que fallo decide cual de los dos errores es.
        IF INSTR(UPPER(SQLERRM), 'UQ_CLIENTE_DOCUMENTO') > 0 THEN
          RAISE_APPLICATION_ERROR(-20301,
            'Ya existe un cliente con el documento ' || UPPER(TRIM(p_tipo_documento))
            || ' ' || TRIM(p_numero_documento) || '.');
        END IF;
        RAISE_APPLICATION_ERROR(-20300, 'Ya existe una cuenta con el correo ' || v_email || '.');
    END;

    RETURN v_id_cliente;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_registrar_cliente;
      RAISE;
  END registrar;

  FUNCTION credencial_por_email(p_email IN VARCHAR2) RETURN SYS_REFCURSOR IS
    v_cursor SYS_REFCURSOR;
  BEGIN
    OPEN v_cursor FOR
      SELECT ID_CLIENTE, EMAIL, PASSWORD_HASH, ACTIVO
        FROM CLIENTE
       WHERE UPPER(EMAIL) = UPPER(TRIM(p_email))
         AND PASSWORD_HASH IS NOT NULL;
    RETURN v_cursor;
  END credencial_por_email;

  PROCEDURE cambiar_password(
    p_id_cliente    IN NUMBER,
    p_password_hash IN VARCHAR2
  ) IS
  BEGIN
    SAVEPOINT sp_cambiar_password_cliente;

    UPDATE CLIENTE
       SET PASSWORD_HASH = p_password_hash
     WHERE ID_CLIENTE = p_id_cliente;
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20304, 'El cliente ' || p_id_cliente || ' no existe.');
    END IF;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK TO SAVEPOINT sp_cambiar_password_cliente;
      RAISE;
  END cambiar_password;
END PKG_CLIENTE;
/
SHOW ERRORS PACKAGE BODY PKG_CLIENTE
PROMPT >> PKG_CLIENTE: cuerpo creado

-- ----------------------------------------------------------------------------
-- 4. PKG_EMPLEADO
-- ----------------------------------------------------------------------------
CREATE OR REPLACE PACKAGE PKG_EMPLEADO
  AUTHID DEFINER
AS
  -- Credencial para el login: ID_EMPLEADO, EMAIL, PASSWORD_HASH y ACTIVO (0 o 1).
  -- Sin distinguir mayusculas. Cero filas si el correo no existe.
  FUNCTION credencial_por_email(p_email IN VARCHAR2) RETURN SYS_REFCURSOR;

  -- Cambia el hash de un empleado. Error -20304 si el empleado no existe.
  PROCEDURE cambiar_password(
    p_id_empleado   IN NUMBER,
    p_password_hash IN VARCHAR2
  );
END PKG_EMPLEADO;
/
SHOW ERRORS PACKAGE PKG_EMPLEADO
PROMPT >> PKG_EMPLEADO: especificacion creada

CREATE OR REPLACE PACKAGE BODY PKG_EMPLEADO
AS
  FUNCTION credencial_por_email(p_email IN VARCHAR2) RETURN SYS_REFCURSOR IS
    v_cursor SYS_REFCURSOR;
  BEGIN
    OPEN v_cursor FOR
      SELECT ID_EMPLEADO, EMAIL, PASSWORD_HASH, ACTIVO
        FROM EMPLEADO
       WHERE UPPER(EMAIL) = UPPER(TRIM(p_email));
    RETURN v_cursor;
  END credencial_por_email;

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
   WHERE name IN ('PKG_CLIENTE', 'PKG_EMPLEADO');
  IF v_errores > 0 THEN
    RAISE_APPLICATION_ERROR(-20004,
      'PKG_CLIENTE o PKG_EMPLEADO compilaron con errores: consulta USER_ERRORS.');
  END IF;
END;
/
PROMPT >> paquetes compilados sin errores

-- ----------------------------------------------------------------------------
-- 5. Permisos (se pueden mover al script 20 para tenerlos todos juntos)
-- ----------------------------------------------------------------------------
GRANT EXECUTE ON PKG_CLIENTE  TO ROL_ECOMERS_CLIENTE;
GRANT EXECUTE ON PKG_EMPLEADO TO ROL_ECOMERS_EMPLEADO;

PROMPT >> permisos otorgados

-- ============================================================================
-- VERIFICACION (resultados esperados entre parentesis)
-- ============================================================================
SELECT object_name, object_type, status
  FROM user_objects
 WHERE object_name IN ('PKG_CLIENTE', 'PKG_EMPLEADO', 'EMPLEADO')
 ORDER BY object_name, object_type;     -- 2 PACKAGE VALID, 2 PACKAGE BODY VALID, 1 TABLE VALID

SELECT table_name, grantee, privilege
  FROM user_tab_privs_made
 WHERE table_name IN ('PKG_CLIENTE', 'PKG_EMPLEADO')
 ORDER BY table_name;                   -- PKG_CLIENTE -> ROL_ECOMERS_CLIENTE, PKG_EMPLEADO -> ROL_ECOMERS_EMPLEADO

-- ============================================================================
-- PRUEBAS RAPIDAS (terminan con ROLLBACK: no dejan datos).
-- Esperado: 17 lineas "OK" y al final "0 fallos".
-- ============================================================================
SET SERVEROUTPUT ON

DECLARE
  v_pruebas  NUMBER := 0;
  v_fallos   NUMBER := 0;
  v_cliente  NUMBER;
  v_cliente2 NUMBER;
  v_empleado NUMBER;
  v_cursor   SYS_REFCURSOR;
  v_id       NUMBER;
  v_correo   VARCHAR2(254);
  v_hash     VARCHAR2(100);
  v_activo   NUMBER;
  v_n        NUMBER;
  -- Un hash con la forma de BCrypt: '$2a$10$' (7) + 53 caracteres = 60.
  c_hash   CONSTANT VARCHAR2(60) := '$2a$10$' || RPAD('a', 53, 'a');
  c_hash_2 CONSTANT VARCHAR2(60) := '$2a$10$' || RPAD('b', 53, 'b');

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

  FUNCTION registrar_bloque(p_email IN VARCHAR2, p_tipo IN VARCHAR2, p_numero IN VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    RETURN 'DECLARE v NUMBER; BEGIN v := PKG_CLIENTE.registrar(''Prueba'', '''
           || p_email || ''', ''' || p_tipo || ''', ''' || p_numero || ''', ''3001234567'', '''
           || c_hash || '''); END;';
  END registrar_bloque;
BEGIN
  -- Registro
  v_cliente := PKG_CLIENTE.registrar('Ana Perez', '  Ana.Prueba21@Ejemplo.com ', 'cc', '9100000001', '3001234567', c_hash);
  verificar('registrar devuelve el ID del cliente', v_cliente IS NOT NULL AND v_cliente > 0);

  SELECT EMAIL, PASSWORD_HASH INTO v_correo, v_hash FROM CLIENTE WHERE ID_CLIENTE = v_cliente;
  verificar('el correo se guarda en minusculas y sin espacios', v_correo = 'ana.prueba21@ejemplo.com');
  verificar('se guarda el hash tal cual', v_hash = c_hash);

  esperar_error('correo repetido (otras mayusculas)',
    registrar_bloque('ANA.PRUEBA21@EJEMPLO.COM', 'CC', '9100000099'), -20300);
  esperar_error('documento repetido',
    registrar_bloque('otra.prueba21@ejemplo.com', 'CC', '9100000001'), -20301);
  esperar_error('tipo de documento inexistente',
    registrar_bloque('otra.prueba21@ejemplo.com', 'ZZ', '9100000002'), -20302);

  v_cliente2 := PKG_CLIENTE.registrar('Luis Gomez', 'luis.prueba21@ejemplo.com', 'NIT', '9100000001', '3109876543', c_hash_2);
  verificar('mismo numero de documento con otro tipo es otro cliente', v_cliente2 <> v_cliente);

  esperar_error('un hash que no es BCrypt se rechaza (CHECK)',
    'DECLARE v NUMBER; BEGIN v := PKG_CLIENTE.registrar(''X'', ''x.prueba21@ejemplo.com'', ''CC'', ''9100000004'', ''3001234567'', ''clave-en-claro''); END;',
    -2290);

  -- Credencial del cliente
  v_cursor := PKG_CLIENTE.credencial_por_email('ANA.PRUEBA21@ejemplo.com');
  FETCH v_cursor INTO v_id, v_correo, v_hash, v_activo;
  verificar('credencial_por_email encuentra al cliente sin distinguir mayusculas',
            v_cursor%FOUND AND v_id = v_cliente AND v_hash = c_hash AND v_activo = 1);
  CLOSE v_cursor;

  v_cursor := PKG_CLIENTE.credencial_por_email('no.existe.prueba21@ejemplo.com');
  FETCH v_cursor INTO v_id, v_correo, v_hash, v_activo;
  verificar('un correo inexistente no devuelve filas', v_cursor%NOTFOUND);
  CLOSE v_cursor;

  -- Un cliente que ya existia (sin hash) no puede iniciar sesion
  INSERT INTO CLIENTE (ID_TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRE, EMAIL)
    VALUES ((SELECT ID_TIPO_DOCUMENTO FROM TIPO_DOCUMENTO WHERE CODIGO = 'CC'), '9100000005',
            'Cliente sin clave', 'sin.clave.prueba21@ejemplo.com');
  v_cursor := PKG_CLIENTE.credencial_por_email('sin.clave.prueba21@ejemplo.com');
  FETCH v_cursor INTO v_id, v_correo, v_hash, v_activo;
  verificar('un cliente sin contrasena no aparece en el login', v_cursor%NOTFOUND);
  CLOSE v_cursor;

  -- Cambio de contrasena
  PKG_CLIENTE.cambiar_password(v_cliente, c_hash_2);
  SELECT PASSWORD_HASH INTO v_hash FROM CLIENTE WHERE ID_CLIENTE = v_cliente;
  verificar('cambiar_password guarda el hash nuevo', v_hash = c_hash_2);
  esperar_error('cambiar la clave de un cliente inexistente',
    'BEGIN PKG_CLIENTE.cambiar_password(-1, ''' || c_hash || '''); END;', -20304);

  -- Empleados
  INSERT INTO EMPLEADO (NOMBRE, EMAIL, PASSWORD_HASH)
    VALUES ('Empleado prueba', 'bodega.prueba21@ejemplo.com', c_hash)
    RETURNING ID_EMPLEADO INTO v_empleado;
  esperar_error('un cliente no puede usar el correo de un empleado',
    registrar_bloque('BODEGA.prueba21@ejemplo.com', 'CC', '9100000006'), -20300);

  v_cursor := PKG_EMPLEADO.credencial_por_email('Bodega.Prueba21@ejemplo.com');
  FETCH v_cursor INTO v_id, v_correo, v_hash, v_activo;
  verificar('credencial_por_email encuentra al empleado', v_cursor%FOUND AND v_id = v_empleado AND v_activo = 1);
  CLOSE v_cursor;

  PKG_EMPLEADO.cambiar_password(v_empleado, c_hash_2);
  SELECT COUNT(*) INTO v_n FROM EMPLEADO WHERE ID_EMPLEADO = v_empleado AND PASSWORD_HASH = c_hash_2;
  verificar('cambiar_password del empleado guarda el hash nuevo', v_n = 1);
  esperar_error('cambiar la clave de un empleado inexistente',
    'BEGIN PKG_EMPLEADO.cambiar_password(-1, ''' || c_hash || '''); END;', -20304);

  ROLLBACK;
  DBMS_OUTPUT.PUT_LINE(v_pruebas || ' pruebas, ' || v_fallos || ' fallos');
  IF v_fallos > 0 THEN
    RAISE_APPLICATION_ERROR(-20005, v_fallos || ' pruebas fallaron: revisa las lineas FALLO.');
  END IF;
END;
/

-- ============================================================================
-- ALTA DEL PRIMER EMPLEADO (ejemplo; ajusta los datos y quita los "--")
-- El hash es un BCrypt de la contrasena inicial (60 caracteres, empieza por $2a$).
-- Pidele a Claude que te genere uno, o usa cualquier herramienta BCrypt (costo 10).
-- El empleado puede cambiarla despues con PUT /api/v1/auth/password.
-- ============================================================================
-- INSERT INTO EMPLEADO (NOMBRE, EMAIL, PASSWORD_HASH)
-- VALUES ('Nombre Apellido', 'correo@ecomers.co', '<hash BCrypt de 60 caracteres>');
-- COMMIT;
