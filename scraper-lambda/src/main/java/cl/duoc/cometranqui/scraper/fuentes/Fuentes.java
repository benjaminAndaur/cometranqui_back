package cl.duoc.cometranqui.scraper.fuentes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cl.duoc.cometranqui.scraper.ScraperConfig;
import cl.duoc.cometranqui.scraper.http.ClienteHttp;
import cl.duoc.cometranqui.scraper.modelo.ClasificadorRestricciones;

/** Construye las fuentes indicadas en FUENTES_ACTIVAS. */
public final class Fuentes {

    private static final Logger log = LoggerFactory.getLogger(Fuentes.class);

    private Fuentes() {
    }

    public static List<FuenteOfertas> activas(ScraperConfig config) {
        ClienteHttp http = new ClienteHttp(config.pausaMs());
        ClasificadorRestricciones clasificador = new ClasificadorRestricciones();
        List<FuenteOfertas> fuentes = new ArrayList<>();
        for (String nombre : config.fuentesActivas()) {
            switch (nombre.trim().toUpperCase(Locale.ROOT)) {
                case "JUMBO" -> fuentes.add(new JumboFuente(http, clasificador, config.maxPaginas()));
                case "SANTA_ISABEL" -> fuentes.add(new SantaIsabelFuente(http, clasificador, config.maxPaginas()));
                case "MOCK" -> fuentes.add(new FuenteMock(config.mockBajaPrecio()));
                case "" -> { }
                default -> log.warn("Fuente desconocida en FUENTES_ACTIVAS: {}", nombre);
            }
        }
        return fuentes;
    }
}
