package cl.duoc.cometranqui.mensajeria.consumidores;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import cl.duoc.cometranqui.eventos.LocalMensaje;
import cl.duoc.cometranqui.locales.LocalService;

/**
 * Ruta {@code persistir-locales}: se activa con cada local que publica el scraper (topic compactado: un consumidor
 * nuevo que lee desde el inicio reconstruye todos los locales). Valida, delega y confirma tras guardar.
 */
@Component
class ConsumidorLocales {

    private final LocalService service;
    private final ValidadorMensajes validador;

    ConsumidorLocales(LocalService service, ValidadorMensajes validador) {
        this.service = service;
        this.validador = validador;
    }

    @KafkaListener(id = "persistir-locales", idIsGroup = false,
            topics = "#{@catalogo.topicDeRuta('persistir-locales')}",
            groupId = "#{@catalogo.grupo('persistir-locales')}",
            concurrency = "#{@catalogo.concurrencia('persistir-locales')}",
            properties = "spring.json.value.default.type=cl.duoc.cometranqui.eventos.LocalMensaje")
    void onLocal(ConsumerRecord<String, LocalMensaje> registro, Acknowledgment ack) {
        service.procesar(validador.validar(registro.value()));
        ack.acknowledge();
    }
}
