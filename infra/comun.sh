#!/usr/bin/env bash
# Variables y funciones compartidas por los scripts de infra/. Se incluye con:  source "$(dirname "$0")/comun.sh"
#
# Restricciones del AWS Academy Learner Lab:
#   - No se pueden crear roles IAM: todo usa LabRole / LabInstanceProfile.
#   - Las credenciales son temporales (~/.aws/credentials). Si un comando falla con ExpiredToken, renuévalas.
#   - Las claves (Kafka, BD) se guardan en SSM Parameter Store como SecureString, nunca en el repo.
set -euo pipefail
export MSYS_NO_PATHCONV=1          # Git Bash en Windows: no convertir rutas tipo /cometranqui/... a C:/...
export AWS_PAGER=""
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="$AWS_REGION"

INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$INFRA_DIR/.." && pwd)"

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
LAB_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/LabRole"
INSTANCE_PROFILE="LabInstanceProfile"
KEY_NAME="${KEY_NAME:-cometranqui-key}"
SSH_KEY="${SSH_KEY:-$REPO_DIR/../.ssh/cometranqui-key.pem}"

# Instancias existentes de la EP1 (por tag Name)
BACKEND_NAME="cometranqui-backend"
FRONTEND_NAME="cometranqui-frontend"
KAFKA_NAME="cometranqui-kafka"
DB_ID="cometranqui-db"
LAMBDA_NAME="cometranqui-scraper"

KAFKA_USER="cometranqui"
DB_USER="cometranqui"
DB_NAME="cometranqui"

# Ruta para file:// del AWS CLI (en Git Bash de Windows el CLI no entiende /tmp/...)
ruta_cli() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

# Nombre de topic de un destino del catálogo app.mensajeria (application.yml), para no duplicarlo en scripts.
#   catalogo_topic ofertas-scrapeadas  ->  ofertas.scrapeadas
catalogo_topic() {
  awk -v destino="      $1:" '
    $0 == destino { dentro = 1; next }
    dentro && /^      [a-z]/ { exit }
    dentro && $1 == "topic:" { print $2; exit }
  ' "$REPO_DIR/src/main/resources/application.yml"
}

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

# IP pública de quien ejecuta el script (para abrir SSH y Kafka UI solo a esa IP)
mi_ip() { curl -s https://checkip.amazonaws.com | tr -d '[:space:]'; }

instancia_por_nombre() {
  aws ec2 describe-instances \
    --filters "Name=tag:Name,Values=$1" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query 'Reservations[0].Instances[0].InstanceId' --output text
}

ip_publica() {
  aws ec2 describe-instances --instance-ids "$1" --query 'Reservations[0].Instances[0].PublicIpAddress' --output text
}

sg_por_nombre() {
  aws ec2 describe-security-groups --filters "Name=group-name,Values=$1" \
    --query 'SecurityGroups[0].GroupId' --output text
}

# Abre un puerto si la regla no existe (authorize falla con Duplicate si ya está)
abrir_puerto() {
  local sg="$1" puerto="$2" origen="$3" descripcion="$4"
  local permiso
  if [[ "$origen" == sg-* ]]; then
    permiso="IpProtocol=tcp,FromPort=$puerto,ToPort=$puerto,UserIdGroupPairs=[{GroupId=$origen,Description=$descripcion}]"
  else
    permiso="IpProtocol=tcp,FromPort=$puerto,ToPort=$puerto,IpRanges=[{CidrIp=$origen,Description=$descripcion}]"
  fi
  aws ec2 authorize-security-group-ingress --group-id "$sg" --ip-permissions "$permiso" >/dev/null 2>&1 \
    && echo "  + $puerto desde $origen ($descripcion)" \
    || echo "  = $puerto desde $origen ya estaba abierto"
}

# Lee un SecureString de SSM
param() { aws ssm get-parameter --name "$1" --with-decryption --query Parameter.Value --output text; }

# Crea un SecureString con una clave aleatoria solo si no existe (idempotente: no rota claves en uso)
param_secreto() {
  local nombre="$1"
  if ! aws ssm get-parameter --name "$nombre" >/dev/null 2>&1; then
    aws ssm put-parameter --name "$nombre" --type SecureString \
      --value "$(openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | head -c 24)" >/dev/null
    echo "  + clave nueva en SSM: $nombre"
  else
    echo "  = clave existente en SSM: $nombre"
  fi
}

ssh_a() {
  local host="$1"; shift
  ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 -o LogLevel=ERROR "ec2-user@$host" "$@"
}

scp_a() {
  local host="$1" origen="$2" destino="$3"
  scp -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new -o LogLevel=ERROR "$origen" "ec2-user@$host:$destino"
}

mvn_cmd() {
  if command -v mvn >/dev/null 2>&1; then echo mvn; else
    ls "$HOME"/.m2/wrapper/dists/apache-maven-*/*/bin/mvn 2>/dev/null | head -1
  fi
}

# Maven sin MSYS_NO_PATHCONV (en Git Bash su script de arranque necesita la conversión de rutas)
mvn_build() { env -u MSYS_NO_PATHCONV "$(mvn_cmd)" "$@"; }
