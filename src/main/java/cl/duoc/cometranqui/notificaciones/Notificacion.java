package cl.duoc.cometranqui.notificaciones;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Notificación para un usuario. Se inserta con UPSERT nativo (ver repositorio); JPA solo la lee y marca leída. */
@Entity
@Table(name = "notificacion")
public class Notificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String usuarioSub;
    private String eventoId;
    private String regla;
    private String titulo;
    private String detalle;
    private String url;
    private boolean leida;
    private Instant creadaEn;

    protected Notificacion() {
    }

    public void marcarLeida() {
        this.leida = true;
    }

    public Long getId() {
        return id;
    }

    public String getUsuarioSub() {
        return usuarioSub;
    }

    public String getEventoId() {
        return eventoId;
    }

    public String getRegla() {
        return regla;
    }

    public String getTitulo() {
        return titulo;
    }

    public String getDetalle() {
        return detalle;
    }

    public String getUrl() {
        return url;
    }

    public boolean isLeida() {
        return leida;
    }

    public Instant getCreadaEn() {
        return creadaEn;
    }
}
