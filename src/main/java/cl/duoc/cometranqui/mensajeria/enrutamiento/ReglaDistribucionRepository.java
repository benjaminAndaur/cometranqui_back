package cl.duoc.cometranqui.mensajeria.enrutamiento;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReglaDistribucionRepository extends JpaRepository<ReglaDistribucion, Long> {

    List<ReglaDistribucion> findByDestinoOrigenAndActivaTrueOrderByPrioridadAscNombreAsc(String destinoOrigen);

    List<ReglaDistribucion> findAllByOrderByPrioridadAscNombreAsc();

    List<ReglaDistribucion> findByTopicDestinoAndActivaTrue(String topicDestino);

    boolean existsByNombre(String nombre);

    boolean existsByNombreAndIdNot(String nombre, Long id);
}
