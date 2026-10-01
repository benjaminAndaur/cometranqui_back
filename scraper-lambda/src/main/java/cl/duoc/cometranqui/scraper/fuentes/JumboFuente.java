package cl.duoc.cometranqui.scraper.fuentes;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cl.duoc.cometranqui.scraper.http.ClienteHttp;
import cl.duoc.cometranqui.scraper.modelo.ClasificadorRestricciones;
import cl.duoc.cometranqui.scraper.modelo.ProductoListado;

/**
 * Jumbo (Cencosud). El sitio es Next.js con SSR: la página {@code /busqueda?ft=} ya trae los productos en el
 * payload RSC ({@code self.__next_f.push([1,"..."])}), con {@code price} y {@code listPrice} por SKU.
 * La categoría sale del atributo {@code data-gtm-impression} de cada tarjeta.
 */
public class JumboFuente extends FuenteBusquedaPaginada {

    static final String BASE = "https://www.jumbo.cl";
    private static final Pattern RSC_PUSH = Pattern.compile("^\\s*self\\.__next_f\\.push\\((.*)\\)\\s*;?\\s*$", Pattern.DOTALL);
    private static final String INICIO_PRODUCTO = "{\"productId\":";

    private final ObjectMapper mapper = new ObjectMapper();

    public JumboFuente(ClienteHttp http, ClasificadorRestricciones clasificador, int maxPaginas) {
        super(http, clasificador, maxPaginas);
    }

    @Override
    public String nombre() {
        return "JUMBO";
    }

    @Override
    String urlBusqueda(String terminoCodificado, int pagina) {
        return BASE + "/busqueda?ft=" + terminoCodificado + (pagina > 1 ? "&page=" + pagina : "");
    }

    @Override
    List<ProductoListado> parsear(String html) throws IOException {
        Document doc = Jsoup.parse(html, BASE);
        Map<String, String> categoriaPorSku = categorias(doc);
        String rsc = payloadRsc(doc);

        Map<String, ProductoListado> productos = new LinkedHashMap<>();
        int desde = 0;
        while ((desde = rsc.indexOf(INICIO_PRODUCTO, desde)) >= 0) {
            JsonNode producto = leerObjeto(rsc, desde);
            desde += INICIO_PRODUCTO.length();
            if (producto == null || !producto.path("items").isArray() || producto.path("items").isEmpty()) {
                continue;
            }
            JsonNode item = producto.path("items").get(0);
            String sku = item.path("skuId").asText(null);
            if (sku == null || productos.containsKey(sku)) {
                continue;
            }
            productos.put(sku, new ProductoListado(
                    sku,
                    item.path("name").asText(),
                    textoONulo(producto.path("brand")),
                    categoriaPorSku.get(sku),
                    item.path("price").asInt(),
                    item.path("listPrice").asInt(),
                    item.path("stock").asBoolean(true),
                    BASE + "/" + producto.path("slug").asText() + "/p",
                    item.path("images").path(0).asText(null)));
        }
        return new ArrayList<>(productos.values());
    }

    /** Concatena los fragmentos de texto del stream RSC de Next.js. */
    private String payloadRsc(Document doc) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Element script : doc.select("script")) {
            Matcher m = RSC_PUSH.matcher(script.data());
            if (m.matches()) {
                JsonNode arreglo = mapper.readTree(m.group(1));
                if (arreglo.path(1).isTextual()) {
                    sb.append(arreglo.get(1).asText());
                }
            }
        }
        return sb.toString();
    }

    /** Lee un único objeto JSON que empieza en {@code desde}, ignorando lo que venga después. */
    private JsonNode leerObjeto(String texto, int desde) {
        try (StringReader reader = new StringReader(texto)) {
            reader.skip(desde);
            JsonParser parser = mapper.getFactory().createParser(reader);
            return mapper.readTree(parser);
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, String> categorias(Document doc) {
        Map<String, String> resultado = new HashMap<>();
        for (Element tarjeta : doc.select("[data-gtm-impression]")) {
            try {
                JsonNode gtm = mapper.readTree(tarjeta.attr("data-gtm-impression"));
                if (gtm.hasNonNull("id") && gtm.hasNonNull("category")) {
                    resultado.put(gtm.get("id").asText(), gtm.get("category").asText());
                }
            } catch (IOException ignorado) {
                // tarjeta sin JSON válido: queda sin categoría
            }
        }
        return resultado;
    }

    private static String textoONulo(JsonNode nodo) {
        return nodo.isTextual() && !nodo.asText().isBlank() ? nodo.asText() : null;
    }
}
