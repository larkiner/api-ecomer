# ECOMERS API

API REST de la tienda de tecnología ECOMERS: Spring Boot 4.1, Java 21 y Oracle (esquema `ECOMERS` en `XEPDB1`).
Sirve a la tienda (clientes), al backoffice (empleados) y a la pasarela de pagos Wompi.

## Arquitectura en capas

```
              ┌────────────────────────────────────────────┐
  HTTP  ───▶  │ controller   presentación: rutas, validación │  dto (contratos JSON)
              ├────────────────────────────────────────────┤
              │ service      negocio: reglas y orquestación  │──▶ client (Wompi, ERP, correo)
              ├────────────────────────────────────────────┤
              │ repository   persistencia: paquetes PL/SQL   │
              └────────────────────────────────────────────┘
                         │ 3 usuarios de Oracle, 1 pool cada uno
                         ▼
     ECOMERS_CLIENTE_APP · ECOMERS_EMPLEADO_APP · ECOMERS_INTEGRACION_APP
```

| Paquete | Capa | Qué contiene |
|---|---|---|
| `controller/auth`, `controller/tienda`, `controller/backoffice`, `controller/webhook` | Presentación | Controladores REST |
| `service/auth`, `service/tienda`, `service/backoffice`, `service/integracion` | Negocio | Reglas, validaciones y orquestación |
| `repository/tienda`, `repository/backoffice`, `repository/integracion` | Persistencia | Llamadas a los paquetes y vistas de Oracle |
| `repository/oracle` | Persistencia | `OracleDb`: transacción, `SET_IDENTIFIER`, reintentos, mapeo de errores |
| `client/wompi`, `client/erp`, `client/correo` | Integraciones | Sistemas externos |
| `scheduler` | Tareas | Consumidores de la cola (correo y ERP) |
| `dto`, `model`, `exception`, `security`, `config`, `util` | Transversal | Contratos, modelo, errores, seguridad y configuración |

Las reglas se **comprueban en cada build** (`ArquitecturaTest`, con ArchUnit):

- Los controladores solo hablan con servicios. Los servicios no conocen HTTP. Los repositorios solo los usan los servicios.
- Cada área de persistencia usa **solo** la conexión de su usuario de Oracle (`BaseDatosCliente`, `BaseDatosEmpleado`,
  `BaseDatosIntegracion`). Cablear un repositorio al usuario equivocado no compila o rompe la prueba.
- La API **nunca** se conecta como el dueño `ECOMERS`. Toda escritura pasa por los paquetes PL/SQL.

## Puesta en marcha

1. Crea `secrets.properties` (está en `.gitignore`) a partir de la plantilla y pon las claves de los tres usuarios de
   aplicación (las de los scripts `01` y `01b`) y un `JWT_SECRET` propio (mínimo 32 caracteres, por ejemplo
   `openssl rand -base64 48`):
   ```bash
   cp secrets.properties.example secrets.properties
   ```
   Sin esas claves la app **no arranca** (fail-closed). En producción usa variables de entorno.
2. Arranca la API:
   ```bash
   ./gradlew bootRun
   ```
   - Documentación: http://localhost:8080/swagger-ui.html (solo con `API_DOCS_HABILITADO=true`; viene apagada por defecto)
   - Estado: http://localhost:8080/actuator/health
3. Si no tienes un SMTP corriendo, arráncala con `COLA_HABILITADA=false`. Así no consume los eventos de correo de tu base.

## Pruebas

```bash
./gradlew test
```
```bash
./gradlew integrationTest
```

- `test` corre 282 pruebas unitarias, de capa web y de arquitectura. No necesita Oracle.
- `integrationTest` corre contra tu Oracle local con `secrets.properties`. **No modifica datos**: solo lee, o llama a
  los paquetes con IDs inexistentes, que validan y fallan antes de escribir. Además comprueba al final que no se
  creó ningún pedido.

## Endpoints (`/api/v1`)

| Método y ruta | Rol | Notas |
|---|---|---|
| `POST /auth/registro` `{nombre, email, tipoDocumento, numeroDocumento, telefono, password}` | público | 201 `{token, tipo, expiraEnSegundos, email, rol}`; crea siempre un CLIENTE y deja la sesión iniciada |
| `POST /auth/login` `{email, password}` | público | 200, mismo cuerpo; el mensaje de error es genérico (401) |
| `POST /auth/logout` | cualquier rol | 204; revoca el token actual |
| `PUT /auth/password` `{passwordActual, passwordNueva}` | cualquier rol | 204; cierra todas las sesiones del usuario |
| `GET /catalogo/variantes?q&categoria&page&size` | público | `size` 1 a 50; `categoria` filtra por esa categoría y sus subcategorías; `Cache-Control: max-age=60` |
| `GET /catalogo/variantes/{id}` | público | |
| `GET /catalogo/categorias` | público | solo categorías **activas**, con `idCategoria`, `idPadre?`, `nombre` e `imagenUrl?`; lee `V_CATEGORIA_PUBLICA` |
| `POST /carrito/items` `{idVariante, cantidad}` | CLIENTE | 204 |
| `PUT /carrito/items/{idVariante}` `{cantidad}` | CLIENTE | 204 |
| `DELETE /carrito/items/{idVariante}`, `DELETE /carrito` | CLIENTE | 204, idempotentes |
| `POST /pedidos` `{idDireccion?}` | CLIENTE | 201 `{idPedido}`; congela precios y reserva stock |
| `POST /pedidos/{id}/cancelacion` `{motivo?}` | CLIENTE | 204; libera el stock |
| `POST /pedidos/{id}/pagos` | CLIENTE | 201 con `checkoutUrl` firmada de Wompi |
| `POST /backoffice/inventario/{idVariante}/entradas` `{cantidad, observacion?}` | EMPLEADO | 204 |
| `POST /backoffice/inventario/{idVariante}/ajustes` `{cantidad±, observacion}` | EMPLEADO | 204 |
| `GET /backoffice/reportes/{reporte}?limite` | EMPLEADO | `stock-bajo`, `conciliacion-stock`, `pedidos-pendientes`, `top-clientes`, `conciliacion-pagos`, `carritos-abandonados`, `cola-pendiente` |
| `GET /backoffice/reportes/ventas-diarias?desde&hasta` | EMPLEADO | fechas `yyyy-MM-dd`; últimos 30 días por defecto |
| `GET /backoffice/reportes/ventas-producto?desde&hasta&limite` | EMPLEADO | |
| `POST /webhooks/wompi` | público | lo protege la firma del evento |

Los errores salen como `application/problem+json` con `codigo` y `mensaje`. Por ejemplo, `STOCK_INSUFICIENTE` (409),
`PEDIDO_NO_ENCONTRADO` (404) o `CLIENTE_NO_IDENTIFICADO` (403). El mapa completo, desde los `ORA-20xxx`, está en
`exception/CodigoError.java`. Los errores internos de la base nunca se exponen.

Protecciones de la capa web:

- Rate limiting por IP (`RATE_LIMIT_POR_MINUTO`, 120 por defecto) y uno más estricto para `/auth/**`
  (`RATE_LIMIT_AUTH_POR_MINUTO`, 10 por defecto) contra la fuerza bruta.
- Cuerpos de más de 64 KB se rechazan con 413.
- Cabeceras de seguridad (CSP, HSTS, no-referrer).

## Autenticación (login propio)

La API tiene su propio login, con la lógica de la API de cafetería. El frontend solo muestra los formularios y llama a
`/api/v1/auth/**`; no hay servidor de identidad aparte.

- **Contraseñas**: BCrypt. La base guarda solo el hash. Política mínima: 8 a 72 caracteres, y se rechazan las muy comunes,
  solo números, un carácter repetido y secuencias (`PASSWORD_DEBIL`, 400).
- **Token**: JWT HS256 firmado con `JWT_SECRET`, con `sub` = correo, `rol` (`CLIENTE` o `EMPLEADO`) y, para clientes,
  `id_cliente`. Dura `JWT_EXPIRATION_MS` (2 h por defecto: la revocación vive en memoria y se pierde al reiniciar, así que un token corto acota el riesgo). Se envía como `Authorization: Bearer <token>`.
- **Registro**: crea siempre un cliente. Pide además tipo y número de documento (la tabla `CLIENTE` los exige) y un
  teléfono de exactamente 10 dígitos. Correo o documento repetidos: 409.
- **Login unificado**: busca primero entre clientes y luego entre empleados. Correo inexistente y contraseña incorrecta
  dan la misma respuesta (401 `CREDENCIALES_INVALIDAS`) y tardan lo mismo.
- **Logout**: revoca el `jti` del token hasta que venza. **Cambiar la contraseña** invalida todos los tokens anteriores
  del usuario. Ambas listas están en memoria: sirven para una sola instancia de la API y se pierden al reiniciar (para
  varias instancias, moverlas a Redis).
- En las rutas públicas se ignora la cabecera `Authorization`: un token vencido que el navegador siga enviando no impide
  iniciar sesión ni ver el catálogo.

En la base, esto vive en `PKG_CLIENTE` y `PKG_EMPLEADO` (script `21_auth_cuentas.sql`); el contrato exacto está en
[`base-de-datos/auth-contrato-base-de-datos.md`](base-de-datos/auth-contrato-base-de-datos.md).

## Administración y permisos delegables

Un **admin** (`EMPLEADO.ROL = 'ADMIN'`) gestiona a los **trabajadores** y puede delegarles dos permisos. El admin siembra
su cuenta a mano en la base (ejemplo comentado al final de `22_admin_catalogo.sql`): la API nunca crea admins.

| Permiso | Qué permite |
|---|---|
| `CATEGORIAS` | Crear, editar y activar o desactivar categorías |
| `PRODUCTOS` | Crear productos con sus variantes, editarlos y activarlos o desactivarlos |

El admin los tiene todos sin asignación. Cualquier empleado puede **leer** categorías, impuestos y productos.

| Quién | Ruta (`/api/v1/backoffice`) | Notas |
|---|---|---|
| ADMIN | `POST /trabajadores` `{nombre, email, password}` | 201 `{idEmpleado}` |
| ADMIN | `GET /trabajadores` | con sus permisos |
| ADMIN | `PUT /trabajadores/{id}/estado` `{activo}` | desactivar cierra sus sesiones |
| ADMIN | `PUT` y `DELETE /trabajadores/{id}/permisos/{codigo}` | 204, idempotentes; cierran sus sesiones |
| EMPLEADO | `GET /categorias`, `GET /impuestos`, `GET /productos?page&size` | incluyen inactivos; `size` 1 a 50 |
| `CATEGORIAS` | `POST /categorias` `{nombre, idPadre?}`, `PUT /categorias/{id}`, `PUT /categorias/{id}/estado` | 201 `{idCategoria}` / 204 |
| `PRODUCTOS` | `POST /productos` `{idCategoria, idImpuesto, nombre, descripcion?, imagenUrl?, variantes[≥1]}` | 201 `{idProducto, idsVariantes}`; una sola transacción |
| `PRODUCTOS` | `PUT /productos/{id}` `{idCategoria, idImpuesto, nombre, descripcion?, imagenUrl?}`, `PUT /productos/{id}/estado` | 204; el PUT reemplaza todo, así que sin `imagenUrl` se quita la imagen |
| `PRODUCTOS` | `POST /imagenes/subida` `{tipoContenido}` | 201 con la URL firmada de subida a Supabase, carpeta `productos/` (ver abajo) |
| `CATEGORIAS` | `POST /imagenes/categorias/subida` `{tipoContenido}` | igual, a la carpeta `categorias/` |
| `PRODUCTOS` | `POST /productos/{id}/variantes`, `PUT /variantes/{id}` `{nombre, precioBase}`, `PUT /variantes/{id}/estado` | `controlaStock` se fija al crear la variante |

- **Token:** un empleado recibe `rol` (`ADMIN` o `EMPLEADO`), `id_empleado` y `permisos`. `SecurityConfig` exige cada
  permiso por URL (`hasAuthority("PERM_<CODIGO>")`) y la base lo **vuelve a verificar** en cada rutina de `PKG_CATALOGO`.
- **Cambios de permiso:** al desactivar a un trabajador o cambiarle los permisos, sus sesiones se invalidan al instante
  (mismo mecanismo en memoria que el logout: solo vale para una instancia de la API). Tiene que volver a iniciar sesión.
- **Precios:** hasta 12 enteros y 2 decimales, enviados como número o como texto. Cada cambio queda en `PRECIO_HIST` con el
  correo del empleado como autor.
- Un admin solo gestiona **trabajadores**: sobre otro admin (o sobre sí mismo) la base responde 409 `CUENTA_NO_GESTIONABLE`.
- Los enteros del JSON ya no se truncan: `{"cantidad": 1.5}` responde 400 en toda la API.

Objetos de la base: `base-de-datos/22_admin_catalogo.sql` (columna `EMPLEADO.ROL`, tablas `PERMISO` y
`EMPLEADO_PERMISO`, `PKG_PERMISOS` interno, `PKG_EMPLEADO` ampliado y `PKG_CATALOGO`). Diseño (solo local) en
la especificacion de diseno (documento local, no publicado).

## Imágenes de producto y de categoría (Supabase Storage)

La imagen **no pasa por la API**: el navegador la sube directo a Supabase y la base solo guarda la URL
(`PRODUCTO.IMAGEN_URL` y `CATEGORIA.IMAGEN_URL`, script `24_imagenes_y_categorias_publicas.sql`). Flujo:

1. `POST /backoffice/imagenes/subida` (permiso `PRODUCTOS`, carpeta `productos/`) o
   `POST /backoffice/imagenes/categorias/subida` (permiso `CATEGORIAS`, carpeta `categorias/`), con `{tipoContenido}` (solo
   `image/jpeg`, `image/png`, `image/webp`). La API pide a Supabase una URL de subida firmada para
   `<carpeta>/<uuid>.<ext>` y devuelve `{urlSubida, token, ruta, urlPublica, tipoContenido, expiraEnSegundos}`.
2. El frontend sube el archivo a Supabase con ese token.
3. El frontend manda `urlPublica` como `imagenUrl` al crear o editar el producto, o al crear o editar la categoría
   (`POST` y `PUT /backoffice/categorias`; el `PUT` reemplaza todo, así que sin `imagenUrl` la imagen se quita). La API
   solo acepta URLs de **su** bucket (`IMAGEN_URL_INVALIDA` si no) y la base exige `https://`.
4. El catálogo público lee la vista `V_CATALOGO` (`V_PRECIO_VIGENTE` + imagen) y devuelve `imagenUrl` por variante.

Configuración (en `secrets.properties` o variables de entorno; opcional para arrancar):

| Variable | Qué es |
|---|---|
| `SUPABASE_URL` | URL del proyecto, `https://<ref>.supabase.co` |
| `SUPABASE_SERVICE_KEY` | clave de servicio: **solo en el servidor**, nunca en el frontend |
| `SUPABASE_BUCKET` | bucket **público** (por defecto `productos`) |

- Sin configurar, la subida responde `503 ALMACEN_IMAGENES_NO_CONFIGURADO` y cualquier `imagenUrl` se rechaza.
- En el bucket define el **tamaño máximo** y los **tipos MIME permitidos**: la URL firmada no puede imponerlos.
- **Orden de despliegue:** ejecuta `24_imagenes_y_categorias_publicas.sql` **antes** de arrancar esta versión (se puede repetir sin
  riesgo); si no, el catálogo público falla (`ORA-00942`, la vista `V_CATALOGO` no existe) y la lista de categorías del
  backoffice también (`ORA-00904`, falta `CATEGORIA.IMAGEN_URL`).
- Las imágenes reemplazadas o de productos no guardados quedan huérfanas en Supabase; borrarlas es tarea aparte.
- La subida con URL firmada ya se probó contra el proyecto real de Supabase con fotos de producto.

## Wompi

- **Pago**: `POST /pedidos/{id}/pagos` crea el intento de pago en la base y devuelve el enlace firmado al Web Checkout.
  - Antes hay que dar de alta la pasarela, como `ECOMERS`:
    `INSERT INTO PASARELA_PAGO (CODIGO, NOMBRE) VALUES ('WOMPI', 'Wompi')`.
  - Sin las llaves de Wompi en `secrets.properties`, el endpoint responde 503 `PASARELA_NO_CONFIGURADA` y no crea
    nada en la base.
- **Webhook**: en el panel de Wompi pon como URL de eventos `https://<tu-dominio>/api/v1/webhooks/wompi`.
  - Sin `WOMPI_EVENTS_SECRET` se rechaza todo con 503 (nunca se acepta un evento sin verificar).
  - El pago lo aplica el JOB de la base unos 30 segundos después.

## Docker

```bash
cp .env.example .env
```
```bash
docker compose up --build
```

Levanta la API y Mailpit. La API se conecta a tu Oracle local (`host.docker.internal:1521`). La imagen corre
las pruebas rápidas al construirse y se ejecuta con un usuario sin privilegios. Los secretos llegan solo por variables
de entorno.

- **Seguridad**:
  - Límite de solicitudes por IP en toda la API, más estricto en registro y login, y otro por usuario autenticado aunque
    cambie de IP. Tras 5 contraseñas equivocadas para el mismo correo, ese correo queda bloqueado 15 minutos.
  - CORS solo con los orígenes exactos de `CORS_ALLOWED_ORIGINS`. Uno mal escrito (con `*`, ruta o barra final) detiene
    el arranque. Expone `Retry-After` y no usa cookies.
  - El token lleva emisor y audiencia (`JWT_AUDIENCE`) y dura 2 horas. Los textos que escribe una persona rechazan
    caracteres de control e invisibles, y los IDs de las rutas deben ser positivos.
- **Chequeo de salud**: la imagen consulta `/actuator/health` cada 30 s (`docker ps` muestra `healthy`).
- **Detrás de un proxy o balanceador** (Nginx, Traefik, la nube): `FORWARD_HEADERS_STRATEGY=native`, para que el límite
  de solicitudes cuente por la IP real del cliente y no por la del proxy. Sin proxy delante se deja en `none`.
- **Producción**: `CORS_ALLOWED_ORIGINS` con el dominio del frontend, `WOMPI_REDIRECT_URL` con su URL de resultado y
  `API_DOCS_HABILITADO=false` (es el valor por defecto).
- **Varias instancias**: la cola ya es segura (`FOR UPDATE SKIP LOCKED`), pero la revocación de sesiones y el límite de
  solicitudes viven en memoria de cada instancia. Antes de escalar a más de una, moverlos a un almacén compartido.

## Lo que falta en la base (y por eso en la API)

Con los permisos actuales, el rol cliente solo ejecuta `PKG_CARRITO` y `PKG_PEDIDO` y lee `V_PRECIO_VIGENTE`
(`ECOMERS_contexto_backend.md`, sección 15.1). Por eso todavía **no hay**:

- registro de clientes (la API ya lo tiene: falta `PKG_CLIENTE`, ver el contrato de auth) ni direcciones;
- ver el carrito;
- listar o consultar pedidos;
- stock ni categorías en el catálogo;
- número de pedido visible.

Las rutas de pedido usan el `ID_PEDIDO` numérico, y la base valida que el pedido sea del cliente.

## Notas técnicas

- **ojdbc fijado en 23.26.2**: la versión 23.26.3, que trae Boot 4.1.1, es rechazada por Oracle XE 21c con
  `ORA-12564` (ver `build.gradle`).
- Las fechas de la base son la hora de Colombia y se exponen con `-05:00`. El dinero viaja como texto (`"238000.00"`).
