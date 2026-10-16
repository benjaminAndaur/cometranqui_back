package cl.duoc.cometranqui.scraper.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RobotsTxtTest {

    /** Extracto real de www.jumbo.cl/robots.txt */
    private static final String JUMBO = """
            User-agent: *
            Disallow: *?map=
            Disallow: *?PS=
            Disallow: *sc=*
            Disallow: *?*?
            Disallow: */*checkout*

            User-agent: Teoma
            Disallow: /
            """;

    @Test
    void permiteLaBusquedaPaginadaDeJumbo() {
        RobotsTxt robots = RobotsTxt.parsear(JUMBO, ClienteHttp.USER_AGENT);
        assertThat(robots.permite("/busqueda?ft=sin%20gluten")).isTrue();
        assertThat(robots.permite("/busqueda?ft=sin%20gluten&page=3")).isTrue();
    }

    @Test
    void bloqueaRutasProhibidas() {
        RobotsTxt robots = RobotsTxt.parsear(JUMBO, ClienteHttp.USER_AGENT);
        assertThat(robots.permite("/lacteos?map=c")).isFalse();
        assertThat(robots.permite("/busqueda?ft=x&sc=1")).isFalse();
        assertThat(robots.permite("/api/checkout/pub")).isFalse();
    }

    @Test
    void reglaEspecificaDelBotYAllowMasLargo() {
        String txt = """
                User-agent: *
                Disallow: /

                User-agent: ComeTranquiBot
                Disallow: /search
                Allow: /search/ofertas
                """;
        RobotsTxt robots = RobotsTxt.parsear(txt, ClienteHttp.USER_AGENT);
        assertThat(robots.permite("/productos")).isTrue();
        assertThat(robots.permite("/search?q=x")).isFalse();
        assertThat(robots.permite("/search/ofertas")).isTrue();
    }
}
