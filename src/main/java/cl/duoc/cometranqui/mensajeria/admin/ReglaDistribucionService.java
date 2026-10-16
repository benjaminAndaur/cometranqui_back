package cl.duoc.cometranqui.mensajeria.admin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.ErrorCampo;
import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.RecursoEnConflicto;
import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.RecursoNoEncontrado;
import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.SolicitudInvalida;
import cl.duoc.cometranqui.mensajeria.admin.Solicitudes.SolicitudRegla;
import cl.duoc.cometranqui.mensajeria.config.CatalogoMensajeria;
import cl.duoc.cometranqui.mensajeria.enrutamiento.MotorReglas;
import cl.duoc.cometranqui.mensajeria.enrutamiento.ReglaDistribucion;
import cl.duoc.cometranqui.mensajeria.enrutamiento.ReglaDistribucionRepository;
import cl.duoc.cometranqui.ofertas.Restriccion;

/**
 * Operaciones sobre reglas de distribución. Además del formato (DTO), valida contra el estado del sistema:
 * nombre único, origen atendido por una ruta del catálogo, destino existente dentro de una familia y al menos una
 * condición (una regla sin condiciones reenviaría todas las alertas a un destino).
 */
@Service
public class ReglaDistribucionService {

    private static final Logger log = LoggerFactory.getLogger(ReglaDistribucionService.class);

    public record ReglaVista(Long id, String nombre, String destinoOrigen, String restriccion,
                             String restriccionEtiqueta, Integer descuentoMinimo, String tienda, String topicDestino,
                             boolean activa, int prioridad, String actualizadaEn) {
    }

    private final ReglaDistribucionRepository repository;
    private final CatalogoMensajeria catalogo;
    private final AdministracionMensajeriaService destinos;

    public ReglaDistribucionService(ReglaDistribucionRepository repository, CatalogoMensajeria catalogo,
                                    AdministracionMensajeriaService destinos) {
        this.repository = repository;
        this.catalogo = catalogo;
        this.destinos = destinos;
    }

    @Transactional(readOnly = true)
    public List<ReglaVista> listar() {
        return repository.findAllByOrderByPrioridadAscNombreAsc().stream().map(ReglaDistribucionService::vista).toList();
    }

    @Transactional
    public ReglaVista crear(SolicitudRegla s) {
        if (repository.existsByNombre(s.nombre())) {
            throw new RecursoEnConflicto("Ya existe una regla llamada " + s.nombre());
        }
        ReglaDistribucion r = new ReglaDistribucion(s.nombre());
        aplicar(r, s);
        ReglaDistribucion guardada = repository.save(r);
        log.info("Regla creada: {} -> {}", guardada.getNombre(), guardada.getTopicDestino());
        return vista(guardada);
    }

    @Transactional
    public ReglaVista modificar(Long id, SolicitudRegla s) {
        ReglaDistribucion r = buscar(id);
        if (repository.existsByNombreAndIdNot(s.nombre(), id)) {
            throw new RecursoEnConflicto("Ya existe otra regla llamada " + s.nombre());
        }
        r.setNombre(s.nombre());
        aplicar(r, s);
        log.info("Regla modificada: {} -> {}", r.getNombre(), r.getTopicDestino());
        return vista(r);
    }

    @Transactional
    public ReglaVista cambiarEstado(Long id, boolean activa) {
        ReglaDistribucion r = buscar(id);
        r.setActiva(activa);
        return vista(r);
    }

    @Transactional
    public void eliminar(Long id) {
        repository.delete(buscar(id));
    }

    private void aplicar(ReglaDistribucion r, SolicitudRegla s) {
        List<ErrorCampo> errores = new ArrayList<>();
        // El origen debe ser un destino que el enrutador realmente evalúa; otro origen dejaría la regla sin efecto
        if (!MotorReglas.ORIGEN_ALERTAS.equals(s.destinoOrigen())) {
            errores.add(new ErrorCampo("destinoOrigen", "El enrutador evalúa reglas sobre '" + MotorReglas.ORIGEN_ALERTAS
                    + "'; otro origen dejaría la regla sin efecto"));
        }
        String restriccion = null;
        if (s.restriccion() != null && !s.restriccion().isBlank()) {
            restriccion = Restriccion.desde(s.restriccion()).map(Enum::name).orElse(null);
            if (restriccion == null) {
                errores.add(new ErrorCampo("restriccion", "Valores permitidos: " + Arrays.toString(Restriccion.values())));
            }
        }
        boolean sinCondiciones = (s.restriccion() == null || s.restriccion().isBlank()) && s.descuentoMinimo() == null
                && (s.tienda() == null || s.tienda().isBlank());
        if (sinCondiciones) {
            errores.add(new ErrorCampo("restriccion", "Indica al menos una condición: restricción, descuento mínimo o tienda"));
        }
        if (destinos.familiaDe(s.topicDestino()) == null) {
            errores.add(new ErrorCampo("topicDestino", "Debe ser un destino de una familia permitida: "
                    + String.join(", ", catalogo.prefijosDeFamilias())));
        }
        if (!errores.isEmpty()) {
            throw new SolicitudInvalida(errores);
        }
        if (!destinos.existeDestino(s.topicDestino())) {
            throw new RecursoNoEncontrado("El destino " + s.topicDestino() + " no existe; créalo primero en «Destinos»");
        }
        r.configurar(s.destinoOrigen(), restriccion, s.descuentoMinimo(),
                s.tienda() == null || s.tienda().isBlank() ? null : s.tienda(), s.topicDestino(),
                s.activa() == null || s.activa(), s.prioridad() == null ? 100 : s.prioridad());
    }

    private ReglaDistribucion buscar(Long id) {
        return repository.findById(id).orElseThrow(() -> new RecursoNoEncontrado("No existe la regla " + id));
    }

    static ReglaVista vista(ReglaDistribucion r) {
        return new ReglaVista(r.getId(), r.getNombre(), r.getDestinoOrigen(), r.getRestriccion(),
                r.getRestriccion() == null ? null : Restriccion.valueOf(r.getRestriccion()).etiqueta(),
                r.getDescuentoMinimo(), r.getTienda(), r.getTopicDestino(), r.isActiva(), r.getPrioridad(),
                r.getActualizadaEn() == null ? null : r.getActualizadaEn().toString());
    }
}
