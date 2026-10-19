package dev.cody.packfailover.tracking;

/**
 * Protocol-independent resource-pack status, mirroring both
 * {@code PlayerResourcePackStatusEvent.Status} and the PacketEvents result enums.
 */
public enum PackStatus {
    ACCEPTED,
    DOWNLOADED,
    SUCCESSFULLY_LOADED,
    FAILED_DOWNLOAD,
    DECLINED,
    FAILED_RELOAD,
    INVALID_URL,
    DISCARDED;

    public boolean isSuccess() {
        return this == SUCCESSFULLY_LOADED;
    }

    public boolean isDownloadFailure() {
        return this == FAILED_DOWNLOAD;
    }
}
