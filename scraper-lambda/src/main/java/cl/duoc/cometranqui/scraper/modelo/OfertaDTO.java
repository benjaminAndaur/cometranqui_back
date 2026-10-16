package cl.duoc.cometranqui.scraper.modelo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/**
 * Mensaje publicado en el topic {@code ofertas.scrapeadas} (contrato JSON compartido con el consumer
 * {@code cl.duoc.cometranqui.ofertas.kafka.OfertaMensaje} del backend).
 *
 * @param supermercado código de la tienda (JUMBO, SANTA_ISABEL, DAORO...). También es la key del mensaje.
 * @param supermercadoNombre nombre para mostrar en la web ("Santa Isabel", "Da'Oro")
 */
public record OfertaDTO(
        String supermercado,
        String supermercadoNombre,
        String sku,
        String nombre,
        String marca,
        String categoria,
        Set<Restriccion> restricciones,
        int precioNormal,
        int precioOferta,
        int porcentajeDescuento,
        String urlProducto,
        String urlImagen,
        LocalDate vigenteHasta,
        Instant scrapeadoEn) {

    public static int calcularDescuento(int precioNormal, int precioOferta) {
        if (precioNormal <= 0 || precioOferta >= precioNormal) {
            return 0;
        }
        return (int) Math.round((precioNormal - precioOferta) * 100.0 / precioNormal);
    }

    /** Une restricciones cuando el mismo producto aparece en varias búsquedas. */
    public OfertaDTO conRestricciones(Set<Restriccion> otras) {
        EnumSet<Restriccion> union = EnumSet.copyOf(restricciones);
        union.addAll(otras);
        return new OfertaDTO(supermercado, supermercadoNombre, sku, nombre, marca, categoria, union, precioNormal,
                precioOferta, porcentajeDescuento, urlProducto, urlImagen, vigenteHasta, scrapeadoEn);
    }

    public String clave() {
        return supermercado + "|" + sku;
    }
}
