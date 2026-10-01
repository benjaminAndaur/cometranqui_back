package cl.duoc.cometranqui.ofertas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Flujo completo del consumer contra Kafka y PostgreSQL reales (Testcontainers):
 * mensaje válido -> UPSERT idempotente -> endpoint público; JSON/validación inválida -> DLT; baja de precio -> alerta.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OfertasKafkaIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.kafka.properties.security.protocol", () -> "PLAINTEXT");
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://localhost/no-usado");
        r.add("app.security.cognito-client-id", () -> "test");
        r.add("app.kafka.backoff-ms", () -> "100");
    }

    /** Evita que SecurityConfig descargue el JWKS de Cognito al arrancar. */
    @MockBean
    NimbusJwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Autowired
    OfertaRepository repository;

    static KafkaProducer<String, String> producer;

    @BeforeAll
    static void crearProductor() {
        producer = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer());
    }

    @AfterAll
    static void cerrarProductor() {
        producer.close();
    }

    @Test
    void ofertaValidaSePersisteUnaSolaVezYSeExponeEnElEndpointPublico() throws Exception {
        String json = oferta("SG-1", 4990, 3990, Instant.now());
        enviar("JUMBO", json);
        enviar("JUMBO", json); // duplicado: at-least-once + UPSERT

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repository.findBySupermercadoAndSku("JUMBO", "SG-1")).isPresent());
        Thread.sleep(1000);
        assertThat(repository.findAll()).filteredOn(o -> o.getSku().equals("SG-1")).hasSize(1);

        mvc.perform(get("/api/public/ofertas").param("restriccion", "Sin Gluten").param("supermercado", "Jumbo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido[?(@.sku == 'SG-1')].restricciones[0]").value("Sin Gluten"))
                .andExpect(jsonPath("$.contenido[?(@.sku == 'SG-1')].porcentajeDescuento").value(20));
        mvc.perform(get("/api/public/ofertas/supermercados"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.codigo == 'JUMBO')].nombre").value("Jumbo"));
        mvc.perform(get("/api/public/ofertas").param("restriccion", "Keto"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void jsonMalformadoYOfertaInvalidaTerminanEnLaDlt() {
        String malformado = "{esto no es json";
        String invalida = oferta("INV-1", 1000, 1500, Instant.now()); // precio oferta > normal
        enviar("LIDER", malformado);
        enviar("LIDER", invalida);

        List<ConsumerRecord<String, String>> dlt = leerHasta("ofertas.dlt", 2);
        assertThat(dlt).extracting(ConsumerRecord::value).contains(malformado);
        assertThat(dlt).anySatisfy(r -> {
            assertThat(r.value()).contains("INV-1");
            assertThat(new String(r.headers().lastHeader("kafka_dlt-exception-message").value(), StandardCharsets.UTF_8))
                    .contains("precioOferta");
        });
        assertThat(repository.findBySupermercadoAndSku("LIDER", "INV-1")).isEmpty();
    }

    @Test
    void bajaDePrecioDeVeintePorCientoPublicaAlerta() {
        Instant t0 = Instant.now().minusSeconds(60);
        enviar("TOTTUS", oferta("ALR-1", 5000, 4000, t0));
        await().atMost(Duration.ofSeconds(20)).until(() ->
                repository.findBySupermercadoAndSku("TOTTUS", "ALR-1").isPresent());

        enviar("TOTTUS", oferta("ALR-1", 5000, 3000, Instant.now())); // baja 25%

        List<ConsumerRecord<String, String>> alertas = leerHasta("ofertas.alertas", 1);
        assertThat(alertas).anySatisfy(r -> {
            assertThat(r.key()).isEqualTo("TOTTUS:ALR-1");
            assertThat(r.value()).contains("\"precioAnterior\":4000", "\"precioNuevo\":3000", "\"bajaPorcentaje\":25");
        });
    }

    @Test
    void mensajeMasAntiguoNoPisaDatosRecientes() {
        enviar("UNIMARC", oferta("OLD-1", 3000, 2000, Instant.now()));
        await().atMost(Duration.ofSeconds(20)).until(() ->
                repository.findBySupermercadoAndSku("UNIMARC", "OLD-1").isPresent());

        enviar("UNIMARC", oferta("OLD-1", 3000, 2500, Instant.now().minusSeconds(3600))); // replay viejo
        enviar("UNIMARC", oferta("OLD-2", 1000, 900, Instant.now())); // marcador para saber que ya procesó

        await().atMost(Duration.ofSeconds(20)).until(() ->
                repository.findBySupermercadoAndSku("UNIMARC", "OLD-2").isPresent());
        assertThat(repository.findBySupermercadoAndSku("UNIMARC", "OLD-1").orElseThrow().getPrecioOferta())
                .isEqualTo(2000);
    }

    private static void enviar(String key, String json) {
        try {
            producer.send(new ProducerRecord<>("ofertas.scrapeadas", key, json)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String oferta(String sku, int normal, int oferta, Instant scrapeadoEn) {
        return """
                {"supermercado":"%s","sku":"%s","nombre":"Producto %s Sin Gluten","marca":"Marca",
                 "categoria":"Despensa","restricciones":["SIN_GLUTEN"],"precioNormal":%d,"precioOferta":%d,
                 "porcentajeDescuento":0,"urlProducto":"https://example.com/%s","urlImagen":null,
                 "vigenteHasta":null,"scrapeadoEn":"%s"}"""
                .formatted(supermercadoDe(sku), sku, sku, normal, oferta, sku, scrapeadoEn);
    }

    private static String supermercadoDe(String sku) {
        return switch (sku.substring(0, sku.indexOf('-'))) {
            case "SG" -> "JUMBO";
            case "INV" -> "LIDER";
            case "ALR" -> "TOTTUS";
            default -> "UNIMARC";
        };
    }

    private static List<ConsumerRecord<String, String>> leerHasta(String topic, int minimo) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> leidos = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(40)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(leidos::add);
                return leidos.size() >= minimo;
            });
        }
        return leidos;
    }
}
