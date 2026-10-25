package co.ecomers.api.dto.comun;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Texto que escribe una persona (nombres, descripciones, observaciones): rechaza caracteres de control y los invisibles
 * que invierten la dirección del texto (se usan para disfrazar un nombre en pantalla). Con {@code multilinea = true}
 * admite saltos de línea y tabulaciones. {@code null} es válido: lo obligatorio lo dice {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = TextoPlanoValidator.class)
@Target({METHOD, FIELD, PARAMETER, ANNOTATION_TYPE, TYPE_USE})
@Retention(RUNTIME)
public @interface TextoPlano {

    String message() default "No admite caracteres de control ni caracteres invisibles";

    boolean multilinea() default false;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
