package cl.duoc.cometranqui.scraper.kafka;

import cl.duoc.cometranqui.scraper.modelo.OfertaDTO;

/** Destino de las ofertas scrapeadas. */
public interface PublicadorOfertas extends AutoCloseable {

    void publicar(OfertaDTO oferta);

    /** Espera a que todo lo enviado quede confirmado. Devuelve cuántos envíos fallaron. */
    int flush();

    @Override
    void close();
}
