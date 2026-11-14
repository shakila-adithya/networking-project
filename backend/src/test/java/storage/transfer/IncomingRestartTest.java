package storage.transfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.files.*;
import java.nio.file.*;
import java.util.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class IncomingRestartTest {
    @TempDir Path root;
    @Test void acknowledgedChunksAndTheirBitmapSurviveReopen() throws Exception {
        UUID id = UUID.randomUUID(); Path part = root.resolve(id + ".part");
        var metadata = new FileMetadata(UUID.randomUUID(), "resume.bin", 6, "0".repeat(64), Instant.now().toString(), null);
        var first = new IncomingTransfer(id, "sender", metadata, 3, part);
        first.save(); assertTrue(first.writeChunk(0, new byte[]{1,2,3})); first.closeFile();
        var journal = AtomicJson.read(root.resolve(id + ".json"), IncomingTransfer.Journal.class);
        var reopened = new IncomingTransfer(id, "sender", journal.metadata(), journal.chunkSize(), part);
        try {
            reopened.restore(journal.received());
            assertEquals(BitSet.valueOf(new byte[]{1}), reopened.receivedChunks());
            assertFalse(reopened.allReceived());
            assertTrue(reopened.writeChunk(1, new byte[]{4,5,6})); assertTrue(reopened.allReceived());
            assertArrayEquals(new byte[]{1,2,3,4,5,6}, Files.readAllBytes(part));
        } finally { reopened.closeFile(); }
    }
}
