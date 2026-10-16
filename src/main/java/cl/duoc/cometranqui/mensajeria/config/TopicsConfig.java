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
 * Crea al arrancar los topics del catálogo (si ya existen, Kafka los deja como están): los destinos fijos y los
 * miembros iniciales de cada familia. Los demás miembros de una familia se crean desde la administración.
 */
@Configuration
public class TopicsConfig {

    @Bean
    KafkaAdmin.NewTopics topicsDelCatalogo(CatalogoMensajeria catalogo) {
        return new KafkaAdmin.NewTopics(catalogo.destinos().values().stream()
                .flatMap(d -> d.esFamilia()
                        ? d.inicialesOVacia().stream().map(nombre -> aNewTopic(nombre, d))
                        : java.util.stream.Stream.of(aNewTopic(d.topic(), d)))
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

    private static NewTopic aNewTopic(String nombre, Destino d) {
        return TopicBuilder.name(nombre)
                .partitions(d.particionesOPorDefecto())
                .replicas(1)
                .configs(configuracionKafka(d))
                .build();
    }
}
