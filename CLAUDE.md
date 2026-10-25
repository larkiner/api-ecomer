# CLAUDE.md

Guía para trabajar en este repositorio.

## Qué es

`ecomers-api`: API REST en Spring Boot 4.1 / Java 21 para la tienda de tecnología ECOMERS sobre Oracle (`XEPDB1`,
esquema `ECOMERS`). El modelo de datos, los paquetes PL/SQL y sus errores están documentados en
`base-de-datos/ECOMERS_contexto_backend.md`. **Cuando ese documento y un script no coincidan, manda el script.** La
base la administra el usuario fuera de este repo: no se crean ni ejecutan scripts SQL desde aquí.

## Comandos

```bash
./gradlew test               # unitarias + capa web + arquitectura (sin Oracle)
./gradlew integrationTest    # contra el Oracle local, con secrets.properties; no modifica datos
./gradlew bootRun            # :8080 (COLA_HABILITADA=false si no hay SMTP)
./gradlew build              # compila, prueba y empaqueta (bootJar)
```

## Arquitectura en capas (la comprueba ArquitecturaTest)

- `controller` → `service` → `repository`, y `service` → `client`. Los controladores no tocan repositorios. Los
  servicios no importan `jakarta.servlet` ni `org.springframework.web`.
- `dto` son los contratos JSON (records con Bean Validation). `model` son objetos de dominio internos (`ClienteActual`,
  `EmpleadoActual`, `IntentoPago`, `NotificacionPago`, `EventoCola`, `PedidoParaNotificar`). Los repositorios pueden
  devolver DTOs de lectura (catálogo y reportes) directamente.
- **Tres usuarios de Oracle, tres pools**. Cada área de `repository` recibe por tipo SU conexión:
  `repository.tienda` → `BaseDatosCliente`, `repository.backoffice` → `BaseDatosEmpleado`,
  `repository.integracion` → `BaseDatosIntegracion`. Un repositorio nuevo va en el área cuyo usuario tiene el grant.
- Nunca se usa el usuario dueño `ECOMERS`.

## Reglas de persistencia

- Toda escritura es una llamada a un paquete PL/SQL vía `OracleDb` (`enTransaccion`, `enTransaccionSinResultado`,
  `lectura`). `OracleDb` fija `DBMS_SESSION.SET_IDENTIFIER` con el usuario final (`cli:<idCliente>` o `emp:<correo>`), hace
  COMMIT o ROLLBACK y reintenta -20144, -20168 y -20188. Los paquetes no hacen COMMIT.
- Los errores `ORA-20xxx` se mapean **por código** en `exception/CodigoError`. Un código nuevo se agrega ahí. Los
  internos (bugs) responden 500 genérico.
- Las llamadas se califican con `ECOMERS.` (no hay sinónimos).
- CLOB: usar `new SqlParameterValue(Types.CLOB, texto)`; `Sesion` lo envía como stream. `setObject(String, CLOB)` da
  ORA-17004.
- Solo cliente, pedido y pago tienen `ID_PUBLICO`. Hoy el rol cliente no puede resolverlo, así que las rutas de pedido
  usan el ID numérico y la base valida la propiedad (404 si no es suyo).

## Seguridad

- Login propio, portado de la API de cafetería (`../Proyecto arabica/api`, paquete `auth/`). La API emite sus JWT
  (HS256, `JwtConfig` + `service/auth/JwtService`) y valida con el resource server de Spring (Nimbus). No hay Keycloak.
- La autorización está centralizada en `config/SecurityConfig` por patrón de URL; no hay `@PreAuthorize`. **Un
  endpoint nuevo hay que declararlo ahí**: el resto queda `denyAll`.
- Roles en el claim `rol` (`CLIENTE`, `EMPLEADO`, `ADMIN`; `RolClaimConverter`). El `ID_CLIENTE` o el `ID_EMPLEADO` salen
  de los claims `id_cliente` / `id_empleado` (los escribe la API al iniciar sesión, desde la base), nunca del cuerpo. El
  sujeto del token es el correo. Un `ADMIN` es también `ROLE_EMPLEADO`.
- **Permisos delegables:** el claim `permisos` (lista de códigos, solo empleados y admin) se convierte en authorities
  `PERM_<CODIGO>` (`CATEGORIAS`, `PRODUCTOS`) y `SecurityConfig` los exige con `hasAuthority`. **Las reglas específicas
  de `/backoffice/...` van ANTES de la general `hasRole("EMPLEADO")`** (Spring aplica la primera que coincide). Un permiso
  nuevo es una fila en la tabla `PERMISO` más su regla en `SecurityConfig`. La base **vuelve a verificar** el permiso en
  cada rutina de `PKG_CATALOGO` con el `id_empleado` que recibe primero. Al desactivar a un trabajador o cambiarle los
  permisos, `TrabajadorService` invalida sus sesiones (`InvalidacionSesionService`).
- `service/auth`: `RegistroService` (siempre crea un CLIENTE), `SesionService` (login y logout; carga los permisos de un
  empleado), `PasswordService`, `CredencialService` (único lugar que distingue cliente de empleado) y
  `PoliticaPassword` (pública: también la usa `TrabajadorService`). Contraseñas con BCrypt: la base solo guarda el hash.
  Las cuentas por `PKG_CLIENTE` / `PKG_EMPLEADO` (`ClienteAuthRepository`, `EmpleadoAuthRepository`; scripts 21 y 22);
  contrato en `base-de-datos/auth-contrato-base-de-datos.md`.
- `PKG_PERMISOS` es un paquete **interno de la base, sin grants** (lo usan `PKG_EMPLEADO` y `PKG_CATALOGO`): la API no lo
  llama directamente.
- Logout y cambio de contraseña se revocan en memoria (`security/TokenBlacklistService`,
  `InvalidacionSesionService`) y las comprueba `RevocacionTokenValidator` en el decodificador. Solo sirven para una
  instancia. En las rutas públicas se ignora la cabecera `Authorization` (`SecurityConfig.resolutorDeToken`).
- Filtros propios: `RateLimitingFilter` (por IP, `/api/**` salvo el webhook; y otro más estricto para `/api/v1/auth/`)
  y `TamanoCuerpoFilter` (64 KB).
- `JWT_SECRET` es obligatorio (mínimo 32 caracteres): sin él la app no arranca.
- **Imágenes de producto:** el navegador sube directo a Supabase Storage; la API solo entrega una URL de subida firmada
  (`POST /backoffice/imagenes/subida`, permiso `PRODUCTOS`, carpeta `productos/`; `POST /backoffice/imagenes/categorias/subida`,
  permiso `CATEGORIAS`, carpeta `categorias/`; `ImagenService` + `client/supabase/SupabaseAlmacenClient`)
  y valida que `imagenUrl` sea de su bucket (`ImagenService.validarUrl`). La base guarda solo la URL
  (`PRODUCTO.IMAGEN_URL`, `PKG_CATALOGO.fijar_imagen`, script 24). El catálogo público lee `V_CATALOGO`, no
  `V_PRECIO_VIGENTE`, y las categorías que muestra la tienda salen de `V_CATEGORIA_PUBLICA` (solo activas; el rol cliente
  no lee la tabla `CATEGORIA`). `SUPABASE_SERVICE_KEY` es un secreto de servidor: nunca se devuelve ni se registra.
- Webhook de Wompi: público. Lo protege la verificación de firma (`VerificadorFirmaWompi`). Sin secreto de eventos, se
  rechaza todo.

## Detalles de Boot 4.1 / Jackson 3

- Jackson 3: paquetes `tools.jackson.*` (las anotaciones siguen en `com.fasterxml.jackson.annotation`). `JsonNode`
  usa `asString()`. `JacksonException` es unchecked.
- `@WebMvcTest` está en `org.springframework.boot.webmvc.test.autoconfigure`. Las pruebas web usan `@PruebaWeb` y
  `Tokens`.
- `ojdbc11` fijado en 23.26.2: la 23.26.3 da ORA-12564 contra Oracle XE 21c.

## Convenciones

- Código, comentarios y mensajes en español.
- Constructor injection y records, sin Lombok.
- Dinero en `BigDecimal` (se serializa como texto). Fechas de la base con `-05:00` (`util/FechaBd`).
- Las pruebas de integración (`@Tag("oracle")`) **no pueden modificar datos**: usar solo lecturas o IDs inexistentes,
  y apagar la cola (`app.cola.habilitada=false`).
- Secretos solo en variables de entorno o `secrets.properties` (gitignored).
