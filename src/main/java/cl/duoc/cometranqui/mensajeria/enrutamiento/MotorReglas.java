package cl.duoc.cometranqui.mensajeria.enrutamiento;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import cl.duoc.cometranqui.eventos.AlertaPrecio;
import cl.duoc.cometranqui.ofertas.Restriccion;

/**
 * Evalúa las reglas de distribución activas de un destino de origen sobre un evento y explica el resultado de cada
 * una. La explicación es la traza que muestra la administración al enviar un mensaje de prueba.
 *
 * <p>Las reglas se leen de la base en cada evaluación: los cambios hechos desde la administración se aplican al
 * instante y de forma consistente entre instancias (el volumen de alertas es bajo, no justifica una caché).
 */
@Component
public class MotorReglas {

    public static final String ORIGEN_ALERTAS = "alertas-precio";

    /** Resultado de evaluar una regla: si coincidió, a qué topic enruta y por qué. */
    public record Evaluacion(String regla, String topicDestino, String restriccion, boolean coincide, String motivo) {
    }

    private final ReglaDistribucionRepository repository;

    public MotorReglas(ReglaDistribucionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<Evaluacion> evaluar(AlertaPrecio alerta) {
        List<Evaluacion> resultado = new ArrayList<>();
        for (ReglaDistribucion r : repository.findByDestinoOrigenAndActivaTrueOrderByPrioridadAscNombreAsc(ORIGEN_ALERTAS)) {
            resultado.add(evaluar(r, alerta));
        }
        return resultado;
    }

    static Evaluacion evaluar(ReglaDistribucion r, AlertaPrecio alerta) {
        List<String> fallos = new ArrayList<>();
        if (r.getRestriccion() != null) {
            Restriccion exigida = Restriccion.valueOf(r.getRestriccion());
            if (alerta.restricciones() == null || !alerta.restricciones().contains(exigida)) {
                fallos.add("no es " + exigida.etiqueta());
            }
        }
        if (r.getDescuentoMinimo() != null && alerta.bajaPorcentaje() < r.getDescuentoMinimo()) {
            fallos.add("bajó " + alerta.bajaPorcentaje() + "% (mínimo " + r.getDescuentoMinimo() + "%)");
        }
        if (r.getTienda() != null && !r.getTienda().equals(alerta.supermercado())) {
            fallos.add("es de " + alerta.supermercado() + " (regla: " + r.getTienda() + ")");
        }
        boolean coincide = fallos.isEmpty();
        String motivo = coincide ? "cumple todas las condiciones" : String.join("; ", fallos);
        return new Evaluacion(r.getNombre(), r.getTopicDestino(), r.getRestriccion(), coincide, motivo);
    }
}
