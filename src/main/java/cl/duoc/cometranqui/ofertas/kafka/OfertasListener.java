package cl.duoc.cometranqui.ofertas.kafka;

import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import cl.duoc.cometranqui.ofertas.OfertaService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/**
 * Consumer del group {@code cometranqui-api-ofertas}, concurrencia 3 (un hilo por partición).
 *
 * <p>Entrega at-least-once: el offset se confirma ({@code ack.acknowledge()}) solo después de persistir.
 * Si la app cae entre el UPSERT y el commit, el mensaje se reprocesa y el UPSERT lo deja igual (idempotente).
 */
@Component
public class OfertasListener {

    private static final Logger log = LoggerFactory.getLogger(OfertasListener.class);

    private final OfertaService service;
    private final Validator validator;

    public OfertasListener(OfertaService service, Validator validator) {
        this.service = service;
        this.validator = validator;
    }

    // idIsGroup=false: si no, el id del listener reemplazaría al group-id configurado
    @KafkaListener(id = "ofertas-listener", idIsGroup = false, topics = "${app.kafka.topic-ofertas}")
    public void onOferta(ConsumerRecord<String, OfertaMensaje> registro, Acknowledgment ack) {
        OfertaMensaje mensaje = registro.value();
        Set<ConstraintViolation<OfertaMensaje>> errores = validator.validate(mensaje);
        if (!errores.isEmpty()) {
            throw new OfertaInvalidaException(errores.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
        service.procesar(mensaje);
        ack.acknowledge();
        log.debug("Oferta {}/{} procesada (partición {}, offset {})",
                mensaje.supermercado(), mensaje.sku(), registro.partition(), registro.offset());
    }
}
