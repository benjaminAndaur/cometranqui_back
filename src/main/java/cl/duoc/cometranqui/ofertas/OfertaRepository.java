package cl.duoc.cometranqui.ofertas;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OfertaRepository extends JpaRepository<Oferta, Long> {

    Optional<Oferta> findBySupermercadoAndSku(String supermercado, String sku);

    /**
     * UPSERT idempotente por (supermercado, sku). El WHERE evita que un mensaje más antiguo
     * (por ejemplo, al hacer replay con --reset-offsets) pise datos más recientes.
     *
     * @param restricciones literal de arreglo PostgreSQL, ej. {@code {SIN_GLUTEN,APLV}}
     * @return filas afectadas (0 si el mensaje era más antiguo que lo guardado)
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO oferta (supermercado, sku, nombre, marca, categoria, restricciones,
                                precio_normal, precio_oferta, porcentaje_descuento,
                                url_producto, url_imagen, vigente_hasta, scrapeado_en, actualizado_en)
            VALUES (:supermercado, :sku, :nombre, :marca, :categoria, CAST(:restricciones AS text[]),
                    :precioNormal, :precioOferta, :porcentajeDescuento,
                    :urlProducto, :urlImagen, :vigenteHasta, :scrapeadoEn, now())
            ON CONFLICT (supermercado, sku) DO UPDATE SET
                nombre = EXCLUDED.nombre,
                marca = EXCLUDED.marca,
                categoria = EXCLUDED.categoria,
                restricciones = EXCLUDED.restricciones,
                precio_normal = EXCLUDED.precio_normal,
                precio_oferta = EXCLUDED.precio_oferta,
                porcentaje_descuento = EXCLUDED.porcentaje_descuento,
                url_producto = EXCLUDED.url_producto,
                url_imagen = EXCLUDED.url_imagen,
                vigente_hasta = EXCLUDED.vigente_hasta,
                scrapeado_en = EXCLUDED.scrapeado_en,
                actualizado_en = now()
            WHERE oferta.scrapeado_en <= EXCLUDED.scrapeado_en
            """)
    int upsert(@Param("supermercado") String supermercado,
               @Param("sku") String sku,
               @Param("nombre") String nombre,
               @Param("marca") String marca,
               @Param("categoria") String categoria,
               @Param("restricciones") String restricciones,
               @Param("precioNormal") int precioNormal,
               @Param("precioOferta") int precioOferta,
               @Param("porcentajeDescuento") int porcentajeDescuento,
               @Param("urlProducto") String urlProducto,
               @Param("urlImagen") String urlImagen,
               @Param("vigenteHasta") LocalDate vigenteHasta,
               @Param("scrapeadoEn") Instant scrapeadoEn);

    String FILTROS = """
            FROM oferta o
            WHERE o.precio_oferta < o.precio_normal
              AND o.scrapeado_en >= :desde
              AND (CAST(:restriccion AS text) IS NULL OR CAST(:restriccion AS text) = ANY(o.restricciones))
              AND (CAST(:supermercado AS text) IS NULL OR o.supermercado = CAST(:supermercado AS text))
              AND (CAST(:categoria AS text) IS NULL OR o.categoria ILIKE CAST(:categoria AS text))
            """;

    @Query(nativeQuery = true,
            value = "SELECT o.* " + FILTROS + " ORDER BY o.porcentaje_descuento DESC, o.id",
            countQuery = "SELECT count(*) " + FILTROS)
    Page<Oferta> buscar(@Param("restriccion") String restriccion,
                        @Param("supermercado") String supermercado,
                        @Param("categoria") String categoria,
                        @Param("desde") Instant desde,
                        Pageable pageable);

    @Query(nativeQuery = true, value = """
            SELECT o.supermercado AS supermercado, count(*) AS ofertas
            FROM oferta o
            WHERE o.precio_oferta < o.precio_normal AND o.scrapeado_en >= :desde
            GROUP BY o.supermercado
            ORDER BY o.supermercado
            """)
    List<ConteoSupermercado> contarPorSupermercado(@Param("desde") Instant desde);

    interface ConteoSupermercado {
        String getSupermercado();

        long getOfertas();
    }
}
