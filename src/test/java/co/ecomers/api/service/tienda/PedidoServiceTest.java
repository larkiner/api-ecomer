package co.ecomers.api.service.tienda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.ecomers.api.dto.tienda.CancelarPedidoRequest;
import co.ecomers.api.dto.tienda.CrearPedidoRequest;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.repository.tienda.CarritoRepository;
import co.ecomers.api.repository.tienda.PedidoRepository;
import co.ecomers.api.dto.tienda.AgregarItemRequest;
import co.ecomers.api.dto.tienda.CambiarCantidadRequest;
import org.junit.jupiter.api.Test;

class PedidoServiceTest {

    private static final ClienteActual CLIENTE = new ClienteActual(7L, "ana@correo.com");

    private final PedidoRepository pedidos = mock(PedidoRepository.class);
    private final CarritoRepository carritos = mock(CarritoRepository.class);
    private final PedidoService pedidoService = new PedidoService(pedidos);
    private final CarritoService carritoService = new CarritoService(carritos);

    @Test
    void crearSinCuerpoUsaLaDireccionPrincipal() {
        when(pedidos.crear(CLIENTE, null)).thenReturn(1001L);
        assertThat(pedidoService.crear(CLIENTE, null).idPedido()).isEqualTo(1001L);
    }

    @Test
    void crearConDireccion() {
        when(pedidos.crear(CLIENTE, 5L)).thenReturn(1002L);
        assertThat(pedidoService.crear(CLIENTE, new CrearPedidoRequest(5L)).idPedido()).isEqualTo(1002L);
    }

    @Test
    void cancelarRecortaElMotivoYConvierteElVacioEnNulo() {
        pedidoService.cancelar(CLIENTE, 10L, new CancelarPedidoRequest("  ya no lo quiero  "));
        verify(pedidos).cancelar(CLIENTE, 10L, "ya no lo quiero");

        pedidoService.cancelar(CLIENTE, 11L, new CancelarPedidoRequest("   "));
        verify(pedidos).cancelar(CLIENTE, 11L, null);

        pedidoService.cancelar(CLIENTE, 12L, null);
        verify(pedidos).cancelar(CLIENTE, 12L, null);
    }

    @Test
    void idDePedidoNoPositivoEsNoEncontradoSinTocarLaBase() {
        assertThatThrownBy(() -> pedidoService.cancelar(CLIENTE, 0L, null))
                .isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.PEDIDO_NO_ENCONTRADO);
        verifyNoInteractions(pedidos);
    }

    @Test
    void carritoDelegaConElClienteAutenticado() {
        carritoService.agregar(CLIENTE, new AgregarItemRequest(5L, 2));
        carritoService.cambiarCantidad(CLIENTE, 5L, new CambiarCantidadRequest(4));
        carritoService.quitar(CLIENTE, 5L);
        carritoService.vaciar(CLIENTE);

        verify(carritos).agregarItem(CLIENTE, 5L, 2);
        verify(carritos).cambiarCantidad(CLIENTE, 5L, 4);
        verify(carritos).quitarItem(CLIENTE, 5L);
        verify(carritos).vaciar(CLIENTE);
    }

    @Test
    void carritoConVarianteNoPositivaEsValidacion() {
        assertThatThrownBy(() -> carritoService.quitar(CLIENTE, -1L))
                .isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.VALIDACION);
        verifyNoInteractions(carritos);
    }
}
