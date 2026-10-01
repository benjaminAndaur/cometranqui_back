package cl.duoc.cometranqui.scraper.modelo;

import java.time.Instant;
import java.util.Map;

/**
 * Mensaje del topic {@code locales.scrapeados} (topic compactado: key = {@link #id()}, se conserva el último
 * estado de cada local). Contrato compartido con {@code cl.duoc.cometranqui.locales.kafka.LocalMensaje}.
 *
 * @param id identificador estable, ej. "osm-node-123456"
 * @param niveles nivel de seguridad por restricción, con la escala de la web:
 *                1 = Exclusivo (100% dedicado), 2 = Opción Segura (requiere verificación manual, nunca se infiere),
 *                3 = Opción Básica (tiene alternativas, sin garantía de trazas)
 */
public record LocalDTO(
        String id,
        String fuente,
        String nombre,
        String tipo,
        Map<Restriccion, Integer> niveles,
        String direccion,
        String comuna,
        double latitud,
        double longitud,
        String sitioWeb,
        String telefono,
        String horario,
        String urlFuente,
        Instant scrapeadoEn) {
}
