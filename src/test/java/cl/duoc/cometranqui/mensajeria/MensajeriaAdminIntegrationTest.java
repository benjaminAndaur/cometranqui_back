package cl.duoc.cometranqui.mensajeria;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import cl.duoc.cometranqui.IntegracionKafkaBase;

/**
 * API de administración contra Kafka real: seguridad, operaciones y validación (un caso válido y uno inválido por
 * regla). Los errores se responden como Problem Details con la lista de campos inválidos.
 */
class MensajeriaAdminIntegrationTest extends IntegracionKafkaBase {

    private static final String BASE = "/api/admin/mensajeria";

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String cuerpo) {
        return b.contentType(MediaType.APPLICATION_JSON).content(cuerpo).with(admin());
    }

    // ── Seguridad ─────────────────────────────────────────────────────────────

    @Test
    void sinTokenEs401YSinRolAdminEs403() throws Exception {
        mvc.perform(get(BASE + "/destinos")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/destinos").with(jwt())).andExpect(status().isForbidden());
    }

    // ── Consultas ─────────────────────────────────────────────────────────────

    @Test
    void listaDestinosDelCatalogoProtegidosYGruposConLag() throws Exception {
        mvc.perform(get(BASE + "/destinos").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.nombre == 'ofertas.scrapeadas')].protegido").value(true))
                .andExpect(jsonPath("$[?(@.nombre == 'ofertas.scrapeadas')].particiones").value(3))
                .andExpect(jsonPath("$[?(@.nombre == 'notificaciones.sin-gluten')].reglas[0]").value("alertas-sin-gluten"))
                .andExpect(jsonPath("$[?(@.nombre == 'notificaciones.sin-gluten')].protegido").value(false));
        mvc.perform(get(BASE + "/grupos").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].ruta", hasItem("persistir-ofertas")))
                .andExpect(jsonPath("$[*].grupo", hasItem("cometranqui-fallidos")));
        mvc.perform(get(BASE + "/catalogo").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinos['ofertas-scrapeadas'].topic").value("ofertas.scrapeadas"))
                .andExpect(jsonPath("$.prefijosPermitidos[0]").value("notificaciones."));
    }

    // ── Destinos: crear, modificar, eliminar ──────────────────────────────────

    @Test
    void creaModificaYEliminaUnDestino() throws Exception {
        mvc.perform(json(post(BASE + "/destinos"), """
                        {"nombre":"notificaciones.prueba-ciclo","particiones":1,"retencionHoras":24,"limpieza":"delete"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value("notificaciones.prueba-ciclo"))
                .andExpect(jsonPath("$.retencionHoras").value(24))
                .andExpect(jsonPath("$.claveCatalogo").value("notificaciones"));
        mvc.perform(json(patch(BASE + "/destinos/notificaciones.prueba-ciclo"), """
                        {"retencionHoras":48,"particiones":2}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retencionHoras").value(48))
                .andExpect(jsonPath("$.particiones").value(2));
        mvc.perform(json(patch(BASE + "/destinos/notificaciones.prueba-ciclo"), """
                        {"particiones":1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("particiones"));
        mvc.perform(delete(BASE + "/destinos/notificaciones.prueba-ciclo").with(admin()))
                .andExpect(status().isNoContent());
        mvc.perform(delete(BASE + "/destinos/notificaciones.prueba-ciclo").with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rechazaDestinosInvalidosConCamposClaros() throws Exception {
        mvc.perform(json(post(BASE + "/destinos"), """
                        {"nombre":"Mal Nombre!","particiones":50}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.errores[*].campo", hasItem("nombre")))
                .andExpect(jsonPath("$.errores[*].campo", hasItem("particiones")));
        mvc.perform(json(post(BASE + "/destinos"), """
                        {"nombre":"otros.cosa","particiones":1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].mensaje").value(containsString("notificaciones.")));
        mvc.perform(json(post(BASE + "/destinos"), """
                        {"nombre":"notificaciones.compacto","particiones":1,"limpieza":"compact","retencionHoras":5}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("retencionHoras"));
        mvc.perform(json(post(BASE + "/destinos"), """
                        {"nombre":"notificaciones.x","particiones":1,"limpieza":"borrar"}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post(BASE + "/destinos"), """
                        {"nombre":"notificaciones.vegano","particiones":1}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("ya existe")));
    }

    @Test
    void protegeLosDestinosDelCatalogoYLosQueUsanReglas() throws Exception {
        mvc.perform(delete(BASE + "/destinos/ofertas.scrapeadas").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("protegido")));
        mvc.perform(json(patch(BASE + "/destinos/ofertas.dlt"), """
                        {"retencionHoras":1}"""))
                .andExpect(status().isConflict());
        mvc.perform(delete(BASE + "/destinos/notificaciones.sin-gluten").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("alertas-sin-gluten")));
    }

    // ── Reglas ────────────────────────────────────────────────────────────────

    @Test
    void creaActivaYEliminaUnaRegla() throws Exception {
        mvc.perform(json(post(BASE + "/destinos"), """
                        {"nombre":"notificaciones.ofertas-jumbo","particiones":1}"""))
                .andExpect(status().isCreated());
        String id = mvc.perform(json(post(BASE + "/reglas"), """
                        {"nombre":"jumbo-grandes-descuentos","destinoOrigen":"alertas-precio","tienda":"JUMBO",
                         "descuentoMinimo":40,"topicDestino":"notificaciones.ofertas-jumbo","prioridad":5}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activa").value(true))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":(\\d+).*", "$1");
        mvc.perform(json(patch(BASE + "/reglas/" + id + "/estado"), """
                        {"activa":false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activa").value(false));
        // Aunque esté inactiva, la regla sigue referenciando el destino
        mvc.perform(delete(BASE + "/destinos/notificaciones.ofertas-jumbo").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("(inactiva)")));
        mvc.perform(delete(BASE + "/reglas/" + id).with(admin())).andExpect(status().isNoContent());
        mvc.perform(delete(BASE + "/destinos/notificaciones.ofertas-jumbo").with(admin())).andExpect(status().isNoContent());
    }

    @Test
    void rechazaReglasInvalidas() throws Exception {
        mvc.perform(json(post(BASE + "/reglas"), """
                        {"nombre":"sin-condiciones","destinoOrigen":"alertas-precio","topicDestino":"notificaciones.vegano"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].mensaje").value(containsString("al menos una condición")));
        mvc.perform(json(post(BASE + "/reglas"), """
                        {"nombre":"keto","destinoOrigen":"alertas-precio","restriccion":"KETO","topicDestino":"notificaciones.vegano"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[*].campo", hasItem("restriccion")));
        mvc.perform(json(post(BASE + "/reglas"), """
                        {"nombre":"origen-malo","destinoOrigen":"ofertas-scrapeadas","restriccion":"VEGANO","topicDestino":"notificaciones.vegano"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[*].campo", hasItem("destinoOrigen")));
        mvc.perform(json(post(BASE + "/reglas"), """
                        {"nombre":"destino-inexistente","destinoOrigen":"alertas-precio","restriccion":"VEGANO","topicDestino":"notificaciones.no-existe"}"""))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(BASE + "/reglas"), """
                        {"nombre":"Nombre Malo","destinoOrigen":"alertas-precio","descuentoMinimo":150,"topicDestino":"notificaciones.vegano"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[*].campo", hasItem("nombre")))
                .andExpect(jsonPath("$.errores[*].campo", hasItem("descuentoMinimo")));
        mvc.perform(json(post(BASE + "/reglas"), """
                        {"nombre":"alertas-vegano","destinoOrigen":"alertas-precio","restriccion":"VEGANO","topicDestino":"notificaciones.vegano"}"""))
                .andExpect(status().isConflict());
    }

    // ── Pruebas y fallidos ────────────────────────────────────────────────────

    @Test
    void validaElMensajeDePruebaYDevuelveLaTraza() throws Exception {
        mvc.perform(json(post(BASE + "/pruebas"), """
                        {"producto":"","tienda":"jumbo","restricciones":[],"bajaPorcentaje":0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.length()").value(4));
        mvc.perform(json(post(BASE + "/pruebas"), """
                        {"producto":"Pan sin gluten","tienda":"JUMBO","restricciones":["Sin Gluten"],"bajaPorcentaje":30}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinosEsperados[0]").value("notificaciones.sin-gluten"))
                .andExpect(jsonPath("$.evaluaciones[?(@.regla == 'alertas-sin-gluten')].coincide").value(true));
    }

    @Test
    void validaElFiltroDeFallidos() throws Exception {
        mvc.perform(get(BASE + "/fallidos").param("estado", "RARO").with(admin()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/fallidos").param("estado", "pendiente").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendientes").isNumber());
        mvc.perform(post(BASE + "/fallidos/999999/reprocesar").with(admin()))
                .andExpect(status().isNotFound());
    }
}
