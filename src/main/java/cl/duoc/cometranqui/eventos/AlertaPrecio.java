package cl.duoc.cometranqui.eventos;

import java.time.Instant;
import java.util.Set;

import cl.duoc.cometranqui.ofertas.Restriccion;

/**
 * Evento de dominio: el precio de oferta de un producto bajó al menos el umbral configurado.
 * Lo consume el enrutador, que decide con las reglas de distribución a qué destinos de notificación va.
 *
 * @param eventoId identificador estable (tienda:sku:precio nuevo:fecha de scraping) para que un reproceso no
 *                 genere notificaciones duplicadas
 */
public record AlertaPrecio(
        String eventoId,
        String supermercado,
        String supermercadoNombre,
        String sku,
        String nombre,
        Set<Restriccion> restricciones,
        int precioAnterior,
        int precioNuevo,
        int bajaPorcentaje,
        String urlProducto,
        Instant detectadaEn) {

    /** Clave del mensaje: el mismo producto siempre cae en la misma partición. */
    public String clave() {
        return supermercado + ":" + sku;
    }
}
