#!/usr/bin/env bash
# Compila y despliega el backend en el EC2 de la EP1 (servicio systemd cometranqui-api).
# Las variables sensibles van en /etc/cometranqui/api.env (root, 600), leídas de SSM en este momento.
#
# Uso: bash infra/03-deploy-backend.sh [--sin-compilar]
source "$(dirname "$0")/comun.sh"

BACKEND_ID="$(instancia_por_nombre "$BACKEND_NAME")"
BACKEND_IP="$(ip_publica "$BACKEND_ID")"
FRONTEND_IP="$(ip_publica "$(instancia_por_nombre "$FRONTEND_NAME")")"
JAR="$REPO_DIR/target/cometranqui-api-1.0.0.jar"

if [[ "${1:-}" != "--sin-compilar" ]]; then
  log "Compilando backend"
  (cd "$REPO_DIR" && mvn_build -q -B package -DskipTests)
fi

log "Leyendo configuración de SSM"
DB_URL="$(aws ssm get-parameter --name /cometranqui/db/url --query Parameter.Value --output text)"
KAFKA_BOOTSTRAP="$(aws ssm get-parameter --name /cometranqui/kafka/bootstrap --query Parameter.Value --output text)"
ENV_FILE="$(mktemp)"
umask 077
cat > "$ENV_FILE" <<EOF
COGNITO_ISSUER_URI=https://cognito-idp.$AWS_REGION.amazonaws.com/us-east-1_QHxXWVmGx
COGNITO_CLIENT_ID=6f7sgt9cri682felac9ddc706c
FRONTEND_ORIGIN=https://$FRONTEND_IP
PORT=8080
DB_URL=$DB_URL
DB_USER=$DB_USER
DB_PASS=$(param /cometranqui/db/pass)
KAFKA_BOOTSTRAP=$KAFKA_BOOTSTRAP
KAFKA_USER=$KAFKA_USER
KAFKA_PASS=$(param /cometranqui/kafka/pass)
SCRAPER_LAMBDA_NAME=$LAMBDA_NAME
AWS_REGION=$AWS_REGION
EOF

UNIT="$(mktemp)"
cat > "$UNIT" <<'EOF'
[Unit]
Description=ComeTranqui Spring Boot API
After=network-online.target
Wants=network-online.target

[Service]
User=ec2-user
EnvironmentFile=/etc/cometranqui/api.env
# t2.micro (1 GB): heap acotado para dejar memoria al sistema
ExecStart=/usr/bin/java -Xms256m -Xmx448m -jar /opt/cometranqui/cometranqui-api.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

log "Subiendo jar y configuración a $BACKEND_IP"
scp_a "$BACKEND_IP" "$JAR" /tmp/cometranqui-api.jar
scp_a "$BACKEND_IP" "$ENV_FILE" /tmp/api.env
scp_a "$BACKEND_IP" "$UNIT" /tmp/cometranqui-api.service
rm -f "$ENV_FILE" "$UNIT"

ssh_a "$BACKEND_IP" 'set -e
  sudo mkdir -p /opt/cometranqui /etc/cometranqui
  sudo install -m 600 -o root -g root /tmp/api.env /etc/cometranqui/api.env
  sudo install -m 644 /tmp/cometranqui-api.jar /opt/cometranqui/cometranqui-api.jar
  sudo install -m 644 /tmp/cometranqui-api.service /etc/systemd/system/cometranqui-api.service
  rm -f /tmp/api.env /tmp/cometranqui-api.jar /tmp/cometranqui-api.service
  sudo systemctl daemon-reload
  sudo systemctl restart cometranqui-api'

log "Esperando health check"
for i in $(seq 1 30); do
  if ssh_a "$BACKEND_IP" 'curl -sf localhost:8080/api/public/health' >/dev/null 2>&1; then
    echo "  Backend arriba"
    ssh_a "$BACKEND_IP" 'sudo journalctl -u cometranqui-api --since "-3 min" --no-pager | grep -E "Successfully applied|partitions assigned|Started Cometranqui" | tail -8'
    exit 0
  fi
  sleep 5
done
echo "  El backend no respondió; revisa: ssh ... 'sudo journalctl -u cometranqui-api -n 100'"
exit 1
