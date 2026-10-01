package cl.duoc.cometranqui.scraper.kafka;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import cl.duoc.cometranqui.scraper.ScraperConfig;
import cl.duoc.cometranqui.scraper.modelo.OfertaDTO;

/**
 * Productor Kafka: key = supermercado (todas las ofertas de una tienda caen en la misma partición y se procesan
 * en orden), value = OfertaDTO en JSON. {@code acks=all} + idempotencia evitan pérdidas y duplicados por reintento.
 */
public class KafkaPublicadorOfertas implements PublicadorOfertas {

    private static final Logger log = LoggerFactory.getLogger(KafkaPublicadorOfertas.class);

    private final KafkaProducer<String, String> producer;
    private final String topic;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AtomicInteger fallidos = new AtomicInteger();

    public KafkaPublicadorOfertas(ScraperConfig config) {
        this.topic = config.topic();
        this.producer = new KafkaProducer<>(propiedades(config), new StringSerializer(), new StringSerializer());
    }

    static Properties propiedades(ScraperConfig config) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "cometranqui-scraper");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "gzip");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 60_000);
        if (config.usaSasl()) {
            props.put("security.protocol", "SASL_PLAINTEXT");
            props.put("sasl.mechanism", "SCRAM-SHA-512");
            props.put("sasl.jaas.config", "org.apache.kafka.common.security.scram.ScramLoginModule required "
                    + "username=\"" + config.kafkaUser() + "\" password=\"" + config.kafkaPass() + "\";");
        }
        return props;
    }

    @Override
    public void publicar(OfertaDTO oferta) {
        String json;
        try {
            json = mapper.writeValueAsString(oferta);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar la oferta " + oferta.clave(), e);
        }
        producer.send(new ProducerRecord<>(topic, oferta.supermercado(), json), (metadata, error) -> {
            if (error != null) {
                fallidos.incrementAndGet();
                log.error("Fallo al publicar {}: {}", oferta.clave(), error.getMessage());
            }
        });
    }

    @Override
    public int flush() {
        producer.flush();
        return fallidos.get();
    }

    @Override
    public void close() {
        producer.close();
    }
}
