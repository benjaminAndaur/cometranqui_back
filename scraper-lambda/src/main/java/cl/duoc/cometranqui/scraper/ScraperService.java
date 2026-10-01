package cl.duoc.cometranqui.scraper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cl.duoc.cometranqui.scraper.fuentes.FuenteOfertas;
import cl.duoc.cometranqui.scraper.kafka.PublicadorOfertas;
import cl.duoc.cometranqui.scraper.modelo.OfertaDTO;
import cl.duoc.cometranqui.scraper.modelo.Restriccion;

/**
 * Recorre fuentes × restricciones, une los productos repetidos (un mismo SKU puede salir en "sin lactosa" y en
 * "vegano") y publica un mensaje por producto. Si una fuente falla se registra el error y se sigue con las demás.
 */
public class ScraperService {

    private static final Logger log = LoggerFactory.getLogger(ScraperService.class);

    public record Resumen(Map<String, Integer> ofertasPorFuente, List<String> errores, int publicadas, int fallidas) {
    }

    private final List<FuenteOfertas> fuentes;
    private final PublicadorOfertas publicador;

    public ScraperService(List<FuenteOfertas> fuentes, PublicadorOfertas publicador) {
        this.fuentes = fuentes;
        this.publicador = publicador;
    }

    public Resumen ejecutar() {
        Map<String, OfertaDTO> unicas = new LinkedHashMap<>();
        Map<String, Integer> porFuente = new LinkedHashMap<>();
        List<String> errores = new ArrayList<>();

        for (FuenteOfertas fuente : fuentes) {
            int antes = unicas.size();
            for (Restriccion restriccion : Restriccion.values()) {
                try {
                    for (OfertaDTO oferta : fuente.obtener(restriccion)) {
                        unicas.merge(oferta.clave(), oferta, (a, b) -> a.conRestricciones(b.restricciones()));
                    }
                } catch (Exception e) {
                    String error = fuente.nombre() + "/" + restriccion + ": " + e.getMessage();
                    log.error("Fuente con error, se continúa con la siguiente: {}", error, e);
                    errores.add(error);
                }
            }
            porFuente.put(fuente.nombre(), unicas.size() - antes);
        }

        unicas.values().forEach(publicador::publicar);
        int fallidas = publicador.flush();
        Resumen resumen = new Resumen(porFuente, errores, unicas.size() - fallidas, fallidas);
        log.info("Scraping terminado: {}", resumen);
        return resumen;
    }
}
