#!/usr/bin/env bash
# Crea (o ajusta) los topics de Come Tranqui. Idempotente: se puede ejecutar N veces.
#
# Topics:
#   ofertas.scrapeadas  3 particiones, key = supermercado, retención 7 días  -> ofertas del scraper
#   ofertas.dlt         1 partición, retención 14 días                      -> mensajes que no se pudieron procesar
#   ofertas.alertas     1 partición, retención 3 días                       -> bajas de precio >= 20%
#   locales.scrapeados  3 particiones, key = id del local, COMPACTADO          -> último estado de cada local
#
# Uso:
#   Dentro de un contenedor apache/kafka (kafka-init lo ejecuta solo):
#     KAFKA_BIN=/opt/kafka/bin BOOTSTRAP=kafka:29092 bash infra/kafka-topics.sh
#   Desde el host contra el compose local:
#     docker exec -i cometranqui-kafka bash -s < infra/kafka-topics.sh
#   En el EC2 del broker:
#     docker exec -i kafka bash -s < kafka-topics.sh
set -euo pipefail

KAFKA_BIN="${KAFKA_BIN:-/opt/kafka/bin}"
BOOTSTRAP="${BOOTSTRAP:-localhost:29092}"
DIA_MS=86400000

crear_topic() {
  local nombre="$1" particiones="$2" retencion_ms="$3"
  "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$nombre" --partitions "$particiones" --replication-factor 1 \
    --config retention.ms="$retencion_ms" --config cleanup.policy=delete
  # Si el topic ya existía, deja la retención alineada con lo declarado aquí
  "$KAFKA_BIN/kafka-configs.sh" --bootstrap-server "$BOOTSTRAP" --alter \
    --entity-type topics --entity-name "$nombre" --add-config retention.ms="$retencion_ms"
}

crear_topic ofertas.scrapeadas 3 $((7 * DIA_MS))
crear_topic ofertas.dlt        1 $((14 * DIA_MS))
crear_topic ofertas.alertas    1 $((3 * DIA_MS))

# Compactado: no expira por tiempo, conserva el último mensaje por key (tabla de estado de locales)
"$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
  --topic locales.scrapeados --partitions 3 --replication-factor 1 \
  --config cleanup.policy=compact --config segment.ms=3600000 --config min.cleanable.dirty.ratio=0.1

"$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --describe \
  --topic '(ofertas|locales)\..*'
