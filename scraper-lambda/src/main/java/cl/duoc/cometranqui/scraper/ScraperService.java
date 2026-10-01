package cl.duoc.cometranqui.scraper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cl.duoc.cometranqui.scraper.fuentes.FuenteOfertas;
import cl.duoc.cometranqui.scraper.kafka.PublicadorOfertas;
import cl.duoc.cometranqui.scraper.modelo.OfertaDTO;
import cl.duoc.cometranqui.scraper.modelo.Restriccion;

/**
 * Recorre fuentes × restricciones, une los productos repetidos (un mismo SKU puede salir en "sin lactosa" y en
 * "vegano") y publica un mensaje por producto. Si una fuente falla se registra el error y se sigue con las demás.
 *
 * <p>Las fuentes corren en paralelo (cada una contra un sitio distinto); la cortesía con cada sitio la asegura
 * la pausa por host de {@code ClienteHttp}. Así el total dura lo que la fuente más lenta, no la suma.
 */
public class ScraperService {

    private static final Logger log = LoggerFactory.getLogger(ScraperService.class);
    private static final int MAX_HILOS = 6;

    public record Resumen(Map<String, Integer> ofertasPorFuente, List<String> errores, int publicadas, int fallidas) {
    }

    private record ResultadoFuente(List<OfertaDTO> ofertas, List<String> errores) {
    }

    private final List<FuenteOfertas> fuentes;
    private final PublicadorOfertas publicador;

    public ScraperService(List<FuenteOfertas> fuentes, PublicadorOfertas publicador) {
        this.fuentes = fuentes;
        this.publicador = publicador;
    }

    public Resumen ejecutar() {
        Map<String, Future<ResultadoFuente>> futuros = new LinkedHashMap<>();
        ExecutorService hilos = Executors.newFixedThreadPool(Math.max(1, Math.min(MAX_HILOS, fuentes.size())));
        try {
            for (FuenteOfertas fuente : fuentes) {
                futuros.put(fuente.nombre(), hilos.submit(() -> recorrer(fuente)));
            }
            Map<String, OfertaDTO> unicas = new LinkedHashMap<>();
            Map<String, Integer> porFuente = new LinkedHashMap<>();
            List<String> errores = new ArrayList<>();
            for (var entrada : futuros.entrySet()) {
                ResultadoFuente r = esperar(entrada.getKey(), entrada.getValue());
                int antes = unicas.size();
                for (OfertaDTO oferta : r.ofertas()) {
                    unicas.merge(oferta.clave(), oferta, (a, b) -> a.conRestricciones(b.restricciones()));
                }
                porFuente.put(entrada.getKey(), unicas.size() - antes);
                errores.addAll(r.errores());
            }

            unicas.values().forEach(publicador::publicar);
            int fallidas = publicador.flush();
            Resumen resumen = new Resumen(porFuente, errores, unicas.size() - fallidas, fallidas);
            log.info("Scraping terminado: {}", resumen);
            return resumen;
        } finally {
            hilos.shutdownNow();
        }
    }

    private ResultadoFuente recorrer(FuenteOfertas fuente) {
        List<OfertaDTO> ofertas = new ArrayList<>();
        List<String> errores = new ArrayList<>();
        for (Restriccion restriccion : Restriccion.values()) {
            try {
                ofertas.addAll(fuente.obtener(restriccion));
            } catch (Exception e) {
                String error = fuente.nombre() + "/" + restriccion + ": " + e.getMessage();
                log.error("Fuente con error, se continúa con la siguiente: {}", error, e);
                errores.add(error);
            }
        }
        return new ResultadoFuente(ofertas, errores);
    }

    private static ResultadoFuente esperar(String fuente, Future<ResultadoFuente> futuro) {
        try {
            return futuro.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ResultadoFuente(List.of(), List.of(fuente + ": interrumpida"));
        } catch (ExecutionException e) {
            return new ResultadoFuente(List.of(), List.of(fuente + ": " + e.getCause().getMessage()));
        }
    }
}
