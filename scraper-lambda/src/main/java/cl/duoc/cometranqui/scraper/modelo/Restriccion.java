package cl.duoc.cometranqui.scraper.modelo;

import java.util.List;

/**
 * Restricciones de la web Come Tranqui (mismos códigos que el backend: SIN_GLUTEN, SIN_LACTOSA, APLV, VEGANO).
 * Cada una define los términos con que se busca en los supermercados; la confirmación final
 * la hace {@link ClasificadorRestricciones} sobre el nombre/categoría del producto.
 */
public enum Restriccion {
    SIN_GLUTEN(List.of("sin gluten", "libre de gluten")),
    SIN_LACTOSA(List.of("sin lactosa", "deslactosado")),
    APLV(List.of("sin leche", "bebida vegetal")),
    VEGANO(List.of("vegano", "plant based"));

    private final List<String> terminosBusqueda;

    Restriccion(List<String> terminosBusqueda) {
        this.terminosBusqueda = terminosBusqueda;
    }

    public List<String> terminosBusqueda() {
        return terminosBusqueda;
    }
}
