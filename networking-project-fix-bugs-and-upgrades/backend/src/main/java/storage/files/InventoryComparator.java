package storage.files;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Compares inventory snapshots by stable UUID and exposes metadata conflicts explicitly. */
public final class InventoryComparator {
    private InventoryComparator() {
    }

    public static List<FileCopies> compare(List<SourceInventory> sources) {
        Map<UUID, Map<String, FileMetadata>> byId = new LinkedHashMap<>();
        for (SourceInventory source : sources) {
            if (!source.available()) {
                continue;
            }
            for (FileMetadata metadata : source.files()) {
                byId.computeIfAbsent(metadata.fileId(), ignored -> new LinkedHashMap<>())
                        .put(source.source(), metadata);
            }
        }
        List<FileCopies> result = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, FileMetadata>> entry : byId.entrySet()) {
            List<FileMetadata> metadata = List.copyOf(entry.getValue().values());
            FileMetadata first = metadata.getFirst();
            boolean consistent = metadata.stream().allMatch(first::equals);
            result.add(new FileCopies(entry.getKey(), Map.copyOf(entry.getValue()), consistent));
        }
        return List.copyOf(result);
    }

    public record SourceInventory(String source, boolean available, List<FileMetadata> files, String reason) {
        public SourceInventory {
            files = files == null ? List.of() : List.copyOf(files);
            if (available && reason != null) {
                throw new IllegalArgumentException("available inventory cannot have an unavailable reason");
            }
        }

        public static SourceInventory available(String source, List<FileMetadata> files) {
            return new SourceInventory(source, true, files, null);
        }

        public static SourceInventory unavailable(String source, String reason) {
            return new SourceInventory(source, false, List.of(), reason);
        }
    }

    public record FileCopies(UUID fileId, Map<String, FileMetadata> copies, boolean metadataAgrees) {
    }
}
