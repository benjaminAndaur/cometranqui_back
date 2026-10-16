package cl.duoc.cometranqui.notificaciones;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.duoc.cometranqui.eventos.AlertaPrecio;
import cl.duoc.cometranqui.eventos.NotificacionEvento;
import cl.duoc.cometranqui.ofertas.Restriccion;

/**
 * Lógica de notificaciones: a quién avisar de una alerta enrutada y qué decirle. No conoce Kafka.
 * Idempotente: procesar el mismo evento dos veces no duplica notificaciones (clave única usuario + evento).
 */
@Service
public class NotificacionService {

    private static final Logger log = LoggerFactory.getLogger(NotificacionService.class);
    private static final NumberFormat CLP = NumberFormat.getCurrencyInstance(new Locale("es", "CL"));

    public record Resumen(List<Notificacion> notificaciones, long noLeidas) {
    }

    private final NotificacionRepository notificaciones;
    private final SuscripcionRepository suscripciones;
    private final EntregaNotificacionRepository entregas;

    public NotificacionService(NotificacionRepository notificaciones, SuscripcionRepository suscripciones,
                               EntregaNotificacionRepository entregas) {
        this.notificaciones = notificaciones;
        this.suscripciones = suscripciones;
        this.entregas = entregas;
    }

    /**
     * Crea una notificación por cada usuario suscrito a la restricción de la regla (o, si la regla no filtraba por
     * restricción, a cualquiera de las restricciones del producto). Devuelve cuántos usuarios fueron notificados.
     */
    @Transactional
    public int registrar(NotificacionEvento evento, String topic) {
        AlertaPrecio a = evento.alerta();
        Set<String> restricciones = evento.restriccion() != null
                ? Set.of(evento.restriccion())
                : a.restricciones().stream().map(Enum::name).collect(Collectors.toSet());
        List<String> usuarios = suscripciones.usuariosSuscritosA(restricciones);
        String titulo = (evento.prueba() ? "[Prueba] " : "") + "Bajó " + a.bajaPorcentaje() + "%: " + a.nombre();
        String detalle = (a.supermercadoNombre() != null ? a.supermercadoNombre() : a.supermercado()) + " · "
                + CLP.format(a.precioAnterior()) + " → " + CLP.format(a.precioNuevo());
        int nuevas = 0;
        for (String usuario : usuarios) {
            nuevas += notificaciones.insertarSiNoExiste(usuario, evento.eventoId(), evento.regla(), recortar(titulo, 300),
                    recortar(detalle, 500), a.urlProducto());
        }
        entregas.registrar(evento.eventoId(), topic, evento.regla(), usuarios.size(), evento.prueba());
        log.info("Evento {} de {} (regla {}): {} usuarios suscritos, {} notificaciones nuevas",
                evento.eventoId(), topic, evento.regla(), usuarios.size(), nuevas);
        return usuarios.size();
    }

    @Transactional(readOnly = true)
    public Resumen de(String usuario) {
        return new Resumen(notificaciones.findTop20ByUsuarioSubOrderByCreadaEnDesc(usuario),
                notificaciones.countByUsuarioSubAndLeidaFalse(usuario));
    }

    @Transactional
    public boolean marcarLeida(String usuario, Long id) {
        return notificaciones.findByIdAndUsuarioSub(id, usuario).map(n -> {
            n.marcarLeida();
            return true;
        }).orElse(false);
    }

    @Transactional
    public int marcarTodasLeidas(String usuario) {
        return notificaciones.marcarTodasLeidas(usuario);
    }

    @Transactional(readOnly = true)
    public List<String> suscripciones(String usuario) {
        return suscripciones.restriccionesDe(usuario);
    }

    /** Reemplaza las suscripciones del usuario. Acepta códigos (SIN_GLUTEN) o etiquetas ("Sin Gluten"). */
    @Transactional
    public List<String> suscribir(String usuario, List<String> restricciones) {
        List<String> codigos = restricciones.stream()
                .map(r -> Restriccion.desde(r).orElseThrow(() -> new IllegalArgumentException(
                        "Restricción desconocida: " + r + ". Valores: " + Arrays.toString(Restriccion.values()))))
                .map(Enum::name)
                .distinct()
                .sorted()
                .toList();
        suscripciones.reemplazar(usuario, codigos);
        return codigos;
    }

    private static String recortar(String texto, int max) {
        return texto.length() <= max ? texto : texto.substring(0, max - 1) + "…";
    }
}
