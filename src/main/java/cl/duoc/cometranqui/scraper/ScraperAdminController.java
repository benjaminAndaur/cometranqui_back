package cl.duoc.cometranqui.scraper;

import java.time.Instant;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * Ejecución on-demand del scraper (rol ADMIN, ya exigido por SecurityConfig para /api/admin/**).
 * Invoca la Lambda en modo asíncrono (Event): responde 202 de inmediato y las ofertas llegan luego por Kafka.
 * En EC2 usa las credenciales de LabInstanceProfile/LabRole.
 */
@RestController
@RequestMapping("/api/admin/scraper")
public class ScraperAdminController {

    private final LambdaClient lambda;
    private final String funcion;

    public ScraperAdminController(LambdaClient lambda, @Value("${app.scraper.lambda-name}") String funcion) {
        this.lambda = lambda;
        this.funcion = funcion;
    }

    @PostMapping("/run")
    ResponseEntity<Map<String, Object>> ejecutar(Authentication auth) {
        // Mismo formato que un evento de EventBridge para que la Lambda lo lea como ScheduledEvent
        String evento = """
                {"source":"cometranqui-api","detail-type":"Ejecucion manual","detail":{"solicitadoPor":"%s"}}"""
                .formatted(auth.getName().replace("\"", ""));
        try {
            InvokeResponse r = lambda.invoke(b -> b.functionName(funcion)
                    .invocationType(InvocationType.EVENT)
                    .payload(SdkBytes.fromUtf8String(evento)));
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "estado", "invocado",
                    "funcion", funcion,
                    "statusCode", r.statusCode(),
                    "solicitadoEn", Instant.now().toString()));
        } catch (SdkException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "estado", "error",
                    "funcion", funcion,
                    "detalle", e.getMessage()));
        }
    }

    @Configuration
    static class LambdaConfig {
        @Bean(destroyMethod = "close")
        LambdaClient lambdaClient(@Value("${app.scraper.region}") String region) {
            return LambdaClient.builder().region(Region.of(region)).build();
        }
    }
}
