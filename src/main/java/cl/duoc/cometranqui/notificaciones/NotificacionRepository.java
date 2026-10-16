package cl.duoc.cometranqui.notificaciones;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificacionRepository extends JpaRepository<Notificacion, Long> {

    /** Idempotente: si el usuario ya tiene la notificación de ese evento, no hace nada y devuelve 0. */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO notificacion (usuario_sub, evento_id, regla, titulo, detalle, url)
            VALUES (:usuario, :eventoId, :regla, :titulo, :detalle, :url)
            ON CONFLICT (usuario_sub, evento_id) DO NOTHING
            """)
    int insertarSiNoExiste(@Param("usuario") String usuario, @Param("eventoId") String eventoId,
                           @Param("regla") String regla, @Param("titulo") String titulo,
                           @Param("detalle") String detalle, @Param("url") String url);

    List<Notificacion> findTop20ByUsuarioSubOrderByCreadaEnDesc(String usuarioSub);

    long countByUsuarioSubAndLeidaFalse(String usuarioSub);

    long countByEventoId(String eventoId);

    Optional<Notificacion> findByIdAndUsuarioSub(Long id, String usuarioSub);

    @Modifying
    @Query("UPDATE Notificacion n SET n.leida = true WHERE n.usuarioSub = :usuario AND n.leida = false")
    int marcarTodasLeidas(@Param("usuario") String usuario);
}
