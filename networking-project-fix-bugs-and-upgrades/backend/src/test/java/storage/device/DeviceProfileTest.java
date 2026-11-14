package storage.device;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeviceProfileTest {
    @TempDir
    Path tmp;

    @Test
    void defaultsAreHiddenWithNoCustomName() {
        assertEquals("", DeviceProfile.DEFAULT.displayName());
        assertFalse(DeviceProfile.DEFAULT.visible());
        assertEquals("C", DeviceProfile.DEFAULT.effectiveName("C"), "no name chosen: show the id");
        assertEquals("Maya's laptop", new DeviceProfile("Maya's laptop", false).effectiveName("C"));
    }

    @Test
    void namesAreTrimmedAndValidated() {
        assertEquals("Maya", new DeviceProfile("  Maya  ", true).displayName());
        assertEquals("", new DeviceProfile(null, true).displayName());
        assertEquals("මායා 📱", new DeviceProfile("මායා 📱", false).displayName(), "any language is fine");
        assertEquals(32, new DeviceProfile("x".repeat(32), false).displayName().length());
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfile("x".repeat(33), false));
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfile("bad\nname", false));
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfile("tab\there", false));
        // 32 emoji are 32 characters (code points) even though they are 64 UTF-16 units
        assertDoesNotThrow(() -> new DeviceProfile("😀".repeat(32), false));
        assertThrows(IllegalArgumentException.class, () -> new DeviceProfile("😀".repeat(33), false));
    }

    @Test
    void storeRoundTripsAndFallsBackToDefaultsWhenMissingOrDamaged() throws Exception {
        DeviceProfileStore store = new DeviceProfileStore(tmp.resolve("config/device-profile.json"));
        assertEquals(DeviceProfile.DEFAULT, store.load(), "no file yet");
        store.save(new DeviceProfile("Kasun's phone", true));
        assertEquals(new DeviceProfile("Kasun's phone", true), store.load());
        assertEquals(new DeviceProfile("Kasun's phone", true), new DeviceProfileStore(store.file()).load(), "survives a restart");

        Files.writeString(store.file(), "{ this is not json");
        assertEquals(DeviceProfile.DEFAULT, store.load(), "damaged file: defaults, not a crash");
        Files.writeString(store.file(), "{\"displayName\":\"" + "x".repeat(40) + "\",\"visible\":true}");
        assertEquals(DeviceProfile.DEFAULT, store.load(), "an invalid stored name is rejected too");
        try (var leftovers = Files.list(store.file().getParent())) {
            assertEquals(1, leftovers.count(), "no temporary files are left behind");
        }
    }

    @Test
    void serviceSavesNotifiesAndKeepsStateOnInvalidInput() throws Exception {
        DeviceProfileStore store = new DeviceProfileStore(tmp.resolve("p.json"));
        DeviceProfileService service = new DeviceProfileService(store);
        List<DeviceProfile> seen = new ArrayList<>();
        service.onChange(seen::add);

        assertEquals(DeviceProfile.DEFAULT, service.profile());
        service.update("Maya", null);
        service.update(null, true);
        assertEquals(new DeviceProfile("Maya", true), service.profile());
        assertEquals(List.of(new DeviceProfile("Maya", false), new DeviceProfile("Maya", true)), seen);
        assertEquals(new DeviceProfile("Maya", true), store.load(), "every change is saved");

        service.update("Maya", true);                       // nothing changed
        assertEquals(2, seen.size(), "an unchanged update notifies nobody");

        assertThrows(IllegalArgumentException.class, () -> service.update("x".repeat(40), false));
        assertEquals(new DeviceProfile("Maya", true), service.profile(), "invalid input changes nothing");
        assertEquals(2, seen.size());

        service.update("", false);                          // clearing the name goes back to the id
        assertEquals("C", service.profile().effectiveName("C"));
    }

    @Test
    void aFailingListenerDoesNotBreakTheUpdate() throws Exception {
        DeviceProfileService service = new DeviceProfileService(new DeviceProfileStore(tmp.resolve("q.json")));
        List<DeviceProfile> seen = new ArrayList<>();
        service.onChange(profile -> { throw new IllegalStateException("boom"); });
        service.onChange(seen::add);
        service.update("Name", null);
        assertEquals(1, seen.size(), "later listeners still run");
    }

    @Test
    void anUnwritableLocationIsReportedAndLeavesTheOldProfile() throws Exception {
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "a file where a directory is needed");
        DeviceProfileService service = new DeviceProfileService(new DeviceProfileStore(blocker.resolve("p.json")));
        assertThrows(IOException.class, () -> service.update("Name", null));
        assertEquals(DeviceProfile.DEFAULT, service.profile());
    }
}
