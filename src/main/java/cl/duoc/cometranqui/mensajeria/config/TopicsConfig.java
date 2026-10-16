package cl.duoc.cometranqui.mensajeria.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import cl.duoc.cometranqui.mensajeria.config.MensajeriaProperties.Destino;
import cl.duoc.cometranqui.mensajeria.config.MensajeriaProperties.Limpieza;

/**
 * Crea al arrancar los topics fijos declarados en el catálogo (si ya existen, Kafka los deja como están).
 * Las familias ({@code prefijo}) no se crean aquí: sus miembros los crea la administración.
 */
@Configuration
public class TopicsConfig {

    @Bean
    KafkaAdmin.NewTopics topicsDelCatalogo(CatalogoMensajeria catalogo) {
        return new KafkaAdmin.NewTopics(catalogo.destinos().values().stream()
                .filter(d -> !d.esFamilia())
                .map(TopicsConfig::aNewTopic)
                .toArray(NewTopic[]::new));
    }

    /** Traduce un destino del catálogo a la configuración de Kafka. También lo usa la administración. */
    public static Map<String, String> configuracionKafka(Destino d) {
        Map<String, String> config = new HashMap<>(d.configOVacia());
        if (d.limpiezaOPorDefecto() == Limpieza.COMPACT) {
            config.put("cleanup.policy", "compact");
        } else {
            config.put("cleanup.policy", "delete");
            if (d.retencion() != null) {
                config.put("retention.ms", String.valueOf(d.retencion().toMillis()));
            }
        }
        return config;
    }

    private static NewTopic aNewTopic(Destino d) {
        return TopicBuilder.name(d.topic())
                .partitions(d.particionesOPorDefecto())
                .replicas(1)
                .configs(configuracionKafka(d))
                .build();
    }
}
