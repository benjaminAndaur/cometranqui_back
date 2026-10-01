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
 * Cliente HTTP "educado": User-Agent identificable, timeout de 10 s, pausa mínima entre requests al mismo host
 * y respeto de robots.txt. Es seguro usarlo desde varios hilos (una fuente por hilo).
 */
public class ClienteHttp {

    public static final String USER_AGENT = "ComeTranquiBot/1.0 (proyecto academico Duoc UC)";
    private static final Logger log = LoggerFactory.getLogger(ClienteHttp.class);
    private static final int TIMEOUT_MS = 10_000;

    private final long pausaMs;
    private final Map<String, RobotsTxt> robotsPorHost = new ConcurrentHashMap<>();
    private final Map<String, Object> candadoPorHost = new ConcurrentHashMap<>();
    private final Map<String, Long> ultimoRequestPorHost = new ConcurrentHashMap<>();

    public ClienteHttp(long pausaMs) {
        this.pausaMs = pausaMs;
    }

    /**
     * GET de una página o JSON. Devuelve vacío si el sitio responde 404 (en VTEX significa "búsqueda sin resultados").
     *
     * @throws BloqueadoPorRobotsException si robots.txt no permite la URL
     * @throws IOException ante errores de red o códigos distintos de 2xx/404 (ej. 403 por bloqueo anti-bot)
     */
    public Optional<String> get(String url) throws IOException {
        return ejecutarPermitido(url, Connection.Method.GET, null, Map.of());
    }

    /** POST con cuerpo JSON (APIs de búsqueda que usa el propio sitio, ej. Unimarc). */
    public Optional<String> postJson(String url, String json, Map<String, String> headers) throws IOException {
        return ejecutarPermitido(url, Connection.Method.POST, json, headers);
    }

    private Optional<String> ejecutarPermitido(String url, Connection.Method metodo, String cuerpo,
                                               Map<String, String> headers) throws IOException {
        URI uri = URI.create(url);
        String rutaYQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        if (!robots(uri).permite(rutaYQuery)) {
            throw new BloqueadoPorRobotsException(url);
        }
        Connection.Response respuesta = ejecutar(url, metodo, cuerpo, headers);
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
                Connection.Response r = ejecutar(base + "/robots.txt", Connection.Method.GET, null, Map.of());
                return r.statusCode() == 200 ? RobotsTxt.parsear(r.body(), USER_AGENT) : RobotsTxt.permitirTodo();
            } catch (IOException e) {
                log.warn("No se pudo leer robots.txt de {}: {}", base, e.getMessage());
                return RobotsTxt.permitirTodo();
            }
        });
    }

    private Connection.Response ejecutar(String url, Connection.Method metodo, String cuerpo,
                                         Map<String, String> headers) throws IOException {
        String host = URI.create(url).getHost();
        synchronized (candadoPorHost.computeIfAbsent(host, h -> new Object())) {
            pausar(host);
            log.info("{} {}", metodo, url);
            try {
                Connection conexion = Jsoup.connect(url)
                        .method(metodo)
                        .userAgent(USER_AGENT)
                        .timeout(TIMEOUT_MS)
                        .maxBodySize(0)
                        .ignoreContentType(true)
                        .ignoreHttpErrors(true)
                        .headers(headers);
                if (cuerpo != null) {
                    conexion.header("Content-Type", "application/json").requestBody(cuerpo);
                }
                return conexion.execute();
            } finally {
                ultimoRequestPorHost.put(host, System.currentTimeMillis());
            }
        }
    }

    private void pausar(String host) {
        long espera = ultimoRequestPorHost.getOrDefault(host, 0L) + pausaMs - System.currentTimeMillis();
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
