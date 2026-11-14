package storage.transfer;

import storage.files.FileMetadata;

/** A file that arrived over the mesh, was verified and is now stored locally. */
public record ReceivedFile(FileMetadata metadata, String from) {
}
