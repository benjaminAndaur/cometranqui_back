#!/usr/bin/env bash
# Deja Come Tranqui funcionando después de iniciar una sesión del AWS Academy Learner Lab. Un solo comando:
#
#   1. Enciende las EC2 (frontend, backend, Kafka) y la RDS si están detenidas.
#   2. Autoriza la IP actual para SSH y Kafka UI (la IP de la casa cambia seguido).
#   3. Espera a que backend y Kafka respondan.
#   4. Ejecuta el scraper (ofertas frescas; el cron de las 06:00 no corre con el laboratorio cerrado).
#   5. Actualiza el respaldo de datos del frontend en Vercel, para que la web 24/7 muestre lo último.
#
# Requisitos: credenciales del laboratorio en ~/.aws/credentials. Para el paso 5, el repo del frontend en
# ../../ComeTranqui-Frontend (o FRONTEND_DIR) y la CLI de Vercel con sesión iniciada.
#
# Uso: bash infra/levantar.sh [--sin-scraper] [--sin-vercel]
source "$(dirname "$0")/comun.sh"

FRONTEND_DIR="${FRONTEND_DIR:-$REPO_DIR/../ComeTranqui-Frontend}"
API_GATEWAY="https://d8ae57sdjk.execute-api.us-east-1.amazonaws.com"
opcion() { [[ " ${ARGS[*]} " == *" $1 "* ]]; }
ARGS=("$@")

log "1/5 Encendiendo servidores"
IDS=()
for nombre in "$FRONTEND_NAME" "$BACKEND_NAME" "$KAFKA_NAME"; do
  id="$(instancia_por_nombre "$nombre")"
  estado="$(aws ec2 describe-instances --instance-ids "$id" --query 'Reservations[0].Instances[0].State.Name' --output text)"
  echo "  $nombre ($id): $estado"
  [[ "$estado" == "stopped" ]] && aws ec2 start-instances --instance-ids "$id" >/dev/null
  IDS+=("$id")
done
DB_ESTADO="$(aws rds describe-db-instances --db-instance-identifier "$DB_ID" --query 'DBInstances[0].DBInstanceStatus' --output text)"
echo "  $DB_ID: $DB_ESTADO"
[[ "$DB_ESTADO" == "stopped" ]] && aws rds start-db-instance --db-instance-identifier "$DB_ID" >/dev/null
aws ec2 wait instance-running --instance-ids "${IDS[@]}"
echo "  EC2 encendidas; esperando la base de datos (si estaba detenida tarda ~5 min)"
aws rds wait db-instance-available --db-instance-identifier "$DB_ID"
echo "  RDS disponible"

log "2/5 Autorizando la IP actual"
MI_IP="$(mi_ip)/32"
BACKEND_SG="$(sg_por_nombre cometranqui-backend-sg)"
FRONTEND_SG="$(sg_por_nombre cometranqui-frontend-sg)"
KAFKA_SG="$(sg_por_nombre cometranqui-kafka-sg)"
abrir_puerto "$BACKEND_SG" 22 "$MI_IP" "ssh-admin"
abrir_puerto "$FRONTEND_SG" 22 "$MI_IP" "ssh-admin"
abrir_puerto "$KAFKA_SG" 22 "$MI_IP" "ssh-admin"
abrir_puerto "$KAFKA_SG" 8081 "$MI_IP" "kafka-ui-admin"

log "3/5 Esperando backend y Kafka"
KAFKA_IP="$(ip_publica "$(instancia_por_nombre "$KAFKA_NAME")")"
for i in $(seq 1 40); do
  if ssh_a "$KAFKA_IP" 'sudo docker inspect -f "{{.State.Health.Status}}" kafka' 2>/dev/null | grep -q healthy; then
    echo "  Kafka sano"; break
  fi
  sleep 10
done
for i in $(seq 1 40); do
  if curl -sf -m 10 "$API_GATEWAY/public/health" >/dev/null; then echo "  Backend responde vía API Gateway"; break; fi
  # Si el backend arrancó antes que la RDS, systemd lo reintenta solo (Restart=always)
  sleep 10
done

if ! opcion --sin-scraper; then
  log "4/5 Ejecutando el scraper (~2 min)"
  SALIDA="$(mktemp)"
  aws lambda invoke --function-name "$LAMBDA_NAME" --cli-read-timeout 320 --cli-binary-format raw-in-base64-out \
    --payload '{"source":"levantar.sh","detail-type":"Ejecucion manual","detail":{}}' \
    "$(ruta_cli "$SALIDA")" --query 'StatusCode' --output text >/dev/null
  python -c "import json,sys; r=json.loads(json.load(open(sys.argv[1]))); print('  Ofertas por fuente:', r['ofertasPorFuente']); print('  Locales:', r['localesPorFuente']); print('  Errores:', r['errores'] or 'ninguno')" "$(ruta_cli "$SALIDA")" \
    || cat "$SALIDA"
  rm -f "$SALIDA"
  sleep 10   # margen para que los consumers terminen de guardar
fi

if ! opcion --sin-vercel && [[ -d "$FRONTEND_DIR" ]] && command -v vercel >/dev/null 2>&1; then
  log "5/5 Actualizando el respaldo de datos en Vercel"
  (cd "$FRONTEND_DIR" && node scripts/actualizar-respaldo.mjs && vercel deploy --prod --yes >/dev/null && echo "  Vercel actualizado")
else
  log "5/5 Vercel omitido (sin repo del frontend o sin CLI de Vercel)"
fi

cat <<EOF

Come Tranqui está arriba
  Web (24/7):     https://cometranqui.vercel.app
  Web (EC2):      https://18.204.236.209   (certificado autofirmado)
  API:            $API_GATEWAY/public/ofertas
  Kafka UI:       http://$KAFKA_IP:8081   (usuario admin)
Al terminar:      bash infra/apagar.sh   (detiene la RDS, que el laboratorio no apaga)
EOF
