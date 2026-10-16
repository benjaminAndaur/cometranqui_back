package cl.duoc.cometranqui.mensajeria.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Bloque {@code app.mensajeria} de application.yml: catálogo de destinos (canales) y rutas (quién consume qué).
 * La coherencia entre ambos (cada ruta apunta a un destino existente) la verifica {@link CatalogoMensajeria}.
 */
@Validated
@ConfigurationProperties(prefix = "app.mensajeria")
public record MensajeriaProperties(
        @NotEmpty Map<String, @Valid Destino> destinos,
        @NotEmpty Map<String, @Valid Ruta> rutas,
        @NotNull @Valid Reintentos reintentos) {

    public enum Limpieza { DELETE, COMPACT }

    /**
     * Un destino es un topic fijo ({@code topic}) o una familia de topics con un prefijo común ({@code prefijo}),
     * cuyos miembros se crean desde la administración.
     */
    public record Destino(
            String topic,
            String prefijo,
            @Min(1) Integer particiones,
            Duration retencion,
            Limpieza limpieza,
            Map<String, String> config,
            List<String> iniciales,
            String descripcion) {

        /** Miembros de una familia que se crean al arrancar (los demás se crean desde la administración). */
        public List<String> inicialesOVacia() {
            return iniciales == null ? List.of() : iniciales;
        }

        public int particionesOPorDefecto() {
            return particiones == null ? 1 : particiones;
        }

        public Limpieza limpiezaOPorDefecto() {
            return limpieza == null ? Limpieza.DELETE : limpieza;
        }

        public Map<String, String> configOVacia() {
            return config == null ? Map.of() : config;
        }

        public boolean esFamilia() {
            return prefijo != null && !prefijo.isBlank();
        }
    }

    public record Ruta(
            @NotBlank String origen,
            @NotBlank String grupo,
            @Min(1) Integer concurrencia,
            String descripcion) {

        public int concurrenciaOPorDefecto() {
            return concurrencia == null ? 1 : concurrencia;
        }
    }

    public record Reintentos(
            @Min(0) int intentos,
            @NotNull Duration backoffInicial,
            @DecimalMin("1.0") double multiplicador) {
    }
}
