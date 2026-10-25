package co.ecomers.api.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class ErroresTest {

    @Test
    void cadaCodigoOracleTieneUnSoloMapeo() {
        Set<Integer> vistos = new HashSet<>();
        List<Integer> repetidos = new ArrayList<>();
        for (CodigoError c : CodigoError.values()) {
            for (int codigo : c.codigosOracle()) {
                if (!vistos.add(codigo)) {
                    repetidos.add(codigo);
                }
            }
        }
        assertThat(repetidos).isEmpty();
    }

    @Test
    void erroresDeBugEInconsistenciaSonInternos() {
        for (int c : new int[] {20010, 20011, 20100, 20124, 20126, 20127, 20169, 20200, 20209}) {
            assertThat(CodigoError.deOracle(c)).get().extracting(CodigoError::estado)
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Test
    void cadaCodigoDeAdministracionYCatalogoSeMapeaAlEstadoDelSpec() {
        // codigo Oracle -> nombre y estado HTTP (spec 2026-10-21-admin-catalogo-permisos, seccion 2)
        Object[][] esperados = {
                {20320, "PERMISO_INSUFICIENTE", HttpStatus.FORBIDDEN},
                {20321, "ACCESO_DENEGADO", HttpStatus.FORBIDDEN},
                {20322, "CUENTA_NO_GESTIONABLE", HttpStatus.CONFLICT},
                {20323, "TRABAJADOR_NO_ENCONTRADO", HttpStatus.NOT_FOUND},
                {20324, "PERMISO_INVALIDO", HttpStatus.BAD_REQUEST},
                {20325, "ACCESO_DENEGADO", HttpStatus.FORBIDDEN},
                {20330, "CATEGORIA_NO_ENCONTRADA", HttpStatus.NOT_FOUND},
                {20331, "CATEGORIA_PADRE_INVALIDO", HttpStatus.CONFLICT},
                {20332, "CATEGORIA_REPETIDA", HttpStatus.CONFLICT},
                {20333, "PRODUCTO_NO_ENCONTRADO", HttpStatus.NOT_FOUND},
                {20334, "IMPUESTO_INVALIDO", HttpStatus.BAD_REQUEST},
                {20335, "SKU_REPETIDO", HttpStatus.CONFLICT},
                {20336, "VARIANTE_NO_ENCONTRADA", HttpStatus.NOT_FOUND},
                {20337, "PRECIO_INVALIDO", HttpStatus.BAD_REQUEST},
        };
        for (Object[] e : esperados) {
            assertThat(CodigoError.deOracle((Integer) e[0]))
                    .as("ORA-%s", e[0]).get()
                    .satisfies(c -> {
                        assertThat(c.name()).isEqualTo(e[1]);
                        assertThat(c.estado()).isEqualTo(e[2]);
                    });
        }
    }

    @Test
    void lasImagenesTienenSusCodigos() {
        assertThat(CodigoError.deOracle(20338)).get().satisfies(c -> {
            assertThat(c.name()).isEqualTo("IMAGEN_URL_INVALIDA");
            assertThat(c.estado()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
        assertThat(CodigoError.TIPO_IMAGEN_INVALIDO.estado()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(CodigoError.ALMACEN_IMAGENES_NO_CONFIGURADO.estado()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(CodigoError.ALMACEN_IMAGENES_NO_DISPONIBLE.estado()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void elEmpleadoSinIdentificarEsUn403SinCodigoOracle() {
        assertThat(CodigoError.EMPLEADO_NO_IDENTIFICADO.estado()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(CodigoError.EMPLEADO_NO_IDENTIFICADO.codigosOracle()).isEmpty();
    }

    @RestController
    static class Controlador {
        @GetMapping("/stock")
        String stock() {
            throw new NegocioException(CodigoError.STOCK_INSUFICIENTE, "Sin stock de X");
        }

        @GetMapping("/bug")
        String bug() {
            throw new NegocioException(CodigoError.ERROR_INTERNO, "ORA-20169 detalle interno", 20169);
        }

        @PostMapping(value = "/json", consumes = "application/json")
        String json() {
            return "ok";
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("secreto");
        }
    }

    MockMvc mvc;

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.standaloneSetup(new Controlador()).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void errorDeNegocioUsaSuEstadoYCodigo() throws Exception {
        mvc.perform(get("/stock")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("STOCK_INSUFICIENTE"))
                .andExpect(jsonPath("$.mensaje").value("Sin stock de X"));
    }

    @Test
    void errorInternoNoExponeElMensajeDeOracle() throws Exception {
        mvc.perform(get("/bug")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.mensaje").value("Ocurrió un error inesperado"));
    }

    @Test
    void excepcionDesconocidaDa500Generico() throws Exception {
        mvc.perform(get("/boom")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.codigo").value("ERROR_INTERNO"))
                .andExpect(jsonPath("$.mensaje").value("Ocurrió un error inesperado"));
    }

    @Test
    void metodoNoPermitidoDa405() throws Exception {
        mvc.perform(post("/stock")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
    }

    @Test
    void tipoDeContenidoNoSoportadoDa415() throws Exception {
        mvc.perform(post("/json").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
    }

    @Test
    void rutaInexistenteDa404() throws Exception {
        mvc.perform(get("/no-existe")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }
}
