package cl.duoc.cometranqui.mensajeria.enrutamiento;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Regla de distribución por contenido: si un evento del destino {@code destinoOrigen} cumple todas las condiciones
 * presentes (restricción, descuento mínimo, tienda), se reenvía a {@code topicDestino}. Las condiciones nulas no filtran.
 */
@Entity
@Table(name = "regla_distribucion")
public class ReglaDistribucion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nombre;
    private String destinoOrigen;
    private String restriccion;
    private Integer descuentoMinimo;
    private String tienda;
    private String topicDestino;
    private boolean activa = true;
    private int prioridad = 100;
    private Instant creadaEn;
    private Instant actualizadaEn;

    protected ReglaDistribucion() {
    }

    public ReglaDistribucion(String nombre) {
        this.nombre = nombre;
    }

    @PrePersist
    void alCrear() {
        creadaEn = Instant.now();
        actualizadaEn = creadaEn;
    }

    @PreUpdate
    void alActualizar() {
        actualizadaEn = Instant.now();
    }

    public void configurar(String destinoOrigen, String restriccion, Integer descuentoMinimo, String tienda,
                           String topicDestino, boolean activa, int prioridad) {
        this.destinoOrigen = destinoOrigen;
        this.restriccion = restriccion;
        this.descuentoMinimo = descuentoMinimo;
        this.tienda = tienda;
        this.topicDestino = topicDestino;
        this.activa = activa;
        this.prioridad = prioridad;
    }

    public void setActiva(boolean activa) {
        this.activa = activa;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getDestinoOrigen() {
        return destinoOrigen;
    }

    public String getRestriccion() {
        return restriccion;
    }

    public Integer getDescuentoMinimo() {
        return descuentoMinimo;
    }

    public String getTienda() {
        return tienda;
    }

    public String getTopicDestino() {
        return topicDestino;
    }

    public boolean isActiva() {
        return activa;
    }

    public int getPrioridad() {
        return prioridad;
    }

    public Instant getCreadaEn() {
        return creadaEn;
    }

    public Instant getActualizadaEn() {
        return actualizadaEn;
    }
}
