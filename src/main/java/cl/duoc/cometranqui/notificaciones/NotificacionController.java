package cl.duoc.cometranqui.notificaciones;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cl.duoc.cometranqui.ofertas.Restriccion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Notificaciones y suscripciones del usuario autenticado (identificado por el {@code sub} de su token de Cognito). */
@RestController
@RequestMapping("/api/user")
public class NotificacionController {

    public record SolicitudSuscripcion(@NotNull @Size(max = 4) List<String> restricciones) {
    }

    public record NotificacionVista(Long id, String titulo, String detalle, String url, String regla, boolean leida,
                                    String creadaEn) {
    }

    private final NotificacionService service;

    public NotificacionController(NotificacionService service) {
        this.service = service;
    }

    @GetMapping("/notificaciones")
    Map<String, Object> notificaciones(JwtAuthenticationToken auth) {
        NotificacionService.Resumen r = service.de(usuario(auth));
        return Map.of(
                "noLeidas", r.noLeidas(),
                "notificaciones", r.notificaciones().stream()
                        .map(n -> new NotificacionVista(n.getId(), n.getTitulo(), n.getDetalle(), n.getUrl(),
                                n.getRegla(), n.isLeida(), n.getCreadaEn().toString()))
                        .toList());
    }

    @PostMapping("/notificaciones/{id}/leida")
    ResponseEntity<Void> marcarLeida(JwtAuthenticationToken auth, @PathVariable Long id) {
        if (!service.marcarLeida(usuario(auth), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificación no encontrada");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notificaciones/leidas")
    Map<String, Integer> marcarTodasLeidas(JwtAuthenticationToken auth) {
        return Map.of("marcadas", service.marcarTodasLeidas(usuario(auth)));
    }

    @GetMapping("/suscripciones")
    Map<String, Object> suscripciones(JwtAuthenticationToken auth) {
        return vista(service.suscripciones(usuario(auth)));
    }

    @PutMapping("/suscripciones")
    Map<String, Object> suscribir(JwtAuthenticationToken auth, @Valid @RequestBody SolicitudSuscripcion solicitud) {
        try {
            return vista(service.suscribir(usuario(auth), solicitud.restricciones()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static Map<String, Object> vista(List<String> codigos) {
        return Map.of(
                "restricciones", codigos,
                "etiquetas", codigos.stream().map(c -> Restriccion.valueOf(c).etiqueta()).toList());
    }

    private static String usuario(JwtAuthenticationToken auth) {
        return auth.getToken().getSubject();
    }
}
