package cl.duoc.cometranqui.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import cl.duoc.cometranqui.scraper.fuentes.FuenteMock;
import cl.duoc.cometranqui.scraper.fuentes.FuenteOfertas;
import cl.duoc.cometranqui.scraper.kafka.PublicadorOfertas;
import cl.duoc.cometranqui.scraper.modelo.OfertaDTO;
import cl.duoc.cometranqui.scraper.modelo.Restriccion;

class ScraperServiceTest {

    @Test
    void unaFuenteQueFallaNoDetieneALasDemas() {
        FuenteOfertas rota = new FuenteOfertas() {
            @Override
            public String nombre() {
                return "ROTA";
            }

            @Override
            public List<OfertaDTO> obtener(Restriccion r) throws IOException {
                throw new IOException("HTTP 403");
            }
        };
        PublicadorEnMemoria publicador = new PublicadorEnMemoria();

        ScraperService.Resumen resumen = new ScraperService(List.of(rota, new FuenteMock(false)), publicador).ejecutar();

        assertThat(resumen.errores()).hasSize(Restriccion.values().length).allMatch(e -> e.startsWith("ROTA/"));
        assertThat(resumen.ofertasPorFuente()).containsEntry("ROTA", 0).containsEntry("MOCK", 20);
        assertThat(publicador.publicadas).hasSize(20);
    }

    @Test
    void unProductoRepetidoEntreRestriccionesSePublicaUnaVezConTodasSusRestricciones() {
        PublicadorEnMemoria publicador = new PublicadorEnMemoria();
        new ScraperService(List.of(new FuenteMock(false)), publicador).ejecutar();

        assertThat(publicador.publicadas).extracting(OfertaDTO::clave).doesNotHaveDuplicates();
        OfertaDTO hamburguesa = publicador.publicadas.stream()
                .filter(o -> o.sku().equals("MOCK-J005")).findFirst().orElseThrow();
        assertThat(hamburguesa.restricciones())
                .containsExactlyInAnyOrder(Restriccion.VEGANO, Restriccion.APLV, Restriccion.SIN_LACTOSA);
    }

    private static final class PublicadorEnMemoria implements PublicadorOfertas {
        final List<OfertaDTO> publicadas = new ArrayList<>();

        @Override
        public void publicar(OfertaDTO oferta) {
            publicadas.add(oferta);
        }

        @Override
        public int flush() {
            return 0;
        }

        @Override
        public void close() {
        }
    }
}
