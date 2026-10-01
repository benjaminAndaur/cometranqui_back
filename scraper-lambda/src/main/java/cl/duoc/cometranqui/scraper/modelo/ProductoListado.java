package cl.duoc.cometranqui.scraper.modelo;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Producto tal como aparece en un listado de búsqueda, antes de decidir si es oferta y para qué restricciones.
 *
 * @param categoria ruta completa de categoría (ej. "Lácteos / Leches / Leche sin lactosa"), se usa como evidencia
 */
public record ProductoListado(
        String sku,
        String nombre,
        String marca,
        String categoria,
        int precio,
        int precioLista,
        boolean disponible,
        String url,
        String urlImagen) {

    /**
     * Convierte el producto en oferta si: está disponible, el precio de lista es mayor al actual y
     * tiene al menos una restricción confirmada por el clasificador.
     */
    public Optional<OfertaDTO> aOferta(String supermercado, ClasificadorRestricciones clasificador, Instant ahora) {
        if (!disponible || precio <= 0 || precioLista <= precio) {
            return Optional.empty();
        }
        Set<Restriccion> restricciones = clasificador.clasificar(nombre, categoria);
        if (restricciones.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new OfertaDTO(supermercado, sku, nombre, marca, departamento(categoria), restricciones,
                precioLista, precio, OfertaDTO.calcularDescuento(precioLista, precio), url, urlImagen, null, ahora));
    }

    /** Primer nivel de la categoría ("Lácteos / Leches" -> "Lácteos"), saltando agrupadores como "Libres De". */
    static String departamento(String categoria) {
        if (categoria == null) {
            return null;
        }
        for (String segmento : categoria.split("[/>]")) {
            String limpio = segmento.trim();
            if (!limpio.isEmpty() && !limpio.equalsIgnoreCase("libres de")) {
                return limpio;
            }
        }
        return null;
    }
}
