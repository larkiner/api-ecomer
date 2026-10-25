# ECOMERS — Contexto de la base de datos para diseñar el backend

> Documento de referencia generado a partir de los scripts `01` a `20` (más `01b` y `05`), todos ejecutados con éxito en Oracle XE 21c el 18/19-oct-2026.
> Todas las pruebas automáticas pasaron, hay 0 objetos inválidos y los JOBs corren sin fallos.
> Cuando este documento y un script no coincidan, **manda el script**. Los scripts están en `C:\Users\USER\Documents\E-comers\base de datos`.

---

## 1. Entorno

| Elemento | Valor |
|---|---|
| Motor | Oracle Database XE 21c |
| Contenedor | CDB `XE`, PDB **`XEPDB1`** (el backend se conecta a este servicio) |
| Esquema dueño | **`ECOMERS`**: dueño de tablas, vistas, paquetes y JOBs. No lo usa la aplicación |
| Tablespace | `ECOMERS_DATA` (200 MB, autoextensible hasta 8 GB) |
| Zona horaria del servidor | `-05:00` (Colombia). Lo verifica el script 09 |
| Juego de caracteres | Las tildes se guardan bien (se verificó con DIVIPOLA) |
| Moneda | Pesos colombianos (COP), `NUMBER(14,2)` |

Contenido del esquema: **28 tablas, 10 vistas, 10 paquetes y 5 JOBs**.

---

## 2. Usuarios, roles y permisos

El backend **nunca** se conecta como `ECOMERS`. Se crearon tres usuarios de aplicación, cada uno con su rol:

| Usuario de conexión | Rol | Lo usa | EXECUTE | SELECT |
|---|---|---|---|---|
| `ECOMERS_CLIENTE_APP` | `ROL_ECOMERS_CLIENTE` | Backend de la tienda (acciones del comprador) | `PKG_CARRITO`, `PKG_PEDIDO` | `V_PRECIO_VIGENTE` |
| `ECOMERS_EMPLEADO_APP` | `ROL_ECOMERS_EMPLEADO` | Backoffice | `PKG_INVENTARIO` | Las 10 vistas |
| `ECOMERS_INTEGRACION_APP` | `ROL_ECOMERS_INTEGRACION` | Webhook de la pasarela y consumidor de la cola (correo, ERP) | `PKG_PAGO`, `PKG_COLA` | `PEDIDO`, `PEDIDO_ITEM` |

Reglas que el backend debe respetar:

- **Ningún rol escribe en tablas directamente.** Toda escritura pasa por los paquetes, que son `AUTHID DEFINER` y corren con los permisos de `ECOMERS` y sus validaciones.
- El EXECUTE de Oracle se da **por paquete completo**. Por eso hay paquetes separados por audiencia. Ejemplo: `iniciar_pago` está en `PKG_PEDIDO` (cliente) y no en `PKG_PAGO` (integración), porque así el usuario de la tienda no puede fabricar un "pago aprobado".
- **Credenciales separadas:** el webhook usa `ECOMERS_INTEGRACION_APP`. Si se filtran las credenciales de la tienda, nadie puede registrar notificaciones de pago con ellas.
- **No hay sinónimos.** El backend debe llamar a `ECOMERS.PKG_PEDIDO.crear_desde_carrito(...)` o ejecutar `ALTER SESSION SET CURRENT_SCHEMA = ECOMERS` al abrir cada conexión del pool.
- **La base solo conoce al usuario de aplicación, no al usuario final.** El backend autentica a la persona y pasa su `ID_CLIENTE` a los paquetes. Los paquetes validan que lo que se toca pertenezca a ese cliente.
- **Trazabilidad:** antes de cada operación, el backend debe hacer `DBMS_SESSION.SET_IDENTIFIER('<usuario final>')` y limpiarlo después con `DBMS_SESSION.CLEAR_IDENTIFIER`. El historial de estados del pedido y el kardex (`MOVIMIENTO_STOCK.USUARIO`) guardan `NVL(CLIENT_IDENTIFIER, SESSION_USER)`. Sin el identificador, el historial solo dirá `ECOMERS_CLIENTE_APP`.
- **Contraseñas:** el script `01` trae claves de ejemplo (`Cambiar_Clave_...`) para `ECOMERS`, `ECOMERS_CLIENTE_APP` y `ECOMERS_EMPLEADO_APP`. Si no se cambiaron al ejecutarlo, **hay que cambiarlas** (`ALTER USER ... IDENTIFIED BY ...`) antes de exponer la base. La clave de integración (`01b`) se generó al azar. Todas deben ir a un gestor de secretos, no al código.

---

## 3. Convenciones del modelo

| Convención | Detalle |
|---|---|
| Claves primarias | `ID_<TABLA>`, `NUMBER(12) GENERATED ALWAYS AS IDENTITY`. No se insertan a mano |
| IDs que se exponen hacia afuera | Solo `CLIENTE`, `PEDIDO` y `PAGO` tienen **`ID_PUBLICO RAW(16)`** (`SYS_GUID()`). En API y URLs se usa el ID público en **hexadecimal de 32 caracteres** (`RAWTOHEX`), nunca el ID numérico |
| Dinero | `NUMBER(14,2)` en COP |
| Banderas | `NUMBER(1)` con CHECK `IN (0,1)` (`ACTIVO`, `ES_PRINCIPAL`, `CONTROLA_STOCK`...) |
| Fechas | `TIMESTAMP` **sin zona**, con la hora local del servidor (Colombia). Los paquetes usan `PKG_UTILIDADES.ahora` = `CAST(SYSTIMESTAMP AS TIMESTAMP)`. Un "día" es `TRUNC` de la hora del servidor |
| Auditoría | Todas las tablas tienen `FECHA_CREACION`. Las editables tienen `FECHA_MODIFICACION`, que llena un trigger `BEFORE UPDATE` |
| Tablas de solo inserción | Un trigger rechaza UPDATE y DELETE (error -20010): `PEDIDO_ITEM`, `PEDIDO_ESTADO_HIST`, `PAGO_EVENTO`, `MOVIMIENTO_STOCK` (kardex), `PRECIO_HIST`, `CIERRE_DIARIO` |
| Foto del pedido | La cabecera de `PEDIDO` (cliente, carrito, datos de facturación y totales) no se modifica después de crearla (error -20011) |
| Unicidad condicional | Índices únicos con `CASE`: un carrito ABIERTO por cliente, una dirección principal activa por cliente, un solo pago APROBADO por pedido, una referencia externa por pasarela |
| COMMIT | **Ningún paquete hace COMMIT, salvo `PKG_TAREAS`.** El backend confirma o deshace. Cada procedimiento es atómico (`SAVEPOINT` + `ROLLBACK TO`): si falla, no deja nada a medias aunque el backend capture el error |
| Errores | Los paquetes lanzan `RAISE_APPLICATION_ERROR(-20xxx)`. En el driver llegan como `ORA-20xxx: <mensaje>`. El backend debe mapear por **código** (ver §12), no por texto |

---

## 4. Scripts ejecutados (en orden)

| # | Script | Qué hace |
|---|---|---|
| 01 | `01_crear_ecomers.sql` (SYSTEM) | Tablespace, usuario `ECOMERS` (con `CREATE JOB`), roles y usuarios de cliente y empleado |
| 01b | `01b_crear_usuario_integracion.sql` (SYSTEM) | Rol y usuario de integración |
| 02 | `02_eliminar_ecomers_SOLO_DESARROLLO.sql` | Borra todo. **Solo desarrollo** |
| 03 | `03_tablas_clientes_catalogo_inventario.sql` | 14 tablas: clientes, catálogo, precios, stock, kardex, carrito y promociones. Datos iniciales: tipos de documento e impuestos |
| 04 | `04_normalizar_ubicacion.sql` | `DEPARTAMENTO` y `MUNICIPIO`. `CLIENTE_DIRECCION` pasa a usar `CODIGO_MUNICIPIO` |
| 05 | `05_datos_divipola.sql` | Carga DIVIPOLA del DANE (corte 30-dic-2024): 33 departamentos y 1.122 códigos. Agrega `MUNICIPIO.TIPO` |
| 06 | `06_tablas_pedidos_pagos.sql` | 8 tablas: estados, pedido, líneas, historial, reservas, pasarelas, pagos y eventos de pago |
| 07 | `07_tablas_cola_erp_cierre.sql` | `COLA_EVENTO`, `PEDIDO_ERP`, `CIERRE_DIARIO` y `PARAMETRO` |
| 08 | `08_vistas.sql` | 10 vistas, más `PAGO.FECHA_APROBACION` y el parámetro de stock bajo |
| 09 | `09_pkg_utilidades.sql` | `PKG_UTILIDADES` |
| 10 | `10_pkg_precios.sql` | `PKG_PRECIOS` |
| 11 | `11_pkg_inventario.sql` | `PKG_INVENTARIO` |
| 12 | `12_pkg_reserva.sql` | `PKG_RESERVA` |
| 13 | `13_pkg_carrito.sql` | `PKG_CARRITO` |
| 14 | `14_pkg_pedido_estado.sql` | `PKG_PEDIDO_ESTADO` |
| 15 | `15_pkg_pedido.sql` (versión 2) | `PKG_PEDIDO`, con `iniciar_pago` |
| 16 | `16_pkg_cola.sql` (versión 2) | `PKG_COLA`, con `completar_erp` |
| 17 | `17_pkg_pago.sql` | `PKG_PAGO` |
| 18 | `18_pkg_tareas.sql` | `PKG_TAREAS` |
| 19 | `19_jobs.sql` | 5 JOBs de `DBMS_SCHEDULER` |
| 20 | `20_permisos.sql` | GRANTs a los tres roles |

Para una instalación nueva, el orden es: 01, 01b, 03, 04, 05, 06, 07, 08, 09 a 20. El 05 solo necesita que antes estén el 04 y el 07.

---

## 5. Modelo de datos (28 tablas)

Se listan las columnas útiles para el backend. Se omiten `FECHA_CREACION` y `FECHA_MODIFICACION`.

### 5.1 Clientes y ubicación

| Tabla | Columnas clave | Reglas |
|---|---|---|
| `TIPO_DOCUMENTO` | `ID_TIPO_DOCUMENTO`, `CODIGO` (único), `NOMBRE`, `ACTIVO` | Datos iniciales: `CC`, `CE`, `NIT`, `PAS` |
| `CLIENTE` | `ID_CLIENTE`, `ID_PUBLICO`, `ID_TIPO_DOCUMENTO`, `NUMERO_DOCUMENTO`, `NOMBRE`, `EMAIL`, `TELEFONO`, `ACTIVO` | Únicos: (tipo, número) y `UPPER(EMAIL)`. **La autenticación vive fuera de la BD**: no hay contraseñas |
| `CLIENTE_DIRECCION` | `ID_CLIENTE_DIRECCION`, `ID_CLIENTE`, `DIRECCION`, `CODIGO_MUNICIPIO` (FK a `MUNICIPIO`), `ES_PRINCIPAL`, `ACTIVO` | Una sola dirección principal activa por cliente. Son direcciones de facturación |
| `DEPARTAMENTO` | `CODIGO_DANE` VARCHAR2(2) (PK), `NOMBRE`, `ACTIVO` | 33 filas (incluye Bogotá D.C. = `11`) |
| `MUNICIPIO` | `CODIGO_DANE` VARCHAR2(5) (PK), `CODIGO_DEPARTAMENTO`, `NOMBRE`, `TIPO`, `ACTIVO` | 1.122 filas. `TIPO`: `MUNICIPIO` (1.103), `AREA_NO_MUNICIPALIZADA` (18), `ISLA` (1). Los dos primeros dígitos son el departamento. **Se cruza siempre por código**, porque hay nombres repetidos. Nombres oficiales DANE en mayúsculas con tildes |

### 5.2 Catálogo, precios y promociones

| Tabla | Columnas clave | Reglas |
|---|---|---|
| `IMPUESTO` | `ID_IMPUESTO`, `NOMBRE`, `TASA` NUMBER(5,4), `ACTIVO` | Datos iniciales: `IVA 19%` (0.19) y `Sin IVA` (0) |
| `CATEGORIA` | `ID_CATEGORIA`, `ID_CATEGORIA_PADRE`, `NOMBRE`, `ACTIVO` | Jerárquica. La BD solo impide que una categoría sea su propio padre; los ciclos largos los vigila la aplicación |
| `PRODUCTO` | `ID_PRODUCTO`, `ID_CATEGORIA`, `ID_IMPUESTO`, `NOMBRE`, `DESCRIPCION`, `ACTIVO` | El impuesto se define por producto |
| `VARIANTE` | `ID_VARIANTE`, `ID_PRODUCTO`, `SKU` (único, en mayúsculas y sin espacios), `NOMBRE`, `PRECIO_BASE`, `CONTROLA_STOCK`, `ACTIVO` | **Lo que se vende es la variante**: en carrito, pedido y stock se usa `ID_VARIANTE`. `CONTROLA_STOCK = 0` para servicios o productos digitales sin inventario |
| `PRECIO_HIST` | `ID_VARIANTE`, `PRECIO_ANTERIOR`, `PRECIO_NUEVO`, `USUARIO` | Lo llena el trigger `TRG_VARIANTE_PRECIO_HIST` cuando cambia `PRECIO_BASE`. Solo inserción |
| `PROMOCION` | `ID_PROMOCION`, `NOMBRE`, `TIPO_DESCUENTO` (`PORCENTAJE` / `MONTO`), `VALOR`, `ACTIVA` | Porcentaje entre 0 y 100. **No tiene fechas de vigencia**: está activa o no |
| `PROMOCION_VARIANTE` | PK `ID_VARIANTE`, `ID_PROMOCION` | Una variante está como máximo en una promoción |

### 5.3 Inventario

| Tabla | Columnas clave | Reglas |
|---|---|---|
| `STOCK` | PK `ID_VARIANTE`, `CANTIDAD_TOTAL`, `CANTIDAD_RESERVADA`, `CANTIDAD_DISPONIBLE` (virtual = total − reservada) | CHECK: `0 <= reservada <= total`. Solo existe para variantes con stock controlado, desde su primera entrada |
| `MOVIMIENTO_STOCK` (kardex) | `ID_VARIANTE`, `TIPO`, `CANTIDAD`, `ID_PEDIDO`, `OBSERVACION`, `USUARIO` | `TIPO`: `ENTRADA`, `AJUSTE` (con signo), `RESERVA`, `LIBERACION`, `CONSUMO`. Solo inserción. `V_CONCILIACION_STOCK` compara el kardex con `STOCK` |

### 5.4 Carrito

| Tabla | Columnas clave | Reglas |
|---|---|---|
| `CARRITO` | `ID_CARRITO`, `ID_CLIENTE`, `ESTADO` (`ABIERTO` / `CONVERTIDO` / `ABANDONADO`), `FECHA_ULTIMA_ACTIVIDAD`, `AVISO_ABANDONO_ENVIADO` | Un solo carrito ABIERTO por cliente. **No guarda precios ni reserva stock** |
| `CARRITO_ITEM` | `ID_CARRITO`, `ID_VARIANTE`, `CANTIDAD` > 0 | Único (carrito, variante) |

### 5.5 Pedidos

| Tabla | Columnas clave | Reglas |
|---|---|---|
| `ESTADO_PEDIDO` | `ID_ESTADO_PEDIDO`, `CODIGO`, `NOMBRE`, `ES_FINAL` | `PENDIENTE_PAGO` (0), `PAGADO` (1), `CANCELADO` (1), `EXPIRADO` (1) |
| `PEDIDO` | `ID_PEDIDO`, `ID_PUBLICO`, `ID_CLIENTE`, `ID_ESTADO_PEDIDO`, `MOTIVO_ESTADO`, `ID_CARRITO` (único), `FECHA_EXPIRACION`, `FACT_TIPO_DOCUMENTO`, `FACT_NUMERO_DOCUMENTO`, `FACT_NOMBRE`, `FACT_EMAIL`, `FACT_DIRECCION`, `FACT_CODIGO_MUNICIPIO`, `SUBTOTAL`, `DESCUENTO_TOTAL`, `IMPUESTO_TOTAL`, `TOTAL` | **Foto fiscal** copiada al comprar y que no se modifica. `TOTAL = SUBTOTAL + IMPUESTO_TOTAL`. `SUBTOTAL` ya tiene aplicados los descuentos; `DESCUENTO_TOTAL` es informativo. **No existe número de pedido visible** (pendiente) |
| `PEDIDO_ITEM` | `ID_PEDIDO_ITEM`, `ID_PEDIDO`, `ID_VARIANTE`, `SKU`, `NOMBRE_ITEM`, `CANTIDAD`, `PRECIO_UNITARIO`, `ID_PROMOCION`, `DESCUENTO_UNITARIO`, `TASA_IMPUESTO`, `SUBTOTAL_LINEA`, `IMPUESTO_LINEA`, `TOTAL_LINEA` | Solo inserción. `SUBTOTAL_LINEA = (precio − descuento) × cantidad`. `IMPUESTO_LINEA = ROUND(subtotal × tasa, 2)`, regla **provisional** |
| `PEDIDO_ESTADO_HIST` | `ID_PEDIDO`, `ID_ESTADO_ANTERIOR`, `ID_ESTADO_NUEVO`, `MOTIVO`, `USUARIO` | Lo escribe un trigger en cada cambio de estado. Solo inserción |
| `RESERVA_STOCK` | PK `ID_PEDIDO_ITEM`, `ESTADO` (`ACTIVA` / `CONSUMIDA` / `LIBERADA`) | Solo para líneas cuya variante controla stock |
| `PEDIDO_ERP` | PK `ID_PEDIDO`, `ID_ERP` (único) | Si existe la fila, el pedido ya está sincronizado con el ERP. Se escribe con `PKG_COLA.completar_erp` |

### 5.6 Pagos

| Tabla | Columnas clave | Reglas |
|---|---|---|
| `PASARELA_PAGO` | `ID_PASARELA_PAGO`, `CODIGO` (único), `NOMBRE`, `ACTIVA` | **Está vacía.** Se inserta la pasarela cuando se defina cuál será |
| `PAGO` | `ID_PAGO`, `ID_PUBLICO`, `ID_PEDIDO`, `ID_PASARELA_PAGO`, `REFERENCIA_EXTERNA`, `METODO_PAGO`, `MONTO`, `MONEDA` (`COP`), `ESTADO`, `FECHA_APROBACION` | Un intento de pago. Un pedido puede tener varios (reintentos), pero **como máximo uno APROBADO**. `ESTADO`: `PENDIENTE` / `APROBADO` / `RECHAZADO` / `ANULADO`. Un pago APROBADO tiene `FECHA_APROBACION`. Hay un solo pago por referencia externa en cada pasarela |
| `PAGO_EVENTO` | `ID_PAGO_EVENTO`, `ID_PAGO`, `ID_EVENTO_EXTERNO`, `ESTADO_REPORTADO`, `PAYLOAD` (CLOB JSON) | Mensaje crudo de cada notificación. Solo inserción. Único por (pago, evento externo) |

### 5.7 Cola, cierre y parámetros

| Tabla | Columnas clave | Reglas |
|---|---|---|
| `COLA_EVENTO` | `ID_COLA_EVENTO`, `TIPO_EVENTO`, `CLAVE_IDEMPOTENCIA` (única), `PAYLOAD` (JSON), `ESTADO` (`PENDIENTE` / `PROCESANDO` / `PROCESADO` / `ERROR`), `INTENTOS`, `PROXIMO_INTENTO`, `FECHA_BLOQUEO`, `FECHA_PROCESADO`, `ULTIMO_ERROR` | Patrón outbox (ver §10) |
| `CIERRE_DIARIO` | PK `FECHA`, `PEDIDOS_PAGADOS`, `SUBTOTAL`, `IMPUESTO`, `TOTAL_PAGADO`, `TOTAL_PAGOS_APROBADOS`, `DIFERENCIA` (virtual) | Una fila por día. Solo inserción. Si `DIFERENCIA` es distinta de 0, pedidos y pagos no cuadran |
| `PARAMETRO` | PK `CLAVE`, `VALOR` NUMBER(10) > 0, `DESCRIPCION` | Ver §8 |

---

## 6. Estados y transiciones

**Pedido** (solo lo cambia `PKG_PEDIDO_ESTADO`):

```
(crear_desde_carrito)
        │
        ▼
 PENDIENTE_PAGO ──pago aprobado──────────────► PAGADO
        │  │
        │  └──cliente cancela────────────────► CANCELADO
        │
        └──vence FECHA_EXPIRACION (JOB)──────► EXPIRADO ──pago tardío con stock──► PAGADO (*)
```

(*) Es la **única** excepción a `ES_FINAL = 1` y está **pendiente de validar con negocio**. Si negocio la rechaza, el pago queda APROBADO, el pedido sigue EXPIRADO y el caso aparece en `V_CONCILIACION_PAGOS` para devolver el dinero.

- **Pago:** `PENDIENTE` → `APROBADO` | `RECHAZADO` | `ANULADO`. Un rechazo viejo que llega tarde no deshace una aprobación.
- **Reserva:** `ACTIVA` → `CONSUMIDA` (al pagar) | `LIBERADA` (al cancelar o expirar). Una reserva LIBERADA puede reactivarse si llega un pago tardío.
- **Carrito:** `ABIERTO` → `CONVERTIDO` (al crear el pedido). `ABANDONADO` existe en el CHECK, pero **no se usa**: el JOB solo marca `AVISO_ABANDONO_ENVIADO`.
- **Evento de cola:** `PENDIENTE` → `PROCESANDO` → `PROCESADO`, o vuelve a `PENDIENTE` con reintento, o pasa a `ERROR`.

---

## 7. Vistas (10)

| Vista | Para qué sirve | Quién la ve |
|---|---|---|
| `V_PRECIO_VIGENTE` | Catálogo vendible: `ID_VARIANTE`, `SKU`, `NOMBRE_ITEM` ("Producto - Variante"), `CONTROLA_STOCK`, `PRECIO_BASE`, `ID_PROMOCION`, `DESCUENTO_UNITARIO`, `PRECIO_FINAL`, `TASA_IMPUESTO`. Solo variantes y productos activos | Cliente, Empleado |
| `V_STOCK_BAJO` | Variantes con disponible ≤ `STOCK_BAJO_UNIDADES` | Empleado |
| `V_CONCILIACION_STOCK` | Diferencias entre `STOCK` y el kardex. **Vacía = todo cuadra** | Empleado |
| `V_PEDIDOS_PENDIENTES` | Pedidos PENDIENTE_PAGO con los minutos que les quedan | Empleado |
| `V_VENTAS_DIARIAS` | Ventas por día de pago | Empleado |
| `V_VENTAS_PRODUCTO` | Ventas por día y variante | Empleado |
| `V_TOP_CLIENTES` | Pedidos pagados y total comprado por cliente | Empleado |
| `V_CONCILIACION_PAGOS` | Pedidos y pagos que no cuadran (pagado sin pago, pago aprobado con pedido no pagado, monto distinto). **Vacía = todo cuadra** | Empleado |
| `V_CARRITOS_ABANDONADOS` | Candidatos al aviso de carrito abandonado | Empleado |
| `V_COLA_PENDIENTE` | Eventos sin resolver y si están atascados (`ATASCADO = 1`) | Empleado |

`V_PRECIO_VIGENTE` **no muestra el stock disponible**. Mostrar "agotado" o "quedan N" requiere una consulta nueva (pendiente).

---

## 8. Parámetros (`PARAMETRO`)

| Clave | Valor | Uso |
|---|---|---|
| `PEDIDO_EXPIRACION_MINUTOS` | 30 | Minutos que tiene el cliente para pagar antes de que se libere el stock |
| `CARRITO_ABANDONO_HORAS` | 24 | Horas sin actividad para avisar de un carrito abandonado |
| `COLA_MAX_INTENTOS` | 5 | Intentos de un evento antes de pasar a ERROR |
| `COLA_RETENCION_DIAS` | 90 | Días que se guardan los eventos PROCESADO |
| `COLA_PROCESANDO_MAX_MINUTOS` | 10 | Tiempo máximo en PROCESANDO antes de que el JOB lo rescate |
| `STOCK_BAJO_UNIDADES` | 5 | Umbral de `V_STOCK_BAJO` |
| `DIVIPOLA_CORTE_AAAAMMDD` | 20241230 | Versión de DIVIPOLA cargada |

Se cambian con un UPDATE (como `ECOMERS`) y se aplican de inmediato: no hay caché.

---

## 9. API de paquetes

Notación: `IN` / `OUT`. Todos son `AUTHID DEFINER`. Los parámetros `p_id_cliente` son siempre el **cliente autenticado** que pasa el backend.

### 9.1 Para el rol CLIENTE (backend de la tienda)

#### `PKG_CARRITO`

| Subprograma | Firma | Comportamiento | Errores |
|---|---|---|---|
| `obtener_carrito` | `FUNCTION (p_id_cliente) RETURN NUMBER` | Devuelve el ID del carrito ABIERTO; lo crea si no existe. Es seguro ante llamadas simultáneas | -20140 |
| `agregar_item` | `PROCEDURE (p_id_cliente, p_id_variante, p_cantidad)` | Suma unidades (si la variante ya estaba, incrementa). Exige que la variante tenga precio vigente | -20140, -20141, -20101, -20144 |
| `cambiar_cantidad` | `PROCEDURE (p_id_cliente, p_id_variante, p_cantidad)` | Fija la cantidad exacta | -20140, -20141, -20143, -20144 |
| `quitar_item` | `PROCEDURE (p_id_cliente, p_id_variante)` | Idempotente | -20140, -20144 |
| `vaciar` | `PROCEDURE (p_id_cliente)` | Idempotente | -20140, -20144 |

Todas las operaciones que modifican el carrito lo bloquean (`FOR UPDATE`) y actualizan `FECHA_ULTIMA_ACTIVIDAD`. Los productos se identifican por **variante**, no por el ID de la fila del carrito.

#### `PKG_PEDIDO`

| Subprograma | Firma | Comportamiento | Errores |
|---|---|---|---|
| `crear_desde_carrito` | `FUNCTION (p_id_cliente, p_id_direccion DEFAULT NULL) RETURN NUMBER` (ID_PEDIDO) | Convierte el carrito ABIERTO en un pedido `PENDIENTE_PAGO`: congela precios, descuentos e impuestos, copia la facturación y reserva el stock. `p_id_direccion` NULL = dirección principal. Es todo o nada: si una línea falla, no queda pedido y el carrito sigue ABIERTO. Un doble clic devuelve -20162 | -20161, -20162, -20163, -20168, -20169, -20101, -20122 |
| `cancelar` | `PROCEDURE (p_id_cliente, p_id_pedido, p_motivo DEFAULT NULL)` | Cancela un pedido propio en PENDIENTE_PAGO y libera el stock. El motivo queda como "Cancelado por el cliente: …". Idempotente | -20160, -20164, -20168 |
| `iniciar_pago` | `PROCEDURE (p_id_cliente, p_id_pedido, p_codigo_pasarela, p_id_pago OUT, p_id_publico OUT VARCHAR2, p_monto OUT)` | Crea el intento de pago (PAGO PENDIENTE) **por el total del pedido**; el monto nunca lo manda el cliente. Si ya hay un intento PENDIENTE con esa pasarela, lo devuelve. `p_id_publico` (hexadecimal de 32 caracteres) es la **referencia que el backend envía a la pasarela** | -20160, -20164, -20166, -20167, -20168 |

Un pedido de otro cliente responde -20160, igual que uno inexistente. Así no se pueden enumerar IDs.

### 9.2 Para el rol EMPLEADO

#### `PKG_INVENTARIO`

| Subprograma | Firma | Comportamiento | Errores |
|---|---|---|---|
| `entrada` | `PROCEDURE (p_id_variante, p_cantidad, p_observacion DEFAULT NULL)` | Suma stock (crea la fila de `STOCK` en la primera entrada) y registra ENTRADA en el kardex | -20120, -20121 |
| `ajuste` | `PROCEDURE (p_id_variante, p_cantidad, p_observacion)` | Cantidad con signo (mermas o conteos). La observación es obligatoria. No puede dejar el total por debajo de lo reservado | -20120, -20121, -20123, -20125 |

### 9.3 Para el rol INTEGRACIÓN

#### `PKG_PAGO` (webhook)

| Subprograma | Firma | Comportamiento | Errores |
|---|---|---|---|
| `registrar_notificacion` | `FUNCTION (p_codigo_pasarela, p_referencia_pago, p_referencia_externa, p_id_evento_externo, p_estado, p_monto, p_metodo_pago, p_payload CLOB) RETURN NUMBER` (ID_PAGO_EVENTO) | Guarda el mensaje crudo y **encola** `PAGO_NOTIFICADO` en la misma transacción. Es rápido. Si el mismo `p_id_evento_externo` ya llegó, devuelve el evento existente sin encolar otra vez. `p_referencia_pago` es el `ID_PUBLICO` en hexadecimal. `p_estado` es el estado **ya traducido** por el backend: `APROBADO` / `RECHAZADO` / `PENDIENTE` / `ANULADO`. `p_monto` es obligatorio si el estado es APROBADO. La referencia externa se fija con la primera notificación | -20180 a -20184, -20188 |
| `procesar_notificacion` | `FUNCTION (p_payload_cola CLOB) RETURN VARCHAR2` | **Lo usa el JOB, no el backend.** Aplica el evento al pago y al pedido | -20185 a -20189 |

#### `PKG_COLA` (consumidor de eventos)

| Subprograma | Firma | Comportamiento | Errores |
|---|---|---|---|
| `tomar_lote` | `FUNCTION (p_tipo_evento, p_limite DEFAULT 10) RETURN SYS_REFCURSOR` | Toma hasta `p_limite` (1–100) eventos PENDIENTE cuyo turno ya llegó y los pasa a PROCESANDO (`FOR UPDATE SKIP LOCKED`). Columnas del cursor: `ID_COLA_EVENTO`, `TIPO_EVENTO`, `CLAVE_IDEMPOTENCIA`, `PAYLOAD`, `INTENTOS`. **Hay que hacer COMMIT enseguida** | -20200, -20203 |
| `completar` | `PROCEDURE (p_id_cola_evento)` | Marca el evento PROCESADO (solo si está PROCESANDO) | -20204 |
| `completar_erp` | `PROCEDURE (p_id_cola_evento, p_id_pedido, p_id_erp)` | Para `SINCRONIZAR_ERP`: guarda `PEDIDO_ERP` y completa el evento **en una sola transacción**. Si llega otra vez el mismo id ERP, no es error | -20204, -20206 a -20209 |
| `fallar` | `PROCEDURE (p_id_cola_evento, p_error)` | Reprograma con espera de 1, 2, 4 y 8 minutos. Al llegar a `COLA_MAX_INTENTOS`, pasa a ERROR | -20204 |
| `encolar` | `FUNCTION (p_tipo_evento, p_clave, p_payload CLOB) RETURN NUMBER` | Idempotente por clave. Normalmente lo usan los paquetes, no el backend | -20200 a -20202 |
| `reactivar` | `PROCEDURE (p_id_cola_evento)` | Devuelve a PENDIENTE (con `INTENTOS = 0`) un evento en ERROR ya revisado | -20205 |
| `rescatar_atascados`, `purgar_procesados` | `FUNCTION RETURN NUMBER` | Los usan los JOBs | — |

Constantes: `PKG_COLA.c_pago_notificado`, `c_enviar_correo` y `c_sincronizar_erp`.

### 9.4 Paquetes internos (sin permisos para ningún rol)

| Paquete | Rol interno |
|---|---|
| `PKG_UTILIDADES` | `ahora` (TIMESTAMP local), `hoy` (DATE) y `valor_parametro(clave)` (-20100 si la clave no existe) |
| `PKG_PRECIOS` | `precio_vigente(id_variante)` y `calcular_linea(id_variante, cantidad)`, que devuelve un registro listo para `PEDIDO_ITEM`. Constante `c_decimales_impuesto = 2` (provisional) |
| `PKG_RESERVA` | `reservar`, `liberar` y `consumir` por línea de pedido. Usa un UPDATE condicional atómico sobre `STOCK` y escribe el kardex |
| `PKG_PEDIDO_ESTADO` | Único dueño de las transiciones de estado: `marcar_pagado` (devuelve `PAGADO`, `YA_PAGADO`, `PAGADO_REACTIVADO`, `SIN_STOCK` o `NO_PAGABLE`), `cancelar(id, motivo)` y `expirar(id)` (BOOLEAN) |
| `PKG_TAREAS` | Lo que ejecutan los JOBs. **Es el único paquete que hace COMMIT**, uno por unidad de trabajo |

---

## 10. Flujos de punta a punta

### 10.1 Compra (usuario `ECOMERS_CLIENTE_APP`)

1. **Catálogo:** `SELECT ... FROM ECOMERS.V_PRECIO_VIGENTE`.
2. **Carrito:** `PKG_CARRITO.agregar_item`, `cambiar_cantidad`, `quitar_item` y `vaciar`, seguidos de COMMIT.
3. **Checkout:** `id_pedido := PKG_PEDIDO.crear_desde_carrito(id_cliente, id_direccion)` y COMMIT.
   - Si falla con -20122, una variante no tiene stock suficiente.
   - Si falla con -20101, una variante se desactivó.
   - En ambos casos el mensaje dice cuál es; el cliente corrige el carrito y reintenta.
4. **Pagar:** `PKG_PEDIDO.iniciar_pago(id_cliente, id_pedido, 'CODIGO_PASARELA', id_pago, id_publico, monto)` y COMMIT. Luego el backend crea la transacción en la pasarela con `referencia = id_publico` y `monto`, y redirige al cliente.
5. El cliente tiene `PEDIDO_EXPIRACION_MINUTOS` (30) para pagar. Pasado ese tiempo, el JOB expira el pedido y libera el stock.

### 10.2 Webhook de la pasarela (usuario `ECOMERS_INTEGRACION_APP`)

1. **Verificar la firma de la pasarela.** Es el control de seguridad real, porque la BD confía en lo que recibe por este canal.
2. Traducir el estado de la pasarela a `APROBADO` / `RECHAZADO` / `PENDIENTE` / `ANULADO`.
3. `PKG_PAGO.registrar_notificacion(...)` y COMMIT.
4. **Responder 200 a la pasarela sin esperar más.** Aplicar el pago es trabajo del JOB.

Si la pasarela reenvía el mismo evento, no pasa nada: la llamada es idempotente.

### 10.3 Lo que hace el JOB con un pago (`JOB_ECOMERS_COLA`, cada 30 s)

`PKG_PAGO.procesar_notificacion` bloquea primero el PAGO y después el PEDIDO:

- **APROBADO:**
  1. Si el monto reportado no es exactamente el del pago, lanza **-20186** y no aprueba.
  2. Si el monto cuadra, aprueba el pago y llama a `marcar_pagado`.
  3. Con `PAGADO` o `PAGADO_REACTIVADO`, encola `ENVIAR_CORREO` (plantilla `PEDIDO_PAGADO`) y `SINCRONIZAR_ERP`.
  4. Con `SIN_STOCK` o `NO_PAGABLE`, el pago queda APROBADO y el pedido no; el caso aparece en `V_CONCILIACION_PAGOS` para devolver el dinero a mano.
  5. Un segundo pago aprobado del mismo pedido lanza **-20187**.
- **RECHAZADO:** pasa a RECHAZADO un pago PENDIENTE; en cualquier otro estado lo ignora.
- **ANULADO:** anula un pago PENDIENTE. Si el pago ya estaba APROBADO, lanza **-20189** (revisión manual).
- **PENDIENTE:** no hace nada.

Los errores llevan el evento a `PKG_COLA.fallar`. Tras 5 intentos queda en ERROR, con el motivo en `ULTIMO_ERROR`, visible en `V_COLA_PENDIENTE`.

### 10.4 Consumidor de correos y ERP (backend, usuario `ECOMERS_INTEGRACION_APP`)

Ciclo recomendado para cada tipo (`ENVIAR_CORREO`, `SINCRONIZAR_ERP`):

```
cursor := ECOMERS.PKG_COLA.tomar_lote('ENVIAR_CORREO', 10);
leer filas; COMMIT;                       -- suelta los bloqueos, los eventos quedan en PROCESANDO
para cada evento:
   hacer el trabajo (enviar correo / llamar al ERP)
   éxito  -> PKG_COLA.completar(id)  o  PKG_COLA.completar_erp(id, id_pedido, id_erp); COMMIT
   fallo  -> PKG_COLA.fallar(id, 'mensaje'); COMMIT
```

- La entrega es **"al menos una vez"**: un evento puede procesarse dos veces. El consumidor debe tolerarlo; por ejemplo, el ERP debe usar `id_pedido` como clave de idempotencia.
- Si el consumidor se cae a mitad de un evento, el JOB lo devuelve a PENDIENTE a los 10 minutos.
- `SINCRONIZAR_ERP` **siempre** se completa con `completar_erp`. Si se usa `completar`, el JOB de revisión falla con -20211.
- `PAGO_NOTIFICADO` lo consume el JOB de la base. **El backend no debe tomar ese tipo.**
- El detalle del pedido para el correo y el ERP se lee de `ECOMERS.PEDIDO` y `ECOMERS.PEDIDO_ITEM` (SELECT otorgado). El email está en `PEDIDO.FACT_EMAIL`.

### 10.5 Otros procesos automáticos

- **Expiración:** cada minuto, los pedidos PENDIENTE_PAGO vencidos pasan a EXPIRADO y su stock se libera. No se envía correo.
- **Carrito abandonado:** cada hora, los carritos con productos y sin actividad durante 24 h reciben un `ENVIAR_CORREO` (plantilla `CARRITO_ABANDONADO`). Se avisa una sola vez por carrito.
- **Cierre diario:** a las 00:10 (hora de Bogotá) se cierran los días pendientes en `CIERRE_DIARIO` y se purga la cola procesada.
- **Revisión ERP:** cada hora (en el minuto 30) se encola la sincronización de los pedidos PAGADO que no tienen `PEDIDO_ERP` ni evento.

---

## 11. Eventos de la cola: claves y payloads

| Tipo | Clave de idempotencia | Payload (JSON) | Lo produce | Lo consume |
|---|---|---|---|---|
| `PAGO_NOTIFICADO` | `PAGO_NOTIFICADO:PAGO_EVENTO:<id_pago_evento>` | `{"id_pago_evento":123,"monto":238000,"metodo":"PSE"}` | `PKG_PAGO.registrar_notificacion` | JOB (BD) |
| `ENVIAR_CORREO` (pedido pagado) | `ENVIAR_CORREO:PEDIDO_PAGADO:<id_pedido>` | `{"plantilla":"PEDIDO_PAGADO","id_pedido":45}` | `PKG_PAGO.procesar_notificacion` | Backend |
| `ENVIAR_CORREO` (carrito abandonado) | `ENVIAR_CORREO:CARRITO_ABANDONADO:<id_carrito>` | `{"plantilla":"CARRITO_ABANDONADO","id_carrito":7,"id_cliente":3,"nombre":"...","email":"..."}` | `PKG_TAREAS.avisar_carritos_abandonados` | Backend |
| `SINCRONIZAR_ERP` | `SINCRONIZAR_ERP:PEDIDO:<id_pedido>` | `{"id_pedido":45}` | `PKG_PAGO` y `PKG_TAREAS.revisar_sincronizacion_erp` | Backend (con `completar_erp`) |

---

## 12. JOBs (`DBMS_SCHEDULER`, dueño `ECOMERS`, hora de Bogotá)

| JOB | Frecuencia | Acción |
|---|---|---|
| `JOB_ECOMERS_COLA` | Cada 30 s | `PKG_TAREAS.procesar_cola`: rescata atascados y aplica los pagos |
| `JOB_ECOMERS_EXPIRAR` | Cada minuto | `PKG_TAREAS.expirar_pedidos` |
| `JOB_ECOMERS_CARRITOS` | Cada hora (:00) | `PKG_TAREAS.avisar_carritos_abandonados` |
| `JOB_ECOMERS_ERP` | Cada hora (:30) | `PKG_TAREAS.revisar_sincronizacion_erp` |
| `JOB_ECOMERS_CIERRE` | 00:10 diario | `PKG_TAREAS.cerrar_dias_pendientes` + `purgar_cola` |

Los fallos se consultan en `USER_SCHEDULER_JOB_RUN_DETAILS WHERE status <> 'SUCCEEDED'`. La bitácora se purga sola a los 30 días.

---

## 13. Catálogo de errores (`ORA-20xxx`)

| Código | Paquete | Significado | Sugerencia HTTP |
|---|---|---|---|
| -20010 | Triggers | Tabla de solo inserción (UPDATE o DELETE prohibido) | 500 (bug) |
| -20011 | Trigger | La foto del pedido no se modifica | 500 (bug) |
| -20100 | UTILIDADES | Falta un parámetro | 500 |
| -20101 | PRECIOS | Variante inexistente o inactiva (sin precio vigente) | 409 / 404 |
| -20102 | PRECIOS | Cantidad inválida | 400 |
| -20120 | INVENTARIO | Variante inexistente, sin stock controlado o sin saldo inicial | 404 / 409 |
| -20121 | INVENTARIO | Cantidad inválida | 400 |
| -20122 | RESERVA | **Stock insuficiente** | 409 |
| -20123 | INVENTARIO | El ajuste dejaría el total por debajo de lo reservado o bajo cero | 409 |
| -20124 | RESERVA | La reserva ya existe o no está ACTIVA (inconsistencia) | 500 |
| -20125 | INVENTARIO | Falta la observación del ajuste | 400 |
| -20126 | RESERVA | La línea de pedido no existe | 500 |
| -20127 | RESERVA | Hay reserva pero no hay saldo de stock | 500 |
| -20140 | CARRITO | Cliente inexistente o inactivo | 403 / 404 |
| -20141 | CARRITO | Cantidad inválida | 400 |
| -20143 | CARRITO | La variante no está en el carrito | 404 |
| -20144 | CARRITO | No se pudo bloquear el carrito (reintentar) | 409 / 503 |
| -20160 | PEDIDO(_ESTADO) | El pedido no existe o no es del cliente | 404 |
| -20161 | PEDIDO | Cliente inexistente o inactivo | 403 / 404 |
| -20162 | PEDIDO | No hay carrito abierto con productos (incluye el doble clic) | 409 |
| -20163 | PEDIDO | Dirección de facturación inválida o sin dirección principal | 400 / 409 |
| -20164 | PEDIDO(_ESTADO) | El estado del pedido no permite la operación | 409 |
| -20165 | PEDIDO_ESTADO | Falta el motivo de cancelación | 400 |
| -20166 | PEDIDO | El pedido ya venció: no se puede pagar | 409 / 410 |
| -20167 | PEDIDO | Pasarela inexistente o inactiva | 400 / 503 |
| -20168 | PEDIDO(_ESTADO) | Carrito o pedido ocupado por otra operación (**reintentar**) | 409 / 503 |
| -20169 | PEDIDO(_ESTADO) | Inconsistencia interna o configuración incompleta | 500 |
| -20180 | PAGO | No existe un pago con esa referencia para esa pasarela | 404 |
| -20181 | PAGO | Estado reportado inválido | 400 |
| -20182 | PAGO | La referencia externa no coincide o ya pertenece a otro pago | 409 |
| -20183 | PAGO | El payload no es JSON válido | 400 |
| -20184 | PAGO | Una aprobación sin monto | 400 |
| -20185 | PAGO | Evento de cola inválido | (JOB) |
| -20186 | PAGO | El monto aprobado no coincide con el del pago | (JOB → ERROR) |
| -20187 | PAGO | Segundo pago aprobado del mismo pedido: devolver el dinero | (JOB → ERROR) |
| -20188 | PAGO | Pago ocupado (reintentar) | 503 |
| -20189 | PAGO | La pasarela anuló un pago ya aprobado: revisión manual | (JOB → ERROR) |
| -20200 | COLA | Tipo de evento inválido | 400 |
| -20201 | COLA | Payload vacío o que no es JSON | 400 |
| -20202 | COLA | Clave de idempotencia vacía | 400 |
| -20203 | COLA | Límite de lote fuera de 1 a 100 | 400 |
| -20204 | COLA | El evento no existe o no está PROCESANDO (otro lo tomó, o ya se completó) | 409 (ignorar) |
| -20205 | COLA | El evento no está en ERROR | 409 |
| -20206 | COLA | Falta el id ERP | 400 |
| -20207 | COLA | Conflicto de id ERP | 409 |
| -20208 | COLA | El pedido no existe | 404 |
| -20209 | COLA | El evento no corresponde a la sincronización de ese pedido | 400 |
| -20210 | TAREAS | Una tarea terminó con fallos (ver el mensaje) | (JOB) |
| -20211 | TAREAS | Sincronización PROCESADA sin `PEDIDO_ERP` | (JOB) |
| -20212 | TAREAS | Fecha de cierre inválida | (JOB) |

Los códigos -20001 a -20004 solo los usan los scripts de instalación.

---

## 14. Reglas para el backend

1. Conectarse a `XEPDB1` con el usuario del rol que corresponda. **Nunca como `ECOMERS`.**
2. Hacer COMMIT o ROLLBACK después de cada operación de negocio. Los paquetes no confirman.
3. Pasar siempre el `ID_CLIENTE` del usuario autenticado. Nunca tomarlo del cuerpo de la petición sin validarlo contra la sesión.
4. Exponer `ID_PUBLICO` en hexadecimal hacia el front, no los IDs numéricos.
5. Mapear los errores por código `ORA-20xxx` (§13). Reintentar automáticamente -20144, -20168 y -20188.
6. Fijar `DBMS_SESSION.SET_IDENTIFIER` por petición, para la trazabilidad.
7. Webhook: verificar la firma, traducir el estado, `registrar_notificacion`, COMMIT y responder 200 sin esperar.
8. Consumidores de la cola: `tomar_lote` y COMMIT inmediato; luego `completar` / `completar_erp` / `fallar`. Ser idempotentes.
9. Leer fechas de la BD como hora local de Colombia (TIMESTAMP sin zona).
10. No mostrar ni validar precios calculados en el front como si fueran la verdad. El precio definitivo es el que congela `crear_desde_carrito`.

---

## 15. Lo que FALTA (brechas conocidas)

### 15.1 Falta en la BD (el backend lo va a necesitar)

| Brecha | Impacto | Propuesta |
|---|---|---|
| **No hay API para registrar ni actualizar clientes y direcciones** | El backend no puede dar de alta compradores: ningún rol tiene INSERT y no existe un paquete para esto | `PKG_CLIENTE` (rol cliente): registrar, actualizar datos, agregar, editar o desactivar direcciones y marcar la principal |
| **No hay API de administración del catálogo** (categorías, productos, variantes, precios, promociones, pasarelas) | El backoffice no puede cargar productos | `PKG_CATALOGO` (rol empleado). Los cambios de precio ya se auditan solos con el trigger |
| **No hay consultas para el cliente** ("mis pedidos", detalle de un pedido, "mi carrito" con precios, resolver `ID_PUBLICO` → `ID_PEDIDO`) | El rol cliente no tiene SELECT sobre `PEDIDO` ni `CARRITO`, y los paquetes reciben el `ID_PEDIDO` numérico | Funciones con `SYS_REFCURSOR` en `PKG_PEDIDO` y `PKG_CARRITO` filtradas por `ID_CLIENTE`, y una que traduzca `ID_PUBLICO` a `ID_PEDIDO` |
| **El stock disponible no se ve en el catálogo** | No se puede mostrar "agotado" | Agregar el disponible a una vista o función de catálogo |
| **No hay número de pedido visible** (consecutivo legible) | Solo existen el ID interno y el GUID | Decidir el formato (secuencia, prefijo, año) |
| `PASARELA_PAGO` vacía | `iniciar_pago` falla con -20167 | Insertar la pasarela cuando se elija |
| El rol empleado no puede cancelar pedidos ni reactivar eventos | Hay que hacerlo como `ECOMERS` | Definirlo con negocio y crear una fachada para empleados |
| Sin correo de "pedido expirado" | — | Decidir si se quiere y encolarlo en `expirar_pedidos` |

### 15.2 Decisiones de negocio pendientes

- **Pasarela de pago:** cuál será (define la traducción de estados y la firma del webhook).
- **Redondeo del IVA:** hoy se redondea a 2 decimales por línea (`PKG_PRECIOS.c_decimales_impuesto`). Hay que confirmarlo con contabilidad.
- **Pago tardío de un pedido EXPIRADO con stock:** hoy se reactiva y pasa a PAGADO. Hay que validarlo.
- **Tiempo para pagar:** 30 minutos (parámetro).
- **Permisos del rol empleado.**
- **Vigencia de las promociones por fecha:** hoy solo existe activa o inactiva.

---

## 16. Garantías de concurrencia (para explicar en revisiones)

- **Sin sobreventa:** la reserva es un único `UPDATE STOCK ... WHERE total − reservada >= :q`. Si no actualiza filas, no hay stock suficiente. Nunca se lee y luego se escribe.
- **Sin interbloqueos:** las líneas de un pedido se reservan en orden ascendente de `ID_VARIANTE`. El pago bloquea siempre PAGO y luego PEDIDO; nadie bloquea en el orden contrario.
- **Un carrito no se convierte dos veces:** se bloquea con `FOR UPDATE WAIT 10`, y además `UNIQUE (PEDIDO.ID_CARRITO)`.
- **Pago y expiración simultáneos:** ambos bloquean el pedido y deciden según el estado que leen ya bloqueado.
- **Un solo pago aprobado:** lo garantiza el índice único `UX_PAGO_APROBADO` (no una consulta previa).
- **Varios consumidores de la cola:** `SKIP LOCKED` evita que dos consumidores tomen el mismo evento.
- **Outbox:** el evento se encola en la misma transacción que el hecho que lo origina. Si hay COMMIT existen los dos; si hay ROLLBACK, ninguno.
