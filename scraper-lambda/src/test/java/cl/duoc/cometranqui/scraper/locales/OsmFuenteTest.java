package cl.duoc.cometranqui.scraper.locales;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import cl.duoc.cometranqui.scraper.modelo.LocalDTO;
import cl.duoc.cometranqui.scraper.modelo.Restriccion;

class OsmFuenteTest {

    @Test
    void parseaLocalesDeOverpassConCoordenadasYNiveles() throws IOException {
        List<LocalDTO> locales = new OsmFuente(null).parsear(fixture("overpass-chile-diet.json"), Instant.now());

        assertThat(locales).hasSizeGreaterThan(100).allSatisfy(l -> {
            assertThat(l.id()).startsWith("osm-");
            assertThat(l.nombre()).isNotBlank();
            assertThat(l.latitud()).isBetween(-56.5, -17.0);
            assertThat(l.longitud()).isBetween(-76.0, -66.0);
            assertThat(l.niveles()).isNotEmpty();
            assertThat(l.niveles().values()).allMatch(n -> n == 1 || n == 3);
            assertThat(l.urlFuente()).startsWith("https://www.openstreetmap.org/");
        });
        assertThat(locales).extracting(LocalDTO::id).doesNotHaveDuplicates();
    }

    @Test
    void nivelUnoSoloParaLocalesExclusivosYNuncaNivelDos() throws IOException {
        ObjectMapper m = new ObjectMapper();
        assertThat(OsmFuente.niveles(m.readTree("{\"diet:vegan\":\"only\"}")))
                .containsEntry(Restriccion.VEGANO, 1)
                .containsEntry(Restriccion.APLV, 1)
                .containsEntry(Restriccion.SIN_LACTOSA, 1);
        assertThat(OsmFuente.niveles(m.readTree("{\"diet:vegan\":\"yes\",\"diet:gluten_free\":\"limited\"}")))
                .containsOnlyKeys(Restriccion.VEGANO, Restriccion.SIN_GLUTEN)
                .containsEntry(Restriccion.SIN_GLUTEN, 3);
        assertThat(OsmFuente.niveles(m.readTree("{\"diet:gluten_free\":\"no\"}"))).isEmpty();
    }

    @Test
    void siOverpassNoRespondeUsaElSnapshot() throws IOException {
        cl.duoc.cometranqui.scraper.http.ClienteHttp sinRed = new cl.duoc.cometranqui.scraper.http.ClienteHttp(0) {
            @Override
            public java.util.Optional<String> get(String url, int timeoutMs) throws IOException {
                throw new java.net.SocketTimeoutException("Read timed out");
            }
        };
        assertThat(new OsmFuente(sinRed).obtener()).hasSizeGreaterThan(100);
    }

    private static String fixture(String nombre) throws IOException {
        try (InputStream in = OsmFuenteTest.class.getResourceAsStream("/fixtures/" + nombre)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
