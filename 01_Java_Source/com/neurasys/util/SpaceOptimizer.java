package com.neurasys.util;

import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.GZIPOutputStream;

public class SpaceOptimizer {
    private static final Logger logger = Logger.getLogger(SpaceOptimizer.class);
    private static final Map<String, String> hashCache = new HashMap<>();

    public static OptimizationResult optimizeBackup(File sourceFile, File backupFile,
                                                    OptimizationConfig config) throws IOException {
        long originalSize = sourceFile.length();
        long actualDiskSize;
        String backupType = "FULL";
        boolean isDeduplicated = false;
        String contentHash = calculateHash(sourceFile);

        // Check deduplication
        if (config.enableDeduplication && hashCache.containsKey(contentHash)) {
            createDeduplicatedBackup(new File(hashCache.get(contentHash)), backupFile);
            actualDiskSize = 0;
            isDeduplicated = true;
            backupType = "DEDUPLICATED";
            logger.info("✓ Deduplicated: {} (100% space saved)", sourceFile.getName());
        }
        // Check incremental
        else if (config.enableIncremental && config.previousBackup != null) {
            actualDiskSize = createIncrementalBackup(sourceFile, config.previousBackup, backupFile);
            backupType = "INCREMENTAL";
        }
        // Full compression
        else {
            if (config.enableCompression) {
                actualDiskSize = compressFile(sourceFile, backupFile, config.compressionLevel);
            } else {
                Files.copy(sourceFile.toPath(), backupFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                actualDiskSize = backupFile.length();
            }
            hashCache.put(contentHash, backupFile.getAbsolutePath());
        }

        long compressedSize = config.enableCompression ? actualDiskSize : originalSize;
        long spaceSaved = originalSize - actualDiskSize;
        double compressionRatio = originalSize > 0 ?
                ((double)(originalSize - compressedSize) / originalSize) * 100 : 0;

        return new OptimizationResult(originalSize, compressedSize, actualDiskSize,
                spaceSaved, compressionRatio, backupType, isDeduplicated, contentHash);
    }

    private static long compressFile(File source, File dest, int level) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest);
             ZstdCompressorOutputStream zstdOut = new ZstdCompressorOutputStream(fos, level)) {

            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                zstdOut.write(buffer, 0, len);
            }
        }
        return dest.length();
    }

    private static long createIncrementalBackup(File source, File previous, File dest)
            throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest);
             GZIPOutputStream gzos = new GZIPOutputStream(fos)) {

            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                gzos.write(buffer, 0, len);
            }
        }
        return dest.length();
    }

    private static void createDeduplicatedBackup(File existing, File dest) throws IOException {
        try {
            Files.createLink(dest.toPath(), existing.toPath());
        } catch (Exception e) {
            try (FileWriter fw = new FileWriter(dest)) {
                fw.write("DEDUP:" + existing.getAbsolutePath());
            }
        }
    }

    private static String calculateHash(File file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            IOException ioex = new IOException("SHA-256 algorithm not available: " + e.getMessage());
            ioex.initCause(e);
            throw ioex;
        }
        try (InputStream is = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                md.update(buffer, 0, read);
            }
        } catch (Exception e) {
            throw new IOException("Hash calculation failed", e);
        }

        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static class OptimizationConfig {
        public boolean enableCompression = true;
        public boolean enableDeduplication = true;
        public boolean enableIncremental = true;
        public int compressionLevel = 9;
        public File previousBackup = null;
    }

    public static class OptimizationResult {
        public final long originalSize;
        public final long compressedSize;
        public final long actualDiskSize;
        public final long spaceSaved;
        public final double compressionRatio;
        public final String backupType;
        public final boolean isDeduplicated;
        public final String contentHash;

        OptimizationResult(long originalSize, long compressedSize, long actualDiskSize,
                           long spaceSaved, double compressionRatio, String backupType,
                           boolean isDeduplicated, String contentHash) {
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
            this.actualDiskSize = actualDiskSize;
            this.spaceSaved = spaceSaved;
            this.compressionRatio = compressionRatio;
            this.backupType = backupType;
            this.isDeduplicated = isDeduplicated;
            this.contentHash = contentHash;
        }

        public double getSavingsPercent() {
            return originalSize > 0 ? ((double)spaceSaved / originalSize) * 100 : 0;
        }
    }
}
