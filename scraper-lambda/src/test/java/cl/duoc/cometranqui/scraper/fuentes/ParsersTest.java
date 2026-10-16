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

    @Test
    void unimarcExtraePreciosDesdeTextoYDetectaOfertas() throws IOException {
        UnimarcFuente fuente = new UnimarcFuente(null, clasificador, 1);
        List<ProductoListado> productos = fuente.parsear(fixture("unimarc-search-sin-lactosa.json"));

        assertThat(productos).hasSize(40).allSatisfy(p -> {
            assertThat(p.precio()).isPositive();
            assertThat(p.url()).startsWith("https://www.unimarc.cl/product/").doesNotEndWith("/p");
        });
        List<OfertaDTO> ofertas = productos.stream()
                .map(p -> p.aOferta(fuente.nombre(), fuente.nombreVisible(), clasificador, Instant.now()))
                .flatMap(java.util.Optional::stream).toList();
        assertThat(ofertas).isNotEmpty()
                .allSatisfy(o -> assertThat(o.supermercadoNombre()).isEqualTo("Unimarc"))
                .anySatisfy(o -> assertThat(o.restricciones()).contains(Restriccion.SIN_LACTOSA));
        assertThat(UnimarcFuente.pesos("$11.990")).isEqualTo(11990);
    }

    @Test
    void shopifyUsaTagsComoEvidencia() throws IOException {
        ShopifyFuente fuente = new ShopifyFuente(
                new FuenteCatalogo.Tienda("DAORO", "Da'Oro", "https://daoro.cl", null), null, clasificador, 1);
        List<ProductoListado> productos = fuente.parsear(fixture("shopify-daoro-products.json"));

        // 60 en el JSON, pero los agotados (sin variante disponible) se descartan
        assertThat(productos).hasSizeBetween(1, 59);
        assertThat(productos).allSatisfy(p -> assertThat(p.url()).startsWith("https://daoro.cl/products/"));
        List<OfertaDTO> ofertas = productos.stream()
                .map(p -> p.aOferta("DAORO", "Da'Oro", clasificador, Instant.now()))
                .flatMap(java.util.Optional::stream).toList();
        assertThat(ofertas).isNotEmpty()
                .anySatisfy(o -> assertThat(o.restricciones()).contains(Restriccion.VEGANO));
    }

    @Test
    void wooCommerceDecodificaEntidadesYAplicaRubroDeTienda() throws IOException {
        WooCommerceFuente fuente = new WooCommerceFuente(
                new FuenteCatalogo.Tienda("THIS_IS_VEGAN", "This is Vegan", "https://thisisvegan.cl", "tienda vegana"),
                null, clasificador, 1);
        List<ProductoListado> productos = fuente.parsear(fixture("woo-thisisvegan-products.json"));

        assertThat(productos).hasSize(40);
        assertThat(productos).extracting(ProductoListado::nombre).noneMatch(n -> n.contains("&#"));
        List<OfertaDTO> ofertas = productos.stream()
                .map(p -> p.aOferta("THIS_IS_VEGAN", "This is Vegan", clasificador, Instant.now()))
                .flatMap(java.util.Optional::stream).toList();
        assertThat(ofertas).isNotEmpty().allSatisfy(o -> assertThat(o.restricciones())
                .contains(Restriccion.VEGANO, Restriccion.APLV, Restriccion.SIN_LACTOSA));
    }

    private List<OfertaDTO> ofertas(FuenteBusquedaPaginada fuente, String archivo) throws IOException {
        Instant ahora = Instant.now();
        return fuente.parsear(fixture(archivo)).stream()
                .map(p -> p.aOferta(fuente.nombre(), fuente.nombreVisible(), clasificador, ahora))
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
