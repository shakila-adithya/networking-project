package storage.platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class PowerStateTest {
    @TempDir Path root;
    @Test void absentBatteryIsUnknownAndActualBatteryReportsDischarging() throws Exception {
        assertNull(PowerState.linux(root).percent());
        assertNull(PowerState.linux(root).pluggedIn());
        Path battery = Files.createDirectory(root.resolve("BAT0"));
        Files.writeString(battery.resolve("capacity"), "12\n");
        Files.writeString(battery.resolve("status"), "Discharging\n");
        assertEquals(new PowerState.Reading(12, false, ""), PowerState.linux(root));
        Files.writeString(battery.resolve("status"), "Charging\n");
        assertEquals(Boolean.TRUE, PowerState.linux(root).pluggedIn());
    }
}
