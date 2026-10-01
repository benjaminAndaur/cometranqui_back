package cl.duoc.cometranqui.locales;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.duoc.cometranqui.locales.kafka.LocalMensaje;
import cl.duoc.cometranqui.ofertas.Restriccion;

@Service
public class LocalService {

    private static final Logger log = LoggerFactory.getLogger(LocalService.class);

    /** Mismos nombres que la sección "Niveles de seguridad" del frontend. */
    static final Map<Integer, String> NOMBRES_NIVEL = Map.of(
            1, "Exclusivo / Certificado",
            2, "Opción Segura",
            3, "Opción Básica");

    public static final String ATRIBUCION_OSM = "© OpenStreetMap contributors (ODbL)";

    public record LocalVista(String id, String nombre, String tipo, String comuna, String direccion,
                             double latitud, double longitud, List<String> restricciones, int nivel,
                             String nivelNombre, Map<String, Integer> nivelesPorRestriccion, String sitioWeb,
                             String telefono, String horario, String fuente, String urlFuente, String atribucion) {
    }

    private final LocalRepository repository;

    public LocalService(LocalRepository repository) {
        this.repository = repository;
    }

    /**
     * UPSERT idempotente por id. Un mensaje más antiguo que lo guardado (replay) se ignora. Como la key del
     * mensaje es el id del local, el mismo local siempre lo procesa el mismo hilo: no hay carreras.
     */
    @Transactional
    public void procesar(LocalMensaje m) {
        Local local = repository.findById(m.id()).orElseGet(() -> new Local(m.id()));
        if (local.getScrapeadoEn() != null && local.getScrapeadoEn().isAfter(m.scrapeadoEn())) {
            log.info("Mensaje de local más antiguo que lo guardado, se ignora: {}", m.id());
            return;
        }
        local.actualizarDesde(m);
        repository.save(local);
    }

    /**
     * @param restriccion si viene, el nivel informado es el de esa restricción; si no, el mejor nivel del local
     */
    @Transactional(readOnly = true)
    public Page<LocalVista> buscar(Restriccion restriccion, int nivelMaximo, String comuna, int pagina, int tamano) {
        String filtroComuna = comuna == null || comuna.isBlank() ? null : comuna.trim();
        return repository.buscar(restriccion == null ? null : restriccion.name(), nivelMaximo, filtroComuna,
                        PageRequest.of(pagina, tamano))
                .map(l -> vista(l, restriccion));
    }

    @Transactional(readOnly = true)
    public long contar() {
        return repository.count();
    }

    static LocalVista vista(Local l, Restriccion restriccion) {
        Map<String, Integer> porEtiqueta = new LinkedHashMap<>();
        l.getNiveles().entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                .forEach(e -> porEtiqueta.put(etiqueta(e.getKey()), e.getValue()));
        int nivel = restriccion != null && l.getNiveles().containsKey(restriccion.name())
                ? l.getNiveles().get(restriccion.name())
                : l.getNiveles().values().stream().min(Integer::compare).orElse(3);
        return new LocalVista(l.getId(), l.getNombre(), l.getTipo(), l.getComuna(), l.getDireccion(),
                l.getLatitud(), l.getLongitud(), List.copyOf(porEtiqueta.keySet()), nivel, NOMBRES_NIVEL.get(nivel),
                porEtiqueta, l.getSitioWeb(), l.getTelefono(), l.getHorario(), l.getFuente(), l.getUrlFuente(),
                "OSM".equals(l.getFuente()) ? ATRIBUCION_OSM : null);
    }

    private static String etiqueta(String codigo) {
        return Restriccion.desde(codigo).map(Restriccion::etiqueta).orElse(codigo);
    }
}
