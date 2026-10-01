package cl.duoc.cometranqui.scraper.modelo;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decide qué restricciones cumple un producto a partir de evidencia explícita en su nombre/categoría.
 *
 * <p>Criterio conservador: para alguien celíaco o con APLV un falso positivo es peor que un falso negativo,
 * así que el resultado de búsqueda del supermercado NO basta: el texto del producto debe decirlo.
 *
 * <p>Regla clave para APLV: "sin lactosa"/"deslactosado" NO es apto para APLV (la alergia es a la proteína
 * de la leche, que sigue presente). Por eso una leche sin lactosa queda como SIN_LACTOSA pero nunca APLV.
 */
public final class ClasificadorRestricciones {

    private static final Pattern SIN_GLUTEN = Pattern.compile(
            "\\b(sin|libre de|s/|no\\s*(\\+|mas))\\s*gluten\\b|\\bgluten\\s*free\\b|\\bapto\\s+celiac");
    private static final Pattern SIN_LACTOSA = Pattern.compile(
            "\\b(sin|libre de|0\\s*%)\\s*(gluten\\s+(y|ni)\\s+)?lactosa\\b|\\bdeslactosad[oa]s?\\b|\\blactose\\s*free\\b");
    private static final Pattern VEGANO = Pattern.compile(
            "\\bvegan[oa]s?\\b|\\bvegan\\b|\\bplant\\s*based\\b|\\b100\\s*%\\s*vegetal\\b");
    /** Productos sin proteína de leche: evidencia directa o bebidas/leches vegetales. */
    private static final Pattern SIN_LECHE = Pattern.compile(
            "\\b(sin|libre de)\\s*(leche|lacteos?)\\b|\\bbebida\\s+vegetal\\b"
                    + "|\\bleche\\s+(vegetal|de\\s+(almendras?|coco|avena|soya|soja|arroz))\\b");
    /** Indicios de lácteo de vaca: invalidan APLV aunque diga "sin lactosa". */
    private static final Pattern LACTEO = Pattern.compile(
            "\\b(leche|queso|quesillo|yogh?urt?|mantequilla|crema|manjar|suero|lactea|lacteo)s?\\b");
    /** Categorías/productos que no son alimentos (un shampoo "sin gluten" no le sirve a la web). */
    private static final Pattern NO_ALIMENTO = Pattern.compile(
            "\\b(mascotas?|perro|gato|shampoo|champu|jabon|panal(es)?|detergente|cosmetic|maquillaje|perfumeria"
                    + "|cuidado personal|limpieza)\\b");

    public Set<Restriccion> clasificar(String nombre, String categoria) {
        String texto = normalizar(nombre + " " + (categoria == null ? "" : categoria));
        EnumSet<Restriccion> resultado = EnumSet.noneOf(Restriccion.class);
        if (NO_ALIMENTO.matcher(texto).find()) {
            return resultado;
        }
        if (SIN_GLUTEN.matcher(texto).find()) {
            resultado.add(Restriccion.SIN_GLUTEN);
        }
        boolean sinLactosaExplicito = SIN_LACTOSA.matcher(texto).find();
        boolean vegano = VEGANO.matcher(texto).find();
        boolean sinLeche = vegano || SIN_LECHE.matcher(texto).find();
        if (vegano) {
            resultado.add(Restriccion.VEGANO);
        }
        if (sinLactosaExplicito || sinLeche) {
            resultado.add(Restriccion.SIN_LACTOSA);
        }
        // APLV: vegano (sin ingredientes animales) o evidencia de "sin leche" en algo que no sea un lácteo.
        // Un "sin lactosa" explícito delata un lácteo deslactosado -> nunca APLV.
        String sinNegaciones = texto.replaceAll("\\b(sin|libre de)\\s*(leche|lacteos?)\\b", " ")
                .replaceAll("\\bleche\\s+(vegetal|de\\s+(almendras?|coco|avena|soya|soja|arroz))\\b", " ");
        boolean aptoAplv = vegano || (sinLeche && !LACTEO.matcher(sinNegaciones).find());
        if (aptoAplv && !sinLactosaExplicito) {
            resultado.add(Restriccion.APLV);
        }
        return resultado;
    }

    static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
    }
}
