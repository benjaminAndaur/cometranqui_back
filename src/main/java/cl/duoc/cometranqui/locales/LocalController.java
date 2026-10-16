package cl.duoc.cometranqui.locales;

import java.util.Arrays;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cl.duoc.cometranqui.ofertas.OfertaController.PaginaResponse;
import cl.duoc.cometranqui.ofertas.Restriccion;

/**
 * Locales para el "Mapa de Locales Seguros" del frontend (incluye latitud/longitud).
 * Vía API Gateway: {@code GET /public/locales} -> {@code /api/public/locales}.
 */
@RestController
@RequestMapping("/api/public/locales")
public class LocalController {

    private final LocalService service;

    public LocalController(LocalService service) {
        this.service = service;
    }

    /**
     * @param restriccion código o etiqueta del frontend ("Sin Gluten")
     * @param nivel nivel máximo aceptado: 1 solo exclusivos, 3 todos (por defecto)
     */
    @GetMapping
    PaginaResponse<LocalService.LocalVista> listar(@RequestParam(required = false) String restriccion,
                                                  @RequestParam(defaultValue = "3") int nivel,
                                                  @RequestParam(required = false) String comuna,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        Restriccion r = null;
        if (restriccion != null && !restriccion.isBlank()) {
            r = Restriccion.desde(restriccion).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "restriccion inválida; valores: " + Arrays.toString(Restriccion.values())));
        }
        if (nivel < 1 || nivel > 3 || page < 0 || size < 1 || size > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nivel 1-3, page >= 0, size 1-200");
        }
        Page<LocalService.LocalVista> resultado = service.buscar(r, nivel, comuna, page, size);
        return new PaginaResponse<>(resultado.getContent(), resultado.getNumber(), resultado.getSize(),
                resultado.getTotalElements(), resultado.getTotalPages());
    }
}
