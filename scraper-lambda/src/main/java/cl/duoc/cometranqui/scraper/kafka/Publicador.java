package cl.duoc.cometranqui.scraper.kafka;

import cl.duoc.cometranqui.scraper.modelo.LocalDTO;
import cl.duoc.cometranqui.scraper.modelo.OfertaDTO;

/** Destino de lo scrapeado: ofertas y locales. */
public interface Publicador extends AutoCloseable {

    void publicar(OfertaDTO oferta);

    void publicar(LocalDTO local);

    /** Espera a que todo lo enviado quede confirmado. Devuelve cuántos envíos fallaron. */
    int flush();

    @Override
    void close();
}
