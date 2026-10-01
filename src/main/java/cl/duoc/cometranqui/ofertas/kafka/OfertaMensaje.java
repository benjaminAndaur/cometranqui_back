package cl.duoc.cometranqui.ofertas.kafka;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;

import cl.duoc.cometranqui.ofertas.Restriccion;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Mensaje del topic {@code ofertas.scrapeadas}. Contrato JSON compartido con
 * {@code scraper-lambda/.../modelo/OfertaDTO}. Un código de restricción desconocido hace fallar la
 * deserialización y el mensaje termina en {@code ofertas.dlt}.
 */
public record OfertaMensaje(
        @NotBlank @Size(max = 40) String supermercado,
        @Size(max = 80) String supermercadoNombre,
        @NotBlank @Size(max = 64) String sku,
        @NotBlank @Size(max = 300) String nombre,
        @Size(max = 120) String marca,
        @Size(max = 120) String categoria,
        @NotEmpty Set<@NotNull Restriccion> restricciones,
        @NotNull @Positive Integer precioNormal,
        @NotNull @Positive Integer precioOferta,
        @Min(0) @Max(100) Integer porcentajeDescuento,
        @NotBlank @Size(max = 500) @Pattern(regexp = "https?://.+") String urlProducto,
        @Size(max = 500) String urlImagen,
        LocalDate vigenteHasta,
        @NotNull Instant scrapeadoEn) {

    @JsonIgnore
    @AssertTrue(message = "precioOferta debe ser menor que precioNormal")
    public boolean isPrecioOfertaMenorQueNormal() {
        return precioNormal == null || precioOferta == null || precioOferta < precioNormal;
    }

    /** Se recalcula siempre en el consumer para no confiar en el productor. */
    public int descuentoCalculado() {
        return (int) Math.round((precioNormal - precioOferta) * 100.0 / precioNormal);
    }
}
