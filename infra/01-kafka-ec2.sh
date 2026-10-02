#!/usr/bin/env bash
# Broker Kafka (KRaft) en un EC2 t3.small dedicado, con Elastic IP y Kafka UI.
# Idempotente: si la instancia ya existe, solo asegura reglas del Security Group y muestra los datos.
#
# Security Group cometranqui-kafka-sg:
#   9092 (SASL/SCRAM)  0.0.0.0/0     La Lambda corre FUERA de la VPC (necesita internet para scrapear y no
#                                    queremos NAT Gateway) y no tiene IP fija: el puerto debe ser público.
#                                    La protección es la autenticación SCRAM-SHA-512 (sin TLS; ver README).
#   8081 (Kafka UI)    solo mi IP    además tiene login (usuario admin, clave en SSM)
#   22   (SSH)         solo mi IP
#
# Uso: bash infra/01-kafka-ec2.sh
source "$(dirname "$0")/comun.sh"

INSTANCE_TYPE="${KAFKA_INSTANCE_TYPE:-t3.small}"
SG_NAME="cometranqui-kafka-sg"
MI_IP="$(mi_ip)/32"

log "Red: misma VPC/subred que el backend de la EP1"
BACKEND_ID="$(instancia_por_nombre "$BACKEND_NAME")"
VPC_ID="$(aws ec2 describe-instances --instance-ids "$BACKEND_ID" --query 'Reservations[0].Instances[0].VpcId' --output text)"
SUBNET_ID="$(aws ec2 describe-instances --instance-ids "$BACKEND_ID" --query 'Reservations[0].Instances[0].SubnetId' --output text)"
echo "  VPC $VPC_ID, subred $SUBNET_ID"

log "Security Group $SG_NAME"
SG_ID="$(sg_por_nombre "$SG_NAME")"
if [[ "$SG_ID" == "None" ]]; then
  SG_ID="$(aws ec2 create-security-group --group-name "$SG_NAME" --vpc-id "$VPC_ID" \
    --description "Kafka Come Tranqui: 9092 SASL publico, 8081 y 22 solo admin" --query GroupId --output text)"
  aws ec2 create-tags --resources "$SG_ID" --tags Key=Name,Value="$SG_NAME" Key=Proyecto,Value=cometranqui
fi
abrir_puerto "$SG_ID" 9092 0.0.0.0/0 "kafka-sasl-lambda-y-backend"
abrir_puerto "$SG_ID" 8081 "$MI_IP" "kafka-ui-admin"
abrir_puerto "$SG_ID" 22 "$MI_IP" "ssh-admin"

log "Claves en SSM Parameter Store"
param_secreto /cometranqui/kafka/pass
param_secreto /cometranqui/kafka-ui/pass

log "Elastic IP"
EIP_ALLOC="$(aws ec2 describe-addresses --filters "Name=tag:Name,Values=cometranqui-kafka-eip" \
  --query 'Addresses[0].AllocationId' --output text)"
if [[ "$EIP_ALLOC" == "None" ]]; then
  EIP_ALLOC="$(aws ec2 allocate-address --domain vpc \
    --tag-specifications 'ResourceType=elastic-ip,Tags=[{Key=Name,Value=cometranqui-kafka-eip},{Key=Proyecto,Value=cometranqui}]' \
    --query AllocationId --output text)"
fi
EIP="$(aws ec2 describe-addresses --allocation-ids "$EIP_ALLOC" --query 'Addresses[0].PublicIp' --output text)"
echo "  $EIP ($EIP_ALLOC)"
aws ssm put-parameter --name /cometranqui/kafka/bootstrap --type String --value "$EIP:9092" --overwrite >/dev/null

INSTANCE_ID="$(instancia_por_nombre "$KAFKA_NAME")"
if [[ "$INSTANCE_ID" == "None" ]]; then
  log "Creando instancia $KAFKA_NAME ($INSTANCE_TYPE, Amazon Linux 2023)"
  AMI_ID="$(aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
    --query Parameter.Value --output text)"
  COMPOSE_B64="$(base64 -w0 "$INFRA_DIR/kafka-ec2/docker-compose.yml")"
  TOPICS_B64="$(base64 -w0 "$INFRA_DIR/kafka-topics.sh")"
  USER_DATA="$(mktemp)"
  # El user-data no lleva claves: las lee de SSM con el rol de la instancia (LabRole)
  cat > "$USER_DATA" <<EOF
#!/bin/bash
set -euxo pipefail
dnf install -y docker
systemctl enable --now docker
mkdir -p /usr/local/lib/docker/cli-plugins
curl -sSL https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
usermod -aG docker ec2-user
mkdir -p /opt/kafka && cd /opt/kafka
echo "$COMPOSE_B64" | base64 -d > docker-compose.yml
echo "$TOPICS_B64" | base64 -d > kafka-topics.sh
export AWS_DEFAULT_REGION=$AWS_REGION
umask 077
cat > .env <<ENV
KAFKA_ADVERTISED_HOST=$EIP
KAFKA_USER=$KAFKA_USER
KAFKA_PASS=\$(aws ssm get-parameter --name /cometranqui/kafka/pass --with-decryption --query Parameter.Value --output text)
KAFKA_UI_PASS=\$(aws ssm get-parameter --name /cometranqui/kafka-ui/pass --with-decryption --query Parameter.Value --output text)
ENV
docker compose up -d
EOF
  INSTANCE_ID="$(aws ec2 run-instances --image-id "$AMI_ID" --instance-type "$INSTANCE_TYPE" \
    --key-name "$KEY_NAME" --subnet-id "$SUBNET_ID" --security-group-ids "$SG_ID" \
    --iam-instance-profile Name="$INSTANCE_PROFILE" --associate-public-ip-address \
    --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=20,VolumeType=gp3,DeleteOnTermination=true}' \
    --metadata-options HttpTokens=required \
    --user-data "file://$(ruta_cli "$USER_DATA")" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$KAFKA_NAME},{Key=Proyecto,Value=cometranqui}]" \
    --query 'Instances[0].InstanceId' --output text)"
  rm -f "$USER_DATA"
  echo "  $INSTANCE_ID"
  aws ec2 wait instance-running --instance-ids "$INSTANCE_ID"
else
  echo "  Instancia existente: $INSTANCE_ID"
fi

log "Asociando Elastic IP"
aws ec2 associate-address --instance-id "$INSTANCE_ID" --allocation-id "$EIP_ALLOC" --allow-reassociation >/dev/null

log "Esperando a que Kafka responda en $EIP:9092 (el user-data tarda ~3 min)"
for i in $(seq 1 60); do
  if (exec 3<>"/dev/tcp/$EIP/9092") 2>/dev/null; then echo "  Kafka escuchando"; break; fi
  sleep 10
done

cat <<EOF

Kafka listo
  Bootstrap (SASL_PLAINTEXT, SCRAM-SHA-512): $EIP:9092   usuario: $KAFKA_USER
  Kafka UI: http://$EIP:8081  (usuario admin; clave: aws ssm get-parameter --name /cometranqui/kafka-ui/pass --with-decryption)
  SSH: ssh -i $SSH_KEY ec2-user@$EIP
EOF
