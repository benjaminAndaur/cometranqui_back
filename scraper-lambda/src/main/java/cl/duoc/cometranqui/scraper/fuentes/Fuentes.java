package cl.duoc.cometranqui.scraper.fuentes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cl.duoc.cometranqui.scraper.ScraperConfig;
import cl.duoc.cometranqui.scraper.fuentes.FuenteCatalogo.Tienda;
import cl.duoc.cometranqui.scraper.http.ClienteHttp;
import cl.duoc.cometranqui.scraper.locales.FuenteLocales;
import cl.duoc.cometranqui.scraper.locales.OsmFuente;
import cl.duoc.cometranqui.scraper.modelo.ClasificadorRestricciones;

/**
 * Registro de fuentes. FUENTES_ACTIVAS acepta códigos individuales o los grupos SUPERMERCADOS / TIENDAS / TODAS.
 *
 * <p>Revisadas y descartadas (2026-10): Líder (robots.txt prohíbe /search y /supermercado), Tottus (HTTP 403 al
 * bot). Para agregar una tienda Shopify o WooCommerce basta una línea en {@link #SHOPIFY} o {@link #WOOCOMMERCE}.
 */
public final class Fuentes {

    private static final Logger log = LoggerFactory.getLogger(Fuentes.class);

    public static final List<String> SUPERMERCADOS = List.of("JUMBO", "SANTA_ISABEL", "UNIMARC");

    /** Tiendas Shopify: cada producto debe demostrar su restricción con nombre, tipo o tags. */
    static final List<Tienda> SHOPIFY = List.of(
            new Tienda("DAORO", "Da'Oro", "https://daoro.cl", null),
            new Tienda("LIREKE", "Lireke", "https://www.lireke.cl", null),
            new Tienda("TASTY_FREE", "Tasty Free", "https://tastyfree.cl", null));

    /** Tiendas WooCommerce. Solo una tienda 100% especializada declara rubro. */
    static final List<Tienda> WOOCOMMERCE = List.of(
            new Tienda("ARADOS", "Arados", "https://arados.cl", null),
            new Tienda("GLOBE_ITALIA", "Emporio Globe Italia", "https://www.emporioglobeitalia.cl", null),
            new Tienda("THIS_IS_VEGAN", "This is Vegan", "https://thisisvegan.cl", "tienda vegana"));

    private Fuentes() {
    }

    public static List<String> tiendas() {
        List<String> codigos = new ArrayList<>();
        SHOPIFY.forEach(t -> codigos.add(t.codigo()));
        WOOCOMMERCE.forEach(t -> codigos.add(t.codigo()));
        return codigos;
    }

    public static List<FuenteLocales> localesActivos(ScraperConfig config) {
        ClienteHttp http = new ClienteHttp(config.pausaMs());
        List<FuenteLocales> fuentes = new ArrayList<>();
        for (String nombre : config.localesActivos()) {
            if (nombre.equalsIgnoreCase("OSM")) {
                fuentes.add(new OsmFuente(http));
            } else {
                log.warn("Fuente de locales desconocida en LOCALES_ACTIVOS: {}", nombre);
            }
        }
        return fuentes;
    }

    public static List<FuenteOfertas> activas(ScraperConfig config) {
        ClienteHttp http = new ClienteHttp(config.pausaMs());
        ClasificadorRestricciones clasificador = new ClasificadorRestricciones();
        int paginas = config.maxPaginas();

        Map<String, FuenteOfertas> disponibles = new LinkedHashMap<>();
        disponibles.put("JUMBO", new JumboFuente(http, clasificador, paginas));
        disponibles.put("SANTA_ISABEL", new SantaIsabelFuente(http, clasificador, paginas));
        disponibles.put("UNIMARC", new UnimarcFuente(http, clasificador, paginas));
        SHOPIFY.forEach(t -> disponibles.put(t.codigo(), new ShopifyFuente(t, http, clasificador, paginas)));
        WOOCOMMERCE.forEach(t -> disponibles.put(t.codigo(), new WooCommerceFuente(t, http, clasificador, paginas)));
        disponibles.put("MOCK", new FuenteMock(config.mockBajaPrecio()));

        Map<String, FuenteOfertas> elegidas = new LinkedHashMap<>();
        for (String nombre : config.fuentesActivas()) {
            String codigo = nombre.trim().toUpperCase(Locale.ROOT);
            List<String> codigos = switch (codigo) {
                case "SUPERMERCADOS" -> SUPERMERCADOS;
                case "TIENDAS" -> tiendas();
                case "TODAS" -> {
                    List<String> todas = new ArrayList<>(SUPERMERCADOS);
                    todas.addAll(tiendas());
                    yield todas;
                }
                default -> List.of(codigo);
            };
            for (String c : codigos) {
                FuenteOfertas fuente = disponibles.get(c);
                if (fuente == null) {
                    log.warn("Fuente desconocida en FUENTES_ACTIVAS: {}", c);
                } else {
                    elegidas.put(c, fuente);
                }
            }
        }
        return new ArrayList<>(elegidas.values());
    }
}
