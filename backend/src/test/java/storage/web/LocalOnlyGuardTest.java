package storage.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.config.SetupConfigStore;

/**
 * The setup agent is reachable from the LAN, and the node behind it sees every proxied request as coming from
 * 127.0.0.1. So the agent itself must refuse, from anyone but this laptop, the requests that change this device.
 */
class LocalOnlyGuardTest {
    @TempDir
    Path root;

    private static int status(int port, String method, String path, String host, String body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String request = method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                    + "\r\n\r\n" + body;
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            in.transferTo(response);
            String statusLine = response.toString(StandardCharsets.UTF_8).split("\r\n", 2)[0];
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    @Test
    void requestsThatChangeThisDeviceNeedALocalHost() throws Exception {
        try (SetupAgent agent = new SetupAgent(0, new SetupConfigStore(root.resolve("c.json")), root, () -> List.of())) {
            agent.start();
            int port = agent.port();
            String local = "localhost:" + port;
            String foreign = "relay.example.com";
            for (String path : List.of("/api/device", "/api/bluetooth/scan", "/api/mesh/connect")) {
                assertEquals(403, status(port, "POST", path, foreign, "{}"), "foreign host must be refused: " + path);
                assertEquals(503, status(port, "POST", path, local, "{}"),
                        "this laptop passes the guard (and finds no running node here): " + path);
            }
            // reading is not restricted by the guard
            assertEquals(503, status(port, "GET", "/api/device", foreign, ""));
            assertEquals(503, status(port, "GET", "/api/nearby", foreign, ""));
        }
    }
}
