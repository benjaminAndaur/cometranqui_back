package cl.duoc.cometranqui.mensajeria.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.ErrorCampo;
import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.RecursoEnConflicto;
import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.RecursoNoEncontrado;
import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.SolicitudInvalida;

/**
 * Respuestas de error de la API de administración en formato Problem Details (RFC 9457):
 * {@code {"title", "status", "detail", "errores": [{"campo", "mensaje"}]}}. La interfaz muestra {@code detail} y marca
 * cada campo de {@code errores} en el formulario.
 */
@RestControllerAdvice(assignableTypes = MensajeriaAdminController.class)
class ManejadorErroresAdmin {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacion(MethodArgumentNotValidException e) {
        List<ErrorCampo> errores = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ErrorCampo(f.getField(), f.getDefaultMessage()))
                .sorted((a, b) -> a.campo().compareTo(b.campo()))
                .toList();
        return problema(HttpStatus.BAD_REQUEST, "Solicitud inválida", "Revisa los campos marcados", errores);
    }

    @ExceptionHandler(SolicitudInvalida.class)
    ProblemDetail invalida(SolicitudInvalida e) {
        return problema(HttpStatus.BAD_REQUEST, "Solicitud inválida", e.getMessage(), e.errores());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail ilegible(HttpMessageNotReadableException e) {
        return problema(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "El cuerpo no es JSON válido o tiene un valor no permitido", List.of());
    }

    @ExceptionHandler(RecursoNoEncontrado.class)
    ProblemDetail noEncontrado(RecursoNoEncontrado e) {
        return problema(HttpStatus.NOT_FOUND, "No encontrado", e.getMessage(), List.of());
    }

    @ExceptionHandler(RecursoEnConflicto.class)
    ProblemDetail conflicto(RecursoEnConflicto e) {
        return problema(HttpStatus.CONFLICT, "Conflicto", e.getMessage(), List.of());
    }

    @ExceptionHandler(BrokerNoDisponible.class)
    ProblemDetail broker(BrokerNoDisponible e) {
        return problema(HttpStatus.SERVICE_UNAVAILABLE, "Broker no disponible", e.getMessage(), List.of());
    }

    private static ProblemDetail problema(HttpStatus status, String titulo, String detalle, List<ErrorCampo> errores) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalle);
        p.setTitle(titulo);
        p.setProperty("errores", errores);
        return p;
    }
}
