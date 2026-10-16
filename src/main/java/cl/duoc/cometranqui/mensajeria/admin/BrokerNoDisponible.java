package cl.duoc.cometranqui.mensajeria.admin;

/** 503: Kafka no respondió a una operación de administración. */
public class BrokerNoDisponible extends RuntimeException {

    public BrokerNoDisponible(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
