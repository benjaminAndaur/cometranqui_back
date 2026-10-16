package cl.duoc.cometranqui.scraper.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Intérprete mínimo de robots.txt (RFC 9309): toma el grupo de nuestro User-Agent o, si no existe, el de "*".
 * Gana la regla más larga que calce; en empate gana Allow. Soporta comodines {@code *} y {@code $}.
 */
public final class RobotsTxt {

    private record Regla(boolean permitir, String patron, Pattern regex) {
    }

    private final List<Regla> reglas;

    private RobotsTxt(List<Regla> reglas) {
        this.reglas = reglas;
    }

    public static RobotsTxt permitirTodo() {
        return new RobotsTxt(List.of());
    }

    public static RobotsTxt parsear(String contenido, String agente) {
        String agenteBuscado = agente.toLowerCase(Locale.ROOT);
        List<Regla> especificas = new ArrayList<>();
        List<Regla> genericas = new ArrayList<>();
        List<String> agentesGrupo = new ArrayList<>();
        boolean leyendoAgentes = false;
        for (String linea : contenido.split("\\R")) {
            String limpia = linea.replaceFirst("#.*", "").trim();
            int dosPuntos = limpia.indexOf(':');
            if (dosPuntos < 0) {
                continue;
            }
            String clave = limpia.substring(0, dosPuntos).trim().toLowerCase(Locale.ROOT);
            String valor = limpia.substring(dosPuntos + 1).trim();
            if (clave.equals("user-agent")) {
                if (!leyendoAgentes) {
                    agentesGrupo.clear();
                }
                agentesGrupo.add(valor.toLowerCase(Locale.ROOT));
                leyendoAgentes = true;
            } else if (clave.equals("allow") || clave.equals("disallow")) {
                leyendoAgentes = false;
                if (valor.isEmpty()) {
                    continue;
                }
                Regla regla = new Regla(clave.equals("allow"), valor, aRegex(valor));
                for (String a : agentesGrupo) {
                    if (a.equals("*")) {
                        genericas.add(regla);
                    } else if (agenteBuscado.startsWith(a)) {
                        especificas.add(regla);
                    }
                }
            }
        }
        return new RobotsTxt(especificas.isEmpty() ? genericas : especificas);
    }

    /** @param rutaYQuery ruta + query, ej. {@code /busqueda?ft=sin%20gluten&page=2} */
    public boolean permite(String rutaYQuery) {
        Regla mejor = null;
        for (Regla r : reglas) {
            if (r.regex().matcher(rutaYQuery).lookingAt()) {
                if (mejor == null || r.patron().length() > mejor.patron().length()
                        || (r.patron().length() == mejor.patron().length() && r.permitir())) {
                    mejor = r;
                }
            }
        }
        return mejor == null || mejor.permitir();
    }

    private static Pattern aRegex(String patron) {
        String p = patron.startsWith("/") || patron.startsWith("*") ? patron : "/" + patron;
        boolean anclado = p.endsWith("$");
        if (anclado) {
            p = p.substring(0, p.length() - 1);
        }
        String regex = Arrays.stream(p.split("\\*", -1))
                .map(Pattern::quote)
                .collect(Collectors.joining(".*"));
        return Pattern.compile(anclado ? regex + "$" : regex);
    }
}
