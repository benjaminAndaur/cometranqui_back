package cl.duoc.cometranqui.scraper.fuentes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import cl.duoc.cometranqui.scraper.modelo.ClasificadorRestricciones;
import cl.duoc.cometranqui.scraper.modelo.OfertaDTO;
import cl.duoc.cometranqui.scraper.modelo.ProductoListado;
import cl.duoc.cometranqui.scraper.modelo.Restriccion;

/** Parsers contra HTML real guardado en src/test/resources/fixtures (capturado el 2026-10-01). */
class ParsersTest {

    private final ClasificadorRestricciones clasificador = new ClasificadorRestricciones();

    @Test
    void jumboExtraeProductosConPrecioYPrecioLista() throws IOException {
        List<ProductoListado> productos = new JumboFuente(null, clasificador, 1)
                .parsear(fixture("jumbo-busqueda-vegano.html"));

        assertThat(productos).hasSizeGreaterThanOrEqualTo(20);
        assertThat(productos).allSatisfy(p -> {
            assertThat(p.sku()).isNotBlank();
            assertThat(p.nombre()).isNotBlank();
            assertThat(p.precio()).isPositive();
            assertThat(p.precioLista()).isGreaterThanOrEqualTo(p.precio());
            assertThat(p.url()).startsWith("https://www.jumbo.cl/").endsWith("/p");
        });
        assertThat(productos).extracting(ProductoListado::sku).doesNotHaveDuplicates();
        assertThat(productos).filteredOn(p -> p.categoria() != null).isNotEmpty();
        assertThat(productos).filteredOn(p -> p.precioLista() > p.precio()).isNotEmpty();
    }

    @Test
    void jumboGeneraOfertasVeganas() throws IOException {
        List<OfertaDTO> ofertas = ofertas(new JumboFuente(null, clasificador, 1), "jumbo-busqueda-vegano.html");

        assertThat(ofertas).isNotEmpty().allSatisfy(o -> {
            assertThat(o.supermercado()).isEqualTo("JUMBO");
            assertThat(o.precioOferta()).isLessThan(o.precioNormal());
            assertThat(o.porcentajeDescuento()).isBetween(1, 99);
        });
        assertThat(ofertas).anySatisfy(o -> assertThat(o.restricciones()).contains(Restriccion.VEGANO));
    }

    @Test
    void santaIsabelExtraeProductosVtex() throws IOException {
        List<ProductoListado> productos = new SantaIsabelFuente(null, clasificador, 1)
                .parsear(fixture("santaisabel-busca-sin-leche.html"));

        assertThat(productos).hasSizeGreaterThanOrEqualTo(20);
        assertThat(productos).allSatisfy(p -> {
            assertThat(p.sku()).isNotBlank();
            assertThat(p.precio()).isPositive();
            assertThat(p.url()).startsWith("https://www.santaisabel.cl/").endsWith("/p");
        });
        assertThat(productos).filteredOn(p -> p.precioLista() > p.precio()).isNotEmpty();
    }

    @Test
    void santaIsabelNoMarcaLecheSinLactosaComoAplv() throws IOException {
        List<OfertaDTO> ofertas = ofertas(new SantaIsabelFuente(null, clasificador, 1), "santaisabel-busca-sin-leche.html");

        assertThat(ofertas)
                .filteredOn(o -> o.nombre().toLowerCase().contains("sin lactosa"))
                .isNotEmpty()
                .allSatisfy(o -> assertThat(o.restricciones())
                        .contains(Restriccion.SIN_LACTOSA)
                        .doesNotContain(Restriccion.APLV));
    }

    @Test
    void htmlSinDatosDevuelveListaVacia() throws IOException {
        String html = "<html><body><p>Mantención</p></body></html>";
        assertThat(new JumboFuente(null, clasificador, 1).parsear(html)).isEmpty();
        assertThat(new SantaIsabelFuente(null, clasificador, 1).parsear(html)).isEmpty();
    }

    private List<OfertaDTO> ofertas(FuenteBusquedaPaginada fuente, String archivo) throws IOException {
        Instant ahora = Instant.now();
        return fuente.parsear(fixture(archivo)).stream()
                .map(p -> p.aOferta(fuente.nombre(), clasificador, ahora))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    private static String fixture(String nombre) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                ParsersTest.class.getResourceAsStream("/fixtures/" + nombre), nombre)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
