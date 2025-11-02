package com.neurasys.service;

import com.neurasys.util.Logger;
import com.neurasys.util.FileUtil;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;

/**
 * ✅ HYBRID BackupManager - Backup and restore operations
 *
 * Integrated with hybrid schema:
 * - Tracks backup_duration_ms for performance monitoring
 * - Records backup_metadata for job tracking
 * - Supports deduplication with content_hash
 * - Logs to file_versions with all hybrid columns
 */
public class BackupManager {
    private static final Logger logger = Logger.getLogger(BackupManager.class);

    private final DatabaseManager dbManager;
    private final DateTimeFormatter formatter;
    private File lastBackup = null;
    private Map<String, String> contentHashMap = new HashMap<>();
    private Map<Integer, File> lastBackupPerPath = new HashMap<>();

    public BackupManager(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        this.formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        logger.info("BackupManager initialized (Hybrid version with performance tracking)");
    }

    public void createOptimizedBackup(int monitorPathId, String monitorPathName,
                                      String backupLocation, File sourceFile, String action) {
        long startTime = System.currentTimeMillis();  // NEW: Track backup duration
        String backupId = UUID.randomUUID().toString();  // NEW: Unique backup job ID

        try {
            if (!sourceFile.exists() || !sourceFile.isFile()) {
                logger.warn("Source file not valid: " + sourceFile.getName());
                recordFailedBackup(backupId, monitorPathId, "File not valid");
                return;
            }

            try {
                if (Files.isHidden(sourceFile.toPath())) {
                    logger.debug("Skipping hidden file: " + sourceFile.getName());
                    return;
                }
            } catch (IOException e) {
                logger.warn("Failed to check hidden attribute: " + sourceFile.getName());
            }

            long originalSize = sourceFile.length();
            String timestamp = LocalDateTime.now().format(formatter);

            // Deduplication support
            String contentHash = calculateSHA256(sourceFile);
            boolean isDeduplicated = false;

            if (contentHashMap.containsKey(contentHash)) {
                logger.info("✓ DEDUPLICATION: File already backed up (100% space saved)");
                isDeduplicated = true;

                long backupDurationMs = System.currentTimeMillis() - startTime;  // NEW

                dbManager.logFileVersion(
                        monitorPathId,
                        sourceFile.getAbsolutePath(),
                        contentHashMap.get(contentHash),
                        timestamp,
                        originalSize,
                        0, 0, originalSize,
                        "DEDUPLICATED",
                        100.0, true, contentHash,
                        backupDurationMs  // NEW: Log duration
                );

                // NEW: Record backup metadata
                recordSuccessfulBackup(backupId, monitorPathId, "DEDUPLICATED",
                        1, 0, originalSize, backupDurationMs);
                return;
            }

            String backupFileName = buildBackupFileName(sourceFile.getName(), timestamp);
            File backupDir = new File(backupLocation);
            File backupFile = new File(backupDir, backupFileName);
            FileUtil.ensureDirectoryExists(backupLocation);

            logger.info("Creating optimized backup: Source=" + sourceFile.getAbsolutePath() +
                    ", Destination=" + backupFile.getAbsolutePath());

            String backupType = "FULL";
            long compressedSize;
            File lastBackupFile = lastBackupPerPath.get(monitorPathId);

            if (action.equals("MODIFY") && lastBackupFile != null && lastBackupFile.exists()) {
                logger.info("INCREMENTAL: Creating delta backup for modified file");
                backupType = "INCREMENTAL";
                compressedSize = createIncrementalBackup(sourceFile, lastBackupFile, backupFile);
            } else {
                logger.info("FULL: Creating compressed backup");
                backupType = "FULL";
                compressedSize = compressFile(sourceFile, backupFile);
            }

            long spaceSaved = originalSize - compressedSize;
            double compressionRatio = originalSize > 0 ? (spaceSaved / (double) originalSize) * 100 : 0;
            long backupDurationMs = System.currentTimeMillis() - startTime;  // NEW: Calculate duration

            contentHashMap.put(contentHash, backupFile.getAbsolutePath());
            lastBackupPerPath.put(monitorPathId, backupFile);
            lastBackup = backupFile;

            // NEW: Log with backup_duration_ms
            dbManager.logFileVersion(
                    monitorPathId,
                    sourceFile.getAbsolutePath(),
                    backupFile.getAbsolutePath(),
                    timestamp,
                    originalSize,
                    compressedSize,
                    compressedSize,
                    spaceSaved,
                    backupType,
                    compressionRatio,
                    false,
                    contentHash,
                    backupDurationMs  // NEW PARAMETER
            );

            // NEW: Record backup job metadata
            recordSuccessfulBackup(backupId, monitorPathId, backupType,
                    1, 0, originalSize, backupDurationMs);

            logger.info("✓ Backup completed: Type=" + backupType +
                    ", File=" + backupFileName +
                    ", Original=" + formatBytes(originalSize) +
                    ", Compressed=" + formatBytes(compressedSize) +
                    ", Saved=" + formatBytes(spaceSaved) +
                    ", Efficiency=" + String.format("%.1f%%", compressionRatio) +
                    ", Duration=" + backupDurationMs + "ms");  // NEW: Log duration

        } catch (Exception e) {
            long backupDurationMs = System.currentTimeMillis() - startTime;
            logger.error("Failed to create backup for: " + sourceFile.getName(), e);
            recordFailedBackup(backupId, monitorPathId, e.getMessage());
            e.printStackTrace();
        }
    }

    // NEW: Record successful backup metadata
    private void recordSuccessfulBackup(String backupId, int monitorPathId, String backupType,
                                        long filesProcessed, long filesFailed, long totalSize,
                                        long durationMs) {
        try {
            dbManager.recordBackupMetadata(
                    backupId,
                    monitorPathId,
                    backupType,
                    filesProcessed,
                    filesFailed,
                    totalSize,
                    durationMs / 1000,  // Convert to seconds
                    "COMPLETED"
            );
        } catch (Exception e) {
            logger.warn("Failed to record backup metadata: " + e.getMessage());
        }
    }

    // NEW: Record failed backup metadata
    private void recordFailedBackup(String backupId, int monitorPathId, String errorMessage) {
        try {
            dbManager.recordBackupMetadata(
                    backupId,
                    monitorPathId,
                    "FULL",
                    0,
                    1,
                    0,
                    0,
                    "FAILED"
            );
        } catch (Exception e) {
            logger.warn("Failed to record failed backup metadata: " + e.getMessage());
        }
    }

    private long createIncrementalBackup(File sourceFile, File lastBackup, File backupFile) throws IOException {
        // Placeholder: Real incremental backup would use binary diff algorithm
        return compressFile(sourceFile, backupFile);
    }

    private long compressFile(File sourceFile, File backupFile) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        try (FileInputStream fis = new FileInputStream(sourceFile);
             GZIPOutputStream gos = new GZIPOutputStream(new FileOutputStream(backupFile), 65536)) {
            while ((bytesRead = fis.read(buffer)) != -1) {
                gos.write(buffer, 0, bytesRead);
            }
            gos.finish();
        }
        return backupFile.length();
    }

    private String calculateSHA256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int bytesRead;
        try (FileInputStream fis = new FileInputStream(file)) {
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private String buildBackupFileName(String originalName, String timestamp) {
        int lastDot = originalName.lastIndexOf('.');
        if (lastDot > 0) {
            String name = originalName.substring(0, lastDot);
            String ext = originalName.substring(lastDot);
            return name + "_" + timestamp + ext + ".gz";
        }
        return originalName + "_" + timestamp + ".gz";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public File getLastBackup() {
        return lastBackup;
    }

    // RESTORE METHOD
    public File restoreBackup(File backupFile, File restoreLocation) throws IOException {
        if (!backupFile.exists() || !backupFile.getName().endsWith(".gz")) {
            logger.warn("Backup file not found or invalid: " + backupFile.getAbsolutePath());
            return null;
        }

        String restoredName = backupFile.getName().replaceAll("\\.gz$", "");
        File restoredFile = new File(restoreLocation, restoredName);
        logger.info("Restoring backup: " + backupFile.getName() + " → " + restoredFile.getAbsolutePath());

        byte[] buffer = new byte[8192];
        int bytesRead;
        try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(backupFile));
             FileOutputStream fos = new FileOutputStream(restoredFile)) {
            while ((bytesRead = gis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }

        logger.info("✓ Backup restored: " + restoredFile.getAbsolutePath());
        return restoredFile;
    }
}