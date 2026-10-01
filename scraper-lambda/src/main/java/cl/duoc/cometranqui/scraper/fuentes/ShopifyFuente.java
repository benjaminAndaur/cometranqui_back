package cl.duoc.cometranqui.scraper.fuentes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cl.duoc.cometranqui.scraper.http.ClienteHttp;
import cl.duoc.cometranqui.scraper.modelo.ClasificadorRestricciones;
import cl.duoc.cometranqui.scraper.modelo.ProductoListado;

/**
 * Tiendas Shopify: endpoint público {@code /products.json} (250 productos por página). Oferta = variante con
 * {@code compare_at_price > price}. Los tags y el product_type se usan como evidencia de la restricción.
 */
public class ShopifyFuente extends FuenteCatalogo {

    private final ObjectMapper mapper = new ObjectMapper();

    public ShopifyFuente(Tienda tienda, ClienteHttp http, ClasificadorRestricciones clasificador, int maxPaginas) {
        super(tienda, http, clasificador, maxPaginas);
    }

    @Override
    String urlPagina(int pagina) {
        return tienda.baseUrl() + "/products.json?limit=250&page=" + pagina;
    }

    @Override
    List<ProductoListado> parsear(String json) throws IOException {
        List<ProductoListado> productos = new ArrayList<>();
        for (JsonNode p : mapper.readTree(json).path("products")) {
            JsonNode variante = primeraDisponible(p.path("variants"));
            if (variante == null) {
                continue;
            }
            String tipo = p.path("product_type").asText("");
            String tags = StreamSupport.stream(p.path("tags").spliterator(), false)
                    .map(JsonNode::asText).collect(Collectors.joining(", "));
            String evidencia = String.join(" / ", tags, tienda.rubro() == null ? "" : tienda.rubro());
            productos.add(new ProductoListado(
                    variante.path("id").asText(),
                    p.path("title").asText(),
                    p.path("vendor").asText(null),
                    tipo.isBlank() ? null : tipo,
                    pesos(variante.path("price").asText()),
                    pesos(variante.path("compare_at_price").asText(null)),
                    true,
                    tienda.baseUrl() + "/products/" + p.path("handle").asText(),
                    p.path("images").path(0).path("src").asText(null),
                    evidencia));
        }
        return productos;
    }

    private static JsonNode primeraDisponible(JsonNode variantes) {
        for (JsonNode v : variantes) {
            if (v.path("available").asBoolean(true)) {
                return v;
            }
        }
        return null;
    }

    /** "6990.00" -> 6990; null/vacío -> 0 */
    static int pesos(String texto) {
        if (texto == null || texto.isBlank() || texto.equals("null")) {
            return 0;
        }
        return (int) Math.round(Double.parseDouble(texto));
    }
}
