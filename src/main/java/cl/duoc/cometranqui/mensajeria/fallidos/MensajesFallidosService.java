package cl.duoc.cometranqui.mensajeria.fallidos;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.RecursoEnConflicto;
import cl.duoc.cometranqui.mensajeria.admin.ErroresAdmin.RecursoNoEncontrado;
import cl.duoc.cometranqui.mensajeria.fallidos.MensajeFallido.Estado;

/**
 * Registro, revisión y recuperación de mensajes apartados en la DLT.
 * El diagnóstico sale de los headers {@code kafka_dlt-*} que agrega {@code DeadLetterPublishingRecoverer}.
 */
@Service
public class MensajesFallidosService {

    private static final Logger log = LoggerFactory.getLogger(MensajesFallidosService.class);
    static final String HEADER_REPROCESO = "cometranqui-reproceso-de";

    private final MensajeFallidoRepository repository;
    private final KafkaTemplate<String, Object> kafka;

    public MensajesFallidosService(MensajeFallidoRepository repository, KafkaTemplate<String, Object> kafka) {
        this.repository = repository;
        this.kafka = kafka;
    }

    @Transactional
    public void registrar(ConsumerRecord<String, String> registro) {
        Headers h = registro.headers();
        String topic = texto(h, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        Integer particion = entero(h, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        Long offset = largo(h, KafkaHeaders.DLT_ORIGINAL_OFFSET);
        int filas = repository.registrar(
                topic != null ? topic : registro.topic(),
                particion != null ? particion : registro.partition(),
                offset != null ? offset : registro.offset(),
                texto(h, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP),
                recortar(registro.key(), 200),
                registro.value(),
                recortar(excepcion(h), 300),
                texto(h, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                recortar(texto(h, KafkaHeaders.DLT_EXCEPTION_STACKTRACE), 4000));
        log.warn("Mensaje fallido de {}-{}@{} registrado{}: {}", topic, particion, offset,
                filas == 0 ? " (ya existía)" : "", texto(h, KafkaHeaders.DLT_EXCEPTION_MESSAGE));
    }

    /**
     * La excepción que importa para diagnosticar. Si el listener la envolvió ({@code ListenerExecutionFailedException}),
     * se muestra la causa real; si no, la excepción con su causa entre paréntesis (ej. deserialización ← JSON inválido).
     */
    static String excepcion(Headers h) {
        String clase = texto(h, KafkaHeaders.DLT_EXCEPTION_FQCN);
        String causa = texto(h, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
        if (causa == null || causa.equals(clase)) {
            return clase;
        }
        if (clase != null && clase.endsWith("ListenerExecutionFailedException")) {
            return causa;
        }
        return clase + " (causa: " + causa + ")";
    }

    @Transactional(readOnly = true)
    public Page<MensajeFallido> listar(Estado estado, int pagina, int tamano) {
        PageRequest p = PageRequest.of(pagina, tamano);
        return estado == null ? repository.findAllByOrderByRecibidoEnDesc(p) : repository.findByEstadoOrderByRecibidoEnDesc(estado, p);
    }

    /**
     * Republica el contenido original en su topic de origen, con la misma clave (misma partición, mismo orden), y
     * lo marca REPROCESADO. Si vuelve a fallar, llega de nuevo a la DLT como un registro nuevo.
     */
    @Transactional
    public MensajeFallido reprocesar(Long id) {
        MensajeFallido m = buscar(id);
        if (m.getEstado() != Estado.PENDIENTE) {
            throw new RecursoEnConflicto("El mensaje " + id + " ya está " + m.getEstado() + "; solo se reprocesan pendientes");
        }
        if (m.getContenido() == null || m.getTopicOrigen() == null) {
            throw new RecursoEnConflicto("El mensaje " + id + " no tiene contenido o topic de origen para reprocesar");
        }
        ProducerRecord<String, Object> registro = new ProducerRecord<>(m.getTopicOrigen(), m.getClave(),
                m.getContenido().getBytes(StandardCharsets.UTF_8));
        registro.headers().add(HEADER_REPROCESO, String.valueOf(id).getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(registro).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido reprocesando " + id, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("No se pudo republicar el mensaje " + id, e);
        }
        m.cambiarEstado(Estado.REPROCESADO);
        log.info("Mensaje fallido {} republicado en {}", id, m.getTopicOrigen());
        return m;
    }

    @Transactional
    public MensajeFallido descartar(Long id) {
        MensajeFallido m = buscar(id);
        if (m.getEstado() != Estado.PENDIENTE) {
            throw new RecursoEnConflicto("El mensaje " + id + " ya está " + m.getEstado());
        }
        m.cambiarEstado(Estado.DESCARTADO);
        return m;
    }

    @Transactional(readOnly = true)
    public long pendientes() {
        return repository.countByEstado(Estado.PENDIENTE);
    }

    private MensajeFallido buscar(Long id) {
        return repository.findById(id).orElseThrow(() -> new RecursoNoEncontrado("No existe el mensaje fallido " + id));
    }

    private static String texto(Headers h, String nombre) {
        Header header = h.lastHeader(nombre);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Integer entero(Headers h, String nombre) {
        Header header = h.lastHeader(nombre);
        return header == null || header.value() == null || header.value().length != 4 ? null : ByteBuffer.wrap(header.value()).getInt();
    }

    private static Long largo(Headers h, String nombre) {
        Header header = h.lastHeader(nombre);
        return header == null || header.value() == null || header.value().length != 8 ? null : ByteBuffer.wrap(header.value()).getLong();
    }

    private static String recortar(String texto, int max) {
        return texto == null || texto.length() <= max ? texto : texto.substring(0, max);
    }
}
