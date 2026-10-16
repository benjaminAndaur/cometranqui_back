package cl.duoc.cometranqui.ofertas;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cl.duoc.cometranqui.ofertas.kafka.AlertaPrecio;
import cl.duoc.cometranqui.ofertas.kafka.OfertaMensaje;

@Service
public class OfertaService {

    private static final Logger log = LoggerFactory.getLogger(OfertaService.class);

    private final OfertaRepository repository;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, Object> kafka;
    private final String topicAlertas;
    private final int umbralAlertaPct;
    private final Duration maxAntiguedad;

    public OfertaService(OfertaRepository repository,
                         TransactionTemplate tx,
                         KafkaTemplate<String, Object> kafka,
                         @Value("${app.kafka.topic-alertas}") String topicAlertas,
                         @Value("${app.ofertas.umbral-alerta-pct}") int umbralAlertaPct,
                         @Value("${app.ofertas.max-antiguedad-horas}") long maxAntiguedadHoras) {
        this.repository = repository;
        this.tx = tx;
        this.kafka = kafka;
        this.topicAlertas = topicAlertas;
        this.umbralAlertaPct = umbralAlertaPct;
        this.maxAntiguedad = Duration.ofHours(maxAntiguedadHoras);
    }

    /**
     * Guarda la oferta (UPSERT) y, si el precio de oferta bajó al menos el umbral respecto del registro anterior,
     * publica una {@link AlertaPrecio}. No hay carreras entre hilos: la key del mensaje es el supermercado, así
     * que un mismo producto siempre llega por la misma partición y la procesa un solo hilo.
     */
    public void procesar(OfertaMensaje m) {
        Optional<AlertaPrecio> alerta = tx.execute(estado -> {
            Optional<Oferta> anterior = repository.findBySupermercadoAndSku(m.supermercado(), m.sku());
            int filas = repository.upsert(m.supermercado(), m.supermercadoNombre(), m.sku(), m.nombre(), m.marca(), m.categoria(),
                    m.restricciones().stream().map(Enum::name).sorted().collect(Collectors.joining(",", "{", "}")),
                    m.precioNormal(), m.precioOferta(), m.descuentoCalculado(), m.urlProducto(), m.urlImagen(),
                    m.vigenteHasta(), m.scrapeadoEn());
            if (filas == 0) {
                log.info("Mensaje más antiguo que lo guardado, se ignora: {}/{}", m.supermercado(), m.sku());
                return Optional.empty();
            }
            return anterior.flatMap(a -> evaluarAlerta(a, m));
        });
        if (alerta != null && alerta.isPresent()) {
            publicarAlerta(alerta.get());
        }
    }

    private Optional<AlertaPrecio> evaluarAlerta(Oferta anterior, OfertaMensaje nuevo) {
        int antes = anterior.getPrecioOferta();
        int ahora = nuevo.precioOferta();
        int baja = (int) Math.round((antes - ahora) * 100.0 / antes);
        if (ahora >= antes || baja < umbralAlertaPct) {
            return Optional.empty();
        }
        return Optional.of(new AlertaPrecio(nuevo.supermercado(), nuevo.sku(), nuevo.nombre(), antes, ahora, baja,
                nuevo.urlProducto(), Instant.now()));
    }

    /**
     * Envío síncrono después del commit en BD. Si falla, la excepción hace reintentar el mensaje, pero para
     * entonces el precio nuevo ya está guardado y no se vuelve a detectar la baja: las alertas son at-most-once.
     */
    private void publicarAlerta(AlertaPrecio alerta) {
        try {
            kafka.send(topicAlertas, alerta.supermercado() + ":" + alerta.sku(), alerta).get(10, TimeUnit.SECONDS);
            log.info("Alerta de precio: {}/{} bajó {}% ({} -> {})", alerta.supermercado(), alerta.sku(),
                    alerta.bajaPorcentaje(), alerta.precioAnterior(), alerta.precioNuevo());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido publicando alerta", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("No se pudo publicar la alerta " + alerta.sku(), e);
        }
    }

    public Page<Oferta> buscar(Restriccion restriccion, String supermercado, String categoria, int pagina, int tamano) {
        return repository.buscar(
                restriccion == null ? null : restriccion.name(),
                supermercado,
                categoria == null || categoria.isBlank() ? null : "%" + categoria.trim() + "%",
                Instant.now().minus(maxAntiguedad),
                PageRequest.of(pagina, tamano));
    }

    public List<OfertaRepository.ConteoSupermercado> supermercados() {
        return repository.contarPorSupermercado(Instant.now().minus(maxAntiguedad));
    }
}
