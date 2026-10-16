package cl.duoc.cometranqui.eventos;

/**
 * Puerto de salida del dominio: cómo los servicios de negocio emiten eventos sin conocer la tecnología de
 * mensajería. La implementación (Kafka, topic, clave, serialización) vive en {@code mensajeria.publicacion}.
 *
 * <p>Gracias a esta interfaz los servicios se prueban con un publicador en memoria, sin levantar Kafka, y cambiar
 * un topic o el broker no obliga a modificar la lógica de negocio.
 */
public interface PublicadorEventos {

    void publicar(AlertaPrecio alerta);
}
