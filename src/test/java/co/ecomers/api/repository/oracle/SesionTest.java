package co.ecomers.api.repository.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Types;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SesionTest {

    private final Connection con = mock(Connection.class);
    private final CallableStatement cs = mock(CallableStatement.class);
    private final Sesion sesion = new Sesion(mock(DataSource.class), con);

    @Test
    void funcionTextoDevuelveElVarchar2DeLaFuncion() throws Exception {
        when(con.prepareCall("{ ? = call ECOMERS.PKG_EMPLEADO.asignar_permiso(?, ?, ?) }")).thenReturn(cs);
        when(cs.getString(1)).thenReturn("ana@correo.com");

        String correo = sesion.funcionTexto("ECOMERS.PKG_EMPLEADO.asignar_permiso", 1L, 3L, "CATEGORIAS");

        assertThat(correo).isEqualTo("ana@correo.com");
        verify(cs).registerOutParameter(1, Types.VARCHAR);
        verify(cs).setObject(2, 1L);
        verify(cs).setObject(3, 3L);
        verify(cs).setObject(4, "CATEGORIAS");
        verify(cs).execute();
    }

    @Test
    void funcionTextoConResultadoNuloDevuelveNulo() throws Exception {
        when(con.prepareCall("{ ? = call ECOMERS.PKG_X.f() }")).thenReturn(cs);
        when(cs.getString(1)).thenReturn(null);

        assertThat(sesion.funcionTexto("ECOMERS.PKG_X.f")).isNull();
    }
}
