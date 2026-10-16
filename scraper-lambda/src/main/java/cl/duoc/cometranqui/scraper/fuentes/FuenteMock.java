package cl.duoc.cometranqui.scraper.fuentes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import cl.duoc.cometranqui.scraper.modelo.OfertaDTO;
import cl.duoc.cometranqui.scraper.modelo.Restriccion;

/**
 * Plan B para la demo: ofertas fijas y realistas, sin salir a internet. Se activa con FUENTES_ACTIVAS=MOCK.
 * Los SKU llevan prefijo MOCK- para poder limpiarlos: {@code DELETE FROM oferta WHERE sku LIKE 'MOCK-%'}.
 *
 * <p>Con {@code bajaPrecio=true} (env MOCK_BAJA_PRECIO=true) los primeros 3 productos bajan 25% adicional,
 * lo que dispara mensajes en {@code ofertas.alertas} en el consumer.
 */
public class FuenteMock implements FuenteOfertas {

    private record Base(String supermercado, String sku, String nombre, String marca, String categoria,
                        EnumSet<Restriccion> restricciones, int normal, int oferta, String slug) {
    }

    private static final EnumSet<Restriccion> SG = EnumSet.of(Restriccion.SIN_GLUTEN);
    private static final EnumSet<Restriccion> SL = EnumSet.of(Restriccion.SIN_LACTOSA);
    private static final EnumSet<Restriccion> VEG = EnumSet.of(Restriccion.VEGANO, Restriccion.APLV, Restriccion.SIN_LACTOSA);
    private static final EnumSet<Restriccion> VEGETAL = EnumSet.of(Restriccion.APLV, Restriccion.SIN_LACTOSA);

    private static final List<Base> CATALOGO = List.of(
            new Base("JUMBO", "MOCK-J001", "Pan Molde Multisemillas Sin Gluten 480 g", "Cuisine & Co", "Panadería y Pastelería", SG, 4990, 3990, "pan-molde-sin-gluten"),
            new Base("JUMBO", "MOCK-J002", "Spaghetti de Arroz Sin Gluten 400 g", "Schär", "Despensa", SG, 3290, 2590, "spaghetti-sin-gluten"),
            new Base("JUMBO", "MOCK-J003", "Galletas de Arroz Sabor Chocolate Sin Gluten 115 g", "Deluxe", "Despensa", SG, 2940, 2205, "galletas-arroz-sin-gluten"),
            new Base("JUMBO", "MOCK-J004", "Leche Semidescremada Sin Lactosa 1 L", "Colun", "Lácteos", SL, 1490, 1190, "leche-sin-lactosa"),
            new Base("JUMBO", "MOCK-J005", "Hamburguesa Vegana de Garbanzo 4 un", "NotCo", "Congelados", VEG, 5490, 4390, "hamburguesa-vegana"),
            new Base("JUMBO", "MOCK-J006", "Bebida Vegetal Almendra Sin Azúcar 1 L", "Natrue", "Bebidas", VEGETAL, 2790, 2232, "bebida-vegetal-almendra"),
            new Base("SANTA_ISABEL", "MOCK-S001", "Pasta Fusilli Sin Gluten 400 g", "Cuisine & Co", "Despensa", SG, 2390, 2100, "fusilli-sin-gluten"),
            new Base("SANTA_ISABEL", "MOCK-S002", "Harina Sin Gluten Premezcla 1 kg", "Sin Gluten Chile", "Despensa", SG, 4590, 3490, "harina-sin-gluten"),
            new Base("SANTA_ISABEL", "MOCK-S003", "Yogur Natural Sin Lactosa 4 un", "Soprole", "Lácteos", SL, 2290, 1790, "yogur-sin-lactosa"),
            new Base("SANTA_ISABEL", "MOCK-S004", "Quesillo Sin Lactosa 320 g", "Colun", "Lácteos", SL, 3160, 2590, "quesillo-sin-lactosa"),
            new Base("SANTA_ISABEL", "MOCK-S005", "Bebida Vegetal Avena 1 L", "Not Milk", "Bebidas", VEGETAL, 2730, 2190, "bebida-vegetal-avena"),
            new Base("SANTA_ISABEL", "MOCK-S006", "Mantequilla Vegana 200 g", "Naturli", "Lácteos", VEG, 3990, 3290, "mantequilla-vegana"),
            new Base("LIDER", "MOCK-L001", "Tostadas de Arroz Integral Sin Gluten 130 g", "Santiveri", "Despensa", SG, 2890, 2290, "tostadas-sin-gluten"),
            new Base("LIDER", "MOCK-L002", "Helado Vegano Chocolate 1 L", "Frozen Vegan", "Congelados", VEG, 5990, 4490, "helado-vegano"),
            new Base("LIDER", "MOCK-L003", "Leche Entera Deslactosada 1 L", "Soprole", "Lácteos", SL, 1390, 1090, "leche-deslactosada"),
            new Base("UNIMARC", "MOCK-U001", "Mix de Cereales Sin Gluten 300 g", "Nature's Path", "Desayuno", SG, 4290, 3290, "cereal-sin-gluten"),
            new Base("UNIMARC", "MOCK-U002", "Chorizo Vegano 260 g", "Doña Rosita", "Congelados", VEG, 3090, 2781, "chorizo-vegano"),
            new Base("UNIMARC", "MOCK-U003", "Crema de Coco para Cocinar 400 ml", "Cocomi", "Despensa", VEGETAL, 2490, 1990, "crema-coco"),
            new Base("TOTTUS", "MOCK-T001", "Pizza Sin Gluten Congelada 350 g", "Schär", "Congelados", SG, 6990, 5490, "pizza-sin-gluten"),
            new Base("TOTTUS", "MOCK-T002", "Bebida Vegetal Soya Calcio 1 L", "Ades", "Bebidas", VEGETAL, 2190, 1690, "bebida-soya"));

    private final boolean bajaPrecio;

    public FuenteMock(boolean bajaPrecio) {
        this.bajaPrecio = bajaPrecio;
    }

    private static final Map<String, String> NOMBRES = Map.of(
            "JUMBO", "Jumbo", "SANTA_ISABEL", "Santa Isabel", "LIDER", "Líder", "UNIMARC", "Unimarc", "TOTTUS", "Tottus");

    @Override
    public String nombre() {
        return "MOCK";
    }

    @Override
    public String nombreVisible() {
        return "Datos de demostración";
    }

    @Override
    public List<OfertaDTO> obtener(Restriccion restriccion) {
        Instant ahora = Instant.now();
        LocalDate vigencia = LocalDate.now(ZoneId.of("America/Santiago")).plusDays(7);
        return CATALOGO.stream()
                .filter(b -> b.restricciones().contains(restriccion))
                .map(b -> {
                    int oferta = bajaPrecio && CATALOGO.indexOf(b) < 3 ? (int) Math.round(b.oferta() * 0.75) : b.oferta();
                    return new OfertaDTO(b.supermercado(), NOMBRES.get(b.supermercado()), b.sku(), b.nombre(), b.marca(), b.categoria(),
                            EnumSet.copyOf(b.restricciones()), b.normal(), oferta,
                            OfertaDTO.calcularDescuento(b.normal(), oferta),
                            "https://example.com/demo/" + b.slug(), null, vigencia, ahora);
                })
                .toList();
    }
}
