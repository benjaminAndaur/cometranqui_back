package cl.duoc.cometranqui.notificaciones;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Traza de eventos recibidos por el consumidor de notificaciones (una fila por evento, idempotente). */
@Repository
public class EntregaNotificacionRepository {

    public record Entrega(String eventoId, String topic, String regla, int destinatarios, boolean prueba,
                          Instant recibidaEn) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public EntregaNotificacionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void registrar(String eventoId, String topic, String regla, int destinatarios, boolean prueba) {
        jdbc.update("""
                INSERT INTO entrega_notificacion (evento_id, topic, regla, destinatarios, prueba)
                VALUES (:e, :t, :r, :d, :p) ON CONFLICT (evento_id) DO NOTHING
                """, new MapSqlParameterSource("e", eventoId).addValue("t", topic).addValue("r", regla)
                .addValue("d", destinatarios).addValue("p", prueba));
    }

    public Optional<Entrega> buscar(String eventoId) {
        return jdbc.query("SELECT * FROM entrega_notificacion WHERE evento_id = :e",
                new MapSqlParameterSource("e", eventoId),
                (rs, i) -> new Entrega(rs.getString("evento_id"), rs.getString("topic"), rs.getString("regla"),
                        rs.getInt("destinatarios"), rs.getBoolean("prueba"),
                        rs.getTimestamp("recibida_en").toInstant()))
                .stream().findFirst();
    }
}
