package cl.duoc.cometranqui.locales;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LocalRepository extends JpaRepository<Local, String> {

    /**
     * @param restriccion código (SIN_GLUTEN...) o null para cualquiera
     * @param nivelMaximo 1 = solo exclusivos, 3 = todos; se aplica a la restricción pedida
     * @param comuna búsqueda parcial o null
     */
    @Query(value = """
            SELECT DISTINCT l FROM Local l JOIN l.niveles n
            WHERE (CAST(:restriccion AS String) IS NULL OR KEY(n) = CAST(:restriccion AS String))
              AND VALUE(n) <= :nivelMaximo
              AND (CAST(:comuna AS String) IS NULL OR LOWER(l.comuna) LIKE LOWER(CONCAT('%', CAST(:comuna AS String), '%')))
            ORDER BY l.nombre
            """,
            countQuery = """
            SELECT COUNT(DISTINCT l) FROM Local l JOIN l.niveles n
            WHERE (CAST(:restriccion AS String) IS NULL OR KEY(n) = CAST(:restriccion AS String))
              AND VALUE(n) <= :nivelMaximo
              AND (CAST(:comuna AS String) IS NULL OR LOWER(l.comuna) LIKE LOWER(CONCAT('%', CAST(:comuna AS String), '%')))
            """)
    Page<Local> buscar(@Param("restriccion") String restriccion,
                       @Param("nivelMaximo") int nivelMaximo,
                       @Param("comuna") String comuna,
                       Pageable pageable);
}
