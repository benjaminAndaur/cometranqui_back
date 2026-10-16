package cl.duoc.cometranqui.eventos;

import java.time.Instant;

/**
 * Mensaje de los destinos {@code notificaciones.*}: una alerta de precio que cumplió una regla de distribución.
 *
 * @param eventoId id de la alerta + nombre de la regla: idempotencia por usuario en el consumidor
 * @param regla nombre de la regla que lo enrutó (trazabilidad)
 * @param restriccion restricción que exigía la regla; null si la regla no filtraba por restricción
 * @param prueba true si lo originó el envío de prueba de la administración
 */
public record NotificacionEvento(
        String eventoId,
        String regla,
        String restriccion,
        AlertaPrecio alerta,
        boolean prueba,
        Instant enrutadoEn) {
}
