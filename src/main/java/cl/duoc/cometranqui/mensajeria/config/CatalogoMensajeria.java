package cl.duoc.cometranqui.mensajeria.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import cl.duoc.cometranqui.mensajeria.config.MensajeriaProperties.Destino;
import cl.duoc.cometranqui.mensajeria.config.MensajeriaProperties.Ruta;

/**
 * Acceso único al catálogo de mensajería. Los listeners lo usan por SpEL ({@code #{@catalogo.topic('...')}}), de modo
 * que ningún nombre de topic ni de grupo queda escrito a mano en el código.
 *
 * <p>Al construirse valida la coherencia del catálogo: si una ruta apunta a un destino que no existe, o un destino
 * no declara ni {@code topic} ni {@code prefijo}, la aplicación no arranca. Así una inconsistencia se detecta al
 * desplegar y no en producción.
 */
@Component("catalogo")
@EnableConfigurationProperties(MensajeriaProperties.class)
public class CatalogoMensajeria {

    private final MensajeriaProperties propiedades;

    public CatalogoMensajeria(MensajeriaProperties propiedades) {
        this.propiedades = propiedades;
        validar();
    }

    private void validar() {
        List<String> errores = new ArrayList<>();
        propiedades.destinos().forEach((clave, d) -> {
            boolean tieneTopic = d.topic() != null && !d.topic().isBlank();
            if (tieneTopic == d.esFamilia()) {
                errores.add("El destino '" + clave + "' debe declarar 'topic' o 'prefijo' (solo uno)");
            }
            d.inicialesOVacia().stream()
                    .filter(t -> !d.esFamilia() || !t.startsWith(d.prefijo()))
                    .forEach(t -> errores.add("El topic inicial '" + t + "' no pertenece a la familia '" + clave + "'"));
        });
        propiedades.rutas().forEach((clave, r) -> {
            if (!propiedades.destinos().containsKey(r.origen())) {
                errores.add("La ruta '" + clave + "' apunta al destino inexistente '" + r.origen() + "'");
            }
        });
        if (!errores.isEmpty()) {
            throw new IllegalStateException("Catálogo de mensajería inválido: " + String.join("; ", errores));
        }
    }

    // ── Consultas usadas por los @KafkaListener (SpEL) ────────────────────────

    /** Nombre del topic de un destino fijo. */
    public String topic(String destino) {
        Destino d = destino(destino);
        if (d.esFamilia()) {
            throw new IllegalArgumentException("El destino '" + destino + "' es una familia de topics; usa patron()");
        }
        return d.topic();
    }

    /** Expresión regular que abarca todos los topics de una familia (ej. {@code notificaciones\..*}). */
    public String patron(String destino) {
        Destino d = destino(destino);
        if (!d.esFamilia()) {
            return Pattern.quote(d.topic());
        }
        return Pattern.quote(d.prefijo()) + ".+";
    }

    public String grupo(String ruta) {
        return ruta(ruta).grupo();
    }

    public String concurrencia(String ruta) {
        return String.valueOf(ruta(ruta).concurrenciaOPorDefecto());
    }

    public String topicDeRuta(String ruta) {
        return topic(ruta(ruta).origen());
    }

    // ── Consultas para administración y configuración ────────────────────────

    public Map<String, Destino> destinos() {
        return new LinkedHashMap<>(propiedades.destinos());
    }

    public Map<String, Ruta> rutas() {
        return new LinkedHashMap<>(propiedades.rutas());
    }

    public MensajeriaProperties.Reintentos reintentos() {
        return propiedades.reintentos();
    }

    /** Topics fijos del catálogo: la aplicación depende de ellos, por eso no se pueden eliminar desde la administración. */
    public Set<String> topicsProtegidos() {
        return propiedades.destinos().values().stream()
                .filter(d -> !d.esFamilia())
                .map(Destino::topic)
                .collect(Collectors.toSet());
    }

    /** Prefijos de familias (ej. {@code notificaciones.}) bajo los que se pueden crear destinos nuevos. */
    public List<String> prefijosDeFamilias() {
        return propiedades.destinos().values().stream()
                .filter(Destino::esFamilia)
                .map(Destino::prefijo)
                .toList();
    }

    /** Clave del catálogo a la que pertenece un topic (destino fijo o familia), si alguna. */
    public String destinoDeTopic(String topic) {
        return propiedades.destinos().entrySet().stream()
                .filter(e -> e.getValue().esFamilia() ? topic.startsWith(e.getValue().prefijo()) : topic.equals(e.getValue().topic()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    public Destino destino(String clave) {
        Destino d = propiedades.destinos().get(clave);
        if (d == null) {
            throw new IllegalArgumentException("Destino no declarado en el catálogo: " + clave);
        }
        return d;
    }

    public Ruta ruta(String clave) {
        Ruta r = propiedades.rutas().get(clave);
        if (r == null) {
            throw new IllegalArgumentException("Ruta no declarada en el catálogo: " + clave);
        }
        return r;
    }
}
