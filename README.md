# Come Tranqui — Backend

Backend de **Come Tranqui** (DSY1107 — Desarrollo Cloud Native I, Duoc UC): ofertas de productos aptos y locales
seguros para personas con celiaquía, APLV, intolerancia a la lactosa o dieta vegana.

| Evaluación | Qué agrega |
|---|---|
| EP1 | API Spring Boot como OAuth2 Resource Server (JWT de Cognito con login de Google) detrás de API Gateway |
| EP2 | Scraper en AWS Lambda (Java) → Apache Kafka → consumidores → PostgreSQL (RDS) |
| EP3 | Catálogo centralizado de mensajería, reglas de distribución, notificaciones, registro de fallidos y **administración** |

Frontend: [ComeTranqui-Frontend](https://github.com/michmelo/ComeTranqui-Frontend) — en producción en
https://cometranqui.vercel.app. Guion de la demo EP3: [`docs/DEMO-EP3.md`](docs/DEMO-EP3.md).

## Arquitectura

```
 EventBridge (06:00) ─► Lambda cometranqui-scraper (Java 17) ── SASL/SCRAM ──► Apache Kafka (EC2, KRaft)
                                                                               │
   ofertas.scrapeadas ─► [persistir-ofertas] ─► OfertaService ─► RDS PostgreSQL │
   locales.scrapeados ─► [persistir-locales] ─► LocalService  ─► RDS            │
   ofertas.alertas    ─► [enrutar-alertas]   ─► reglas ─► notificaciones.<restricción>
   notificaciones.*   ─► [notificar-usuarios] ─► NotificacionService ─► RDS
   ofertas.dlt        ─► [registrar-fallidos] ─► mensaje_fallido ─► revisión / reproceso

 Navegador ─► Vercel / EC2 (Next.js) ─► API Gateway (JWT) ─► Spring Boot (EC2) ─► RDS / Kafka AdminClient
```

## Mensajería

### Catálogo centralizado (`app.mensajeria` en `application.yml`)

Única fuente de verdad de **destinos** (topics) y **rutas** (qué consumer group atiende cada destino):

- `TopicsConfig` crea los topics al arrancar; los `@KafkaListener` toman topic, grupo y concurrencia del catálogo
  por SpEL (`#{@catalogo.topicDeRuta('persistir-ofertas')}`); la Lambda recibe los nombres desde
  `infra/deploy-lambda.sh`.
- `CatalogoMensajeria` valida al arrancar que cada ruta apunte a un destino existente.

| Destino | Topic | Particiones | Política | Uso |
|---|---|---|---|---|
| `ofertas-scrapeadas` | `ofertas.scrapeadas` | 3 | 7 días | Ofertas del scraper (key = tienda) |
| `locales-scrapeados` | `locales.scrapeados` | 3 | compactado | Último estado de cada local |
| `alertas-precio` | `ofertas.alertas` | 1 | 3 días | Bajas de precio ≥ 20 % |
| `notificaciones` | `notificaciones.*` (familia) | 1 | 7 días | Un destino por restricción; se crean más desde la administración |
| `fallidos` | `ofertas.dlt` | 1 | 14 días | Dead Letter Topic con diagnóstico |

### Consumidores (`mensajeria/consumidores`)

| Consumidor | Grupo | Se activa cuando | Hace |
|---|---|---|---|
| `ConsumidorOfertas` | `cometranqui-api-ofertas` | Cada oferta del scraper | Valida y guarda (UPSERT); detecta bajas de precio |
| `ConsumidorLocales` | `cometranqui-api-locales` | Cada local del scraper | Actualiza locales y niveles |
| `EnrutadorAlertas` | `cometranqui-enrutador` | Una baja de precio | Aplica las reglas de distribución y reenvía |
| `ConsumidorNotificaciones` | `cometranqui-notificaciones` | Una regla enruta una alerta | Notifica a los suscritos (idempotente) |
| `ConsumidorFallidos` | `cometranqui-fallidos` | Un mensaje falla definitivamente | Registra el mensaje y su diagnóstico |

### Reglas de distribución

Tabla `regla_distribucion`: si una alerta cumple **todas** las condiciones presentes (restricción, descuento mínimo,
tienda) se reenvía a `topic_destino`. Hay 4 reglas iniciales (una por restricción) y se administran desde `/admin`.

### Confirmación, reintentos y errores (`mensajeria/config/ErroresConfig`)

| Escenario | Comportamiento |
|---|---|
| Éxito | Commit manual del offset **después** de persistir (at-least-once) |
| Duplicado | UPSERT / claves únicas: mismo resultado |
| Error recuperable | 3 reintentos con backoff exponencial (1 s, 2 s, 4 s) |
| Reintentos agotados / error definitivo | `ofertas.dlt` con headers `kafka_dlt-*` → `ConsumidorFallidos` → tabla `mensaje_fallido` |
| Reproceso | Desde `/admin`: republica en el topic de origen con la misma clave |

### Separación de capas

```
ofertas/, locales/, notificaciones/   dominio (sin Kafka)
eventos/                              contratos de mensajes y puerto PublicadorEventos
mensajeria/config|publicacion|consumidores|enrutamiento|fallidos|admin   infraestructura de mensajería
```

`OfertaService` publica con `PublicadorEventos` y se prueba sin Kafka (`OfertaServiceTest`).

## Administración (`/api/admin/mensajeria`, rol ADMIN)

`AdministracionMensajeriaService` es el único componente que usa Kafka `AdminClient`. Errores en formato Problem
Details (RFC 9457) con `errores[{campo, mensaje}]`.

| Método y ruta | Descripción |
|---|---|
| `GET /catalogo` | Configuración vigente |
| `GET/POST /destinos` · `PATCH/DELETE /destinos/{nombre}` | Topics: listar, crear, modificar retención/particiones, eliminar |
| `GET/POST /reglas` · `PUT/DELETE /reglas/{id}` · `PATCH /reglas/{id}/estado` | Reglas de distribución |
| `GET /grupos` | Consumer groups con lag por partición |
| `GET /fallidos` · `POST /fallidos/{id}/reprocesar` · `DELETE /fallidos/{id}` | Mensajes fallidos |
| `POST /pruebas` · `GET /pruebas/{eventoId}` | Mensaje de prueba y su recorrido |

Validaciones: nombre (`^[a-z0-9]+(\.[a-z0-9]+(-[a-z0-9]+)*)+$`, ≤ 100), prefijo de una familia del catálogo,
particiones 1–6 (solo aumentar), retención 1–720 h, limpieza `delete|compact`, duplicados (409), protegidos (409),
destinos en uso por reglas (409); reglas con al menos una condición, origen atendido por el enrutador, destino existente.

## Otros endpoints

| Método | Ruta | Acceso |
|---|---|---|
| GET | `/api/public/health`, `/api/public/ofertas`, `/api/public/ofertas/supermercados`, `/api/public/locales` | Público |
| GET | `/api/me`, `/api/user/locales` | JWT |
| GET/PUT | `/api/user/suscripciones` · GET `/api/user/notificaciones` · POST `/api/user/notificaciones/{id}/leida` | JWT |
| POST | `/api/admin/scraper/run` | JWT + ADMIN |

## Seguridad

- JWT de Cognito validado dos veces: autorizador JWT de API Gateway y `SecurityConfig` (issuer, firma, `token_use`,
  `client_id`). Grupos de Cognito → roles (`CognitoJwtAuthenticationConverter`): el grupo `admin` da `ROLE_ADMIN`.
- Kafka con SASL/SCRAM-SHA-512; claves en SSM Parameter Store, nunca en el repositorio.

## Ejecutar en local

```bash
cp .env.example .env                 # completar COGNITO_*
docker compose up -d                 # Postgres :5432, Kafka :9092 (SCRAM), Kafka UI :8081
mvn spring-boot:run                  # crea tablas (Flyway) y topics (catálogo)

cd scraper-lambda && mvn package
FUENTES_ACTIVAS=MOCK KAFKA_USER=cometranqui KAFKA_PASS=cometranqui-local java -jar target/cometranqui-scraper-1.0.0.jar
```

## Pruebas

```bash
mvn -B test                          # 29 tests (Testcontainers: Kafka + PostgreSQL reales)
cd scraper-lambda && mvn -B test     # 23 tests del scraper (parsers sobre HTML/JSON reales)
```

## Despliegue en AWS (Learner Lab, us-east-1)

```bash
bash infra/levantar.sh               # al iniciar el laboratorio: enciende todo, autoriza la IP, corre el scraper
bash infra/03-deploy-backend.sh      # backend en EC2
bash infra/deploy-lambda.sh          # Lambda + schedule diario
bash infra/apagar.sh                 # al terminar: detiene la RDS
```

Desde cero: `01-kafka-ec2.sh` (broker), `02-rds.sh` (base de datos), luego los anteriores. La Lambda corre fuera de la
VPC (necesita internet para scrapear; evitar un NAT Gateway), por eso el puerto 9092 es público y protegido con SCRAM.

## Variables de entorno

Ver [`.env.example`](.env.example): `COGNITO_ISSUER_URI`, `COGNITO_CLIENT_ID`, `FRONTEND_ORIGIN` (uno o varios
separados por coma), `DB_URL`, `DB_USER`, `DB_PASS`, `KAFKA_BOOTSTRAP`, `KAFKA_USER`, `KAFKA_PASS`,
`SCRAPER_LAMBDA_NAME`, `AWS_REGION`.
