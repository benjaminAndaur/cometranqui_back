package cl.duoc.cometranqui.scraper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Configuración por variables de entorno (las mismas en Lambda y en ejecución local).
 *
 * <ul>
 *   <li>KAFKA_BOOTSTRAP: host:puerto del broker (local: localhost:9092)</li>
 *   <li>KAFKA_USER / KAFKA_PASS: credenciales SASL/SCRAM-SHA-512. Sin usuario se conecta en PLAINTEXT.</li>
 *   <li>KAFKA_TOPIC: topic destino (por defecto ofertas.scrapeadas)</li>
 *   <li>FUENTES_ACTIVAS: códigos separados por coma (JUMBO, UNIMARC, DAORO, MOCK...) o los grupos
 *       SUPERMERCADOS, TIENDAS, TODAS (por defecto)</li>
 *   <li>SCRAPER_MAX_PAGINAS: páginas por búsqueda (1-5, por defecto 3)</li>
 *   <li>SCRAPER_PAUSA_MS: pausa entre requests (por defecto 1500)</li>
 *   <li>MOCK_BAJA_PRECIO: true para que FuenteMock simule bajas de precio (demo de ofertas.alertas)</li>
 * </ul>
 */
public record ScraperConfig(
        String kafkaBootstrap,
        String kafkaUser,
        String kafkaPass,
        String topic,
        List<String> fuentesActivas,
        int maxPaginas,
        long pausaMs,
        boolean mockBajaPrecio) {

    public static ScraperConfig desdeEntorno() {
        return desde(System.getenv());
    }

    static ScraperConfig desde(Map<String, String> env) {
        return new ScraperConfig(
                env.getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092"),
                env.getOrDefault("KAFKA_USER", ""),
                env.getOrDefault("KAFKA_PASS", ""),
                env.getOrDefault("KAFKA_TOPIC", "ofertas.scrapeadas"),
                Arrays.stream(env.getOrDefault("FUENTES_ACTIVAS", "TODAS").split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList(),
                Math.max(1, Math.min(5, Integer.parseInt(env.getOrDefault("SCRAPER_MAX_PAGINAS", "3")))),
                Long.parseLong(env.getOrDefault("SCRAPER_PAUSA_MS", "1500")),
                Boolean.parseBoolean(env.getOrDefault("MOCK_BAJA_PRECIO", "false")));
    }

    public boolean usaSasl() {
        return kafkaUser != null && !kafkaUser.isBlank();
    }

    @Override
    public String toString() {
        // Nunca exponer la clave en logs
        return "ScraperConfig[kafkaBootstrap=%s, kafkaUser=%s, topic=%s, fuentes=%s, maxPaginas=%d, pausaMs=%d, mockBajaPrecio=%s]"
                .formatted(kafkaBootstrap, kafkaUser, topic, fuentesActivas, maxPaginas, pausaMs, mockBajaPrecio);
    }
}
