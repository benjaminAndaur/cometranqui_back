package cl.duoc.cometranqui.ofertas.kafka;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

import org.apache.kafka.clients.admin.NewTopic;

/**
 * Topics, productor (alertas y DLT) y manejo de errores del consumer.
 *
 * <p>Errores: 3 reintentos con backoff exponencial (1 s, 2 s, 4 s) y luego el registro se publica en
 * {@code ofertas.dlt} con headers {@code kafka_dlt-exception-*}. JSON inválido ({@code DeserializationException})
 * y {@link MensajeInvalidoException} no se reintentan: van directo a la DLT.
 */
@Configuration
public class KafkaConfig {

    private static final long DIA_MS = Duration.ofDays(1).toMillis();

    @Bean
    NewTopic topicOfertas(@Value("${app.kafka.topic-ofertas}") String nombre) {
        return TopicBuilder.name(nombre).partitions(3).replicas(1)
                .config("retention.ms", String.valueOf(7 * DIA_MS)).build();
    }

    @Bean
    NewTopic topicDlt(@Value("${app.kafka.topic-dlt}") String nombre) {
        return TopicBuilder.name(nombre).partitions(1).replicas(1)
                .config("retention.ms", String.valueOf(14 * DIA_MS)).build();
    }

    @Bean
    NewTopic topicAlertas(@Value("${app.kafka.topic-alertas}") String nombre) {
        return TopicBuilder.name(nombre).partitions(1).replicas(1)
                .config("retention.ms", String.valueOf(3 * DIA_MS)).build();
    }

    /**
     * Topic compactado: Kafka conserva el último mensaje de cada key (id del local) en vez de borrar por tiempo.
     * Funciona como una tabla de estado: un consumer nuevo que lee desde el inicio obtiene todos los locales.
     * segment.ms de 1 h para que la compactación se note en la demo.
     */
    @Bean
    NewTopic topicLocales(@Value("${app.kafka.topic-locales}") String nombre) {
        return TopicBuilder.name(nombre).partitions(3).replicas(1).compact()
                .config("segment.ms", String.valueOf(Duration.ofHours(1).toMillis()))
                .config("min.cleanable.dirty.ratio", "0.1")
                .build();
    }

    /**
     * Un solo productor para alertas (objetos -> JSON) y DLT (bytes crudos de un mensaje que no se pudo leer,
     * u objeto ya deserializado si falló la validación).
     */
    @Bean
    ProducerFactory<String, Object> producerFactory(KafkaProperties properties) {
        Map<Class<?>, Serializer<?>> porTipo = new LinkedHashMap<>();
        porTipo.put(byte[].class, new ByteArraySerializer());
        porTipo.put(Object.class, new JsonSerializer<>().noTypeInfo());
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(null),
                new StringSerializer(), new DelegatingByTypeSerializer(porTipo, true));
    }

    @Bean
    KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> template,
                                          @Value("${app.kafka.topic-dlt}") String topicDlt,
                                          @Value("${app.kafka.reintentos}") int reintentos,
                                          @Value("${app.kafka.backoff-ms}") long backoffMs) {
        // partición -1: deja que Kafka elija (la DLT tiene 1 partición, la original tiene 3)
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (registro, error) -> new TopicPartition(topicDlt, -1));
        ExponentialBackOff backOff = new ExponentialBackOff(backoffMs, 2.0);
        backOff.setMaxAttempts(reintentos);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MensajeInvalidoException.class);
        return handler;
    }
}
