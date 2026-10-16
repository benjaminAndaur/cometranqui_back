package cl.duoc.cometranqui.scraper.fuentes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cl.duoc.cometranqui.scraper.http.ClienteHttp;
import cl.duoc.cometranqui.scraper.modelo.ClasificadorRestricciones;
import cl.duoc.cometranqui.scraper.modelo.ProductoListado;

/**
 * Unimarc (SMU). El sitio renderiza en el navegador y busca con {@code POST /catalog/product/search} en su BFF
 * público, que responde JSON con {@code price.price} y {@code price.listPrice} como texto ("$1.250").
 * Las promociones tipo "2 x $3.000" no bajan el precio unitario y no se consideran ofertas.
 */
public class UnimarcFuente extends FuenteBusquedaPaginada {

    static final String BASE = "https://www.unimarc.cl";
    private static final String API = "https://bff-unimarc-ecommerce.unimarc.cl/catalog/product/search";
    private static final int TAMANO_PAGINA = 40;
    private static final Map<String, String> HEADERS = Map.of("channel", "UNIMARC", "source", "web", "version", "1.0.0");

    private final ObjectMapper mapper = new ObjectMapper();

    public UnimarcFuente(ClienteHttp http, ClasificadorRestricciones clasificador, int maxPaginas) {
        super(http, clasificador, maxPaginas);
    }

    @Override
    public String nombre() {
        return "UNIMARC";
    }

    @Override
    public String nombreVisible() {
        return "Unimarc";
    }

    @Override
    String urlBusqueda(String terminoCodificado, int pagina) {
        return API;
    }

    @Override
    Optional<String> descargar(String termino, String terminoCodificado, int pagina) throws IOException {
        int desde = (pagina - 1) * TAMANO_PAGINA;
        String cuerpo = mapper.writeValueAsString(Map.of(
                "from", String.valueOf(desde),
                "to", String.valueOf(desde + TAMANO_PAGINA - 1),
                "searching", termino,
                "promotionsOnly", false,
                "userTriggered", true));
        return http().postJson(API, cuerpo, HEADERS);
    }

    @Override
    List<ProductoListado> parsear(String json) throws IOException {
        List<ProductoListado> productos = new ArrayList<>();
        for (JsonNode p : mapper.readTree(json).path("availableProducts")) {
            JsonNode item = p.path("item");
            JsonNode precio = p.path("price");
            String sku = item.path("itemId").asText(null);
            if (sku == null) {
                continue;
            }
            String slug = item.path("slug").asText("").replaceFirst("^/", "").replaceFirst("/p$", "");
            productos.add(new ProductoListado(
                    sku,
                    item.path("name").asText(),
                    item.path("brand").asText(null),
                    item.path("categories").path(0).asText(null),
                    pesos(precio.path("price").asText()),
                    pesos(precio.path("listPrice").asText()),
                    precio.path("availableQuantity").asInt(0) > 0,
                    BASE + "/product/" + slug,
                    item.path("images").path(0).asText(null),
                    null));
        }
        return productos;
    }

    /** "$1.250" -> 1250 */
    static int pesos(String texto) {
        String digitos = texto == null ? "" : texto.replaceAll("[^0-9]", "");
        return digitos.isEmpty() ? 0 : Integer.parseInt(digitos);
    }
}
