# Demo EP3 — Mensajería asíncrona: destinos, reglas, fallidos y administración

Guion de la presentación de la Evaluación Parcial N° 3 (DSY1107). Cada criterio de la rúbrica tiene **dónde está en el
código**, **por qué se diseñó así** y **cómo se demuestra**. Duración estimada: 15 minutos.

## 0. Preparación (10 minutos antes)

```bash
# AWS (Learner Lab iniciado y credenciales en ~/.aws/credentials)
cd cometranqui_back && bash infra/levantar.sh      # enciende todo, autoriza tu IP, corre el scraper (~3 min)

# o en local
docker compose up -d                               # Kafka (SASL/SCRAM) + Postgres + Kafka UI :8081
mvn spring-boot:run                                # API en :8080 (variables del .env)
```

- Web: https://cometranqui.vercel.app — iniciar sesión con una cuenta del grupo `admin` → **Administración**.
- Kafka UI: `http://<IP-broker>:8081` (usuario `admin`; clave en SSM `/cometranqui/kafka-ui/pass`).
- Token para `curl` (opcional): en la consola del navegador, `sessionStorage.getItem("cometranqui_access_token")`.

```bash
export API=https://d8ae57sdjk.execute-api.us-east-1.amazonaws.com   # o http://localhost:8080/api
export TOKEN=...                                                   # token de un usuario admin
```

## Mapa rúbrica → evidencia

| # | Criterio (peso) | Dónde está | Demo |
|---|---|---|---|
| 1 | Configuración centralizada de canales y rutas (12 %) | `application.yml` → `app.mensajeria`; `mensajeria/config/CatalogoMensajeria`, `TopicsConfig` | Paso 2 |
| 2 | Destinos y reglas por caso de uso (13 %) | `regla_distribucion` (Flyway V4), `MotorReglas`, `EnrutadorAlertas` | Pasos 5 y 6 |
| 3 | Lógica de negocio separada de la mensajería (10 %) | `eventos/PublicadorEventos`, `OfertaService`, `OfertaServiceTest` | Paso 3 |
| 4 | Consumidores por responsabilidad (15 %) | `mensajeria/consumidores/*` | Pasos 1 y 7 |
| 5 | Confirmación, reintentos y errores (20 %) | `mensajeria/config/ErroresConfig`, `ConsumidorFallidos`, `MensajeriaFlujosIntegrationTest` | Pasos 8 a 10 |
| 6 | Interfaz de administración (13 %) | Frontend `src/app/admin/**`; API `MensajeriaAdminController` | Pasos 4 a 10 |
| 7 | Operaciones encapsuladas (10 %) | `AdministracionMensajeriaService` (único uso de `AdminClient`), `ReglaDistribucionService`, `MensajesFallidosService` | Paso 4 y 11 |
| 8 | Validación de solicitudes (7 %) | `mensajeria/admin/Solicitudes` + validaciones de estado en los servicios + `ManejadorErroresAdmin` | Pasos 4 y 11 |

## 1. Arquitectura y casos de uso (criterios 2 y 4)

```
 Lambda scraper ─► ofertas.scrapeadas ─► [persistir-ofertas]  ─► OfertaService ─► RDS
                ─► locales.scrapeados ─► [persistir-locales]  ─► LocalService  ─► RDS
 OfertaService ─(PublicadorEventos)─► ofertas.alertas ─► [enrutar-alertas] aplica reglas
                                                             ├─► notificaciones.sin-gluten ─┐
                                                             ├─► notificaciones.vegano  ...─┤
                                                             └─ (sin ruta: log + ack)       │
                                       [notificar-usuarios] (patrón notificaciones.*) ◄──────┘ ─► notificaciones de usuarios
 cualquier fallo definitivo ─► ofertas.dlt ─► [registrar-fallidos] ─► mensaje_fallido ─► /admin «Fallidos»
```

| Consumidor (ruta) | Grupo | Escucha | Se activa cuando | Responsabilidad |
|---|---|---|---|---|
| `ConsumidorOfertas` (persistir-ofertas) | `cometranqui-api-ofertas` | `ofertas.scrapeadas` | Cada ejecución del scraper | Valida y guarda (UPSERT); puede generar una alerta |
| `ConsumidorLocales` (persistir-locales) | `cometranqui-api-locales` | `locales.scrapeados` | Cada ejecución del scraper | Actualiza el local y sus niveles |
| `EnrutadorAlertas` (enrutar-alertas) | `cometranqui-enrutador` | `ofertas.alertas` | Un precio baja ≥ 20 % | Evalúa reglas y reenvía a los destinos que coinciden |
| `ConsumidorNotificaciones` (notificar-usuarios) | `cometranqui-notificaciones` | `notificaciones.*` | Una regla enruta una alerta | Notifica a los suscritos a esa restricción |
| `ConsumidorFallidos` (registrar-fallidos) | `cometranqui-fallidos` | `ofertas.dlt` | Un mensaje falla definitivamente | Guarda el mensaje y su diagnóstico para revisarlo |

Los consumidores **solo traducen y delegan**: no contienen reglas de negocio.

## 2. Configuración centralizada (criterio 1)

Mostrar el bloque `app.mensajeria` de `src/main/resources/application.yml`: los **destinos** (canales) y las **rutas**
(quién consume cada canal, grupo y concurrencia) están en un solo lugar.

- Los topics se crean desde ahí al arrancar (`TopicsConfig`).
- Los `@KafkaListener` no tienen nombres escritos a mano: `topics = "#{@catalogo.topicDeRuta('persistir-ofertas')}"`.
- La Lambda recibe los nombres de topic desde el mismo catálogo (`infra/deploy-lambda.sh` → `catalogo_topic`).
- `CatalogoMensajeria` valida al arrancar que cada ruta apunte a un destino existente: si alguien rompe el catálogo,
  la aplicación **no arranca** (ver `CatalogoMensajeriaTest`). Así se evitan inconsistencias en producción.
- En la web: **Administración → Catálogo** muestra la configuración vigente.

**Por qué facilita cambios:** cambiar la retención de `ofertas.scrapeadas` o el nombre de un grupo es editar una línea;
no hay que buscar el nombre en listeners, scripts y la Lambda (antes estaba en 4 lugares).

## 3. Negocio separado de la mensajería (criterio 3)

- `eventos/PublicadorEventos` es una **interfaz del dominio**; `mensajeria/publicacion/KafkaPublicadorEventos` la implementa.
- `OfertaService` no importa nada de Kafka: decide *si* hay alerta; la capa de mensajería decide *dónde y cómo* se publica.
- Ejecutar `mvn -Dtest=OfertaServiceTest test`: 4 tests de la lógica de alertas **sin Kafka**, con un publicador en memoria.

**Efecto:** las reglas de negocio se prueban en milisegundos y sin infraestructura; cambiar de topic o de broker no toca
el dominio.

## 4. Administración de destinos y validación (criterios 6, 7 y 8)

En **Administración → Destinos**:

1. La tabla muestra los topics con particiones, retención, mensajes, reglas que los usan y si están **protegidos**.
2. Crear `notificaciones.ofertas-jumbo` (1 partición, 168 h) → aparece en la tabla (**201**).
3. Escribir `Mal Nombre!` y 50 particiones → el navegador marca los campos. Pulsar **Enviar igual al servidor** →
   el backend responde **400** con `errores[{campo, mensaje}]` (Problem Details).
4. Intentar crear `notificaciones.vegano` → **409** «ya existe».
5. Editar retención de `notificaciones.ofertas-jumbo` a 48 h (**200**); intentar bajar particiones → **400**.

Equivalente con `curl`:

```bash
curl -s -X POST "$API/admin/mensajeria/destinos" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"nombre":"Mal Nombre!","particiones":50}'
# {"title":"Solicitud inválida","status":400,"detail":"Revisa los campos marcados",
#  "errores":[{"campo":"nombre","mensaje":"Solo minúsculas, números, puntos y guiones ..."},
#             {"campo":"particiones","mensaje":"Debe estar entre 1 y 6"}]}
```

**Encapsulación:** `AdministracionMensajeriaService` es el **único** componente que usa `AdminClient`; el controlador
no tiene lógica y la interfaz solo ve DTOs. Si mañana cambia el broker, se reemplaza un servicio.

**Validación en dos capas:** formato y rangos con Bean Validation (`Solicitudes`), y reglas que dependen del estado en
los servicios: prefijo de una familia con consumidor, duplicados, protegidos, en uso, origen atendido por el enrutador.

## 5. Reglas de distribución (criterio 2)

En **Administración → Reglas**: las 4 reglas iniciales (una por restricción) y crear una nueva:

- Nombre `jumbo-grandes-descuentos`, tienda `JUMBO`, descuento mínimo 40, destino `notificaciones.ofertas-jumbo`.
- Intentar una regla sin condiciones → **400** «Indica al menos una condición».
- Intentar eliminar `notificaciones.ofertas-jumbo` en **Destinos** → **409** «en uso por las reglas: jumbo-grandes-descuentos».

## 6. Mensajes de prueba: por qué llegan al consumidor previsto (criterio 2)

En **Administración → Probar**:

1. Producto «Pan sin gluten», tienda `JUMBO`, restricción Sin Gluten, baja 45 % → **Enviar**.
2. El recorrido muestra cada regla: `alertas-sin-gluten` **coincide**; `jumbo-grandes-descuentos` **coincide**;
   `alertas-vegano` **no** («no es Vegano»).
3. Segundos después cada destino pasa a **«recibido por cometranqui-notificaciones»** con la cantidad de suscritos.
4. Repetir con Sin Lactosa y 10 % → ninguna regla coincide: «sin ruta» (motivo: «bajó 10% (mínimo 20%)»).
5. Con la cuenta suscrita a Sin Gluten (campana 🔔 del Header), aparece la notificación «[Prueba] Bajó 45%…».

## 7. Consumidores en vivo y lag (criterio 4)

En **Administración → Consumidores** activar «Actualizar cada 3 s» y ejecutar el scraper:

```bash
aws lambda invoke --function-name cometranqui-scraper --invocation-type Event /dev/null    # AWS
# o: botón POST /api/admin/scraper/run, o en local: FUENTES_ACTIVAS=MOCK MOCK_BAJA_PRECIO=true java -jar scraper-lambda/target/*.jar
```

El lag de `persistir-ofertas` sube y vuelve a 0. Con `MOCK_BAJA_PRECIO=true` (segunda corrida del mock) hay bajas de
precio: se ve actividad en `enrutar-alertas` y `notificar-usuarios`.

Por consola (dentro del broker: `sudo docker exec -it kafka bash`):

```bash
/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:29092 --describe --all-groups
```

## 8. Éxito y error definitivo (criterio 5)

```bash
# Dentro del broker
/opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:29092 --topic ofertas.scrapeadas \
  --property parse.key=true --property key.separator=:
> DEMO:{esto no es json
```

En **Administración → Fallidos** aparece el mensaje con `DeserializationException (causa: JsonParseException)`,
topic/partición/offset de origen, grupo y stack trace. Fue **directo** a la DLT: reintentar un mensaje ilegible no lo arregla.

| Escenario | Comportamiento | Justificación |
|---|---|---|
| Éxito | Se guarda y **después** se confirma el offset | Si el proceso cae antes, se reprocesa: no se pierde |
| Duplicado | UPSERT / clave única: mismo resultado | At-least-once sin reprocesos indebidos |
| Error recuperable (BD caída) | 3 reintentos: 1 s, 2 s, 4 s | Los fallos transitorios se resuelven solos |
| Reintentos agotados | A la DLT con diagnóstico, y se confirma | No bloquea la partición; queda para reproceso |
| Error definitivo (JSON, validación, restricción de BD) | Directo a la DLT | Reintentar no lo arregla y atrasa a los demás |
| Sin ruta | Log + confirmación | Es un resultado válido, no un error |

## 9. Error recuperable (criterio 5)

En local:

```bash
docker stop cometranqui-postgres      # BD caída
# publicar una oferta (mock) y mirar el log de la API: "Reintento 1/3 ... 2/3 ..."
docker start cometranqui-postgres     # antes de ~7 s: el reintento siguiente tiene éxito y no va a la DLT
```

Automatizado: `mvn -Dtest=MensajeriaFlujosIntegrationTest test` — `errorRecuperableSeReintentaYTerminaBien`,
`reintentosAgotadosApartanElMensajeConSuDiagnostico`, `errorDefinitivoVaDirectoALaDltSinReintentos`.

## 10. Reproceso (criterios 5 y 6)

En **Fallidos**, sobre un mensaje pendiente: **Reprocesar** → se republica en su topic de origen con la misma clave
(misma partición, mismo orden) y queda `REPROCESADO`. Si vuelve a fallar, llega como registro nuevo; si no sirve,
**Descartar**.

Replay masivo (reprocesar todo un topic desde el inicio, sin duplicar gracias al UPSERT):

```bash
# detener el backend (grupo inactivo) y dentro del broker:
/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:29092 --group cometranqui-api-ofertas \
  --topic ofertas.scrapeadas --reset-offsets --to-earliest --execute
```

## 11. Protecciones (criterios 7 y 8)

- Eliminar `ofertas.scrapeadas` → **409** «protegido: es parte del catálogo base».
- Eliminar `notificaciones.sin-gluten` → **409** «en uso por las reglas: alertas-sin-gluten».
- Usuario sin grupo `admin` → **403**; sin token → **401** (y API Gateway rechaza antes de llegar al backend).

## Pruebas automatizadas

```bash
mvn -B test        # 29 tests: catálogo, OfertaService sin Kafka, flujos de mensajería y API de administración
```
