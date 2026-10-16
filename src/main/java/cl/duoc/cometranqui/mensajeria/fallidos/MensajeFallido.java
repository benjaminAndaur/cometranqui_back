package cl.duoc.cometranqui.mensajeria.fallidos;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** Mensaje apartado en la DLT con su diagnóstico. Se inserta con SQL nativo idempotente (ver repositorio). */
@Entity
@Table(name = "mensaje_fallido")
public class MensajeFallido {

    public enum Estado { PENDIENTE, REPROCESADO, DESCARTADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String topicOrigen;
    private Integer particionOrigen;
    private Long offsetOrigen;
    private String grupoOrigen;
    private String clave;
    private String contenido;
    private String excepcion;
    private String mensajeError;
    private String traza;
    @Enumerated(EnumType.STRING)
    private Estado estado;
    private Instant recibidoEn;
    private Instant actualizadoEn;

    protected MensajeFallido() {
    }

    @PreUpdate
    void alActualizar() {
        actualizadoEn = Instant.now();
    }

    public void cambiarEstado(Estado nuevo) {
        this.estado = nuevo;
    }

    public Long getId() {
        return id;
    }

    public String getTopicOrigen() {
        return topicOrigen;
    }

    public Integer getParticionOrigen() {
        return particionOrigen;
    }

    public Long getOffsetOrigen() {
        return offsetOrigen;
    }

    public String getGrupoOrigen() {
        return grupoOrigen;
    }

    public String getClave() {
        return clave;
    }

    public String getContenido() {
        return contenido;
    }

    public String getExcepcion() {
        return excepcion;
    }

    public String getMensajeError() {
        return mensajeError;
    }

    public String getTraza() {
        return traza;
    }

    public Estado getEstado() {
        return estado;
    }

    public Instant getRecibidoEn() {
        return recibidoEn;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
