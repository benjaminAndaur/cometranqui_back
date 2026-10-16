package cl.duoc.cometranqui.mensajeria.consumidores;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import cl.duoc.cometranqui.mensajeria.MensajeInvalidoException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/** Bean Validation de los mensajes entrantes; un mensaje inválido es un error definitivo (va a la DLT). */
@Component
class ValidadorMensajes {

    private final Validator validator;

    ValidadorMensajes(Validator validator) {
        this.validator = validator;
    }

    <T> T validar(T mensaje) {
        if (mensaje == null) {
            throw new MensajeInvalidoException("Mensaje vacío");
        }
        Set<ConstraintViolation<T>> errores = validator.validate(mensaje);
        if (!errores.isEmpty()) {
            throw new MensajeInvalidoException(errores.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
        return mensaje;
    }
}
