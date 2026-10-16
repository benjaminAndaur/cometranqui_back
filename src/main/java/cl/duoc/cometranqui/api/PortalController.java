package cl.duoc.cometranqui.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.duoc.cometranqui.locales.LocalService;

@RestController
@RequestMapping("/api")
public class PortalController {

    /** Respaldo de EP1 mientras la tabla local esté vacía (antes de la primera ejecución del scraper). */
    private static final List<Map<String, Object>> LOCALES_EJEMPLO = List.of(
            Map.of(
                    "id", 1,
                    "nombre", "Panadería Sin Trazas",
                    "comuna", "Providencia",
                    "restricciones", List.of("Sin Gluten", "Sin Lactosa"),
                    "nivel", 1,
                    "nivelNombre", "Exclusivo / Certificado"),
            Map.of(
                    "id", 2,
                    "nombre", "Café Verde Vida",
                    "comuna", "Las Condes",
                    "restricciones", List.of("Vegano", "Vegetariano"),
                    "nivel", 2,
                    "nivelNombre", "Opción Segura"),
            Map.of(
                    "id", 3,
                    "nombre", "Bistró La Esquina",
                    "comuna", "Ñuñoa",
                    "restricciones", List.of("APLV"),
                    "nivel", 3,
                    "nivelNombre", "Opción Básica"));

    private final LocalService locales;

    public PortalController(LocalService locales) {
        this.locales = locales;
    }

    @GetMapping("/public/health")
    Map<String, Object> health() {
        return Map.of("status", "ok", "timestamp", Instant.now().toString());
    }

    @GetMapping("/me")
    Map<String, Object> me(Authentication authentication) {
        JwtAuthenticationToken jwt = (JwtAuthenticationToken) authentication;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("username", authentication.getName());
        response.put("subject", jwt.getToken().getSubject());
        response.put("email", jwt.getToken().getClaimAsString("email"));
        response.put("roles", authentication.getAuthorities().stream().map(Object::toString).toList());
        return response;
    }

    /**
     * Mismo formato que consume el frontend (id, nombre, comuna, restricciones, nivel, nivelNombre), ahora con
     * los locales reales que llegan por Kafka. Primero los de mejor nivel.
     */
    @GetMapping("/user/locales")
    List<?> locales() {
        if (locales.contar() == 0) {
            return LOCALES_EJEMPLO;
        }
        return locales.buscar(null, 3, null, 0, 200).stream()
                .sorted((a, b) -> Integer.compare(a.nivel(), b.nivel()))
                .map(l -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", l.id());
                    m.put("nombre", l.nombre());
                    m.put("comuna", l.comuna() != null ? l.comuna() : "");
                    m.put("restricciones", l.restricciones());
                    m.put("nivel", l.nivel());
                    m.put("nivelNombre", l.nivelNombre());
                    return m;
                })
                .toList();
    }
}
