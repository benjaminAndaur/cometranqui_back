package cl.duoc.cometranqui.mensajeria.consumidores;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import cl.duoc.cometranqui.mensajeria.fallidos.MensajesFallidosService;

/**
 * Ruta {@code registrar-fallidos}: se activa cuando cualquier consumidor aparta un mensaje en la DLT (error definitivo
 * o reintentos agotados). Guarda el mensaje y su diagnóstico para que un administrador lo revise y lo reprocese o
 * descarte desde /admin. Usa su propia fábrica: lee el valor como texto y nunca reenvía a la DLT.
 */
@Component
class ConsumidorFallidos {

    private final MensajesFallidosService service;

    ConsumidorFallidos(MensajesFallidosService service) {
        this.service = service;
    }

    @KafkaListener(id = "registrar-fallidos", idIsGroup = false, containerFactory = "fabricaFallidos",
            topics = "#{@catalogo.topicDeRuta('registrar-fallidos')}",
            groupId = "#{@catalogo.grupo('registrar-fallidos')}",
            concurrency = "#{@catalogo.concurrencia('registrar-fallidos')}")
    void onFallido(ConsumerRecord<String, String> registro, Acknowledgment ack) {
        service.registrar(registro);
        ack.acknowledge();
    }
}
