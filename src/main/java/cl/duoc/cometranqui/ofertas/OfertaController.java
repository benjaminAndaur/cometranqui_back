package cl.duoc.cometranqui.ofertas;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ofertas públicas para el "Buscador de Ofertas" del frontend. Vía API Gateway:
 * {@code GET /public/ofertas} -> {@code /api/public/ofertas}.
 */
@RestController
@RequestMapping("/api/public/ofertas")
public class OfertaController {

    private static final Map<String, String> NOMBRES = Map.of(
            "JUMBO", "Jumbo",
            "SANTA_ISABEL", "Santa Isabel",
            "LIDER", "Líder",
            "UNIMARC", "Unimarc",
            "TOTTUS", "Tottus");

    public record OfertaResponse(
            Long id, String supermercado, String supermercadoNombre, String sku, String nombre, String marca,
            String categoria, List<String> restricciones, int precioNormal, int precioOferta,
            int porcentajeDescuento, String urlProducto, String urlImagen, String vigenteHasta, String actualizadoEn) {
    }

    public record PaginaResponse<T>(List<T> contenido, int pagina, int tamano, long totalElementos, int totalPaginas) {
    }

    public record SupermercadoResponse(String codigo, String nombre, long ofertas) {
    }

    private final OfertaService service;

    public OfertaController(OfertaService service) {
        this.service = service;
    }

    /**
     * @param restriccion código (SIN_GLUTEN) o etiqueta del frontend ("Sin Gluten")
     * @param supermercado código (SANTA_ISABEL) o nombre ("Santa Isabel")
     * @param categoria búsqueda parcial, sin distinguir mayúsculas
     */
    @GetMapping
    PaginaResponse<OfertaResponse> listar(@RequestParam(required = false) String restriccion,
                                         @RequestParam(required = false) String supermercado,
                                         @RequestParam(required = false) String categoria,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        Restriccion r = null;
        if (restriccion != null && !restriccion.isBlank()) {
            r = Restriccion.desde(restriccion).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "restriccion inválida; valores: " + Arrays.toString(Restriccion.values())));
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page >= 0 y size entre 1 y 100");
        }
        Page<Oferta> resultado = service.buscar(r, codigoSupermercado(supermercado), categoria, page, size);
        return new PaginaResponse<>(resultado.map(OfertaController::aResponse).getContent(),
                resultado.getNumber(), resultado.getSize(), resultado.getTotalElements(), resultado.getTotalPages());
    }

    @GetMapping("/supermercados")
    List<SupermercadoResponse> supermercados() {
        return service.supermercados().stream()
                .map(c -> new SupermercadoResponse(c.getSupermercado(), nombre(c.getSupermercado(), c.getNombre()),
                        c.getOfertas()))
                .toList();
    }

    static String codigoSupermercado(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return Normalizer.normalize(valor.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[\\s-]+", "_")
                .toUpperCase();
    }

    /** Prefiere el nombre que envía el scraper; el mapa cubre mensajes antiguos sin ese campo. */
    private static String nombre(String codigo, String nombreEnviado) {
        return nombreEnviado != null ? nombreEnviado : NOMBRES.getOrDefault(codigo, codigo);
    }

    private static OfertaResponse aResponse(Oferta o) {
        List<String> etiquetas = Arrays.stream(o.getRestricciones())
                .map(c -> Restriccion.desde(c).map(Restriccion::etiqueta).orElse(c))
                .toList();
        return new OfertaResponse(o.getId(), o.getSupermercado(), nombre(o.getSupermercado(), o.getSupermercadoNombre()), o.getSku(),
                o.getNombre(), o.getMarca(), o.getCategoria(), etiquetas, o.getPrecioNormal(), o.getPrecioOferta(),
                o.getPorcentajeDescuento(), o.getUrlProducto(), o.getUrlImagen(),
                o.getVigenteHasta() == null ? null : o.getVigenteHasta().toString(),
                o.getActualizadoEn() == null ? null : o.getActualizadoEn().toString());
    }
}
