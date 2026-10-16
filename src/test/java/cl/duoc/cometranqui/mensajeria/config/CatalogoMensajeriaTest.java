package cl.duoc.cometranqui.mensajeria.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cl.duoc.cometranqui.mensajeria.config.MensajeriaProperties.Destino;
import cl.duoc.cometranqui.mensajeria.config.MensajeriaProperties.Reintentos;
import cl.duoc.cometranqui.mensajeria.config.MensajeriaProperties.Ruta;

class CatalogoMensajeriaTest {

    private static final Reintentos REINTENTOS = new Reintentos(3, Duration.ofSeconds(1), 2.0);

    private static Destino fijo(String topic) {
        return new Destino(topic, null, 3, Duration.ofDays(7), null, null, null);
    }

    private static Destino familia(String prefijo) {
        return new Destino(null, prefijo, 1, null, null, null, null);
    }

    @Test
    void resuelveTopicsGruposYPatronesDesdeElCatalogo() {
        CatalogoMensajeria catalogo = new CatalogoMensajeria(new MensajeriaProperties(
                Map.of("ofertas", fijo("ofertas.scrapeadas"), "notificaciones", familia("notificaciones.")),
                Map.of("persistir", new Ruta("ofertas", "grupo-ofertas", 3, null),
                        "notificar", new Ruta("notificaciones", "grupo-notif", null, null)),
                REINTENTOS));

        assertThat(catalogo.topicDeRuta("persistir")).isEqualTo("ofertas.scrapeadas");
        assertThat(catalogo.grupo("persistir")).isEqualTo("grupo-ofertas");
        assertThat(catalogo.concurrencia("notificar")).isEqualTo("1");
        assertThat("notificaciones.sin-gluten").matches(catalogo.patron("notificaciones"));
        assertThat("ofertas.scrapeadas").doesNotMatch(catalogo.patron("notificaciones"));
        assertThat(catalogo.topicsProtegidos()).containsExactly("ofertas.scrapeadas");
        assertThat(catalogo.destinoDeTopic("notificaciones.vegano")).isEqualTo("notificaciones");
    }

    @Test
    void unaRutaHaciaUnDestinoInexistenteImpideArrancar() {
        assertThatThrownBy(() -> new CatalogoMensajeria(new MensajeriaProperties(
                Map.of("ofertas", fijo("ofertas.scrapeadas")),
                Map.of("persistir", new Ruta("no-existe", "grupo", 1, null)),
                REINTENTOS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destino inexistente 'no-existe'");
    }

    @Test
    void unDestinoSinTopicNiPrefijoImpideArrancar() {
        assertThatThrownBy(() -> new CatalogoMensajeria(new MensajeriaProperties(
                Map.of("roto", new Destino(null, null, 1, null, null, null, null)),
                Map.of(),
                REINTENTOS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'topic' o 'prefijo'");
    }
}
