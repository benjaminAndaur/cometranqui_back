package cl.duoc.cometranqui.mensajeria.fallidos;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MensajeFallidoRepository extends JpaRepository<MensajeFallido, Long> {

    /** Idempotente por (topic, partición, offset) de origen: releer la DLT no duplica registros. */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO mensaje_fallido (topic_origen, particion_origen, offset_origen, grupo_origen, clave, contenido,
                                         excepcion, mensaje_error, traza)
            VALUES (:topic, :particion, :offset, :grupo, :clave, :contenido, :excepcion, :mensaje, :traza)
            ON CONFLICT (topic_origen, particion_origen, offset_origen) DO NOTHING
            """)
    int registrar(@Param("topic") String topic, @Param("particion") Integer particion, @Param("offset") Long offset,
                  @Param("grupo") String grupo, @Param("clave") String clave, @Param("contenido") String contenido,
                  @Param("excepcion") String excepcion, @Param("mensaje") String mensaje, @Param("traza") String traza);

    Page<MensajeFallido> findByEstadoOrderByRecibidoEnDesc(MensajeFallido.Estado estado, Pageable pageable);

    Page<MensajeFallido> findAllByOrderByRecibidoEnDesc(Pageable pageable);

    long countByEstado(MensajeFallido.Estado estado);
}
