package co.ecomers.api.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.ecomers.api.repository.oracle.OracleDb;
import co.ecomers.api.repository.oracle.Sesion;
import java.util.function.Consumer;
import java.util.function.Function;

/** Conexion simulada: ejecuta el trabajo del repositorio sobre una {@link Sesion} simulada, sin base de datos. */
public final class DbFalsa<T extends OracleDb> {

    public final T db;
    public final Sesion sesion = mock(Sesion.class);

    @SuppressWarnings("unchecked")
    private DbFalsa(Class<T> tipo) {
        db = mock(tipo);
        when(db.enTransaccion(any(), any(Function.class)))
                .thenAnswer(i -> ((Function<Sesion, Object>) i.getArgument(1)).apply(sesion));
        when(db.lectura(any(), any(Function.class)))
                .thenAnswer(i -> ((Function<Sesion, Object>) i.getArgument(1)).apply(sesion));
        doAnswer(i -> {
            ((Consumer<Sesion>) i.getArgument(1)).accept(sesion);
            return null;
        }).when(db).enTransaccionSinResultado(any(), any(Consumer.class));
    }

    public static <T extends OracleDb> DbFalsa<T> de(Class<T> tipo) {
        return new DbFalsa<>(tipo);
    }
}
