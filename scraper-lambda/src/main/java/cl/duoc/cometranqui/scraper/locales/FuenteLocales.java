package cl.duoc.cometranqui.scraper.locales;

import java.io.IOException;
import java.util.List;

import cl.duoc.cometranqui.scraper.modelo.LocalDTO;

/** Fuente de restaurantes, cafés y tiendas con opciones para restricciones alimentarias. */
public interface FuenteLocales {

    String nombre();

    List<LocalDTO> obtener() throws IOException;
}
