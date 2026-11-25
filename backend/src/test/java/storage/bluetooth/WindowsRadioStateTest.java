package storage.bluetooth;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowsRadioStateTest {
    @Test void alreadyEnabledStateDoesNotNeedAnotherWindowsChange() throws Exception {
        StubState state = new StubState(true, false, 5);
        WindowsBluetoothAdapter.ensureState(state, true, "Bluetooth discovery");
        assertEquals(0, state.calls);
    }
    @Test void alreadyRestoredStateIsNotAnError() throws Exception {
        StubState state = new StubState(false, false, 5);
        WindowsBluetoothAdapter.ensureState(state, false, "Bluetooth discovery");
        assertEquals(0, state.calls);
    }
    @Test void actualStateWinsEvenWhenTheSetterReportsNoChange() throws Exception {
        StubState state = new StubState(false, true, 5);
        WindowsBluetoothAdapter.ensureState(state, true, "Bluetooth discovery");
        assertTrue(state.current);
    }
    @Test void rejectedChangeStillReportsTheNativeError() {
        StubState state = new StubState(false, false, 5);
        IOException failure = assertThrows(IOException.class,
                () -> WindowsBluetoothAdapter.ensureState(state, true, "Bluetooth discovery"));
        assertTrue(failure.getMessage().contains("Windows error 5"));
        assertFalse(state.current);
    }
    private static final class StubState implements WindowsBluetoothAdapter.RadioState {
        private boolean current;
        private final boolean accepts;
        private final int error;
        private int calls;
        StubState(boolean current, boolean accepts, int error) { this.current = current; this.accepts = accepts; this.error = error; }
        public boolean get() { return current; }
        public int set(boolean enabled) { calls++; if (accepts) current = enabled; return error; }
    }
}
