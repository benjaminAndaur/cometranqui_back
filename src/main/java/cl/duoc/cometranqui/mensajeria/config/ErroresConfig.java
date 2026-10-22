package cl.duoc.cometranqui.mensajeria.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import cl.duoc.cometranqui.mensajeria.MensajeInvalidoException;

/**
 * Política de confirmación, reintentos y mensajes fallidos, común a todos los consumidores de negocio.
 *
 * <pre>
 *  Escenario                     Comportamiento                                   Motivo
 *  ─────────────────────────────  ───────────────────────────────────────────────  ─────────────────────────────────
 *  Éxito                          el consumidor confirma (ack) DESPUÉS de persistir  si el proceso cae, se reprocesa
 *  Duplicado (at-least-once)      UPSERT / clave única: mismo resultado             sin reprocesos indebidos
 *  Error recuperable (BD caída)   reintentos con backoff exponencial (catálogo)      fallos transitorios se resuelven
 *  Reintentos agotados            se aparta a la DLT con headers y se confirma       no bloquea la partición
 *  Error definitivo               directo a la DLT, sin reintentos                   reintentar no lo arregla
 *    (JSON inválido, validación, violación de restricción en BD)
 * </pre>
 *
 * El DLT es el destino {@code fallidos} del catálogo; lo consume {@code ConsumidorFallidos}.
 */
@Configuration
public class ErroresConfig {

    private static final Logger log = LoggerFactory.getLogger(ErroresConfig.class);

    /**
     * Un solo productor: objetos → JSON (eventos) y byte[] → tal cual (mensajes ilegibles hacia la DLT y reprocesos).
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

    /** Manejador por defecto de los consumidores de negocio (Spring Boot lo asigna a la fábrica de contenedores). */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> template, CatalogoMensajeria catalogo) {
        String topicDlt = catalogo.topic("fallidos");
        // partición -1: Kafka elige (la DLT tiene 1 partición; los orígenes tienen 3)
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (registro, error) -> new TopicPartition(topicDlt, -1));
        MensajeriaProperties.Reintentos r = catalogo.reintentos();
        // "intentos" = reintentos después del primer fallo (3 → 4 entregas en total: 1 s, 2 s, 4 s)
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(r.intentos());
        backOff.setInitialInterval(r.backoffInicial().toMillis());
        backOff.setMultiplier(r.multiplicador());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // DeserializationException y MessageConversionException ya son no reintentables por defecto
        handler.addNotRetryableExceptions(MensajeInvalidoException.class, DataIntegrityViolationException.class);
        // con ack manual Spring no confirma el registro enviado a la DLT: sin esto queda lag y se reentrega al reiniciar
        handler.setCommitRecovered(true);
        handler.setRetryListeners((registro, error, intento) -> log.warn("Reintento {}/{} de {}-{}@{}: {}",
                intento, r.intentos(), registro.topic(), registro.partition(), registro.offset(), error.getMessage()));
        return handler;
    }
}
