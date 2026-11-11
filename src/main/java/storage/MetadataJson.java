package storage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

public final class MetadataJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private MetadataJson() {
    }

    public static byte[] encode(FileMetadata metadata) throws IOException {
        return MAPPER.writeValueAsBytes(metadata);
    }

    public static FileMetadata decode(byte[] json) throws Protocol.ProtocolException {
        try {
            return MAPPER.readValue(json, FileMetadata.class);
        } catch (IOException | IllegalArgumentException e) {
            throw new Protocol.ProtocolException("INVALID_METADATA", "metadata JSON is invalid: " + e.getMessage());
        }
    }
}
