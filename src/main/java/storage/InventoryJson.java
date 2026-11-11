package storage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;

final class InventoryJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private InventoryJson() {
    }

    static byte[] encode(List<FileMetadata> files) throws IOException {
        if (files.size() > Protocol.MAX_INVENTORY_ENTRIES) {
            throw new Protocol.ProtocolException("INVENTORY_TOO_LARGE", "inventory contains too many entries");
        }
        byte[] json = MAPPER.writeValueAsBytes(new InventoryPayload(files));
        if (json.length > Protocol.MAX_INVENTORY_SIZE) {
            throw new Protocol.ProtocolException("INVENTORY_TOO_LARGE", "inventory JSON exceeds protocol limit");
        }
        return json;
    }

    static List<FileMetadata> decode(byte[] json) throws Protocol.ProtocolException {
        if (json.length > Protocol.MAX_INVENTORY_SIZE) {
            throw new Protocol.ProtocolException("INVENTORY_TOO_LARGE", "inventory JSON exceeds protocol limit");
        }
        try {
            InventoryPayload payload = MAPPER.readValue(json, InventoryPayload.class);
            if (payload.files() == null || payload.files().size() > Protocol.MAX_INVENTORY_ENTRIES) {
                throw new Protocol.ProtocolException("INVALID_INVENTORY", "inventory entry count is invalid");
            }
            return List.copyOf(payload.files());
        } catch (Protocol.ProtocolException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new Protocol.ProtocolException("INVALID_INVENTORY", "inventory JSON is invalid: " + e.getMessage());
        }
    }

    private record InventoryPayload(List<FileMetadata> files) {
    }
}
