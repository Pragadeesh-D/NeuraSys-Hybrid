package com.neurasys.model;

public class FileVersion {
    private int id;
    private int monitorPathId;
    private String filePath;
    private String backupPath;
    private String versionTag;
    private long originalSize;
    private long compressedSize;
    private long actualDiskSize;
    private long spaceSaved;
    private String backupType; // FULL, INCREMENTAL, DEDUPLICATED
    private String compressionAlgo; // GZIP, ZSTD, NONE
    private double compressionRatio;
    private boolean isDeduplicated;
    private String contentHash;
    private String timestamp;

    public FileVersion(int id, int monitorPathId, String filePath, String backupPath,
                       String versionTag, long originalSize, long compressedSize,
                       String backupType, String compressionAlgo) {
        this.id = id;
        this.monitorPathId = monitorPathId;
        this.filePath = filePath;
        this.backupPath = backupPath;
        this.versionTag = versionTag;
        this.originalSize = originalSize;
        this.compressedSize = compressedSize;
        this.actualDiskSize = compressedSize;
        this.spaceSaved = originalSize - compressedSize;
        this.backupType = backupType;
        this.compressionAlgo = compressionAlgo;
        this.compressionRatio = originalSize > 0 ? ((double)(originalSize - compressedSize) / originalSize) * 100 : 0;
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getMonitorPathId() { return monitorPathId; }
    public void setMonitorPathId(int monitorPathId) { this.monitorPathId = monitorPathId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getBackupPath() { return backupPath; }
    public void setBackupPath(String backupPath) { this.backupPath = backupPath; }

    public String getVersionTag() { return versionTag; }
    public void setVersionTag(String versionTag) { this.versionTag = versionTag; }

    public long getOriginalSize() { return originalSize; }
    public void setOriginalSize(long originalSize) { this.originalSize = originalSize; }

    public long getCompressedSize() { return compressedSize; }
    public void setCompressedSize(long compressedSize) { this.compressedSize = compressedSize; }

    public long getActualDiskSize() { return actualDiskSize; }
    public void setActualDiskSize(long actualDiskSize) { this.actualDiskSize = actualDiskSize; }

    public long getSpaceSaved() { return spaceSaved; }
    public void setSpaceSaved(long spaceSaved) { this.spaceSaved = spaceSaved; }

    public String getBackupType() { return backupType; }
    public void setBackupType(String backupType) { this.backupType = backupType; }

    public String getCompressionAlgo() { return compressionAlgo; }
    public void setCompressionAlgo(String compressionAlgo) { this.compressionAlgo = compressionAlgo; }

    public double getCompressionRatio() { return compressionRatio; }
    public void setCompressionRatio(double compressionRatio) { this.compressionRatio = compressionRatio; }

    public boolean isDeduplicated() { return isDeduplicated; }
    public void setDeduplicated(boolean deduplicated) { isDeduplicated = deduplicated; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
