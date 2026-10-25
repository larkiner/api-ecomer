package co.ecomers.api.service.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.ecomers.api.client.correo.CorreoClient;
import co.ecomers.api.client.erp.ErpClient;
import co.ecomers.api.model.EventoCola;
import co.ecomers.api.model.PedidoParaNotificar;
import co.ecomers.api.repository.integracion.ColaRepository;
import co.ecomers.api.repository.integracion.PedidoNotificacionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class ConsumidoresColaTest {

    private final ColaRepository cola = mock(ColaRepository.class);
    private final PedidoNotificacionRepository pedidos = mock(PedidoNotificacionRepository.class);
    private final CorreoClient correo = mock(CorreoClient.class);
    private final ErpClient erp = mock(ErpClient.class);
    private final ProcesadorColaService procesador = new ProcesadorColaService(cola);
    private final CorreoService correoService =
            new CorreoService(procesador, cola, pedidos, correo, JsonMapper.builder().build());
    private final SincronizacionErpService erpService =
            new SincronizacionErpService(procesador, cola, pedidos, erp, JsonMapper.builder().build());

    private static EventoCola evento(long id, String tipo, String payload) {
        return new EventoCola(id, tipo, "c" + id, payload, 0);
    }

    private static PedidoParaNotificar pedido45() {
        var linea = new PedidoParaNotificar.Linea("LAP-001", "Portátil X", 1, new BigDecimal("238000.00"));
        return new PedidoParaNotificar(45L, "Ana Pérez", "ana@correo.co", new BigDecimal("200000.00"),
                new BigDecimal("38000.00"), new BigDecimal("238000.00"), List.of(linea));
    }

    // ---------------- Procesador generico ----------------

    @Test
    void unEventoQueFallaSeReportaYElLoteSigue() {
        when(cola.tomarLote("ENVIAR_CORREO", 10)).thenReturn(List.of(
                evento(1, "ENVIAR_CORREO", "{}"), evento(2, "ENVIAR_CORREO", "{}"), evento(3, "ENVIAR_CORREO", "{}")));
        List<Long> vistos = new ArrayList<>();

        int n = procesador.procesar("ENVIAR_CORREO", 10, e -> {
            vistos.add(e.id());
            if (e.id() == 2) {
                throw new IllegalStateException("SMTP caido");
            }
        });

        assertThat(n).isEqualTo(3);
        assertThat(vistos).containsExactly(1L, 2L, 3L);
        verify(cola).fallar(eq(2L), eq("ENVIAR_CORREO"), contains("SMTP caido"));
        verify(cola, never()).fallar(eq(1L), anyString(), anyString());
    }

    @Test
    void siTampocoSePuedeReportarElFalloElLoteSigue() {
        when(cola.tomarLote("ENVIAR_CORREO", 10)).thenReturn(List.of(
                evento(1, "ENVIAR_CORREO", "{}"), evento(2, "ENVIAR_CORREO", "{}")));
        doThrow(new IllegalStateException("base caida")).when(cola).fallar(eq(1L), anyString(), anyString());
        List<Long> vistos = new ArrayList<>();

        int n = procesador.procesar("ENVIAR_CORREO", 10, e -> {
            vistos.add(e.id());
            if (e.id() == 1) {
                throw new IllegalStateException("falla");
            }
        });

        assertThat(n).isEqualTo(2);
        assertThat(vistos).containsExactly(1L, 2L);
    }

    @Test
    void nuncaTomaPagoNotificado() {
        assertThatThrownBy(() -> procesador.procesar("PAGO_NOTIFICADO", 10, e -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        verify(cola, never()).tomarLote(anyString(), anyInt());
    }

    // ---------------- Correo ----------------

    @Test
    @SuppressWarnings("unchecked")
    void pedidoPagadoVaAlCorreoDeFacturacionYCompleta() throws Exception {
        when(pedidos.buscar(45L)).thenReturn(Optional.of(pedido45()));

        correoService.manejar(evento(9L, "ENVIAR_CORREO", "{\"plantilla\":\"PEDIDO_PAGADO\",\"id_pedido\":45}"));

        var modelo = ArgumentCaptor.forClass(Map.class);
        verify(correo).enviar(eq("ana@correo.co"), eq("Tu pedido #45 está pagado"), eq("correo/pedido-pagado"),
                modelo.capture());
        assertThat(modelo.getValue()).containsEntry("nombre", "Ana Pérez").containsEntry("idPedido", 45L);
        verify(cola).completar(9L, "ENVIAR_CORREO");
    }

    @Test
    @SuppressWarnings("unchecked")
    void carritoAbandonadoUsaLosDatosDelPayload() throws Exception {
        correoService.manejar(evento(10L, "ENVIAR_CORREO", "{\"plantilla\":\"CARRITO_ABANDONADO\",\"id_carrito\":7,"
                + "\"id_cliente\":3,\"nombre\":\"Ana\",\"email\":\"ana@correo.co\"}"));

        var modelo = ArgumentCaptor.forClass(Map.class);
        verify(correo).enviar(eq("ana@correo.co"), eq("Dejaste productos en tu carrito"),
                eq("correo/carrito-abandonado"), modelo.capture());
        assertThat(modelo.getValue()).containsEntry("nombre", "Ana");
        verify(pedidos, never()).buscar(anyLong());
        verify(cola).completar(10L, "ENVIAR_CORREO");
    }

    @Test
    void plantillaDesconocidaOPedidoInexistenteFallanSinCompletar() {
        assertThatThrownBy(() -> correoService.manejar(evento(11L, "ENVIAR_CORREO", "{\"plantilla\":\"FOO\"}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FOO");
        when(pedidos.buscar(46L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> correoService.manejar(
                evento(12L, "ENVIAR_CORREO", "{\"plantilla\":\"PEDIDO_PAGADO\",\"id_pedido\":46}")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("46");
        verify(cola, never()).completar(anyLong(), any());
    }

    @Test
    void siElEnvioFallaNoSeCompleta() {
        when(pedidos.buscar(45L)).thenReturn(Optional.of(pedido45()));
        doThrow(new IllegalStateException("SMTP")).when(correo).enviar(any(), any(), any(), any());

        assertThatThrownBy(() -> correoService.manejar(
                evento(12L, "ENVIAR_CORREO", "{\"plantilla\":\"PEDIDO_PAGADO\",\"id_pedido\":45}")))
                .isInstanceOf(IllegalStateException.class);
        verify(cola, never()).completar(anyLong(), any());
    }

    // ---------------- ERP ----------------

    @Test
    void sincronizaYCompletaConCompletarErp() throws Exception {
        when(pedidos.buscar(45L)).thenReturn(Optional.of(pedido45()));
        when(erp.enviarPedido(pedido45())).thenReturn("ERP-45");

        erpService.manejar(evento(9L, "SINCRONIZAR_ERP", "{\"id_pedido\":45}"));

        verify(cola).completarErp(9L, 45L, "ERP-45");
        verify(cola, never()).completar(anyLong(), anyString());
    }

    @Test
    void siElErpFallaNoSeCompleta() {
        when(pedidos.buscar(45L)).thenReturn(Optional.of(pedido45()));
        when(erp.enviarPedido(pedido45())).thenThrow(new IllegalStateException("ERP caido"));

        assertThatThrownBy(() -> erpService.manejar(evento(9L, "SINCRONIZAR_ERP", "{\"id_pedido\":45}")))
                .isInstanceOf(IllegalStateException.class);
        verify(cola, never()).completarErp(anyLong(), anyLong(), anyString());
    }

    @Test
    void payloadSinIdPedidoFalla() {
        assertThatThrownBy(() -> erpService.manejar(evento(9L, "SINCRONIZAR_ERP", "{}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void procesarPendientesUsaElTipoCorrecto() {
        when(cola.tomarLote(anyString(), anyInt())).thenReturn(List.of());
        correoService.procesarPendientes(10);
        erpService.procesarPendientes(5);
        verify(cola).tomarLote("ENVIAR_CORREO", 10);
        verify(cola).tomarLote("SINCRONIZAR_ERP", 5);
    }
}
