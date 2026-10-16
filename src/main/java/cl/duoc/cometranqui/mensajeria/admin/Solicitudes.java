package cl.duoc.cometranqui.mensajeria.admin;

import java.util.List;

import cl.duoc.cometranqui.mensajeria.config.MensajeriaProperties.Limpieza;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuerpos de las solicitudes de administración. Primera capa de validación (formato y rangos, Bean Validation);
 * las reglas que dependen del estado (duplicados, protegidos, en uso) las valida cada servicio.
 */
public final class Solicitudes {

    private Solicitudes() {
    }

    public static final String FORMATO_TOPIC = "^[a-z0-9]+(\\.[a-z0-9]+(-[a-z0-9]+)*)+$";
    public static final String FORMATO_REGLA = "^[a-z0-9]+(-[a-z0-9]+)*$";

    /** Crear un destino (topic). */
    public record SolicitudDestino(
            @NotBlank(message = "El nombre es obligatorio")
            @Size(max = 100, message = "Máximo 100 caracteres")
            @Pattern(regexp = FORMATO_TOPIC,
                    message = "Solo minúsculas, números, puntos y guiones (ej. notificaciones.sin-gluten)")
            String nombre,
            @NotNull(message = "Las particiones son obligatorias")
            @Min(value = 1, message = "Debe estar entre 1 y 6") @Max(value = 6, message = "Debe estar entre 1 y 6")
            Integer particiones,
            @Min(value = 1, message = "Debe estar entre 1 y 720 horas (30 días)")
            @Max(value = 720, message = "Debe estar entre 1 y 720 horas (30 días)")
            Integer retencionHoras,
            Limpieza limpieza) {
    }

    /** Modificar un destino: retención y/o aumento de particiones. */
    public record SolicitudCambioDestino(
            @Min(value = 1, message = "Debe estar entre 1 y 720 horas (30 días)")
            @Max(value = 720, message = "Debe estar entre 1 y 720 horas (30 días)")
            Integer retencionHoras,
            @Min(value = 1, message = "Debe estar entre 1 y 6") @Max(value = 6, message = "Debe estar entre 1 y 6")
            Integer particiones) {
    }

    /** Crear o reemplazar una regla de distribución. */
    public record SolicitudRegla(
            @NotBlank(message = "El nombre es obligatorio")
            @Size(max = 60, message = "Máximo 60 caracteres")
            @Pattern(regexp = FORMATO_REGLA, message = "Solo minúsculas, números y guiones (ej. alertas-celiacos)")
            String nombre,
            @NotBlank(message = "El destino de origen es obligatorio")
            String destinoOrigen,
            String restriccion,
            @Min(value = 0, message = "Debe estar entre 0 y 100") @Max(value = 100, message = "Debe estar entre 0 y 100")
            Integer descuentoMinimo,
            @Pattern(regexp = "^[A-Z][A-Z_]{1,39}$", message = "Código de tienda en mayúsculas (ej. JUMBO, SANTA_ISABEL)")
            String tienda,
            @NotBlank(message = "El destino es obligatorio")
            @Pattern(regexp = FORMATO_TOPIC, message = "Nombre de destino inválido")
            String topicDestino,
            Boolean activa,
            @Min(value = 0, message = "Debe estar entre 0 y 1000") @Max(value = 1000, message = "Debe estar entre 0 y 1000")
            Integer prioridad) {
    }

    public record SolicitudEstadoRegla(@NotNull(message = "Indica si la regla queda activa") Boolean activa) {
    }

    /** Enviar una alerta de prueba para ver por qué reglas y a qué destinos llega. */
    public record SolicitudPrueba(
            @NotBlank(message = "El nombre del producto es obligatorio")
            @Size(max = 200, message = "Máximo 200 caracteres")
            String producto,
            @NotBlank(message = "La tienda es obligatoria")
            @Pattern(regexp = "^[A-Z][A-Z_]{1,39}$", message = "Código de tienda en mayúsculas (ej. JUMBO)")
            String tienda,
            @NotEmpty(message = "Indica al menos una restricción")
            @Size(max = 4, message = "Máximo 4 restricciones")
            List<@NotBlank String> restricciones,
            @NotNull(message = "La baja de precio es obligatoria")
            @Min(value = 1, message = "Debe estar entre 1 y 99") @Max(value = 99, message = "Debe estar entre 1 y 99")
            Integer bajaPorcentaje) {
    }
}
