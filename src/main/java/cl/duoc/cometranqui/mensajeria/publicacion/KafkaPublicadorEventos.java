package cl.duoc.cometranqui.mensajeria.publicacion;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import cl.duoc.cometranqui.eventos.AlertaPrecio;
import cl.duoc.cometranqui.eventos.PublicadorEventos;
import cl.duoc.cometranqui.mensajeria.config.CatalogoMensajeria;

/**
 * Implementación Kafka del puerto {@link PublicadorEventos}: resuelve el topic desde el catálogo y la clave desde
 * el evento. El envío es síncrono para que un fallo del broker se propague al consumidor y se reintente.
 */
@Component
class KafkaPublicadorEventos implements PublicadorEventos {

    private static final Logger log = LoggerFactory.getLogger(KafkaPublicadorEventos.class);

    private final KafkaTemplate<String, Object> kafka;
    private final CatalogoMensajeria catalogo;

    KafkaPublicadorEventos(KafkaTemplate<String, Object> kafka, CatalogoMensajeria catalogo) {
        this.kafka = kafka;
        this.catalogo = catalogo;
    }

    @Override
    public void publicar(AlertaPrecio alerta) {
        String topic = catalogo.topic("alertas-precio");
        enviar(topic, alerta.clave(), alerta);
        log.info("Alerta {} publicada en {}: {} bajó {}% ({} -> {})", alerta.eventoId(), topic, alerta.clave(),
                alerta.bajaPorcentaje(), alerta.precioAnterior(), alerta.precioNuevo());
    }

    private void enviar(String topic, String clave, Object valor) {
        try {
            kafka.send(topic, clave, valor).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido publicando en " + topic, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("No se pudo publicar en " + topic, e);
        }
    }
}
