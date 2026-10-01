package cl.duoc.cometranqui.ofertas.kafka;

/** Mensaje que no pasa Bean Validation. No se reintenta: va directo a {@code ofertas.dlt}. */
public class OfertaInvalidaException extends RuntimeException {

    public OfertaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
