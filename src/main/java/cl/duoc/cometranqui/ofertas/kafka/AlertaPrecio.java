package cl.duoc.cometranqui.ofertas.kafka;

import java.time.Instant;

/** Mensaje del topic {@code ofertas.alertas}: el precio de oferta bajó al menos el umbral configurado. */
public record AlertaPrecio(
        String supermercado,
        String sku,
        String nombre,
        int precioAnterior,
        int precioNuevo,
        int bajaPorcentaje,
        String urlProducto,
        Instant detectadaEn) {
}
