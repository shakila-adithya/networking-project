package storage.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class WindowsDirectGroupTest {
    @Test void loadsPackagedPublisherWithoutChangingTheNetwork() throws Exception {
        try (var group = new WindowsDirectGroup()) {
            assertThrows(IllegalArgumentException.class, () -> group.start("valid-password", false));
            assertThrows(IllegalArgumentException.class, () -> group.start("short", true));
            var probe = new ProcessBuilder(group.compileHelper().toString(), "--probe").redirectErrorStream(true).start();
            try {
                assertTrue(probe.waitFor(10, TimeUnit.SECONDS));
                assertEquals("Created", probe.inputReader().readLine());
                assertEquals(0, probe.exitValue());
                assertEquals("STOPPED", group.status().get("state"));
            } finally { if (probe.isAlive()) probe.destroyForcibly(); }
        }
    }
}
