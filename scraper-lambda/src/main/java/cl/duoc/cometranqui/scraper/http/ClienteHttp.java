package cl.duoc.cometranqui.scraper.http;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cliente HTTP "educado": User-Agent identificable, timeout de 10 s, pausa entre requests y respeto de robots.txt.
 */
public class ClienteHttp {

    public static final String USER_AGENT = "ComeTranquiBot/1.0 (proyecto academico Duoc UC)";
    private static final Logger log = LoggerFactory.getLogger(ClienteHttp.class);
    private static final int TIMEOUT_MS = 10_000;

    private final long pausaMs;
    private final Map<String, RobotsTxt> robotsPorHost = new ConcurrentHashMap<>();
    private long ultimoRequest;

    public ClienteHttp(long pausaMs) {
        this.pausaMs = pausaMs;
    }

    /**
     * GET de una página. Devuelve vacío si el sitio responde 404 (en VTEX significa "búsqueda sin resultados").
     *
     * @throws BloqueadoPorRobotsException si robots.txt no permite la URL
     * @throws IOException ante errores de red o códigos distintos de 2xx/404 (ej. 403 por bloqueo anti-bot)
     */
    public Optional<String> get(String url) throws IOException {
        URI uri = URI.create(url);
        String rutaYQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        if (!robots(uri).permite(rutaYQuery)) {
            throw new BloqueadoPorRobotsException(url);
        }
        Connection.Response respuesta = ejecutar(url);
        if (respuesta.statusCode() == 404) {
            return Optional.empty();
        }
        if (respuesta.statusCode() >= 300) {
            throw new IOException("HTTP " + respuesta.statusCode() + " en " + url);
        }
        return Optional.of(respuesta.body());
    }

    private RobotsTxt robots(URI uri) {
        return robotsPorHost.computeIfAbsent(uri.getScheme() + "://" + uri.getHost(), base -> {
            try {
                Connection.Response r = ejecutar(base + "/robots.txt");
                return r.statusCode() == 200 ? RobotsTxt.parsear(r.body(), USER_AGENT) : RobotsTxt.permitirTodo();
            } catch (IOException e) {
                log.warn("No se pudo leer robots.txt de {}: {}", base, e.getMessage());
                return RobotsTxt.permitirTodo();
            }
        });
    }

    private synchronized Connection.Response ejecutar(String url) throws IOException {
        pausar();
        log.info("GET {}", url);
        try {
            return Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .maxBodySize(0)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .execute();
        } finally {
            ultimoRequest = System.currentTimeMillis();
        }
    }

    private void pausar() {
        long espera = ultimoRequest + pausaMs - System.currentTimeMillis();
        if (espera > 0) {
            try {
                Thread.sleep(espera);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static class BloqueadoPorRobotsException extends IOException {
        public BloqueadoPorRobotsException(String url) {
            super("robots.txt no permite " + url);
        }
    }
}
