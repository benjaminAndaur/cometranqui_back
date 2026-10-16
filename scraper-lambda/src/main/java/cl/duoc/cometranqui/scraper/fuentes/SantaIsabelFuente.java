package cl.duoc.cometranqui.scraper.fuentes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cl.duoc.cometranqui.scraper.http.ClienteHttp;
import cl.duoc.cometranqui.scraper.modelo.ClasificadorRestricciones;
import cl.duoc.cometranqui.scraper.modelo.ProductoListado;

/**
 * Santa Isabel (Cencosud, VTEX). La página {@code /busca?ft=} incrusta el estado inicial en
 * {@code window.__renderData = "<json escapado>"} con el formato de catálogo VTEX
 * ({@code items[].sellers[].commertialOffer.Price / ListPrice}). Una búsqueda sin resultados responde 404.
 */
public class SantaIsabelFuente extends FuenteBusquedaPaginada {

    static final String BASE = "https://www.santaisabel.cl";
    private static final String MARCA_ESTADO = "window.__renderData";

    private final ObjectMapper mapper = new ObjectMapper();

    public SantaIsabelFuente(ClienteHttp http, ClasificadorRestricciones clasificador, int maxPaginas) {
        super(http, clasificador, maxPaginas);
    }

    @Override
    public String nombre() {
        return "SANTA_ISABEL";
    }

    @Override
    public String nombreVisible() {
        return "Santa Isabel";
    }

    @Override
    String urlBusqueda(String terminoCodificado, int pagina) {
        return BASE + "/busca?ft=" + terminoCodificado + (pagina > 1 ? "&page=" + pagina : "");
    }

    @Override
    List<ProductoListado> parsear(String html) throws IOException {
        JsonNode estado = estadoInicial(html);
        List<ProductoListado> productos = new ArrayList<>();
        if (estado == null) {
            return productos;
        }
        for (JsonNode p : estado.path("plp").path("plp_products").path("products")) {
            JsonNode item = p.path("items").path(0);
            JsonNode oferta = item.path("sellers").path(0).path("commertialOffer");
            String sku = item.path("itemId").asText(null);
            if (sku == null || oferta.isMissingNode()) {
                continue;
            }
            productos.add(new ProductoListado(
                    sku,
                    p.path("productName").asText(),
                    p.path("brand").asText(null),
                    p.path("categories").path(0).asText(null),
                    oferta.path("Price").asInt(),
                    oferta.path("ListPrice").asInt(),
                    oferta.path("AvailableQuantity").asInt(0) > 0,
                    BASE + "/" + p.path("linkText").asText() + "/p",
                    item.path("images").path(0).path("imageUrl").asText(null),
                    null));
        }
        return productos;
    }

    private JsonNode estadoInicial(String html) throws IOException {
        for (Element script : Jsoup.parse(html).select("script")) {
            String js = script.data();
            int marca = js.indexOf(MARCA_ESTADO);
            if (marca < 0) {
                continue;
            }
            int comilla = js.indexOf('"', marca);
            try (JsonParser parser = mapper.getFactory().createParser(js.substring(comilla))) {
                if (parser.nextToken() == JsonToken.VALUE_STRING) {
                    return mapper.readTree(parser.getText());
                }
            }
        }
        return null;
    }
}
