# Auth propio: lo que la API necesita de la base

La API ya tiene registro, login, logout y cambio de contraseña (`/api/v1/auth/**`), con la lógica de la API de
cafetería: contraseñas con BCrypt, JWT firmado por la propia API y revocación de sesiones. **Falta la parte de la
base**, y la administras tú. Este documento es el contrato: lo que la API llama y lo que espera recibir. No hay
scripts SQL aquí a propósito.

> Cambia una decisión de diseño de la base: el script `03` dice "la autenticación vive fuera de la BD: esta tabla no
> guarda contraseñas". Con el login propio, la base guarda el **hash BCrypt** (nunca la contraseña). Si prefieres no
> guardarlo en `CLIENTE`, puede ir en una tabla aparte (`CLIENTE_CREDENCIAL`); a la API le da igual mientras los
> paquetes respeten las firmas de abajo.

> **Implementado en** [`base de datos/21_auth_cuentas.sql`](../base%20de%20datos/21_auth_cuentas.sql) (columna, tabla
> `EMPLEADO`, los dos paquetes, grants y 17 pruebas con `ROLLBACK`); el dueño de la base ya lo ejecutó.
>
> **Ampliado por** [`base de datos/22_admin_catalogo.sql`](../base%20de%20datos/22_admin_catalogo.sql) (rol `ADMIN`,
> permisos delegables, trabajadores y `PKG_CATALOGO`; borrador por revisar y ejecutar). Cambia `PKG_EMPLEADO`:
> `credencial_por_email` devuelve además `ROL`, y se agregan `permisos_de`, `crear_trabajador`, `listar_trabajadores`,
> `cambiar_estado`, `asignar_permiso` y `quitar_permiso`. Sus códigos de error (`-20320` a `-20337`) están en el diseño:
> `docs/superpowers/specs/2026-10-21-admin-catalogo-permisos-design.md`.
>
> **Ampliado por** [`24_imagenes_y_categorias_publicas.sql`](24_imagenes_y_categorias_publicas.sql) (borrador por
> revisar y ejecutar): columnas `PRODUCTO.IMAGEN_URL` y `CATEGORIA.IMAGEN_URL`, vista `V_CATALOGO` (la lee el catálogo público), y `PKG_CATALOGO`
> con `fijar_imagen(p_id_empleado, p_id_producto, p_imagen_url)` (permiso `PRODUCTOS`; error nuevo `-20338`) y
> `listar_productos` devolviendo `IMAGEN_URL`.

## 1. Datos

| Qué | Detalle |
|---|---|
| `CLIENTE.PASSWORD_HASH` | `VARCHAR2(100)`. Un hash BCrypt mide 60 caracteres (`$2a$10$...`). Puede ser `NULL` para no romper los clientes que ya existen: sin hash no pueden iniciar sesión (`credencial_por_email` no los devuelve). |
| Empleados | Hoy no existe ninguna tabla de empleados. Hace falta una `EMPLEADO` con `ID_EMPLEADO`, `EMAIL` (único sin distinguir mayúsculas), `PASSWORD_HASH` y `ACTIVO`. |
| Correo del cliente | Ya es único sin distinguir mayúsculas (`UX_CLIENTE_EMAIL`). La API lo guarda en minúsculas. |

## 2. `PKG_CLIENTE` (grant EXECUTE al rol cliente)

Todas se llaman como `ECOMERS.PKG_CLIENTE.<nombre>`. Los paquetes no hacen COMMIT (lo hace `OracleDb`).

| Rutina | Parámetros (en este orden) | Devuelve |
|---|---|---|
| `registrar` (función) | `p_nombre`, `p_email`, `p_tipo_documento` (el **código**, por ejemplo `CC`), `p_numero_documento`, `p_telefono`, `p_password_hash` | `NUMBER`: el `ID_CLIENTE` nuevo |
| `credencial_por_email` (función) | `p_email` | `SYS_REFCURSOR` con las columnas `ID_CLIENTE`, `EMAIL`, `PASSWORD_HASH`, `ACTIVO` (0 o 1). Cero filas si no existe. Debe comparar sin distinguir mayúsculas. |
| `cambiar_password` (procedimiento) | `p_id_cliente`, `p_password_hash` | — |

Errores que `registrar` debe lanzar (la API los traduce por código):

| Código | Cuándo | Respuesta HTTP |
|---|---|---|
| `-20300` | El correo ya está registrado | 409 `EMAIL_YA_REGISTRADO` |
| `-20301` | El documento (tipo y número) ya está registrado | 409 `DOCUMENTO_YA_REGISTRADO` |
| `-20302` | El código de tipo de documento no existe o está inactivo | 400 `TIPO_DOCUMENTO_INVALIDO` |
| `-20304` | `cambiar_password`: la cuenta no existe (cliente o empleado) | 401 `CREDENCIALES_INVALIDAS` |

Los 203xx están libres en el mapa de errores (`ECOMERS_contexto_backend.md`, sección 13). Si usas otros números, hay
que cambiarlos en `exception/CodigoError.java`. La verificación debe ser **dentro del paquete** y respaldada por los
índices únicos: así no hay carrera entre dos registros simultáneos.

## 3. `PKG_EMPLEADO` (grant EXECUTE al rol empleado)

| Rutina | Parámetros | Devuelve |
|---|---|---|
| `credencial_por_email` (función) | `p_email` | `SYS_REFCURSOR` con `ID_EMPLEADO`, `EMAIL`, `PASSWORD_HASH`, `ACTIVO` |
| `cambiar_password` (procedimiento) | `p_id_empleado`, `p_password_hash` | — |

A los empleados los da de alta el administrador de la base (la API no crea empleados). Para el primero hace falta un
hash BCrypt: se puede generar con cualquier herramienta BCrypt (costo 10) o pedírselo a Claude.

## 4. Qué NO necesita la base

- Tokens, sesiones y revocación: viven en la API (en memoria; ver `TokenBlacklistService`).
- La contraseña en claro: nunca llega a Oracle, solo el hash.

## 5. Cómo probarlo cuando lo tengas

1. Define `JWT_SECRET` (mínimo 32 caracteres) en `secrets.properties`.
2. Arranca la API: `./gradlew bootRun` (con `COLA_HABILITADA=false` si no hay SMTP).
3. En Postman, carpeta `auth`: `POST /api/v1/auth/registro` y luego `POST /api/v1/auth/login`. Guarda el `token` y úsalo
   como Bearer en `/carrito` y `/pedidos`.

Mientras la base no tenga estos paquetes, `registro` y `login` responden 500 (la llamada a `PKG_CLIENTE` falla); el
resto de la API no se ve afectado.
