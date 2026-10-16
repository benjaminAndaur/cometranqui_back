package cl.duoc.cometranqui.mensajeria.fallidos;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Fábrica de contenedores exclusiva para leer la DLT.
 * <ul>
 *   <li>Valor como texto: la DLT trae JSON o bytes ilegibles (justo lo que no se pudo deserializar).</li>
 *   <li>Su manejador de errores NO publica en la DLT: si registrar un fallido falla 3 veces, se loguea y se sigue,
 *       para no crear un bucle DLT → DLT.</li>
 * </ul>
 */
@Configuration
class FallidosConfig {

    private static final Logger log = LoggerFactory.getLogger(FallidosConfig.class);

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> fabricaFallidos(KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                (registro, error) -> log.error("No se pudo registrar el mensaje fallido {}-{}@{}: {}",
                        registro.topic(), registro.partition(), registro.offset(), error.getMessage()),
                new FixedBackOff(1000L, 2)));
        return factory;
    }
}
