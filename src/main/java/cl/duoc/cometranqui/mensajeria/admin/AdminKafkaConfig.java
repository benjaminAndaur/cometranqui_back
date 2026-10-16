package cl.duoc.cometranqui.mensajeria.admin;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/** Cliente de administración de Kafka con la misma conexión (SASL/SCRAM) que el resto de la aplicación. */
@Configuration
class AdminKafkaConfig {

    @Bean(destroyMethod = "close")
    AdminClient adminClient(KafkaAdmin kafkaAdmin) {
        return AdminClient.create(kafkaAdmin.getConfigurationProperties());
    }
}
