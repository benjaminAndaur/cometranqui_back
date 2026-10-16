package cl.duoc.cometranqui.mensajeria.admin;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.duoc.cometranqui.mensajeria.admin.AdministracionMensajeriaService.CatalogoVista;
import cl.duoc.cometranqui.mensajeria.admin.AdministracionMensajeriaService.DestinoVista;
import cl.duoc.cometranqui.mensajeria.admin.AdministracionMensajeriaService.GrupoVista;
import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.SolicitudInvalida;
import cl.duoc.cometranqui.mensajeria.admin.PruebasMensajeriaService.Llegada;
import cl.duoc.cometranqui.mensajeria.admin.PruebasMensajeriaService.ResultadoPrueba;
import cl.duoc.cometranqui.mensajeria.admin.ReglaDistribucionService.ReglaVista;
import cl.duoc.cometranqui.mensajeria.admin.Solicitudes.SolicitudCambioDestino;
import cl.duoc.cometranqui.mensajeria.admin.Solicitudes.SolicitudDestino;
import cl.duoc.cometranqui.mensajeria.admin.Solicitudes.SolicitudEstadoRegla;
import cl.duoc.cometranqui.mensajeria.admin.Solicitudes.SolicitudPrueba;
import cl.duoc.cometranqui.mensajeria.admin.Solicitudes.SolicitudRegla;
import cl.duoc.cometranqui.mensajeria.fallidos.MensajeFallido;
import cl.duoc.cometranqui.mensajeria.fallidos.MensajesFallidosService;
import jakarta.validation.Valid;

/**
 * API de administración de la mensajería ({@code /api/admin/**} exige rol ADMIN, grupo {@code admin} de Cognito).
 * No contiene lógica: traduce HTTP a llamadas de los servicios que encapsulan cada recurso.
 *
 * <pre>
 *  Recurso             Operaciones                                  Servicio
 *  destinos (topics)   listar, crear, modificar, eliminar          AdministracionMensajeriaService
 *  reglas              listar, crear, reemplazar, activar, borrar  ReglaDistribucionService
 *  grupos              listar con lag                               AdministracionMensajeriaService
 *  fallidos            listar, reprocesar, descartar                MensajesFallidosService
 *  pruebas             enviar mensaje de prueba y seguirlo          PruebasMensajeriaService
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/mensajeria")
public class MensajeriaAdminController {

    public record FallidoVista(Long id, String topicOrigen, Integer particion, Long offset, String grupo, String clave,
                               String contenido, String excepcion, String mensajeError, String traza, String estado,
                               String recibidoEn) {
    }

    private final AdministracionMensajeriaService destinos;
    private final ReglaDistribucionService reglas;
    private final MensajesFallidosService fallidos;
    private final PruebasMensajeriaService pruebas;

    public MensajeriaAdminController(AdministracionMensajeriaService destinos, ReglaDistribucionService reglas,
                                     MensajesFallidosService fallidos, PruebasMensajeriaService pruebas) {
        this.destinos = destinos;
        this.reglas = reglas;
        this.fallidos = fallidos;
        this.pruebas = pruebas;
    }

    // ── Catálogo y destinos ───────────────────────────────────────────────────

    @GetMapping("/catalogo")
    CatalogoVista catalogo() {
        return destinos.catalogo();
    }

    @GetMapping("/destinos")
    List<DestinoVista> listarDestinos() {
        return destinos.listarDestinos();
    }

    @PostMapping("/destinos")
    ResponseEntity<DestinoVista> crearDestino(@Valid @RequestBody SolicitudDestino solicitud) {
        return ResponseEntity.status(HttpStatus.CREATED).body(destinos.crearDestino(solicitud));
    }

    @PatchMapping("/destinos/{nombre}")
    DestinoVista modificarDestino(@PathVariable String nombre, @Valid @RequestBody SolicitudCambioDestino solicitud) {
        return destinos.modificarDestino(nombre, solicitud);
    }

    @DeleteMapping("/destinos/{nombre}")
    ResponseEntity<Void> eliminarDestino(@PathVariable String nombre) {
        destinos.eliminarDestino(nombre);
        return ResponseEntity.noContent().build();
    }

    // ── Reglas de distribución ────────────────────────────────────────────────

    @GetMapping("/reglas")
    List<ReglaVista> listarReglas() {
        return reglas.listar();
    }

    @PostMapping("/reglas")
    ResponseEntity<ReglaVista> crearRegla(@Valid @RequestBody SolicitudRegla solicitud) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reglas.crear(solicitud));
    }

    @PutMapping("/reglas/{id}")
    ReglaVista modificarRegla(@PathVariable Long id, @Valid @RequestBody SolicitudRegla solicitud) {
        return reglas.modificar(id, solicitud);
    }

    @PatchMapping("/reglas/{id}/estado")
    ReglaVista cambiarEstado(@PathVariable Long id, @Valid @RequestBody SolicitudEstadoRegla solicitud) {
        return reglas.cambiarEstado(id, solicitud.activa());
    }

    @DeleteMapping("/reglas/{id}")
    ResponseEntity<Void> eliminarRegla(@PathVariable Long id) {
        reglas.eliminar(id);
        return ResponseEntity.noContent().build();
    }

    // ── Consumer groups ───────────────────────────────────────────────────────

    @GetMapping("/grupos")
    List<GrupoVista> listarGrupos() {
        return destinos.listarGrupos();
    }

    // ── Mensajes fallidos ─────────────────────────────────────────────────────

    @GetMapping("/fallidos")
    Map<String, Object> listarFallidos(@RequestParam(required = false) String estado,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        MensajeFallido.Estado filtro = null;
        if (estado != null && !estado.isBlank()) {
            try {
                filtro = MensajeFallido.Estado.valueOf(estado.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new SolicitudInvalida("estado", "Valores permitidos: PENDIENTE, REPROCESADO, DESCARTADO");
            }
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new SolicitudInvalida("size", "page >= 0 y size entre 1 y 100");
        }
        Page<MensajeFallido> p = fallidos.listar(filtro, page, size);
        return Map.of(
                "contenido", p.getContent().stream().map(MensajeriaAdminController::vista).toList(),
                "totalElementos", p.getTotalElements(),
                "pendientes", fallidos.pendientes());
    }

    @PostMapping("/fallidos/{id}/reprocesar")
    ResponseEntity<FallidoVista> reprocesar(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(vista(fallidos.reprocesar(id)));
    }

    @DeleteMapping("/fallidos/{id}")
    FallidoVista descartar(@PathVariable Long id) {
        return vista(fallidos.descartar(id));
    }

    // ── Pruebas ───────────────────────────────────────────────────────────────

    @PostMapping("/pruebas")
    ResultadoPrueba enviarPrueba(@Valid @RequestBody SolicitudPrueba solicitud) {
        return pruebas.enviar(solicitud);
    }

    @GetMapping("/pruebas/{eventoId}")
    List<Llegada> seguirPrueba(@PathVariable String eventoId, @RequestParam List<String> reglas) {
        return pruebas.seguimiento(eventoId, reglas);
    }

    private static FallidoVista vista(MensajeFallido m) {
        return new FallidoVista(m.getId(), m.getTopicOrigen(), m.getParticionOrigen(), m.getOffsetOrigen(),
                m.getGrupoOrigen(), m.getClave(), m.getContenido(), m.getExcepcion(), m.getMensajeError(), m.getTraza(),
                m.getEstado().name(), m.getRecibidoEn().toString());
    }
}
