package cl.duoc.cometranqui.mensajeria.admin;

import java.util.List;

/**
 * Errores de las operaciones de administración. Los traduce a Problem Details (RFC 9457) {@link ManejadorErroresAdmin}.
 */
public final class ErroresAdmin {

    private ErroresAdmin() {
    }

    public record ErrorCampo(String campo, String mensaje) {
    }

    /** 400: la solicitud no cumple las reglas (formato, rango, valores permitidos). */
    public static class SolicitudInvalida extends RuntimeException {
        private final transient List<ErrorCampo> errores;

        public SolicitudInvalida(String campo, String mensaje) {
            this(List.of(new ErrorCampo(campo, mensaje)));
        }

        public SolicitudInvalida(List<ErrorCampo> errores) {
            super(errores.stream().map(e -> e.campo() + ": " + e.mensaje()).reduce((a, b) -> a + "; " + b).orElse(""));
            this.errores = errores;
        }

        public List<ErrorCampo> errores() {
            return errores;
        }
    }

    /** 404: el recurso pedido no existe. */
    public static class RecursoNoEncontrado extends RuntimeException {
        public RecursoNoEncontrado(String mensaje) {
            super(mensaje);
        }
    }

    /** 409: la operación choca con el estado actual (duplicado, protegido, en uso). */
    public static class RecursoEnConflicto extends RuntimeException {
        public RecursoEnConflicto(String mensaje) {
            super(mensaje);
        }
    }
}
