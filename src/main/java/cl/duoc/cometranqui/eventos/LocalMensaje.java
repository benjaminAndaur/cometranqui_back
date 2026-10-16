package cl.duoc.cometranqui.eventos;

import java.time.Instant;
import java.util.Map;

import cl.duoc.cometranqui.ofertas.Restriccion;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Mensaje del topic compactado {@code locales.scrapeados}. Contrato compartido con
 * {@code scraper-lambda/.../modelo/LocalDTO}.
 */
public record LocalMensaje(
        @NotBlank @Size(max = 80) String id,
        @NotBlank @Size(max = 20) String fuente,
        @NotBlank @Size(max = 200) String nombre,
        @Size(max = 40) String tipo,
        @NotEmpty Map<@NotNull Restriccion, @NotNull @Min(1) @Max(3) Integer> niveles,
        @Size(max = 200) String direccion,
        @Size(max = 80) String comuna,
        @DecimalMin("-90") @DecimalMax("90") double latitud,
        @DecimalMin("-180") @DecimalMax("180") double longitud,
        @Size(max = 300) String sitioWeb,
        @Size(max = 60) String telefono,
        @Size(max = 300) String horario,
        @Size(max = 300) String urlFuente,
        @NotNull Instant scrapeadoEn) {
}
