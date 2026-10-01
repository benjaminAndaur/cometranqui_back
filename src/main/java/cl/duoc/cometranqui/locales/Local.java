package cl.duoc.cometranqui.locales;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import cl.duoc.cometranqui.locales.kafka.LocalMensaje;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "local")
public class Local {

    @Id
    private String id;
    private String fuente;
    private String nombre;
    private String tipo;
    private String direccion;
    private String comuna;
    private double latitud;
    private double longitud;
    private String sitioWeb;
    private String telefono;
    private String horario;
    private String urlFuente;
    private Instant scrapeadoEn;
    private Instant actualizadoEn;

    /** Código de Restriccion -> nivel (1, 2 o 3). */
    @ElementCollection
    @CollectionTable(name = "local_restriccion", joinColumns = @JoinColumn(name = "local_id"))
    @MapKeyColumn(name = "restriccion")
    @Column(name = "nivel")
    private Map<String, Integer> niveles = new HashMap<>();

    protected Local() {
    }

    public Local(String id) {
        this.id = id;
    }

    /** Copia el estado del mensaje. Reemplaza los niveles completos (lo que ya no informa la fuente se elimina). */
    public void actualizarDesde(LocalMensaje m) {
        this.fuente = m.fuente();
        this.nombre = m.nombre();
        this.tipo = m.tipo();
        this.direccion = m.direccion();
        this.comuna = m.comuna();
        this.latitud = m.latitud();
        this.longitud = m.longitud();
        this.sitioWeb = m.sitioWeb();
        this.telefono = m.telefono();
        this.horario = m.horario();
        this.urlFuente = m.urlFuente();
        this.scrapeadoEn = m.scrapeadoEn();
        this.actualizadoEn = Instant.now();
        this.niveles.clear();
        m.niveles().forEach((restriccion, nivel) -> this.niveles.put(restriccion.name(), nivel));
    }

    public String getId() {
        return id;
    }

    public String getFuente() {
        return fuente;
    }

    public String getNombre() {
        return nombre;
    }

    public String getTipo() {
        return tipo;
    }

    public String getDireccion() {
        return direccion;
    }

    public String getComuna() {
        return comuna;
    }

    public double getLatitud() {
        return latitud;
    }

    public double getLongitud() {
        return longitud;
    }

    public String getSitioWeb() {
        return sitioWeb;
    }

    public String getTelefono() {
        return telefono;
    }

    public String getHorario() {
        return horario;
    }

    public String getUrlFuente() {
        return urlFuente;
    }

    public Instant getScrapeadoEn() {
        return scrapeadoEn;
    }

    public Map<String, Integer> getNiveles() {
        return niveles;
    }
}
