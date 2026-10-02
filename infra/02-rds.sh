#!/usr/bin/env bash
# RDS PostgreSQL 16 db.t3.micro, privada: solo acepta conexiones desde el Security Group del backend.
# Idempotente. La creación tarda ~5-10 min.
#
# OJO Learner Lab: al cerrar la sesión las EC2 se detienen, pero RDS sigue corriendo y gastando créditos.
# Para detenerla: aws rds stop-db-instance --db-instance-identifier cometranqui-db
#
# Uso: bash infra/02-rds.sh
source "$(dirname "$0")/comun.sh"

SG_NAME="cometranqui-rds-sg"
SUBNET_GROUP="cometranqui-db-subnets"

BACKEND_ID="$(instancia_por_nombre "$BACKEND_NAME")"
VPC_ID="$(aws ec2 describe-instances --instance-ids "$BACKEND_ID" --query 'Reservations[0].Instances[0].VpcId' --output text)"
BACKEND_SG="$(aws ec2 describe-instances --instance-ids "$BACKEND_ID" \
  --query 'Reservations[0].Instances[0].SecurityGroups[0].GroupId' --output text)"

log "Security Group $SG_NAME (5432 solo desde $BACKEND_SG)"
SG_ID="$(sg_por_nombre "$SG_NAME")"
if [[ "$SG_ID" == "None" ]]; then
  SG_ID="$(aws ec2 create-security-group --group-name "$SG_NAME" --vpc-id "$VPC_ID" \
    --description "PostgreSQL Come Tranqui: solo desde el backend" --query GroupId --output text)"
  aws ec2 create-tags --resources "$SG_ID" --tags Key=Name,Value="$SG_NAME" Key=Proyecto,Value=cometranqui
fi
abrir_puerto "$SG_ID" 5432 "$BACKEND_SG" "backend-cometranqui"

log "DB subnet group (subredes por defecto de la VPC, RDS exige al menos 2 AZ)"
if ! aws rds describe-db-subnet-groups --db-subnet-group-name "$SUBNET_GROUP" >/dev/null 2>&1; then
  SUBNETS="$(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$VPC_ID" "Name=default-for-az,Values=true" \
    --query 'Subnets[].SubnetId' --output text)"
  # shellcheck disable=SC2086
  aws rds create-db-subnet-group --db-subnet-group-name "$SUBNET_GROUP" \
    --db-subnet-group-description "Come Tranqui" --subnet-ids $SUBNETS >/dev/null
  echo "  + creado"
else
  echo "  = existente"
fi

log "Clave de la BD en SSM"
param_secreto /cometranqui/db/pass

if ! aws rds describe-db-instances --db-instance-identifier "$DB_ID" >/dev/null 2>&1; then
  log "Creando $DB_ID (PostgreSQL 16, db.t3.micro, 20 GB gp2, sin acceso público)"
  aws rds create-db-instance \
    --db-instance-identifier "$DB_ID" \
    --engine postgres --engine-version 16.15 \
    --db-instance-class db.t3.micro \
    --allocated-storage 20 --storage-type gp2 \
    --db-name "$DB_NAME" \
    --master-username "$DB_USER" \
    --master-user-password "$(param /cometranqui/db/pass)" \
    --vpc-security-group-ids "$SG_ID" \
    --db-subnet-group-name "$SUBNET_GROUP" \
    --availability-zone "$(aws ec2 describe-instances --instance-ids "$BACKEND_ID" \
        --query 'Reservations[0].Instances[0].Placement.AvailabilityZone' --output text)" \
    --no-publicly-accessible --no-multi-az \
    --backup-retention-period 1 \
    --storage-encrypted \
    --tags Key=Proyecto,Value=cometranqui >/dev/null
else
  echo "  Instancia existente: $DB_ID"
fi

log "Esperando a que $DB_ID quede disponible"
aws rds wait db-instance-available --db-instance-identifier "$DB_ID"
ENDPOINT="$(aws rds describe-db-instances --db-instance-identifier "$DB_ID" \
  --query 'DBInstances[0].Endpoint.Address' --output text)"
aws ssm put-parameter --name /cometranqui/db/url --type String \
  --value "jdbc:postgresql://$ENDPOINT:5432/$DB_NAME" --overwrite >/dev/null

cat <<EOF

RDS lista
  JDBC: jdbc:postgresql://$ENDPOINT:5432/$DB_NAME   usuario: $DB_USER (clave en SSM /cometranqui/db/pass)
  Las tablas las crea Flyway al arrancar el backend.
EOF
