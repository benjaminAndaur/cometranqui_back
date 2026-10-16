package cl.duoc.cometranqui.mensajeria.admin;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import cl.duoc.cometranqui.eventos.AlertaPrecio;
import cl.duoc.cometranqui.eventos.PublicadorEventos;
import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.SolicitudInvalida;
import cl.duoc.cometranqui.mensajeria.admin.Solicitudes.SolicitudPrueba;
import cl.duoc.cometranqui.mensajeria.config.CatalogoMensajeria;
import cl.duoc.cometranqui.mensajeria.enrutamiento.MotorReglas;
import cl.duoc.cometranqui.mensajeria.enrutamiento.MotorReglas.Evaluacion;
import cl.duoc.cometranqui.notificaciones.EntregaNotificacionRepository;
import cl.duoc.cometranqui.notificaciones.EntregaNotificacionRepository.Entrega;
import cl.duoc.cometranqui.ofertas.Restriccion;

/**
 * Mensajes de prueba para demostrar la distribución: publica una alerta real en el destino {@code alertas-precio}
 * (recorre el mismo camino que una baja de precio detectada) y devuelve la evaluación de cada regla. Luego la
 * interfaz consulta {@link #seguimiento} para ver qué consumidor la recibió.
 */
@Service
public class PruebasMensajeriaService {

    public record ResultadoPrueba(String eventoId, String topicOrigen, List<Evaluacion> evaluaciones,
                                  List<String> destinosEsperados) {
    }

    public record Llegada(String regla, String topicDestino, boolean recibida, String consumidor, Integer destinatarios,
                          String recibidaEn) {
    }

    private final PublicadorEventos publicador;
    private final MotorReglas motor;
    private final CatalogoMensajeria catalogo;
    private final EntregaNotificacionRepository entregas;

    public PruebasMensajeriaService(PublicadorEventos publicador, MotorReglas motor, CatalogoMensajeria catalogo,
                                    EntregaNotificacionRepository entregas) {
        this.publicador = publicador;
        this.motor = motor;
        this.catalogo = catalogo;
        this.entregas = entregas;
    }

    public ResultadoPrueba enviar(SolicitudPrueba s) {
        Set<Restriccion> restricciones = EnumSet.noneOf(Restriccion.class);
        for (String r : s.restricciones()) {
            restricciones.add(Restriccion.desde(r).orElseThrow(() -> new SolicitudInvalida("restricciones",
                    "Restricción desconocida: " + r)));
        }
        int precioAnterior = 10_000;
        int precioNuevo = precioAnterior * (100 - s.bajaPorcentaje()) / 100;
        String eventoId = "prueba-" + UUID.randomUUID();
        AlertaPrecio alerta = new AlertaPrecio(eventoId, s.tienda(), s.tienda(), "PRUEBA-" + eventoId.substring(7, 15),
                s.producto(), restricciones, precioAnterior, precioNuevo, s.bajaPorcentaje(),
                "https://cometranqui.vercel.app/#ofertas", Instant.now());
        List<Evaluacion> evaluaciones = motor.evaluar(alerta);
        publicador.publicar(alerta);
        return new ResultadoPrueba(eventoId, catalogo.topic("alertas-precio"), evaluaciones,
                evaluaciones.stream().filter(Evaluacion::coincide).map(Evaluacion::topicDestino).toList());
    }

    /** Para cada regla que coincidió: si el consumidor de notificaciones ya recibió el evento y cuándo. */
    public List<Llegada> seguimiento(String eventoId, List<String> reglas) {
        String consumidor = catalogo.grupo("notificar-usuarios");
        return reglas.stream().map(regla -> {
            Optional<Entrega> e = entregas.buscar(eventoId + "|" + regla);
            return e.map(x -> new Llegada(regla, x.topic(), true, consumidor, x.destinatarios(), x.recibidaEn().toString()))
                    .orElseGet(() -> new Llegada(regla, null, false, consumidor, null, null));
        }).toList();
    }
}
