package storage.app;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Starts the real application in a separate JVM and proves that closing it ends the process:
 * a started node must not leave a Java process (or its ports) behind.
 */
@Timeout(120)
class ShutdownTest {
    @TempDir
    Path workDir;
    private Process process;

    @AfterEach
    void makeSureNothingSurvivesTheTest() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    /** Collects the child's output so the test can wait for a line without blocking forever. */
    private static final class OutputCollector implements Runnable {
        private final BufferedReader reader;
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        OutputCollector(Process process) {
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException e) {
                lines.add("output closed: " + e.getMessage());
            }
        }
    }

    private Process launch(List<String> programArguments, String... systemProperties) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("-Dmesh.transport=tcp"); // keep these tests off the real Bluetooth adapter
        for (String property : systemProperties) {
            command.add(property);
        }
        command.add("storage.app.PeerNode");
        command.addAll(programArguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true);
        process = builder.start();
        return process;
    }

    /** A static two-peer CLI configuration on free ports (the legacy A/B/C shortcut needs fixed ports). */
    private String writeCliConfig() throws IOException {
        int port = freePort();
        Path file = workDir.resolve("cli-config.json");
        Files.writeString(file, """
                {"localPeerId":"A","bindAddress":"127.0.0.1","advertisedAddress":"127.0.0.1","port":%d,
                 "storageDirectory":"storage-data/A","discoverySecret":"test-only-shutdown-password",
                 "peers":[{"id":"A","address":"127.0.0.1","port":%d},{"id":"B","address":"127.0.0.1","port":%d}]}
                """.formatted(port, port, port + 5));
        return file.toString();
    }

    private static int freePort() throws IOException {
        for (int attempt = 0; attempt < 200; attempt++) {
            int candidate = 20_000 + (int) (Math.random() * 20_000);
            if (free(candidate) && free(candidate + 1_000) && free(candidate + 2_000)) {
                return candidate;
            }
        }
        throw new IOException("no free port range found");
    }

    private static boolean free(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String waitForLine(OutputCollector output, String contains, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        List<String> seen = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            String line = output.lines.poll(200, TimeUnit.MILLISECONDS);
            if (line != null) {
                seen.add(line);
                if (line.contains(contains)) {
                    return line;
                }
            }
        }
        fail("did not see \"" + contains + "\" in output: " + seen);
        return null;
    }

    @Test
    void cliNodeExitsAfterQuitCommand() throws Exception {
        launch(List.of(writeCliConfig()));
        OutputCollector output = new OutputCollector(process);
        Thread reader = new Thread(output, "test-output");
        reader.setDaemon(true);
        reader.start();
        waitForLine(output, "Commands:", 30);

        process.getOutputStream().write("quit\n".getBytes());
        process.getOutputStream().flush();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "node must exit after quit, not linger");
        assertEquals(0, process.exitValue());
    }

    @Test
    void cliNodeExitsWhenItsInputIsClosed() throws Exception {
        launch(List.of(writeCliConfig()));
        OutputCollector output = new OutputCollector(process);
        Thread reader = new Thread(output, "test-output");
        reader.setDaemon(true);
        reader.start();
        waitForLine(output, "Commands:", 30);

        process.getOutputStream().close(); // e.g. the terminal was closed: console EOF
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "node must exit on console EOF, not linger");
    }

    @Test
    void setupAgentExitsWhenTerminated() throws Exception {
        int port = freePort();
        launch(List.of("--setup"), "-Dsetup.port=" + port, "-Dui.dir=" + workDir);
        OutputCollector output = new OutputCollector(process);
        Thread reader = new Thread(output, "test-output");
        reader.setDaemon(true);
        reader.start();
        waitForLine(output, "Keep this agent running", 30);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/setup")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        process.destroy(); // SIGTERM, what Ctrl+C / closing the terminal / a service stop delivers
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "agent must exit when terminated, not linger");
        assertTrue(free(port), "the agent's port must be released");
    }

    @Test
    void fullNodeStartedBySetupAgentExitsWhenTerminatedAndReleasesAllPorts() throws Exception {
        int peerPort = freePort();
        int restPort = freePort();
        int setupPort = freePort();
        Files.createDirectories(workDir.resolve("config"));
        Files.writeString(workDir.resolve("config/local-setup.json"), """
                {"peer":{"localPeerId":"S1","bindAddress":"127.0.0.1","advertisedAddress":"127.0.0.1","port":%d,
                 "storageDirectory":"storage-data/S1",
                 "peers":[{"id":"S1","address":"127.0.0.1","port":%d},{"id":"S2","address":"127.0.0.1","port":%d}],
                 "clusterId":"shutdown-test","discoveryEnabled":false,
                 "discoveryGroup":"239.255.42.99","discoveryPort":45555,
                 "discoverySecret":"shutdown-test-secret-0123456789"},"restPort":%d}
                """.formatted(peerPort, peerPort, peerPort + 1, restPort));
        launch(List.of("--setup"), "-Dsetup.port=" + setupPort, "-Dui.dir=" + workDir);
        OutputCollector output = new OutputCollector(process);
        Thread reader = new Thread(output, "test-output");
        reader.setDaemon(true);
        reader.start();
        waitForLine(output, "Keep this agent running", 30);

        // Wait until the node behind the agent is really running and serving its API.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        int status = 0;
        while (System.nanoTime() < deadline && status != 200) {
            try {
                status = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + setupPort + "/api/mesh")).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode();
            } catch (IOException e) {
                status = 0;
            }
            if (status != 200) {
                Thread.sleep(300);
            }
        }
        assertEquals(200, status, "node did not start; output: " + output.lines);
        assertFalse(free(peerPort), "peer port is in use while the node runs");

        process.destroy();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "node must exit when terminated, not linger");
        assertTrue(free(peerPort) && free(restPort) && free(peerPort + 2_000) && free(setupPort),
                "every port the node opened must be released");
    }
}
