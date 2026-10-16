package cl.duoc.cometranqui.mensajeria.consumidores;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import cl.duoc.cometranqui.eventos.OfertaMensaje;
import cl.duoc.cometranqui.ofertas.OfertaService;

/**
 * Ruta {@code persistir-ofertas}: se activa con cada oferta que publica el scraper.
 * Solo valida y delega en {@link OfertaService}; confirma el offset recién después de guardar (at-least-once).
 */
@Component
class ConsumidorOfertas {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorOfertas.class);

    private final OfertaService service;
    private final ValidadorMensajes validador;

    ConsumidorOfertas(OfertaService service, ValidadorMensajes validador) {
        this.service = service;
        this.validador = validador;
    }

    @KafkaListener(id = "persistir-ofertas", idIsGroup = false,
            topics = "#{@catalogo.topicDeRuta('persistir-ofertas')}",
            groupId = "#{@catalogo.grupo('persistir-ofertas')}",
            concurrency = "#{@catalogo.concurrencia('persistir-ofertas')}",
            properties = "spring.json.value.default.type=cl.duoc.cometranqui.eventos.OfertaMensaje")
    void onOferta(ConsumerRecord<String, OfertaMensaje> registro, Acknowledgment ack) {
        OfertaMensaje mensaje = validador.validar(registro.value());
        service.procesar(mensaje);
        ack.acknowledge();
        log.debug("Oferta {}/{} procesada (partición {}, offset {})",
                mensaje.supermercado(), mensaje.sku(), registro.partition(), registro.offset());
    }
}
