package cl.duoc.cometranqui;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Base de los tests de integración: Kafka y PostgreSQL reales (Testcontainers), compartidos entre clases.
 * Cada clase cierra su contexto al terminar ({@link DirtiesContext}) para que sus consumidores no compitan con los
 * de otra clase por los mismos mensajes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IntegracionKafkaBase {

    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final KafkaProducer<String, String> PRODUCTOR;

    static {
        KAFKA.start();
        POSTGRES.start();
        PRODUCTOR = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer());
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.kafka.properties.security.protocol", () -> "PLAINTEXT");
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://localhost/no-usado");
        r.add("app.security.cognito-client-id", () -> "test");
        r.add("app.mensajeria.reintentos.backoff-inicial", () -> "100ms");
    }

    /** Evita que SecurityConfig descargue el JWKS de Cognito al arrancar. */
    @MockBean
    protected NimbusJwtDecoder jwtDecoder;

    protected static void enviar(String topic, String key, String valor) {
        try {
            PRODUCTOR.send(new ProducerRecord<>(topic, key, valor)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lee un topic desde el inicio con un grupo efímero hasta tener al menos {@code minimo} registros que cumplan. */
    protected static List<ConsumerRecord<String, String>> leerHasta(String topic, int minimo,
                                                                   java.util.function.Predicate<ConsumerRecord<String, String>> filtro) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> leidos = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(40)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (filtro.test(r)) {
                        leidos.add(r);
                    }
                });
                return leidos.size() >= minimo;
            });
        }
        return leidos;
    }

    protected static String header(ConsumerRecord<String, String> r, String nombre) {
        var h = r.headers().lastHeader(nombre);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
