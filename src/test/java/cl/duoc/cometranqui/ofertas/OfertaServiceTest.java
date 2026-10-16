package cl.duoc.cometranqui.ofertas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.duoc.cometranqui.eventos.AlertaPrecio;
import cl.duoc.cometranqui.eventos.OfertaMensaje;
import cl.duoc.cometranqui.eventos.PublicadorEventos;

/**
 * La lógica de negocio se prueba sin Kafka: basta un {@link PublicadorEventos} en memoria. Es el efecto concreto de
 * separar el dominio de la mensajería.
 */
class OfertaServiceTest {

    private final List<AlertaPrecio> publicadas = new ArrayList<>();
    private OfertaRepository repository;
    private OfertaService service;

    @BeforeEach
    void preparar() {
        repository = mock(OfertaRepository.class);
        when(repository.upsert(anyString(), any(), anyString(), anyString(), any(), any(), anyString(), anyInt(),
                anyInt(), anyInt(), anyString(), any(), any(), any())).thenReturn(1);
        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        service = new OfertaService(repository, tx, publicadas::add, 20, 48);
    }

    @Test
    void bajaDeVeintePorCientoOMasPublicaAlertaConRestricciones() {
        conPrecioAnterior(5000);

        service.procesar(oferta(3500));

        assertThat(publicadas).singleElement().satisfies(a -> {
            assertThat(a.bajaPorcentaje()).isEqualTo(30);
            assertThat(a.restricciones()).containsExactly(Restriccion.SIN_GLUTEN);
            assertThat(a.clave()).isEqualTo("JUMBO:SKU-1");
            assertThat(a.eventoId()).startsWith("JUMBO:SKU-1:3500:");
        });
    }

    @Test
    void bajaMenorAlUmbralNoPublica() {
        conPrecioAnterior(5000);
        service.procesar(oferta(4500));
        assertThat(publicadas).isEmpty();
    }

    @Test
    void ofertaNuevaSinRegistroAnteriorNoPublica() {
        when(repository.findBySupermercadoAndSku("JUMBO", "SKU-1")).thenReturn(Optional.empty());
        service.procesar(oferta(1000));
        assertThat(publicadas).isEmpty();
    }

    @Test
    void mensajeMasAntiguoQueLoGuardadoNoPublica() {
        conPrecioAnterior(5000);
        when(repository.upsert(anyString(), any(), anyString(), anyString(), any(), any(), anyString(), anyInt(),
                anyInt(), anyInt(), anyString(), any(), any(), eq(Instant.EPOCH))).thenReturn(0);

        service.procesar(new OfertaMensaje("JUMBO", "Jumbo", "SKU-1", "Pan Sin Gluten", null, null,
                Set.of(Restriccion.SIN_GLUTEN), 6000, 3000, null, "https://example.com/p", null, null, Instant.EPOCH));

        assertThat(publicadas).isEmpty();
    }

    private void conPrecioAnterior(int precio) {
        Oferta anterior = mock(Oferta.class);
        when(anterior.getPrecioOferta()).thenReturn(precio);
        when(repository.findBySupermercadoAndSku("JUMBO", "SKU-1")).thenReturn(Optional.of(anterior));
    }

    private static OfertaMensaje oferta(int precioOferta) {
        return new OfertaMensaje("JUMBO", "Jumbo", "SKU-1", "Pan Sin Gluten", "Marca", "Panadería",
                Set.of(Restriccion.SIN_GLUTEN), 6000, precioOferta, null, "https://example.com/p", null, null,
                Instant.parse("2026-10-16T09:00:00Z"));
    }
}
