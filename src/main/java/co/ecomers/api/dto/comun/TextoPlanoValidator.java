package co.ecomers.api.dto.comun;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Valida {@link TextoPlano}. */
public class TextoPlanoValidator implements ConstraintValidator<TextoPlano, String> {

    private boolean multilinea;

    @Override
    public void initialize(TextoPlano anotacion) {
        this.multilinea = anotacion.multilinea();
    }

    @Override
    public boolean isValid(String valor, ConstraintValidatorContext contexto) {
        return valor == null || valor.codePoints().allMatch(this::permitido);
    }

    private boolean permitido(int c) {
        if (multilinea && (c == '\n' || c == '\r' || c == '\t')) {
            return true;
        }
        return !Character.isISOControl(c) && !esControlBidireccional(c);
    }

    /** U+202A a U+202E y U+2066 a U+2069: cambian el orden en que se ve el texto. */
    static boolean esControlBidireccional(int c) {
        return (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
    }
}
