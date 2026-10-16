package cl.duoc.cometranqui.mensajeria.consumidores;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import cl.duoc.cometranqui.eventos.NotificacionEvento;
import cl.duoc.cometranqui.mensajeria.MensajeInvalidoException;
import cl.duoc.cometranqui.notificaciones.NotificacionService;

/**
 * Ruta {@code notificar-usuarios}: escucha toda la familia {@code notificaciones.*} con un patrón, de modo que un
 * destino nuevo creado desde la administración empieza a consumirse solo (el patrón se reevalúa cada 30 s).
 * Se activa cuando el enrutador reenvía una alerta; crea las notificaciones de los usuarios suscritos.
 */
@Component
class ConsumidorNotificaciones {

    private final NotificacionService service;

    ConsumidorNotificaciones(NotificacionService service) {
        this.service = service;
    }

    @KafkaListener(id = "notificar-usuarios", idIsGroup = false,
            topicPattern = "#{@catalogo.patron('notificaciones')}",
            groupId = "#{@catalogo.grupo('notificar-usuarios')}",
            concurrency = "#{@catalogo.concurrencia('notificar-usuarios')}",
            properties = {
                    "spring.json.value.default.type=cl.duoc.cometranqui.eventos.NotificacionEvento",
                    "metadata.max.age.ms=30000"
            })
    void onNotificacion(ConsumerRecord<String, NotificacionEvento> registro, Acknowledgment ack) {
        NotificacionEvento evento = registro.value();
        if (evento == null || evento.eventoId() == null || evento.alerta() == null) {
            throw new MensajeInvalidoException("Notificación sin eventoId o sin alerta");
        }
        service.registrar(evento, registro.topic());
        ack.acknowledge();
    }
}
