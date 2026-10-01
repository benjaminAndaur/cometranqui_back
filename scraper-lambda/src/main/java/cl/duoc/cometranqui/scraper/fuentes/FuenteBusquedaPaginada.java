package cl.duoc.cometranqui.scraper.fuentes;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cl.duoc.cometranqui.scraper.http.ClienteHttp;
import cl.duoc.cometranqui.scraper.modelo.ClasificadorRestricciones;
import cl.duoc.cometranqui.scraper.modelo.OfertaDTO;
import cl.duoc.cometranqui.scraper.modelo.ProductoListado;
import cl.duoc.cometranqui.scraper.modelo.Restriccion;

/**
 * Lógica común a las tiendas que se scrapean por su página de búsqueda: recorre los términos de la restricción,
 * pagina hasta {@code maxPaginas} (o hasta que no haya productos nuevos) y filtra ofertas con el clasificador.
 */
abstract class FuenteBusquedaPaginada implements FuenteOfertas {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ClienteHttp http;
    private final ClasificadorRestricciones clasificador;
    private final int maxPaginas;

    FuenteBusquedaPaginada(ClienteHttp http, ClasificadorRestricciones clasificador, int maxPaginas) {
        this.http = http;
        this.clasificador = clasificador;
        this.maxPaginas = maxPaginas;
    }

    /** URL de la página {@code pagina} (base 1) de resultados para {@code termino} ya codificado. */
    abstract String urlBusqueda(String terminoCodificado, int pagina);

    /** Descarga una página de resultados. Por defecto GET a {@link #urlBusqueda}; vacío = sin resultados. */
    Optional<String> descargar(String termino, String terminoCodificado, int pagina) throws IOException {
        return http.get(urlBusqueda(terminoCodificado, pagina));
    }

    ClienteHttp http() {
        return http;
    }

    /** Extrae los productos del HTML de una página de resultados. */
    abstract List<ProductoListado> parsear(String html) throws IOException;

    @Override
    public List<OfertaDTO> obtener(Restriccion restriccion) throws IOException {
        List<OfertaDTO> ofertas = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        Instant ahora = Instant.now();
        for (String termino : restriccion.terminosBusqueda()) {
            String codificado = URLEncoder.encode(termino, StandardCharsets.UTF_8).replace("+", "%20");
            for (int pagina = 1; pagina <= maxPaginas; pagina++) {
                Optional<String> html = descargar(termino, codificado, pagina);
                if (html.isEmpty()) {
                    break;
                }
                List<ProductoListado> productos = parsear(html.get());
                int nuevos = 0;
                for (ProductoListado p : productos) {
                    if (!vistos.add(p.sku())) {
                        continue;
                    }
                    nuevos++;
                    p.aOferta(nombre(), nombreVisible(), clasificador, ahora)
                            .filter(o -> o.restricciones().contains(restriccion))
                            .ifPresent(ofertas::add);
                }
                log.info("{} '{}' pág {}: {} productos ({} nuevos), {} ofertas acumuladas",
                        nombre(), termino, pagina, productos.size(), nuevos, ofertas.size());
                if (nuevos == 0) {
                    break;
                }
            }
        }
        return ofertas;
    }
}
