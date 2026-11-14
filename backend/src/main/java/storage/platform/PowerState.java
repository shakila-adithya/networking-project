package storage.platform;
import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.util.*;
import static java.lang.foreign.ValueLayout.*;
/** Cached OS battery readings. Unknown values remain null; no simulated battery telemetry. */
public final class PowerState {
    public record Reading(Integer percent, Boolean pluggedIn, String reason) { }
    private static Reading cached = new Reading(null, null, "Battery state has not been reported");
    private static long nextRead;
    private PowerState() { }
    public static synchronized Reading current() {
        if (System.nanoTime() < nextRead) return cached;
        nextRead = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("linux")) cached = linux(Path.of("/sys/class/power_supply"));
            else if (os.contains("windows")) cached = windows();
            else cached = new Reading(null, null, "Battery API unavailable on this OS");
        } catch (Throwable error) { cached = new Reading(null, null, "OS battery information unavailable"); }
        return cached;
    }
    static Reading linux(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return new Reading(null, null, "No battery reported by the OS");
        try (var entries = Files.list(directory)) {
            for (Path battery : entries.toList()) if (Files.isRegularFile(battery.resolve("capacity"))) {
                int percent = Integer.parseInt(Files.readString(battery.resolve("capacity")).strip());
                String state = Files.readString(battery.resolve("status")).strip();
                if (percent >= 0 && percent <= 100) return new Reading(percent, switch (state.toLowerCase(Locale.ROOT)) {
                    case "charging", "full" -> true; case "discharging" -> false; default -> null;
                }, "");
            }
        }
        return new Reading(null, null, "No battery reported by the OS");
    }
    private static Reading windows() throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32.dll", arena);
            MethodHandle status = Linker.nativeLinker().downcallHandle(kernel.find("GetSystemPowerStatus").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            MemorySegment record = arena.allocate(12, 4);
            if ((int)status.invokeExact(record) == 0) return new Reading(null, null, "Windows power query failed");
            int percent = Byte.toUnsignedInt(record.get(JAVA_BYTE, 2)), ac = Byte.toUnsignedInt(record.get(JAVA_BYTE, 0));
            return new Reading(percent <= 100 ? percent : null, ac <= 1 ? ac == 1 : null, "");
        }
    }
}
