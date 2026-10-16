package cl.duoc.cometranqui.mensajeria.consumidores;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import cl.duoc.cometranqui.eventos.AlertaPrecio;
import cl.duoc.cometranqui.eventos.NotificacionEvento;
import cl.duoc.cometranqui.mensajeria.MensajeInvalidoException;
import cl.duoc.cometranqui.mensajeria.enrutamiento.MotorReglas;
import cl.duoc.cometranqui.mensajeria.enrutamiento.MotorReglas.Evaluacion;

/**
 * Ruta {@code enrutar-alertas}: se activa cuando {@code OfertaService} detecta una baja de precio. Aplica las reglas de
 * distribución y reenvía la alerta, como {@link NotificacionEvento}, a cada destino cuya regla coincide.
 *
 * <ul>
 *   <li>Sin coincidencias: se registra «sin ruta» y se confirma (no es un error).</li>
 *   <li>Si una publicación falla: excepción → reintentos → DLT. Un reintento puede reenviar a destinos que ya lo
 *       recibieron; el consumidor de notificaciones es idempotente por {@code eventoId}.</li>
 * </ul>
 */
@Component
class EnrutadorAlertas {

    private static final Logger log = LoggerFactory.getLogger(EnrutadorAlertas.class);

    private final MotorReglas motor;
    private final KafkaTemplate<String, Object> kafka;

    EnrutadorAlertas(MotorReglas motor, KafkaTemplate<String, Object> kafka) {
        this.motor = motor;
        this.kafka = kafka;
    }

    @KafkaListener(id = "enrutar-alertas", idIsGroup = false,
            topics = "#{@catalogo.topicDeRuta('enrutar-alertas')}",
            groupId = "#{@catalogo.grupo('enrutar-alertas')}",
            concurrency = "#{@catalogo.concurrencia('enrutar-alertas')}",
            properties = "spring.json.value.default.type=cl.duoc.cometranqui.eventos.AlertaPrecio")
    void onAlerta(ConsumerRecord<String, AlertaPrecio> registro, Acknowledgment ack) {
        AlertaPrecio alerta = registro.value();
        if (alerta == null || alerta.eventoId() == null || alerta.restricciones() == null) {
            throw new MensajeInvalidoException("Alerta sin eventoId o sin restricciones");
        }
        List<Evaluacion> coincidencias = motor.evaluar(alerta).stream().filter(Evaluacion::coincide).toList();
        if (coincidencias.isEmpty()) {
            log.info("Alerta {} sin ruta: ninguna regla activa coincide", alerta.eventoId());
        }
        boolean prueba = alerta.eventoId().startsWith("prueba-");
        for (Evaluacion e : coincidencias) {
            NotificacionEvento evento = new NotificacionEvento(alerta.eventoId() + "|" + e.regla(), e.regla(),
                    e.restriccion(), alerta, prueba, Instant.now());
            enviar(e.topicDestino(), alerta.clave(), evento);
            log.info("Alerta {} -> {} (regla {})", alerta.eventoId(), e.topicDestino(), e.regla());
        }
        ack.acknowledge();
    }

    private void enviar(String topic, String clave, Object valor) {
        try {
            kafka.send(topic, clave, valor).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido enrutando a " + topic, ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("No se pudo enrutar a " + topic, ex);
        }
    }
}
