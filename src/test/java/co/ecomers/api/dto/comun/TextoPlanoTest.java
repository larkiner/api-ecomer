package co.ecomers.api.dto.comun;

import static org.assertj.core.api.Assertions.assertThat;

import co.ecomers.api.dto.backoffice.AjusteInventarioRequest;
import co.ecomers.api.dto.backoffice.CategoriaRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/** Los textos que escribe una persona no admiten caracteres de control ni invisibles. */
class TextoPlanoTest {

    private final Validator validador = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void unNombreNormalConTildesEsValido() {
        assertThat(validador.validate(new CategoriaRequest("Portátiles y accesorios ñ", null, null))).isEmpty();
    }

    @Test
    void unNombreConCaracterDeControlOInvisibleSeRechaza() {
        assertThat(validador.validate(new CategoriaRequest("Celu\u0000lares", null, null))).isNotEmpty();
        assertThat(validador.validate(new CategoriaRequest("Linea\nnueva", null, null))).isNotEmpty();
        // U+202E invierte el texto: "Apple‮elppa" se veria distinto en pantalla
        assertThat(validador.validate(new CategoriaRequest("Apple‮elppa", null, null))).isNotEmpty();
    }

    @Test
    void unTextoMultilineaAdmiteSaltosDeLineaPeroNoControles() {
        assertThat(validador.validate(new AjusteInventarioRequest(3, "Conteo fisico\nbodega 2"))).isEmpty();
        assertThat(validador.validate(new AjusteInventarioRequest(3, "Conteo\u0007fisico"))).isNotEmpty();
    }

    @Test
    void unIdDePadreNegativoSeRechaza() {
        assertThat(validador.validate(new CategoriaRequest("Celulares", -4L, null))).isNotEmpty();
    }
}
