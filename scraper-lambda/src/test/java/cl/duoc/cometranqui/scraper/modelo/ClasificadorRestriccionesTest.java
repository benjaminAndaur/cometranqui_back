package cl.duoc.cometranqui.scraper.modelo;

import static cl.duoc.cometranqui.scraper.modelo.Restriccion.APLV;
import static cl.duoc.cometranqui.scraper.modelo.Restriccion.SIN_GLUTEN;
import static cl.duoc.cometranqui.scraper.modelo.Restriccion.SIN_LACTOSA;
import static cl.duoc.cometranqui.scraper.modelo.Restriccion.VEGANO;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClasificadorRestriccionesTest {

    private final ClasificadorRestricciones c = new ClasificadorRestricciones();

    @Test
    void detectaSinGlutenConVariantes() {
        assertThat(c.clasificar("Pan Molde Multisemillas Sin Gluten 480 g", null)).containsExactly(SIN_GLUTEN);
        assertThat(c.clasificar("Galletas Libre de Gluten", null)).containsExactly(SIN_GLUTEN);
        assertThat(c.clasificar("Tostadas S/Gluten", null)).containsExactly(SIN_GLUTEN);
        // Marca real en Unimarc
        assertThat(c.clasificar("Pan de molde blanco No + Gluten 600 g", null)).containsExactly(SIN_GLUTEN);
    }

    @Test
    void sinEvidenciaEnElTextoNoSeClasifica() {
        // El buscador del supermercado la devuelve para "sin gluten", pero la avena puede tener trazas
        assertThat(c.clasificar("Avena Integral 1 kg", "Desayuno / Cereales")).isEmpty();
    }

    @Test
    void lecheSinLactosaNoEsAptaParaAplv() {
        assertThat(c.clasificar("Leche Semidescremada Sin Lactosa 1 L", "Lácteos")).containsExactly(SIN_LACTOSA);
        assertThat(c.clasificar("Leche Entera Deslactosada 1 L", null)).containsExactly(SIN_LACTOSA);
        assertThat(c.clasificar("Quesillo Sin Lactosa Colun 320 g", null)).doesNotContain(APLV);
    }

    @Test
    void bebidaVegetalEsAptaParaAplvYSinLactosa() {
        assertThat(c.clasificar("Bebida Vegetal Almendra Sin Azúcar 1 L", null)).containsExactlyInAnyOrder(APLV, SIN_LACTOSA);
        assertThat(c.clasificar("Leche de Coco 400 ml", null)).containsExactlyInAnyOrder(APLV, SIN_LACTOSA);
    }

    @Test
    void veganoImplicaSinLecheAunqueImiteUnLacteo() {
        assertThat(c.clasificar("Queso Vegano Cheddar 200 g", null)).containsExactlyInAnyOrder(VEGANO, APLV, SIN_LACTOSA);
        assertThat(c.clasificar("Hamburguesa Plant Based 100 g", null)).contains(VEGANO);
    }

    @Test
    void productosNoAlimentariosSeDescartan() {
        assertThat(c.clasificar("Shampoo Sin Gluten 400 ml", "Cuidado Personal")).isEmpty();
        assertThat(c.clasificar("Alimento Vegano para Perro 2 kg", "Mascotas")).isEmpty();
    }

    @Test
    void variasRestriccionesALaVez() {
        assertThat(c.clasificar("Galletas Veganas Sin Gluten", null)).containsExactlyInAnyOrder(SIN_GLUTEN, VEGANO, APLV, SIN_LACTOSA);
        // Caso real de Jumbo
        assertThat(c.clasificar("Alimento de Coco Samui Libre de Gluten y Lactosa 400 ml", null))
                .containsExactlyInAnyOrder(SIN_GLUTEN, SIN_LACTOSA);
    }
}
