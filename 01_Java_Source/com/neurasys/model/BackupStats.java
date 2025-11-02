package com.neurasys.model;

import org.apache.commons.io.FileUtils;

/**
 * ✅ HYBRID BackupStats - Statistics for backup operations
 *
 * Integrated with new database schema:
 * - Queries from file_versions table (with compression_ratio, is_deduplicated)
 * - Tracks space_optimization_stats daily aggregates
 * - Supports deduplication and compression effectiveness metrics
 */
public class BackupStats {
    private final int totalBackups;
    private final long totalOriginalSize;
    private final long totalDiskUsed;
    private final long totalSpaceSaved;
    private final double savingsPercent;
    private final int filesMonitored;
    private final String lastBackupTime;

    // NEW: Hybrid metrics
    private final int deduplicatedFiles;
    private final int nativeMonitoredPaths;
    private final double avgCompressionRatio;

    public BackupStats(int totalBackups, long totalOriginalSize, long totalDiskUsed,
                       double savingsPercent, int filesMonitored, String lastBackupTime) {
        this(totalBackups, totalOriginalSize, totalDiskUsed, savingsPercent, filesMonitored,
                lastBackupTime, 0, 0, 0);
    }

    // NEW: Constructor with hybrid metrics
    public BackupStats(int totalBackups, long totalOriginalSize, long totalDiskUsed,
                       double savingsPercent, int filesMonitored, String lastBackupTime,
                       int deduplicatedFiles, int nativeMonitoredPaths, double avgCompressionRatio) {
        this.totalBackups = totalBackups;
        this.totalOriginalSize = totalOriginalSize;
        this.totalDiskUsed = totalDiskUsed;
        this.totalSpaceSaved = totalOriginalSize - totalDiskUsed;
        this.savingsPercent = savingsPercent;
        this.filesMonitored = filesMonitored;
        this.lastBackupTime = lastBackupTime != null ? lastBackupTime : "Never";
        this.deduplicatedFiles = deduplicatedFiles;
        this.nativeMonitoredPaths = nativeMonitoredPaths;
        this.avgCompressionRatio = avgCompressionRatio;
    }

    // ============ GETTERS ============

    public int getTotalBackups() { return totalBackups; }
    public long getTotalOriginalSize() { return totalOriginalSize; }
    public long getTotalDiskUsed() { return totalDiskUsed; }
    public long getTotalSpaceSaved() { return totalSpaceSaved; }
    public double getSavingsPercent() { return savingsPercent; }
    public int getFilesMonitored() { return filesMonitored; }
    public String getLastBackupTime() { return lastBackupTime; }

    // NEW: Hybrid metrics getters
    public int getDeduplicatedFiles() { return deduplicatedFiles; }
    public int getNativeMonitoredPaths() { return nativeMonitoredPaths; }
    public double getAvgCompressionRatio() { return avgCompressionRatio; }

    // ============ FORMATTED VALUES ============

    public String getOriginalSizeFormatted() {
        return FileUtils.byteCountToDisplaySize(totalOriginalSize);
    }

    public String getDiskUsedFormatted() {
        return FileUtils.byteCountToDisplaySize(totalDiskUsed);
    }

    public String getSpaceSavedFormatted() {
        return FileUtils.byteCountToDisplaySize(totalSpaceSaved);
    }

    @Override
    public String toString() {
        return String.format(
                "BackupStats[backups=%d, saved=%s (%.1f%%), files=%d, dedup=%d, native_paths=%d, compression=%.1f%%]",
                totalBackups, getSpaceSavedFormatted(), savingsPercent, filesMonitored,
                deduplicatedFiles, nativeMonitoredPaths, avgCompressionRatio
        );
    }
}