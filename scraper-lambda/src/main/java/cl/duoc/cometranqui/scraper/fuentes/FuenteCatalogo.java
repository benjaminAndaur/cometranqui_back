package cl.duoc.cometranqui.scraper.fuentes;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cl.duoc.cometranqui.scraper.http.ClienteHttp;
import cl.duoc.cometranqui.scraper.modelo.ClasificadorRestricciones;
import cl.duoc.cometranqui.scraper.modelo.OfertaDTO;
import cl.duoc.cometranqui.scraper.modelo.ProductoListado;
import cl.duoc.cometranqui.scraper.modelo.Restriccion;

/**
 * Tiendas online especializadas (catálogos chicos): se descarga el catálogo completo una vez por ejecución y
 * se filtra por restricción en memoria, en vez de hacer una búsqueda por término.
 */
abstract class FuenteCatalogo implements FuenteOfertas {

    /**
     * @param rubro evidencia a nivel de tienda, solo para tiendas 100% especializadas (ej. "tienda vegana").
     *              null si la tienda vende de todo y cada producto debe demostrar su restricción.
     */
    record Tienda(String codigo, String nombre, String baseUrl, String rubro) {
    }

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ClienteHttp http;
    private final ClasificadorRestricciones clasificador;
    private final int maxPaginas;
    final Tienda tienda;
    private List<ProductoListado> catalogo;

    FuenteCatalogo(Tienda tienda, ClienteHttp http, ClasificadorRestricciones clasificador, int maxPaginas) {
        this.tienda = tienda;
        this.http = http;
        this.clasificador = clasificador;
        this.maxPaginas = maxPaginas;
    }

    abstract String urlPagina(int pagina);

    abstract List<ProductoListado> parsear(String json) throws IOException;

    @Override
    public String nombre() {
        return tienda.codigo();
    }

    @Override
    public String nombreVisible() {
        return tienda.nombre();
    }

    @Override
    public List<OfertaDTO> obtener(Restriccion restriccion) throws IOException {
        Instant ahora = Instant.now();
        return catalogo().stream()
                .map(p -> p.aOferta(nombre(), nombreVisible(), clasificador, ahora))
                .flatMap(java.util.Optional::stream)
                .filter(o -> o.restricciones().contains(restriccion))
                .toList();
    }

    private synchronized List<ProductoListado> catalogo() throws IOException {
        if (catalogo == null) {
            List<ProductoListado> todos = new ArrayList<>();
            for (int pagina = 1; pagina <= maxPaginas; pagina++) {
                var cuerpo = http.get(urlPagina(pagina));
                List<ProductoListado> productos = cuerpo.isPresent() ? parsear(cuerpo.get()) : List.of();
                if (productos.isEmpty()) {
                    break;
                }
                todos.addAll(productos);
            }
            log.info("{}: catálogo de {} productos", nombre(), todos.size());
            catalogo = todos;
        }
        return catalogo;
    }
}
