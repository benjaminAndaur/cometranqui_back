package cl.duoc.cometranqui.scraper.locales;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cl.duoc.cometranqui.scraper.http.ClienteHttp;
import cl.duoc.cometranqui.scraper.modelo.LocalDTO;
import cl.duoc.cometranqui.scraper.modelo.Restriccion;

/**
 * Locales desde OpenStreetMap (datos abiertos, licencia ODbL: se debe atribuir "© OpenStreetMap contributors").
 * Una sola consulta a la Overpass API por ejecución, filtrando las etiquetas {@code diet:*} dentro de un polígono
 * simplificado de Chile (mucho más liviano que {@code area["ISO3166-1"="CL"]}, que suele dar 504).
 *
 * <p>Se usan instancias públicas de Overpass cuyo robots.txt no restringe {@code /api/}. overpass-api.de sí lo
 * prohíbe para bots, así que no se usa.
 *
 * <p>Mapeo a los niveles de la web (conservador):
 * <ul>
 *   <li>{@code diet:X=only} -> Nivel 1, el local es 100% para esa dieta. Un local 100% vegano tampoco usa lácteos,
 *       por eso suma APLV y SIN_LACTOSA en nivel 1.</li>
 *   <li>{@code diet:X=yes|limited} -> Nivel 3, ofrece alternativas pero nada garantiza ausencia de trazas.</li>
 *   <li>Nivel 2 (cocinas separadas) no se puede saber desde OSM: queda para verificación manual.</li>
 * </ul>
 */
public class OsmFuente implements FuenteLocales {

    static final List<String> SERVIDORES = List.of(
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter");

    /** Frontera andina simplificada: deja fuera Mendoza, Bariloche y Ushuaia; incluye San Pedro de Atacama. */
    static final String POLIGONO_CHILE = "-17.4 -69.4 -22.0 -68.0 -24.0 -68.3 -27.0 -68.8 -30.0 -69.9 -33.0 -70.0 "
            + "-35.0 -70.3 -38.0 -71.0 -40.0 -71.6 -42.0 -71.7 -44.0 -71.5 -46.0 -71.7 -48.0 -72.5 -50.0 -73.2 "
            + "-51.5 -72.0 -52.2 -69.0 -52.6 -68.4 -55.0 -68.6 -56.2 -67.2 -56.2 -76.0 -17.4 -75.0";

    /** Unión de filtros por clave exacta (usan índice; un regex sobre la clave obliga a revisar todo). */
    static final String CONSULTA = "[out:json][timeout:90];("
            + List.of("diet:vegan", "diet:gluten_free", "diet:lactose_free", "diet:dairy_free").stream()
                    .map(clave -> "nwr[\"" + clave + "\"~\"^(only|yes|limited)$\"](poly:\"" + POLIGONO_CHILE + "\");")
                    .collect(java.util.stream.Collectors.joining())
            + ");out center tags;";

    private static final Map<String, Restriccion> ETIQUETAS = Map.of(
            "diet:gluten_free", Restriccion.SIN_GLUTEN,
            "diet:lactose_free", Restriccion.SIN_LACTOSA,
            "diet:dairy_free", Restriccion.APLV,
            "diet:vegan", Restriccion.VEGANO);

    private static final Map<String, String> TIPOS = Map.ofEntries(
            Map.entry("restaurant", "Restaurante"), Map.entry("cafe", "Café"),
            Map.entry("fast_food", "Comida rápida"), Map.entry("ice_cream", "Heladería"),
            Map.entry("bakery", "Panadería"), Map.entry("pastry", "Pastelería"),
            Map.entry("convenience", "Almacén"), Map.entry("supermarket", "Supermercado"),
            Map.entry("health_food", "Tienda naturista"), Map.entry("deli", "Delicatessen"),
            Map.entry("bar", "Bar"), Map.entry("pub", "Bar"));

    /** Captura del 2026-10-01 (123 locales). Regenerar con la CONSULTA de esta clase. */
    static final String SNAPSHOT = "osm-locales-snapshot.json";
    private static final int TIMEOUT_MS = 100_000;
    private static final Logger log = LoggerFactory.getLogger(OsmFuente.class);

    private final ClienteHttp http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OsmFuente(ClienteHttp http) {
        this.http = http;
    }

    @Override
    public String nombre() {
        return "OSM";
    }

    @Override
    public List<LocalDTO> obtener() throws IOException {
        for (String servidor : SERVIDORES) {
            try {
                String url = servidor + "?data=" + URLEncoder.encode(CONSULTA, StandardCharsets.UTF_8);
                return parsear(http.get(url, TIMEOUT_MS).orElse("{}"), Instant.now());
            } catch (IOException e) {
                log.warn("Overpass {} no respondió: {}", servidor, e.getMessage());
            }
        }
        // Las instancias públicas de Overpass se saturan seguido. Los locales cambian poco y el topic es
        // compactado, así que publicar el último snapshot conocido es preferible a no publicar nada.
        log.warn("Ningún servidor Overpass respondió; se usa el snapshot {}", SNAPSHOT);
        try (InputStream in = OsmFuente.class.getResourceAsStream("/" + SNAPSHOT)) {
            if (in == null) {
                throw new IOException("Overpass no disponible y no hay snapshot");
            }
            return parsear(new String(in.readAllBytes(), StandardCharsets.UTF_8), Instant.now());
        }
    }

    List<LocalDTO> parsear(String json, Instant ahora) throws IOException {
        List<LocalDTO> locales = new ArrayList<>();
        for (JsonNode e : mapper.readTree(json).path("elements")) {
            JsonNode tags = e.path("tags");
            String nombre = tags.path("name").asText("");
            Map<Restriccion, Integer> niveles = niveles(tags);
            JsonNode punto = e.has("lat") ? e : e.path("center");
            if (nombre.isBlank() || niveles.isEmpty() || !punto.has("lat")) {
                continue;
            }
            String tipoOsm = tags.has("amenity") ? tags.path("amenity").asText() : tags.path("shop").asText("");
            String tipoElemento = e.path("type").asText();
            long idOsm = e.path("id").asLong();
            locales.add(new LocalDTO(
                    "osm-" + tipoElemento + "-" + idOsm,
                    nombre(),
                    nombre,
                    TIPOS.getOrDefault(tipoOsm, "Local"),
                    niveles,
                    direccion(tags),
                    primero(tags, "addr:city", "addr:suburb", "is_in:city"),
                    punto.path("lat").asDouble(),
                    punto.path("lon").asDouble(),
                    primero(tags, "website", "contact:website", "contact:instagram"),
                    primero(tags, "phone", "contact:phone"),
                    primero(tags, "opening_hours"),
                    "https://www.openstreetmap.org/" + tipoElemento + "/" + idOsm,
                    ahora));
        }
        return locales;
    }

    static Map<Restriccion, Integer> niveles(JsonNode tags) {
        Map<Restriccion, Integer> niveles = new EnumMap<>(Restriccion.class);
        ETIQUETAS.forEach((etiqueta, restriccion) -> {
            String valor = tags.path(etiqueta).asText("");
            int nivel = switch (valor) {
                case "only" -> 1;
                case "yes", "limited" -> 3;
                default -> 0;
            };
            if (nivel > 0) {
                niveles.merge(restriccion, nivel, Math::min);
                if (restriccion == Restriccion.VEGANO && nivel == 1) {
                    niveles.merge(Restriccion.APLV, 1, Math::min);
                    niveles.merge(Restriccion.SIN_LACTOSA, 1, Math::min);
                }
            }
        });
        return niveles;
    }

    private static String direccion(JsonNode tags) {
        String calle = tags.path("addr:street").asText("");
        String numero = tags.path("addr:housenumber").asText("");
        String direccion = (calle + " " + numero).trim();
        return direccion.isEmpty() ? null : direccion;
    }

    private static String primero(JsonNode tags, String... claves) {
        for (String clave : claves) {
            String valor = tags.path(clave).asText("");
            if (!valor.isBlank()) {
                return valor;
            }
        }
        return null;
    }
}
