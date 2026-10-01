package cl.duoc.cometranqui.scraper.fuentes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.jsoup.Jsoup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cl.duoc.cometranqui.scraper.http.ClienteHttp;
import cl.duoc.cometranqui.scraper.modelo.ClasificadorRestricciones;
import cl.duoc.cometranqui.scraper.modelo.ProductoListado;

/**
 * Tiendas WooCommerce: Store API pública {@code /wp-json/wc/store/v1/products} (sin autenticación, la misma que
 * usa el carrito del sitio). Precios en unidades menores según {@code currency_minor_unit} (0 para CLP).
 */
public class WooCommerceFuente extends FuenteCatalogo {

    private final ObjectMapper mapper = new ObjectMapper();

    public WooCommerceFuente(Tienda tienda, ClienteHttp http, ClasificadorRestricciones clasificador, int maxPaginas) {
        super(tienda, http, clasificador, maxPaginas);
    }

    @Override
    String urlPagina(int pagina) {
        return tienda.baseUrl() + "/wp-json/wc/store/v1/products?per_page=100&page=" + pagina;
    }

    @Override
    List<ProductoListado> parsear(String json) throws IOException {
        JsonNode raiz = mapper.readTree(json);
        List<ProductoListado> productos = new ArrayList<>();
        if (!raiz.isArray()) {
            return productos;
        }
        for (JsonNode p : raiz) {
            JsonNode precios = p.path("prices");
            int divisor = (int) Math.pow(10, precios.path("currency_minor_unit").asInt(0));
            String categorias = nombres(p.path("categories"));
            String evidencia = String.join(" / ", nombres(p.path("tags")), tienda.rubro() == null ? "" : tienda.rubro());
            productos.add(new ProductoListado(
                    p.path("id").asText(),
                    texto(p.path("name").asText()),
                    null,
                    categorias.isBlank() ? null : categorias,
                    precios.path("price").asInt() / divisor,
                    precios.path("regular_price").asInt() / divisor,
                    p.path("is_in_stock").asBoolean(true) && p.path("is_purchasable").asBoolean(true),
                    p.path("permalink").asText(),
                    p.path("images").path(0).path("src").asText(null),
                    evidencia));
        }
        return productos;
    }

    private static String nombres(JsonNode lista) {
        return StreamSupport.stream(lista.spliterator(), false)
                .map(n -> texto(n.path("name").asText()))
                .collect(Collectors.joining(" / "));
    }

    /** WooCommerce entrega nombres con entidades HTML ("Pack &#8220;Parrillita&#8221;"). */
    private static String texto(String html) {
        return Jsoup.parse(html).text();
    }
}
