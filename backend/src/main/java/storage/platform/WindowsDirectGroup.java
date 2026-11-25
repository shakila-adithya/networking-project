package storage.platform;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Owns one Windows Wi-Fi Direct autonomous group and its WinRT publisher process. */
public final class WindowsDirectGroup implements AutoCloseable {
    private final Path windows = Path.of(System.getenv("SystemRoot"));
    private final Path runtime = windows.resolve("Microsoft.NET/Framework64/v4.0.30319");
    private Path executable;
    private Process host;
    private String state = "STOPPED", error = "", name = "";
    private long started;

    public synchronized Map<String, Object> status() {
        if ("STARTING".equals(state) && System.nanoTime() - started > TimeUnit.SECONDS.toNanos(25)) {
            close();
            error = "Windows timed out starting Wi-Fi Direct. Check Wi-Fi and turn off Mobile Hotspot before retrying.";
        }
        boolean installed = Files.isRegularFile(runtime.resolve("csc.exe"))
                && Files.isRegularFile(windows.resolve("System32/WinMetadata/Windows.Devices.winmd"));
        return Map.of("available", installed, "directHost", installed, "state", state, "mode", host == null ? "" : "wifi-direct",
                "groupName", name, "peers", List.of(), "adapters", List.of(), "error", error,
                "reason", installed ? "" : "Windows Wi-Fi Direct hosting needs 64-bit Windows with .NET Framework and WinRT.");
    }

    public synchronized void start(String password, boolean confirmed) throws IOException {
        NetworkManagerService.requireConfirmed(confirmed);
        if (host != null) throw new IOException("Stop the current Wi-Fi Direct group first.");
        if (!password.matches("[ -~]{8,63}")) throw new IllegalArgumentException("Use an 8–63 character ASCII group password.");
        if (executable == null) executable = compileHelper();
        Process child = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
        host = child;
        state = "STARTING";
        started = System.nanoTime();
        error = "";
        name = "DIRECT-Relay-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            var input = child.outputWriter(StandardCharsets.US_ASCII);
            input.write(name + "\n" + password + "\n");
            input.flush();
            Thread.ofVirtual().name("windows-wifi-direct-status").start(new Monitor(child));
        } catch (IOException | RuntimeException failure) { close(); throw failure; }
    }

    Path compileHelper() throws IOException {
        Path directory = Files.createTempDirectory("relay-wifi-direct-");
        directory.toFile().deleteOnExit();
        Path source = directory.resolve("WifiDirectHost.cs"), binary = directory.resolve("WifiDirectHost.exe");
        source.toFile().deleteOnExit();
        binary.toFile().deleteOnExit();
        try (var resource = WindowsDirectGroup.class.getResourceAsStream("/windows/WifiDirectHost.cs")) {
            if (resource == null) throw new IOException("Missing packaged Windows Wi-Fi Direct helper.");
            Files.copy(resource, source);
        }
        var args = new ArrayList<>(List.of(runtime.resolve("csc.exe").toString(), "/nologo", "/out:" + binary));
        for (String module : List.of("Devices", "Security", "Foundation"))
            args.add("/r:" + windows.resolve("System32/WinMetadata/Windows." + module + ".winmd"));
        for (String assembly : List.of("System.Runtime.dll", "System.Runtime.InteropServices.WindowsRuntime.dll"))
            args.add("/r:" + runtime.resolve(assembly));
        args.add(source.toString());
        Process compiler = new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (compiler.waitFor(20, TimeUnit.SECONDS) && compiler.exitValue() == 0) return binary;
            throw new IOException("Could not build the Windows Wi-Fi Direct helper with the installed .NET Framework.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Windows Wi-Fi Direct setup was interrupted.", e);
        } finally { if (compiler.isAlive()) compiler.destroyForcibly(); }
    }

    private final class Monitor implements Runnable {
        private final Process child;
        Monitor(Process child) { this.child = child; }
        public void run() {
            try (var reader = child.inputReader()) {
                String line;
                while ((line = reader.readLine()) != null) synchronized (WindowsDirectGroup.this) {
                    if (host != child) return;
                    if ("ACTIVE".equals(line)) state = "ACTIVE";
                    else error = line;
                }
            } catch (IOException e) {
                synchronized (WindowsDirectGroup.this) { if (host == child) error = e.getMessage(); }
            } finally {
                synchronized (WindowsDirectGroup.this) {
                    if (host == child) {
                        close();
                        if (error.isBlank()) error = "Windows closed the Wi-Fi Direct group. Check the Wi-Fi driver and Mobile Hotspot.";
                    }
                }
            }
        }
    }

    public synchronized void close() {
        if (host != null) {
            try {
                host.getOutputStream().close();
                if (!host.waitFor(2, TimeUnit.SECONDS)) host.destroyForcibly();
            } catch (IOException e) { host.destroyForcibly(); }
            catch (InterruptedException e) { host.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
        host = null;
        state = "STOPPED";
        name = "";
    }
}
