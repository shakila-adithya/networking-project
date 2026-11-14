package storage.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import storage.routing.RoutingCodec.RouteReply;
import storage.routing.RoutingCodec.RouteRequest;
import storage.routing.RoutingCodec.Unreachable;

class RoutingCodecTest {
    @Test
    void requestReplyAndErrorsRoundTrip() throws Exception {
        RouteRequest request = new RouteRequest(7, 11, "D", -1, 3, 2);
        assertEquals(request, RoutingCodec.decodeRequest(RoutingCodec.encode(request)));
        RouteReply reply = new RouteReply("D", 12, "A", 4, 3);
        assertEquals(reply, RoutingCodec.decodeReply(RoutingCodec.encode(reply)));
        List<Unreachable> errors = List.of(new Unreachable("D", 9), new Unreachable("E", 3));
        assertEquals(errors, RoutingCodec.decodeErrors(RoutingCodec.encodeErrors(errors)));
    }

    @Test
    void malformedPayloadsAreRejected() throws Exception {
        byte[] good = RoutingCodec.encode(new RouteRequest(1, 1, "D", 0, 0, 0));
        assertThrows(IOException.class, () -> RoutingCodec.decodeRequest(new byte[3]));
        assertThrows(IOException.class, () -> RoutingCodec.decodeRequest(java.util.Arrays.copyOf(good, good.length + 1)));
        assertThrows(IOException.class, () -> RoutingCodec.decodeErrors(new byte[] {0}));
        assertThrows(IOException.class, () -> RoutingCodec.decodeErrors(new byte[] {(byte) 200}));
        assertThrows(IOException.class, () -> RoutingCodec.encodeErrors(List.of()));
        List<Unreachable> tooMany = new ArrayList<>();
        for (int i = 0; i <= RoutingCodec.MAX_ERROR_ENTRIES; i++) tooMany.add(new Unreachable("N" + i, 1));
        assertThrows(IOException.class, () -> RoutingCodec.encodeErrors(tooMany));
        assertThrows(IOException.class, () -> RoutingCodec.encode(new RouteRequest(1, 1, "", 0, 0, 0)));
    }
}
