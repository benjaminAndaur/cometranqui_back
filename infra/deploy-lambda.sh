#!/usr/bin/env bash
# Compila el scraper, crea/actualiza la Lambda cometranqui-scraper y su schedule diario en EventBridge Scheduler.
#
# La Lambda va FUERA de la VPC: necesita salir a internet para scrapear y dentro de la VPC eso exigiría un
# NAT Gateway (~USD 32/mes). Por eso el broker expone 9092 a internet, protegido con SASL/SCRAM.
#
# Uso: bash infra/deploy-lambda.sh [--sin-compilar] [--invocar]
source "$(dirname "$0")/comun.sh"

SCRAPER_DIR="$REPO_DIR/scraper-lambda"
JAR="$SCRAPER_DIR/target/cometranqui-scraper-1.0.0.jar"
SCHEDULE="cometranqui-scraper-diario"

if [[ " $* " != *" --sin-compilar "* ]]; then
  log "Compilando scraper (con tests)"
  (cd "$SCRAPER_DIR" && mvn_build -q -B package)
fi

KAFKA_BOOTSTRAP="$(aws ssm get-parameter --name /cometranqui/kafka/bootstrap --query Parameter.Value --output text)"
ENV_JSON="$(mktemp)"
umask 077
cat > "$ENV_JSON" <<EOF
{"Variables":{
  "KAFKA_BOOTSTRAP":"$KAFKA_BOOTSTRAP",
  "KAFKA_USER":"$KAFKA_USER",
  "KAFKA_PASS":"$(param /cometranqui/kafka/pass)",
  "FUENTES_ACTIVAS":"TODAS",
  "LOCALES_ACTIVOS":"OSM",
  "SCRAPER_MAX_PAGINAS":"3",
  "SCRAPER_PAUSA_MS":"1500",
  "JAVA_TOOL_OPTIONS":"-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
}}
EOF

if aws lambda get-function --function-name "$LAMBDA_NAME" >/dev/null 2>&1; then
  log "Actualizando código de $LAMBDA_NAME"
  aws lambda update-function-code --function-name "$LAMBDA_NAME" --zip-file "fileb://$(ruta_cli "$JAR")" >/dev/null
  aws lambda wait function-updated --function-name "$LAMBDA_NAME"
  aws lambda update-function-configuration --function-name "$LAMBDA_NAME" \
    --memory-size 1024 --timeout 300 --environment "file://$(ruta_cli "$ENV_JSON")" >/dev/null
else
  log "Creando $LAMBDA_NAME (Java 17, 1024 MB, 5 min, rol LabRole)"
  aws lambda create-function --function-name "$LAMBDA_NAME" \
    --runtime java17 --architectures x86_64 \
    --handler cl.duoc.cometranqui.scraper.ScraperHandler::handleRequest \
    --role "$LAB_ROLE_ARN" \
    --zip-file "fileb://$(ruta_cli "$JAR")" \
    --memory-size 1024 --timeout 300 \
    --environment "file://$(ruta_cli "$ENV_JSON")" \
    --description "Scraper Come Tranqui: ofertas y locales -> Kafka" \
    --tags Proyecto=cometranqui >/dev/null
fi
rm -f "$ENV_JSON"
aws lambda wait function-updated --function-name "$LAMBDA_NAME"
# Sin reintentos automáticos de invocaciones asíncronas: una corrida fallida no debe repetirse 2 veces más
aws lambda put-function-event-invoke-config --function-name "$LAMBDA_NAME" \
  --maximum-retry-attempts 0 --maximum-event-age-in-seconds 3600 >/dev/null
LAMBDA_ARN="$(aws lambda get-function --function-name "$LAMBDA_NAME" --query Configuration.FunctionArn --output text)"

log "Schedule $SCHEDULE: todos los días 06:00 America/Santiago"
ENTRADA='{"source":"aws.scheduler","detail-type":"Scheduled Event","detail":{}}'
TARGET="$(mktemp)"
cat > "$TARGET" <<EOF
{"Arn":"$LAMBDA_ARN","RoleArn":"$LAB_ROLE_ARN","Input":$(printf '%s' "$ENTRADA" | python -c 'import json,sys; print(json.dumps(sys.stdin.read()))'),
 "RetryPolicy":{"MaximumRetryAttempts":0}}
EOF
if aws scheduler get-schedule --name "$SCHEDULE" >/dev/null 2>&1; then ACCION=update-schedule; else ACCION=create-schedule; fi
aws scheduler "$ACCION" --name "$SCHEDULE" \
  --schedule-expression "cron(0 6 * * ? *)" \
  --schedule-expression-timezone "America/Santiago" \
  --flexible-time-window Mode=OFF \
  --target "file://$(ruta_cli "$TARGET")" >/dev/null
rm -f "$TARGET"
echo "  $ACCION OK"

if [[ " $* " == *" --invocar "* ]]; then
  log "Invocando $LAMBDA_NAME (síncrono, puede tardar ~2-4 min)"
  SALIDA="$(mktemp)"
  aws lambda invoke --function-name "$LAMBDA_NAME" --cli-read-timeout 320 \
    --payload "$(printf '%s' "$ENTRADA" | base64 -w0)" "$(ruta_cli "$SALIDA")" --query '{status:StatusCode,error:FunctionError}'
  cat "$SALIDA"; echo
  rm -f "$SALIDA"
fi

cat <<EOF

Lambda lista: $LAMBDA_ARN
  Invocar a mano:  aws lambda invoke --function-name $LAMBDA_NAME /dev/stdout
  Logs:            aws logs tail /aws/lambda/$LAMBDA_NAME --follow
EOF
