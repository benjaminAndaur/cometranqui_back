package cl.duoc.cometranqui.ofertas.kafka;

/** Mensaje (oferta o local) que no pasa Bean Validation. No se reintenta: va directo a {@code ofertas.dlt}. */
public class MensajeInvalidoException extends RuntimeException {

    public MensajeInvalidoException(String mensaje) {
        super(mensaje);
    }
}
