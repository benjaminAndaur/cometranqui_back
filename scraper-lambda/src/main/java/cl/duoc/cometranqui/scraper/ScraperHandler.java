package cl.duoc.cometranqui.scraper;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import cl.duoc.cometranqui.scraper.fuentes.FuenteOfertas;
import cl.duoc.cometranqui.scraper.fuentes.Fuentes;
import cl.duoc.cometranqui.scraper.kafka.KafkaPublicador;
import cl.duoc.cometranqui.scraper.kafka.Publicador;
import cl.duoc.cometranqui.scraper.locales.FuenteLocales;
import cl.duoc.cometranqui.scraper.modelo.LocalDTO;
import cl.duoc.cometranqui.scraper.modelo.OfertaDTO;

/**
 * Entrada de la Lambda {@code cometranqui-scraper}. La invoca EventBridge Scheduler (06:00 America/Santiago)
 * o el backend con {@code POST /api/admin/scraper/run}.
 *
 * <p>Ejecución local: {@code java -jar target/cometranqui-scraper-1.0.0.jar [--dry-run]}. Con {@code --dry-run}
 * imprime las ofertas en JSON sin conectarse a Kafka.
 */
public class ScraperHandler implements RequestHandler<ScheduledEvent, String> {

    private static final Logger log = LoggerFactory.getLogger(ScraperHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public String handleRequest(ScheduledEvent evento, Context context) {
        log.info("Invocación {} (origen: {})", context == null ? "-" : context.getAwsRequestId(),
                evento == null ? "-" : evento.getSource());
        return ejecutar(ScraperConfig.desdeEntorno(), false);
    }

    static String ejecutar(ScraperConfig config, boolean dryRun) {
        log.info("Configuración: {}", config);
        List<FuenteOfertas> fuentes = Fuentes.activas(config);
        List<FuenteLocales> locales = Fuentes.localesActivos(config);
        try (Publicador publicador = dryRun ? new PublicadorConsola() : new KafkaPublicador(config)) {
            ScraperService.Resumen resumen = new ScraperService(fuentes, locales, publicador).ejecutar();
            return JSON.writeValueAsString(resumen);
        } catch (Exception e) {
            throw new IllegalStateException("Fallo la ejecución del scraper", e);
        }
    }

    public static void main(String[] args) {
        boolean dryRun = Arrays.asList(args).contains("--dry-run");
        System.out.println(ejecutar(ScraperConfig.desdeEntorno(), dryRun));
    }

    /** Publicador para --dry-run: imprime cada oferta y local como una línea JSON. */
    private static final class PublicadorConsola implements Publicador {
        private int total;

        private void publicar(Object valor) {
            try {
                System.out.println(JSON.writeValueAsString(valor));
                total++;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void publicar(LocalDTO local) {
            publicar((Object) local);
        }

        @Override
        public void publicar(OfertaDTO oferta) {
            publicar((Object) oferta);
        }

        @Override
        public int flush() {
            log.info("dry-run: {} mensajes impresos", total);
            return 0;
        }

        @Override
        public void close() {
        }
    }
}
