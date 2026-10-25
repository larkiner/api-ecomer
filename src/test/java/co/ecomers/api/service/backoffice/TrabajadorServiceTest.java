package co.ecomers.api.service.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.ecomers.api.dto.backoffice.CrearTrabajadorRequest;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.repository.backoffice.TrabajadorRepository;
import co.ecomers.api.security.InvalidacionSesionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class TrabajadorServiceTest {

    private static final EmpleadoActual ADMIN = new EmpleadoActual(1L, "admin@ecomers.co");

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final TrabajadorRepository repo = mock(TrabajadorRepository.class);
    private final InvalidacionSesionService invalidacion = mock(InvalidacionSesionService.class);
    private final TrabajadorService servicio = new TrabajadorService(repo, encoder, invalidacion);

    @Test
    void crearGuardaElHashBCryptElCorreoEnMinusculasYElNombreRecortado() {
        when(repo.crear(eq(ADMIN), anyString(), anyString(), anyString())).thenReturn(9L);

        var creado = servicio.crear(ADMIN, new CrearTrabajadorRequest("  Ana Bodega ", " Ana@Ecomers.co ", "Tienda!2026"));

        assertThat(creado.idEmpleado()).isEqualTo(9L);
        var hash = ArgumentCaptor.forClass(String.class);
        verify(repo).crear(eq(ADMIN), eq("Ana Bodega"), eq("ana@ecomers.co"), hash.capture());
        assertThat(hash.getValue()).startsWith("$2").isNotEqualTo("Tienda!2026");
        assertThat(encoder.matches("Tienda!2026", hash.getValue())).isTrue();
    }

    @Test
    void crearConContrasenaDebilNoTocaLaBase() {
        for (String debil : new String[] {"12345678", "aaaaaaaa", "password1"}) {
            assertThatThrownBy(() -> servicio.crear(ADMIN, new CrearTrabajadorRequest("Ana", "ana@ecomers.co", debil)))
                    .isInstanceOf(NegocioException.class)
                    .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.PASSWORD_DEBIL);
        }
        verifyNoInteractions(repo);
    }

    @Test
    void desactivarUnTrabajadorCierraSusSesionesPeroReactivarloNo() {
        when(repo.cambiarEstado(ADMIN, 5L, false)).thenReturn("ana@ecomers.co");
        when(repo.cambiarEstado(ADMIN, 5L, true)).thenReturn("ana@ecomers.co");

        servicio.cambiarEstado(ADMIN, 5L, true);
        verifyNoInteractions(invalidacion);

        servicio.cambiarEstado(ADMIN, 5L, false);
        verify(invalidacion).invalidarSesionesDe("ana@ecomers.co");
    }

    @Test
    void asignarYQuitarUnPermisoCierranLasSesionesDelTrabajador() {
        when(repo.asignarPermiso(ADMIN, 5L, "PRODUCTOS")).thenReturn("ana@ecomers.co");
        when(repo.quitarPermiso(ADMIN, 5L, "PRODUCTOS")).thenReturn("ana@ecomers.co");

        servicio.asignarPermiso(ADMIN, 5L, "PRODUCTOS");
        servicio.quitarPermiso(ADMIN, 5L, "PRODUCTOS");

        verify(invalidacion, org.mockito.Mockito.times(2)).invalidarSesionesDe("ana@ecomers.co");
    }

    @Test
    void siLaBaseRechazaElCambioNoSeCierranSesiones() {
        when(repo.quitarPermiso(any(), anyLong(), anyString()))
                .thenThrow(new NegocioException(CodigoError.CUENTA_NO_GESTIONABLE, "es admin", 20322));
        when(repo.cambiarEstado(any(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new NegocioException(CodigoError.TRABAJADOR_NO_ENCONTRADO, "no existe", 20323));

        assertThatThrownBy(() -> servicio.quitarPermiso(ADMIN, 1L, "PRODUCTOS")).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> servicio.cambiarEstado(ADMIN, 99L, false)).isInstanceOf(NegocioException.class);

        verifyNoInteractions(invalidacion);
    }

    @Test
    void listarDelegaEnElRepositorio() {
        servicio.listar(ADMIN);
        verify(repo).listar(ADMIN);
    }
}
