package cl.duoc.cometranqui.notificaciones;

import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Suscripciones (usuario, restricción). Tabla simple: se maneja con SQL directo, sin entidad JPA. */
@Repository
public class SuscripcionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SuscripcionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> restriccionesDe(String usuario) {
        return jdbc.queryForList("SELECT restriccion FROM suscripcion WHERE usuario_sub = :u ORDER BY restriccion",
                new MapSqlParameterSource("u", usuario), String.class);
    }

    public List<String> usuariosSuscritosA(Collection<String> restricciones) {
        if (restricciones.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("SELECT DISTINCT usuario_sub FROM suscripcion WHERE restriccion IN (:r)",
                new MapSqlParameterSource("r", restricciones), String.class);
    }

    public void reemplazar(String usuario, Collection<String> restricciones) {
        jdbc.update("DELETE FROM suscripcion WHERE usuario_sub = :u", new MapSqlParameterSource("u", usuario));
        for (String r : restricciones) {
            jdbc.update("INSERT INTO suscripcion (usuario_sub, restriccion) VALUES (:u, :r) ON CONFLICT DO NOTHING",
                    new MapSqlParameterSource("u", usuario).addValue("r", r));
        }
    }
}
