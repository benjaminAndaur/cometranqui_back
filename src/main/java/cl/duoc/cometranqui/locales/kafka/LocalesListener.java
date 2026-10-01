package cl.duoc.cometranqui.locales.kafka;

import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import cl.duoc.cometranqui.locales.LocalService;
import cl.duoc.cometranqui.ofertas.kafka.MensajeInvalidoException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/**
 * Consumer del topic compactado {@code locales.scrapeados} (group {@code cometranqui-api-locales}).
 * Mismo manejo de errores que las ofertas: reintentos con backoff y luego DLT; commit manual tras persistir.
 * Como el topic es compactado, un consumer nuevo que lee desde el inicio reconstruye el estado completo.
 */
@Component
public class LocalesListener {

    private final LocalService service;
    private final Validator validator;

    public LocalesListener(LocalService service, Validator validator) {
        this.service = service;
        this.validator = validator;
    }

    @KafkaListener(id = "locales-listener", idIsGroup = false, groupId = "cometranqui-api-locales",
            topics = "${app.kafka.topic-locales}",
            properties = "spring.json.value.default.type=cl.duoc.cometranqui.locales.kafka.LocalMensaje")
    public void onLocal(ConsumerRecord<String, LocalMensaje> registro, Acknowledgment ack) {
        LocalMensaje mensaje = registro.value();
        Set<ConstraintViolation<LocalMensaje>> errores = validator.validate(mensaje);
        if (!errores.isEmpty()) {
            throw new MensajeInvalidoException(errores.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
        service.procesar(mensaje);
        ack.acknowledge();
    }
}
