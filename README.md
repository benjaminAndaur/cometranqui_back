# ComeTranqui API

Backend en Java + Spring Boot para **Come Tranqui**, evaluación EP1 (DSY1107 — Desarrollo Cloud Native I, Duoc UC).

Expone un API REST protegido como **OAuth2 Resource Server**, validando tokens JWT emitidos por **Amazon Cognito** (federado con Google OAuth 2.0), y desplegado detrás de **AWS API Gateway** como único punto de entrada público.

Repositorio del frontend: [ComeTranqui-Frontend](https://github.com/michmelo/ComeTranqui-Frontend)

## Arquitectura

```
[Usuario] → [Frontend Next.js en EC2, HTTPS via Nginx]
              → Login → Cognito Hosted UI → Google OAuth → Cognito emite JWT
[Frontend] → llama al API Gateway con Authorization: Bearer <JWT>
[AWS API Gateway (HTTP API)] → JWT Authorizer valida firma (JWKS), issuer y audience
              → si el token es inválido o falta → 401, sin llegar al backend
              → si es válido → proxy HTTP hacia el backend
[Backend Spring Boot en EC2] → vuelve a validar el JWT (issuer, client_id, firma)
              → responde según rol/claims del token
```

La validación ocurre en **dos capas independientes** (API Gateway y backend), cumpliendo el criterio de impenetrabilidad: ninguna petición sin token válido llega a tocar la instancia EC2 del backend.

## Stack

- Java 17, Spring Boot 3.3.3
- `spring-boot-starter-oauth2-resource-server` (validación JWT vía JWKS)
- `spring-boot-starter-web`, `spring-boot-starter-validation`
- Sin base de datos en esta iteración (datos mock en memoria) — pendiente para siguiente entrega: Amazon RDS PostgreSQL

## Seguridad — validación de JWT

`SecurityConfig` (`src/main/java/cl/duoc/cometranqui/security/SecurityConfig.java`):

- Decodifica y valida el JWT contra el `issuer-uri` de Cognito (JWKS).
- Verifica además `token_use=access` y que el `client_id` del token coincida con el App Client de Cognito configurado.
- CORS restringido al origin del frontend.
- Rutas:
  - `/api/public/**` — público
  - `/api/admin/**` — requiere rol `ADMIN` (grupo de Cognito)
  - Cualquier otra ruta bajo `/api/**` — requiere autenticación

`CognitoJwtAuthenticationConverter` mapea los grupos de Cognito (`cognito:groups`) a `ROLE_*` de Spring Security.

## Endpoints

| Método | Ruta | Acceso |
|---|---|---|
| GET | `/api/public/health` | Público |
| GET | `/api/me` | Autenticado — retorna claims del JWT (email, sub, roles) |
| GET | `/api/user/locales` | Autenticado — lista mock de locales certificados |

## Infraestructura AWS desplegada (AWS Academy Learner Lab)

Todo se aprovisionó con AWS CLI (sin Terraform, por las restricciones de permisos de Academy) en la región `us-east-1`.

### Cognito
- User Pool: `ComeTranquiUserPool`
- Identity Provider: Google OAuth 2.0 (scopes `openid email profile`)
- App Client (SPA, sin secret): callback/logout URLs apuntando al frontend
- Dominio hosted UI: `cometranqui-duoc-2026.auth.us-east-1.amazoncognito.com`

### EC2
- **Backend**: Amazon Linux 2023, `t2.micro`, corre este jar como servicio `systemd` (`cometranqui-api.service`) en el puerto 8080. Security Group permite el puerto 8080 (para el proxy del API Gateway) y SSH restringido.
- **Frontend**: Amazon Linux 2023, `t2.micro`, Next.js servido con Nginx como reverse proxy HTTPS (certificado autofirmado) hacia el puerto 3000.
- Ambas instancias con Elastic IP asociada para mantener IP estable.
- Perfil de instancia: `LabInstanceProfile` / `LabRole` (reutilizado, Academy no permite crear roles IAM nuevos).

### API Gateway (HTTP API)
- Nombre: `ComeTranquiApiGateway`
- JWT Authorizer (`CognitoAuthorizer`) apuntando al issuer y audience de Cognito.
- Ruta pública `GET /public/{proxy+}` → integración HTTP proxy a `/api/public/{proxy}` en el backend, sin autorización.
- Ruta protegida `ANY /{proxy+}` → integración HTTP proxy a `/api/{proxy}` en el backend, con `AuthorizationType: JWT`.
- Stage `$default` con auto-deploy.

## Variables de entorno

Ver `.env.example`:

```
COGNITO_ISSUER_URI=https://cognito-idp.us-east-1.amazonaws.com/<USER_POOL_ID>
COGNITO_CLIENT_ID=<APP_CLIENT_ID>
FRONTEND_ORIGIN=<https://IP-DEL-FRONTEND>
PORT=8080
```

## Desarrollo local

```bash
cp .env.example .env
# exporta las variables del .env en tu shell (Spring no las carga automáticamente)
mvn spring-boot:run
```

## Build y contenedor

```bash
mvn -B package -DskipTests
docker build -t cometranqui-api .
docker run -p 8080:8080 --env-file .env cometranqui-api
```

## Verificación de seguridad end-to-end

```bash
# Ruta pública — 200 sin token
curl https://<API_GATEWAY_ENDPOINT>/public/health

# Ruta protegida sin token — 401, nunca llega al backend
curl -i https://<API_GATEWAY_ENDPOINT>/user/locales

# Ruta protegida con token alterado — 401
curl -i -H "Authorization: Bearer token.invalido.aqui" https://<API_GATEWAY_ENDPOINT>/user/locales

# Ruta protegida con token válido (obtenido tras login real en el frontend) — 200
curl -i -H "Authorization: Bearer <ACCESS_TOKEN>" https://<API_GATEWAY_ENDPOINT>/user/locales
```
