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

@RestController
@RequestMapping("/api")
public class PortalController {

    private static final List<Map<String, Object>> LOCALES = List.of(
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

    @GetMapping("/user/locales")
    List<Map<String, Object>> locales() {
        return LOCALES;
    }
}
