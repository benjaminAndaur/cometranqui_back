package cl.duoc.cometranqui.ofertas;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entidad de solo lectura desde JPA: las escrituras se hacen con el UPSERT nativo de {@link OfertaRepository}.
 */
@Entity
@Table(name = "oferta")
public class Oferta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String supermercado;
    private String sku;
    private String nombre;
    private String marca;
    private String categoria;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] restricciones;

    private Integer precioNormal;
    private Integer precioOferta;
    private Integer porcentajeDescuento;
    private String urlProducto;
    private String urlImagen;
    private LocalDate vigenteHasta;
    private Instant scrapeadoEn;
    private Instant actualizadoEn;

    protected Oferta() {
    }

    public Long getId() {
        return id;
    }

    public String getSupermercado() {
        return supermercado;
    }

    public String getSku() {
        return sku;
    }

    public String getNombre() {
        return nombre;
    }

    public String getMarca() {
        return marca;
    }

    public String getCategoria() {
        return categoria;
    }

    public String[] getRestricciones() {
        return restricciones;
    }

    public Integer getPrecioNormal() {
        return precioNormal;
    }

    public Integer getPrecioOferta() {
        return precioOferta;
    }

    public Integer getPorcentajeDescuento() {
        return porcentajeDescuento;
    }

    public String getUrlProducto() {
        return urlProducto;
    }

    public String getUrlImagen() {
        return urlImagen;
    }

    public LocalDate getVigenteHasta() {
        return vigenteHasta;
    }

    public Instant getScrapeadoEn() {
        return scrapeadoEn;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
