package cl.duoc.cometranqui.mensajeria.admin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.RecursoEnConflicto;
import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.RecursoNoEncontrado;
import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.SolicitudInvalida;
import cl.duoc.cometranqui.mensajeria.admin.Solicitudes.SolicitudCambioDestino;
import cl.duoc.cometranqui.mensajeria.admin.Solicitudes.SolicitudDestino;
import cl.duoc.cometranqui.mensajeria.config.CatalogoMensajeria;
import cl.duoc.cometranqui.mensajeria.config.MensajeriaProperties.Destino;
import cl.duoc.cometranqui.mensajeria.config.MensajeriaProperties.Limpieza;
import cl.duoc.cometranqui.mensajeria.config.MensajeriaProperties.Ruta;
import cl.duoc.cometranqui.mensajeria.config.TopicsConfig;
import cl.duoc.cometranqui.mensajeria.enrutamiento.ReglaDistribucion;
import cl.duoc.cometranqui.mensajeria.enrutamiento.ReglaDistribucionRepository;

/**
 * Único componente que administra recursos de Kafka (topics y consumer groups) mediante {@link AdminClient}.
 * Quien lo usa (controlador, interfaz) trabaja con DTOs propios y nunca con tipos de Kafka.
 *
 * <p>Reglas que protegen la coherencia del sistema:
 * <ul>
 *   <li>Solo se crean destinos dentro de una familia del catálogo (ej. {@code notificaciones.}): así todo destino
 *       nuevo tiene un consumidor que lo atiende.</li>
 *   <li>Los topics fijos del catálogo y los internos de Kafka ({@code __*}) están protegidos: se cambian en el
 *       catálogo, no desde aquí.</li>
 *   <li>No se elimina un destino que alguna regla de distribución use.</li>
 * </ul>
 */
@Service
public class AdministracionMensajeriaService {

    private static final Logger log = LoggerFactory.getLogger(AdministracionMensajeriaService.class);
    private static final long HORA_MS = 3_600_000L;
    private static final int TIMEOUT_S = 10;

    public record DestinoVista(String nombre, String claveCatalogo, String descripcion, int particiones,
                               Integer retencionHoras, String limpieza, long mensajes, boolean protegido,
                               List<String> reglas) {
    }

    public record ParticionVista(String topic, int particion, long confirmado, long fin, long lag) {
    }

    public record GrupoVista(String ruta, String grupo, String descripcion, String topic, String estado, int miembros,
                             long lag, List<ParticionVista> particiones) {
    }

    public record CatalogoVista(Map<String, Destino> destinos, Map<String, Ruta> rutas, Object reintentos,
                                List<String> prefijosPermitidos) {
    }

    private final AdminClient admin;
    private final CatalogoMensajeria catalogo;
    private final ReglaDistribucionRepository reglas;

    public AdministracionMensajeriaService(AdminClient admin, CatalogoMensajeria catalogo,
                                           ReglaDistribucionRepository reglas) {
        this.admin = admin;
        this.catalogo = catalogo;
        this.reglas = reglas;
    }

    // ── Consultas ─────────────────────────────────────────────────────────────

    public CatalogoVista catalogo() {
        return new CatalogoVista(catalogo.destinos(), catalogo.rutas(), catalogo.reintentos(),
                catalogo.prefijosDeFamilias());
    }

    public List<DestinoVista> listarDestinos() {
        Set<String> nombres = esperar(admin.listTopics().names());
        if (nombres.isEmpty()) {
            return List.of();
        }
        Map<String, TopicDescription> descripciones = esperar(admin.describeTopics(nombres).allTopicNames());
        Map<ConfigResource, Config> configs = esperar(admin.describeConfigs(nombres.stream()
                .map(n -> new ConfigResource(ConfigResource.Type.TOPIC, n)).toList()).all());
        Map<String, Long> mensajes = contarMensajes(descripciones.values());
        Map<String, List<String>> reglasPorTopic = reglas.findAll().stream()
                .collect(Collectors.groupingBy(ReglaDistribucion::getTopicDestino,
                        Collectors.mapping(ReglaDistribucion::getNombre, Collectors.toList())));

        return descripciones.values().stream()
                .sorted(Comparator.comparing(TopicDescription::name))
                .map(d -> {
                    Config c = configs.get(new ConfigResource(ConfigResource.Type.TOPIC, d.name()));
                    String limpieza = valor(c, "cleanup.policy");
                    String retencion = valor(c, "retention.ms");
                    Integer horas = limpieza != null && limpieza.contains("compact") || retencion == null
                            ? null : (int) (Long.parseLong(retencion) / HORA_MS);
                    String clave = catalogo.destinoDeTopic(d.name());
                    return new DestinoVista(d.name(), clave,
                            clave == null ? null : catalogo.destino(clave).descripcion(),
                            d.partitions().size(), horas, limpieza, mensajes.getOrDefault(d.name(), 0L),
                            esProtegido(d.name()), reglasPorTopic.getOrDefault(d.name(), List.of()));
                })
                .toList();
    }

    public boolean existeDestino(String nombre) {
        return esperar(admin.listTopics().names()).contains(nombre);
    }

    public List<GrupoVista> listarGrupos() {
        List<GrupoVista> grupos = new ArrayList<>();
        catalogo.rutas().forEach((clave, ruta) -> grupos.add(describirGrupo(clave, ruta)));
        return grupos;
    }

    // ── Operaciones ───────────────────────────────────────────────────────────

    public DestinoVista crearDestino(SolicitudDestino s) {
        String familia = familiaDe(s.nombre());
        if (familia == null) {
            throw new SolicitudInvalida("nombre", "Debe comenzar con un prefijo permitido: "
                    + String.join(", ", catalogo.prefijosDeFamilias()));
        }
        if (existeDestino(s.nombre())) {
            throw new RecursoEnConflicto("El destino " + s.nombre() + " ya existe");
        }
        Limpieza limpieza = s.limpieza() == null ? Limpieza.DELETE : s.limpieza();
        if (limpieza == Limpieza.COMPACT && s.retencionHoras() != null) {
            throw new SolicitudInvalida("retencionHoras",
                    "Un destino compactado conserva el último mensaje por clave: no usa retención por tiempo");
        }
        Destino base = catalogo.destino(familia);
        Map<String, String> config = new HashMap<>(TopicsConfig.configuracionKafka(new Destino(s.nombre(), null,
                s.particiones(), base.retencion(), limpieza, null, null, null)));
        if (s.retencionHoras() != null) {
            config.put("retention.ms", String.valueOf(s.retencionHoras() * HORA_MS));
        }
        esperar(admin.createTopics(List.of(new NewTopic(s.nombre(), s.particiones(), (short) 1).configs(config))).all());
        log.info("Destino creado desde la administración: {} ({} particiones, {})", s.nombre(), s.particiones(), config);
        return vista(s.nombre());
    }

    public DestinoVista modificarDestino(String nombre, SolicitudCambioDestino s) {
        exigirExistente(nombre);
        exigirNoProtegido(nombre, "modificar");
        if (s.retencionHoras() == null && s.particiones() == null) {
            throw new SolicitudInvalida("retencionHoras", "Indica la nueva retención o las nuevas particiones");
        }
        DestinoVista actual = vista(nombre);
        if (s.retencionHoras() != null) {
            if (actual.limpieza() != null && actual.limpieza().contains("compact")) {
                throw new SolicitudInvalida("retencionHoras", "El destino es compactado: no usa retención por tiempo");
            }
            ConfigResource recurso = new ConfigResource(ConfigResource.Type.TOPIC, nombre);
            AlterConfigOp op = new AlterConfigOp(new ConfigEntry("retention.ms",
                    String.valueOf(s.retencionHoras() * HORA_MS)), AlterConfigOp.OpType.SET);
            esperar(admin.incrementalAlterConfigs(Map.of(recurso, List.of(op))).all());
        }
        if (s.particiones() != null && s.particiones() != actual.particiones()) {
            if (s.particiones() < actual.particiones()) {
                throw new SolicitudInvalida("particiones", "Kafka no permite reducir particiones (actual: "
                        + actual.particiones() + ")");
            }
            esperar(admin.createPartitions(Map.of(nombre, NewPartitions.increaseTo(s.particiones()))).all());
        }
        log.info("Destino modificado desde la administración: {} -> {}", nombre, s);
        return vista(nombre);
    }

    public void eliminarDestino(String nombre) {
        exigirExistente(nombre);
        exigirNoProtegido(nombre, "eliminar");
        List<String> enUso = reglas.findAll().stream()
                .filter(r -> r.getTopicDestino().equals(nombre))
                .map(r -> r.getNombre() + (r.isActiva() ? "" : " (inactiva)"))
                .toList();
        if (!enUso.isEmpty()) {
            throw new RecursoEnConflicto("El destino " + nombre + " está en uso por las reglas: "
                    + String.join(", ", enUso) + ". Elimínalas o cámbiales el destino primero.");
        }
        esperar(admin.deleteTopics(List.of(nombre)).all());
        log.info("Destino eliminado desde la administración: {}", nombre);
    }

    // ── Apoyo ─────────────────────────────────────────────────────────────────

    public boolean esProtegido(String nombre) {
        return nombre.startsWith("__") || catalogo.topicsProtegidos().contains(nombre);
    }

    /** Clave de la familia del catálogo a la que pertenecería un topic nuevo, o null. */
    public String familiaDe(String nombre) {
        return catalogo.destinos().entrySet().stream()
                .filter(e -> e.getValue().esFamilia() && nombre.startsWith(e.getValue().prefijo())
                        && nombre.length() > e.getValue().prefijo().length())
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    private void exigirExistente(String nombre) {
        if (!existeDestino(nombre)) {
            throw new RecursoNoEncontrado("No existe el destino " + nombre);
        }
    }

    private void exigirNoProtegido(String nombre, String accion) {
        if (esProtegido(nombre)) {
            throw new RecursoEnConflicto("El destino " + nombre + " está protegido: es parte del catálogo base de "
                    + "la aplicación y no se puede " + accion + " desde aquí (se cambia en application.yml)");
        }
    }

    private DestinoVista vista(String nombre) {
        return listarDestinos().stream().filter(d -> d.nombre().equals(nombre)).findFirst()
                .orElseThrow(() -> new RecursoNoEncontrado("No existe el destino " + nombre));
    }

    private GrupoVista describirGrupo(String clave, Ruta ruta) {
        Destino origen = catalogo.destino(ruta.origen());
        String topicVisible = origen.esFamilia() ? origen.prefijo() + "*" : origen.topic();
        try {
            ConsumerGroupDescription d = esperar(admin.describeConsumerGroups(List.of(ruta.grupo())).all()).get(ruta.grupo());
            Map<TopicPartition, OffsetAndMetadata> confirmados =
                    esperar(admin.listConsumerGroupOffsets(ruta.grupo()).partitionsToOffsetAndMetadata());
            Map<TopicPartition, Long> fines = finDe(confirmados.keySet());
            List<ParticionVista> particiones = confirmados.entrySet().stream()
                    .filter(e -> e.getValue() != null)
                    .map(e -> {
                        long fin = fines.getOrDefault(e.getKey(), e.getValue().offset());
                        return new ParticionVista(e.getKey().topic(), e.getKey().partition(), e.getValue().offset(), fin,
                                Math.max(0, fin - e.getValue().offset()));
                    })
                    .sorted(Comparator.comparing(ParticionVista::topic).thenComparing(ParticionVista::particion))
                    .toList();
            long lag = particiones.stream().mapToLong(ParticionVista::lag).sum();
            return new GrupoVista(clave, ruta.grupo(), ruta.descripcion(), topicVisible, d.state().toString(),
                    d.members().size(), lag, particiones);
        } catch (BrokerNoDisponible e) {
            if (e.getCause() instanceof GroupIdNotFoundException) {
                return new GrupoVista(clave, ruta.grupo(), ruta.descripcion(), topicVisible, "SIN_DATOS", 0, 0, List.of());
            }
            throw e;
        }
    }

    private Map<String, Long> contarMensajes(Collection<TopicDescription> topics) {
        List<TopicPartition> particiones = topics.stream()
                .flatMap(t -> t.partitions().stream().map(p -> new TopicPartition(t.name(), p.partition())))
                .toList();
        Map<TopicPartition, Long> inicio = offsets(particiones, OffsetSpec.earliest());
        Map<TopicPartition, Long> fin = offsets(particiones, OffsetSpec.latest());
        Map<String, Long> total = new HashMap<>();
        fin.forEach((tp, f) -> total.merge(tp.topic(), f - inicio.getOrDefault(tp, 0L), Long::sum));
        return total;
    }

    private Map<TopicPartition, Long> finDe(Collection<TopicPartition> particiones) {
        return offsets(particiones, OffsetSpec.latest());
    }

    private Map<TopicPartition, Long> offsets(Collection<TopicPartition> particiones, OffsetSpec spec) {
        if (particiones.isEmpty()) {
            return Map.of();
        }
        Map<TopicPartition, OffsetSpec> pedido = particiones.stream().collect(Collectors.toMap(p -> p, p -> spec));
        return esperar(admin.listOffsets(pedido).all()).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
    }

    private static String valor(Config c, String clave) {
        if (c == null) {
            return null;
        }
        ConfigEntry e = c.get(clave);
        return e == null ? null : e.value();
    }

    private static <T> T esperar(KafkaFuture<T> futuro) {
        try {
            return futuro.get(TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerNoDisponible("Operación interrumpida", e);
        } catch (ExecutionException e) {
            throw new BrokerNoDisponible("Kafka rechazó la operación: " + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            throw new BrokerNoDisponible("Kafka no respondió en " + TIMEOUT_S + " s", e);
        }
    }
}
