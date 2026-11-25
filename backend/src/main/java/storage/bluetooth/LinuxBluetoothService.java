package storage.bluetooth;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** BlueZ SDP lookup for peers (including Windows) whose radio name carries no Relay identity. */
final class LinuxBluetoothService {
    private static volatile String limitation = "";
    static String limitation() { return limitation; }

    static Optional<String> findName(String address, Duration timeout) throws IOException {
        String normalized = BluetoothAddress.normalize(address);
        Path output = Files.createTempFile("relay-sdp-", ".xml");
        Process process = null;
        try {
            try {
                process = new ProcessBuilder("sdptool", "browse", "--xml", normalized)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(output.toFile()).start();
            } catch (IOException e) {
                limitation = "Bluetooth service discovery requires BlueZ sdptool to find Windows Relay devices. Install the bluez tools on Linux.";
                throw new IOException(limitation, e);
            }
            limitation = "";
            if (!process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS)) return Optional.empty();
            if (process.exitValue() != 0 || Files.size(output) > 65536) return Optional.empty();
            return parseName(Files.readString(output));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Bluetooth service query interrupted", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(1, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            Files.deleteIfExists(output);
        }
    }

    static Optional<String> parseName(String output) throws IOException {
        if (output.length() > 65536) return Optional.empty();
        // sdptool emits a separate XML document per service, sometimes preceded by status text.
        int start = 0;
        while ((start = output.indexOf("<record>", start)) >= 0) {
            int end = output.indexOf("</record>", start);
            if (end < 0) return Optional.empty();
            String record = output.substring(start, end + 9);
            start = end + 9;
            try {
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(record)));
                boolean relayClass = false;
                String name = "";
                var attributes = document.getElementsByTagName("attribute");
                for (int i = 0; i < attributes.getLength(); i++) {
                    Element attribute = (Element) attributes.item(i);
                    if ("0x0001".equals(attribute.getAttribute("id"))) {
                        var uuids = attribute.getElementsByTagName("uuid");
                        for (int j = 0; j < uuids.getLength(); j++)
                            if (WindowsBluetoothService.UUID_TEXT.equalsIgnoreCase(((Element) uuids.item(j)).getAttribute("value"))) relayClass = true;
                    }
                    if ("0x0100".equals(attribute.getAttribute("id"))) {
                        var names = attribute.getElementsByTagName("text");
                        if (names.getLength() > 0) name = ((Element) names.item(0)).getAttribute("value");
                    }
                }
                if (relayClass && new Nearby("", name, null, false).relayDeviceId().isPresent()) return Optional.of(name);
            } catch (Exception e) { throw new IOException("Invalid Bluetooth SDP response", e); }
        }
        return Optional.empty();
    }
}
