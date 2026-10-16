#!/usr/bin/env bash
# Muestra los topics de Come Tranqui con sus particiones y configuración.
#
# Los topics NO se declaran aquí: la única fuente de verdad es el catálogo app.mensajeria de
# src/main/resources/application.yml. La API los crea al arrancar (mensajeria/config/TopicsConfig) y los destinos
# adicionales (notificaciones.*) se crean desde la administración (/admin o /api/admin/mensajeria/destinos).
#
# Uso:
#   Dentro de un contenedor apache/kafka (kafka-init lo ejecuta al levantar el compose):
#     KAFKA_BIN=/opt/kafka/bin BOOTSTRAP=kafka:29092 bash infra/kafka-topics.sh
#   Desde el host contra el compose local:
#     docker exec -i cometranqui-kafka bash -s < infra/kafka-topics.sh
#   En el EC2 del broker:
#     sudo docker exec -i kafka bash -s < kafka-topics.sh
set -euo pipefail

KAFKA_BIN="${KAFKA_BIN:-/opt/kafka/bin}"
BOOTSTRAP="${BOOTSTRAP:-localhost:29092}"

"$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --describe \
  --topic '(ofertas|locales|notificaciones)\..*' 2>/dev/null \
  || echo "Aún no hay topics: se crean cuando arranca la API (catálogo app.mensajeria)."
