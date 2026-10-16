#!/usr/bin/env bash
# Detiene lo que el Learner Lab NO apaga solo al cerrar la sesión, para no gastar créditos.
# La web en Vercel sigue disponible con el último respaldo de datos.
#
#   - RDS: siempre (AWS la vuelve a encender sola a los 7 días; levantar.sh la detecta).
#   - EC2: solo con --todo (el laboratorio ya las detiene al cerrar la sesión).
#
# Uso: bash infra/apagar.sh [--todo]
source "$(dirname "$0")/comun.sh"

log "Deteniendo RDS $DB_ID"
ESTADO="$(aws rds describe-db-instances --db-instance-identifier "$DB_ID" --query 'DBInstances[0].DBInstanceStatus' --output text)"
if [[ "$ESTADO" == "available" ]]; then
  aws rds stop-db-instance --db-instance-identifier "$DB_ID" >/dev/null
  echo "  Deteniendo (estaba $ESTADO)"
else
  echo "  No se detiene: estado actual $ESTADO"
fi

if [[ "${1:-}" == "--todo" ]]; then
  log "Deteniendo EC2"
  IDS=()
  for nombre in "$FRONTEND_NAME" "$BACKEND_NAME" "$KAFKA_NAME"; do IDS+=("$(instancia_por_nombre "$nombre")"); done
  aws ec2 stop-instances --instance-ids "${IDS[@]}" --query 'StoppingInstances[].[InstanceId,CurrentState.Name]' --output text
fi

echo
echo "Listo. La web https://cometranqui.vercel.app sigue disponible con el último respaldo de datos."
