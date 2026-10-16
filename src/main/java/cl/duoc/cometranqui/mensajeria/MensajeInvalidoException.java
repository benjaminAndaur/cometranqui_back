package cl.duoc.cometranqui.mensajeria;

/** Mensaje que no pasa Bean Validation: error definitivo, no se reintenta y va directo a la DLT. */
public class MensajeInvalidoException extends RuntimeException {

    public MensajeInvalidoException(String mensaje) {
        super(mensaje);
    }
}
