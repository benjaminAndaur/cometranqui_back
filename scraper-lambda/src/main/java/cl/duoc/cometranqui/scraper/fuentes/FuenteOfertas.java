package cl.duoc.cometranqui.scraper.fuentes;

import java.io.IOException;
import java.util.List;

import cl.duoc.cometranqui.scraper.modelo.OfertaDTO;
import cl.duoc.cometranqui.scraper.modelo.Restriccion;

/**
 * Una tienda de la que se obtienen ofertas. Para sumar una tienda nueva basta con implementar esta interfaz
 * y registrarla en {@link Fuentes}.
 */
public interface FuenteOfertas {

    /** Código de la fuente, usado en FUENTES_ACTIVAS (ej. JUMBO). */
    String nombre();

    /** Ofertas vigentes aptas para la restricción indicada. */
    List<OfertaDTO> obtener(Restriccion restriccion) throws IOException;
}
