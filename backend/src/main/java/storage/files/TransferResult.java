package storage.files;

import java.util.UUID;

public record TransferResult(boolean success, String code, String message, UUID fileId) {
    public static TransferResult success(String message, UUID fileId) {
        return new TransferResult(true, "OK", message, fileId);
    }

    public static TransferResult failure(String code, String message, UUID fileId) {
        return new TransferResult(false, code, message, fileId);
    }

}
