package cl.duoc.cometranqui.mensajeria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.domain.PageRequest;

import cl.duoc.cometranqui.IntegracionKafkaBase;
import cl.duoc.cometranqui.eventos.OfertaMensaje;
import cl.duoc.cometranqui.mensajeria.admin.PruebasMensajeriaService;
import cl.duoc.cometranqui.mensajeria.admin.Solicitudes.SolicitudPrueba;
import cl.duoc.cometranqui.mensajeria.fallidos.MensajeFallido;
import cl.duoc.cometranqui.mensajeria.fallidos.MensajeFallidoRepository;
import cl.duoc.cometranqui.mensajeria.fallidos.MensajesFallidosService;
import cl.duoc.cometranqui.notificaciones.EntregaNotificacionRepository;
import cl.duoc.cometranqui.notificaciones.NotificacionRepository;
import cl.duoc.cometranqui.notificaciones.SuscripcionRepository;
import cl.duoc.cometranqui.ofertas.OfertaRepository;
import cl.duoc.cometranqui.ofertas.OfertaService;

/**
 * Escenarios de la rúbrica sobre Kafka y PostgreSQL reales:
 * distribución por reglas hasta el consumidor previsto, alerta sin ruta, idempotencia, error recuperable,
 * reintentos agotados, error definitivo y registro + reproceso de fallidos.
 */
class MensajeriaFlujosIntegrationTest extends IntegracionKafkaBase {

    private static final Duration ESPERA = Duration.ofSeconds(30);

    @SpyBean
    OfertaService ofertaService;

    @Autowired
    OfertaRepository ofertas;

    @Autowired
    SuscripcionRepository suscripciones;

    @Autowired
    NotificacionRepository notificaciones;

    @Autowired
    EntregaNotificacionRepository entregas;

    @Autowired
    MensajeFallidoRepository fallidos;

    @Autowired
    MensajesFallidosService fallidosService;

    @Autowired
    PruebasMensajeriaService pruebas;

    // ── Distribución (criterio 2) ─────────────────────────────────────────────

    @Test
    void bajaDePrecioSinGlutenLlegaAlSuscritoPorLaReglaDeCeliacos() {
        suscripciones.reemplazar("usuario-celiaco", List.of("SIN_GLUTEN"));
        suscripciones.reemplazar("usuario-vegano", List.of("VEGANO"));

        enviar("ofertas.scrapeadas", "JUMBO", oferta("JUMBO", "RUTA-1", "SIN_GLUTEN", 5000, 4000, Instant.now().minusSeconds(60)));
        await().atMost(ESPERA).until(() -> ofertas.findBySupermercadoAndSku("JUMBO", "RUTA-1").isPresent());
        enviar("ofertas.scrapeadas", "JUMBO", oferta("JUMBO", "RUTA-1", "SIN_GLUTEN", 5000, 2800, Instant.now()));

        await().atMost(ESPERA).untilAsserted(() -> assertThat(notificaciones.findTop20ByUsuarioSubOrderByCreadaEnDesc("usuario-celiaco"))
                .anySatisfy(n -> {
                    assertThat(n.getTitulo()).contains("Bajó 30%").contains("Pan RUTA-1");
                    assertThat(n.getRegla()).isEqualTo("alertas-sin-gluten");
                }));
        // El suscrito a otra restricción no recibe esta alerta
        assertThat(notificaciones.findTop20ByUsuarioSubOrderByCreadaEnDesc("usuario-vegano"))
                .noneMatch(n -> n.getTitulo().contains("RUTA-1"));
    }

    @Test
    void mensajeDePruebaMuestraPorQueReglaYAQueConsumidorLlega() {
        var resultado = pruebas.enviar(new SolicitudPrueba("Hamburguesa vegana", "JUMBO", List.of("Vegano"), 35));

        assertThat(resultado.destinosEsperados()).containsExactly("notificaciones.vegano");
        assertThat(resultado.evaluaciones()).anySatisfy(e -> {
            assertThat(e.regla()).isEqualTo("alertas-sin-gluten");
            assertThat(e.coincide()).isFalse();
            assertThat(e.motivo()).contains("no es Sin Gluten");
        });
        await().atMost(ESPERA).untilAsserted(() -> assertThat(pruebas.seguimiento(resultado.eventoId(), List.of("alertas-vegano")))
                .singleElement().satisfies(l -> {
                    assertThat(l.recibida()).isTrue();
                    assertThat(l.topicDestino()).isEqualTo("notificaciones.vegano");
                    assertThat(l.consumidor()).isEqualTo("cometranqui-notificaciones");
                }));
    }

    @Test
    void alertaQueNoCumpleNingunaReglaNoSeEnruta() {
        var resultado = pruebas.enviar(new SolicitudPrueba("Leche sin lactosa", "JUMBO", List.of("Sin Lactosa"), 10));

        assertThat(resultado.destinosEsperados()).isEmpty();
        assertThat(resultado.evaluaciones()).filteredOn(e -> e.regla().equals("alertas-sin-lactosa"))
                .singleElement().satisfies(e -> assertThat(e.motivo()).contains("bajó 10% (mínimo 20%)"));
    }

    @Test
    void notificacionDuplicadaNoSeRepite() {
        suscripciones.reemplazar("usuario-duplicado", List.of("APLV"));
        String evento = """
                {"eventoId":"dup-1|alertas-aplv","regla":"alertas-aplv","restriccion":"APLV","prueba":false,
                 "enrutadoEn":"2026-10-16T10:00:00Z","alerta":{"eventoId":"dup-1","supermercado":"JUMBO",
                 "supermercadoNombre":"Jumbo","sku":"DUP-1","nombre":"Bebida vegetal","restricciones":["APLV"],
                 "precioAnterior":3000,"precioNuevo":2000,"bajaPorcentaje":33,"urlProducto":"https://example.com/d",
                 "detectadaEn":"2026-10-16T10:00:00Z"}}""";
        enviar("notificaciones.aplv", "JUMBO:DUP-1", evento);
        enviar("notificaciones.aplv", "JUMBO:DUP-1", evento);

        await().atMost(ESPERA).until(() -> entregas.buscar("dup-1|alertas-aplv").isPresent());
        await().pollDelay(Duration.ofSeconds(2)).atMost(ESPERA).untilAsserted(() ->
                assertThat(notificaciones.countByEventoId("dup-1|alertas-aplv")).isEqualTo(1));
    }

    // ── Reintentos y errores (criterio 5) ─────────────────────────────────────

    @Test
    void errorRecuperableSeReintentaYTerminaBien() {
        doThrow(new TransientDataAccessResourceException("BD no disponible"))
                .doThrow(new TransientDataAccessResourceException("BD no disponible"))
                .doCallRealMethod()
                .when(ofertaService).procesar(argThat((OfertaMensaje m) -> m != null && m.sku().equals("REC-1")));

        enviar("ofertas.scrapeadas", "UNIMARC", oferta("UNIMARC", "REC-1", "VEGANO", 3000, 2500, Instant.now()));

        await().atMost(ESPERA).until(() -> ofertas.findBySupermercadoAndSku("UNIMARC", "REC-1").isPresent());
        verify(ofertaService, times(3)).procesar(argThat((OfertaMensaje m) -> m != null && m.sku().equals("REC-1")));
        assertThat(fallidosDe("REC-1")).isEmpty();
    }

    @Test
    void reintentosAgotadosApartanElMensajeConSuDiagnostico() {
        doThrow(new TransientDataAccessResourceException("BD caída"))
                .when(ofertaService).procesar(argThat((OfertaMensaje m) -> m != null && m.sku().equals("AGOT-1")));

        enviar("ofertas.scrapeadas", "UNIMARC", oferta("UNIMARC", "AGOT-1", "VEGANO", 3000, 2500, Instant.now()));

        await().atMost(ESPERA).untilAsserted(() -> assertThat(fallidosDe("AGOT-1")).singleElement().satisfies(f -> {
            assertThat(f.getExcepcion()).contains("TransientDataAccessResourceException");
            assertThat(f.getMensajeError()).contains("BD caída");
            assertThat(f.getTopicOrigen()).isEqualTo("ofertas.scrapeadas");
            assertThat(f.getGrupoOrigen()).isEqualTo("cometranqui-api-ofertas");
            assertThat(f.getEstado()).isEqualTo(MensajeFallido.Estado.PENDIENTE);
        }));
        // 1 intento + 3 reintentos del catálogo. "Al menos": si hay un rebalanceo del grupo a mitad de los reintentos,
        // Kafka reentrega el mensaje (at-least-once) y el contador vuelve a empezar; igual termina UNA vez en la DLT.
        verify(ofertaService, atLeast(4)).procesar(argThat((OfertaMensaje m) -> m != null && m.sku().equals("AGOT-1")));
    }

    @Test
    void errorDefinitivoVaDirectoALaDltSinReintentos() {
        doThrow(new DataIntegrityViolationException("viola una restricción de la BD"))
                .when(ofertaService).procesar(argThat((OfertaMensaje m) -> m != null && m.sku().equals("DEF-1")));

        enviar("ofertas.scrapeadas", "UNIMARC", oferta("UNIMARC", "DEF-1", "VEGANO", 3000, 2500, Instant.now()));

        await().atMost(ESPERA).until(() -> !fallidosDe("DEF-1").isEmpty());
        verify(ofertaService, times(1)).procesar(argThat((OfertaMensaje m) -> m != null && m.sku().equals("DEF-1")));
    }

    @Test
    void jsonInvalidoSeRegistraYSePuedeReprocesarODescartar() {
        enviar("ofertas.scrapeadas", "UNIMARC", "{roto-reproceso");
        await().atMost(ESPERA).until(() -> fallidosPorContenido("{roto-reproceso").size() == 1);
        MensajeFallido original = fallidosPorContenido("{roto-reproceso").get(0);
        assertThat(original.getExcepcion()).contains("DeserializationException");

        fallidosService.reprocesar(original.getId());

        // Sigue siendo inválido: vuelve a la DLT como registro nuevo; el original queda REPROCESADO
        await().atMost(ESPERA).until(() -> fallidosPorContenido("{roto-reproceso").size() == 2);
        assertThat(fallidos.findById(original.getId()).orElseThrow().getEstado()).isEqualTo(MensajeFallido.Estado.REPROCESADO);
        MensajeFallido nuevo = fallidosPorContenido("{roto-reproceso").stream()
                .filter(f -> f.getEstado() == MensajeFallido.Estado.PENDIENTE).findFirst().orElseThrow();
        assertThat(fallidosService.descartar(nuevo.getId()).getEstado()).isEqualTo(MensajeFallido.Estado.DESCARTADO);
    }

    private List<MensajeFallido> fallidosDe(String sku) {
        return fallidos.findAllByOrderByRecibidoEnDesc(PageRequest.of(0, 200)).stream()
                .filter(f -> f.getContenido() != null && f.getContenido().contains("\"sku\":\"" + sku + "\""))
                .toList();
    }

    private List<MensajeFallido> fallidosPorContenido(String contenido) {
        return fallidos.findAllByOrderByRecibidoEnDesc(PageRequest.of(0, 200)).stream()
                .filter(f -> contenido.equals(f.getContenido()))
                .toList();
    }

    private static String oferta(String tienda, String sku, String restriccion, int normal, int oferta, Instant fecha) {
        return """
                {"supermercado":"%s","supermercadoNombre":null,"sku":"%s","nombre":"Pan %s","marca":"Marca",
                 "categoria":"Despensa","restricciones":["%s"],"precioNormal":%d,"precioOferta":%d,
                 "porcentajeDescuento":0,"urlProducto":"https://example.com/%s","urlImagen":null,
                 "vigenteHasta":null,"scrapeadoEn":"%s"}"""
                .formatted(tienda, sku, sku, restriccion, normal, oferta, sku, fecha);
    }
}
