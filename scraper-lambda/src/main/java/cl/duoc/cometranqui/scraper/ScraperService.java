package cl.duoc.cometranqui.scraper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cl.duoc.cometranqui.scraper.fuentes.FuenteOfertas;
import cl.duoc.cometranqui.scraper.kafka.Publicador;
import cl.duoc.cometranqui.scraper.locales.FuenteLocales;
import cl.duoc.cometranqui.scraper.modelo.LocalDTO;
import cl.duoc.cometranqui.scraper.modelo.OfertaDTO;
import cl.duoc.cometranqui.scraper.modelo.Restriccion;

/**
 * Recorre fuentes de ofertas × restricciones y fuentes de locales, une los productos repetidos (un mismo SKU puede
 * salir en "sin lactosa" y en "vegano") y publica un mensaje por producto y por local. Si una fuente falla se
 * registra el error y se sigue con las demás.
 *
 * <p>Las fuentes corren en paralelo (cada una contra un sitio distinto); la cortesía con cada sitio la asegura
 * la pausa por host de {@code ClienteHttp}. Así el total dura lo que la fuente más lenta, no la suma.
 */
public class ScraperService {

    private static final Logger log = LoggerFactory.getLogger(ScraperService.class);
    private static final int MAX_HILOS = 8;

    public record Resumen(Map<String, Integer> ofertasPorFuente, Map<String, Integer> localesPorFuente,
                          List<String> errores, int publicadas, int fallidas) {
    }

    private record Resultado<T>(List<T> datos, List<String> errores) {
    }

    private final List<FuenteOfertas> fuentes;
    private final List<FuenteLocales> fuentesLocales;
    private final Publicador publicador;

    public ScraperService(List<FuenteOfertas> fuentes, Publicador publicador) {
        this(fuentes, List.of(), publicador);
    }

    public ScraperService(List<FuenteOfertas> fuentes, List<FuenteLocales> fuentesLocales, Publicador publicador) {
        this.fuentes = fuentes;
        this.fuentesLocales = fuentesLocales;
        this.publicador = publicador;
    }

    public Resumen ejecutar() {
        int total = fuentes.size() + fuentesLocales.size();
        ExecutorService hilos = Executors.newFixedThreadPool(Math.max(1, Math.min(MAX_HILOS, total)));
        try {
            Map<String, Future<Resultado<OfertaDTO>>> futurosOfertas = new LinkedHashMap<>();
            fuentes.forEach(f -> futurosOfertas.put(f.nombre(), hilos.submit(() -> recorrer(f))));
            Map<String, Future<Resultado<LocalDTO>>> futurosLocales = new LinkedHashMap<>();
            fuentesLocales.forEach(f -> futurosLocales.put(f.nombre(), hilos.submit(capturando(f.nombre(), f::obtener))));

            List<String> errores = new ArrayList<>();
            Map<String, OfertaDTO> unicas = new LinkedHashMap<>();
            Map<String, Integer> ofertasPorFuente = new LinkedHashMap<>();
            for (var entrada : futurosOfertas.entrySet()) {
                Resultado<OfertaDTO> r = esperar(entrada.getKey(), entrada.getValue());
                int antes = unicas.size();
                r.datos().forEach(o -> unicas.merge(o.clave(), o, (a, b) -> a.conRestricciones(b.restricciones())));
                ofertasPorFuente.put(entrada.getKey(), unicas.size() - antes);
                errores.addAll(r.errores());
            }
            Map<String, Integer> localesPorFuente = new LinkedHashMap<>();
            List<LocalDTO> locales = new ArrayList<>();
            for (var entrada : futurosLocales.entrySet()) {
                Resultado<LocalDTO> r = esperar(entrada.getKey(), entrada.getValue());
                locales.addAll(r.datos());
                localesPorFuente.put(entrada.getKey(), r.datos().size());
                errores.addAll(r.errores());
            }

            unicas.values().forEach(publicador::publicar);
            locales.forEach(publicador::publicar);
            int fallidas = publicador.flush();
            Resumen resumen = new Resumen(ofertasPorFuente, localesPorFuente, errores,
                    unicas.size() + locales.size() - fallidas, fallidas);
            log.info("Scraping terminado: {}", resumen);
            return resumen;
        } finally {
            hilos.shutdownNow();
        }
    }

    private Resultado<OfertaDTO> recorrer(FuenteOfertas fuente) {
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
        return new Resultado<>(ofertas, errores);
    }

    private interface Obtener<T> {
        List<T> obtener() throws Exception;
    }

    private static <T> Callable<Resultado<T>> capturando(String fuente, Obtener<T> obtener) {
        return () -> {
            try {
                return new Resultado<>(obtener.obtener(), List.of());
            } catch (Exception e) {
                log.error("Fuente de locales {} con error: {}", fuente, e.getMessage(), e);
                return new Resultado<>(List.of(), List.of(fuente + ": " + e.getMessage()));
            }
        };
    }

    private static <T> Resultado<T> esperar(String fuente, Future<Resultado<T>> futuro) {
        try {
            return futuro.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resultado<>(List.of(), List.of(fuente + ": interrumpida"));
        } catch (ExecutionException e) {
            return new Resultado<>(List.of(), List.of(fuente + ": " + e.getCause().getMessage()));
        }
    }
}
