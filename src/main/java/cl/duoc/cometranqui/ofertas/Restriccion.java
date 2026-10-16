package cl.duoc.cometranqui.ofertas;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Optional;

/**
 * Restricciones alimentarias que maneja la web (mismas etiquetas que el filtro del frontend).
 */
public enum Restriccion {
    SIN_GLUTEN("Sin Gluten"),
    SIN_LACTOSA("Sin Lactosa"),
    APLV("APLV"),
    VEGANO("Vegano");

    private final String etiqueta;

    Restriccion(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
    }

    /** Acepta el código ("SIN_GLUTEN") o la etiqueta del frontend ("Sin Gluten"), sin importar mayúsculas ni tildes. */
    public static Optional<Restriccion> desde(String valor) {
        if (valor == null || valor.isBlank()) {
            return Optional.empty();
        }
        String normalizado = normalizar(valor);
        return Arrays.stream(values())
                .filter(r -> normalizar(r.name()).equals(normalizado) || normalizar(r.etiqueta).equals(normalizado))
                .findFirst();
    }

    static String normalizar(String valor) {
        return Normalizer.normalize(valor, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[\\s_-]+", "")
                .toUpperCase();
    }
}
