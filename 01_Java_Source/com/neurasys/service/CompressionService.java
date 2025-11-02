package com.neurasys.service;

import com.neurasys.util.Logger;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;

import java.io.*;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class CompressionService {
    private static final Logger logger = Logger.getLogger(CompressionService.class);

    public enum Algorithm {
        GZIP, ZSTD
    }

    public static File compress(File source, File destination, Algorithm algorithm,
                                int level) throws IOException {
        switch (algorithm) {
            case ZSTD:
                return compressZSTD(source, destination, level);
            case GZIP:
            default:
                return compressGZIP(source, destination);
        }
    }

    private static File compressZSTD(File source, File dest, int level) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest);
             ZstdCompressorOutputStream zstdOut = new ZstdCompressorOutputStream(fos, level)) {

            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                zstdOut.write(buffer, 0, len);
            }
        }

        long originalSize = source.length();
        long compressedSize = dest.length();
        double ratio = ((double)(originalSize - compressedSize) / originalSize) * 100;

        logger.debug("ZSTD compressed: {} → {} ({:.1f}% saved)",
                source.getName(), dest.getName(), ratio);

        return dest;
    }

    private static File compressGZIP(File source, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest);
             GZIPOutputStream gzos = new GZIPOutputStream(fos)) {

            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                gzos.write(buffer, 0, len);
            }
        }

        logger.debug("GZIP compressed: {}", dest.getName());
        return dest;
    }

    public static File decompress(File source, File destination, Algorithm algorithm)
            throws IOException {
        switch (algorithm) {
            case ZSTD:
                return decompressZSTD(source, destination);
            case GZIP:
            default:
                return decompressGZIP(source, destination);
        }
    }

    private static File decompressZSTD(File source, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             ZstdCompressorInputStream zstdIn = new ZstdCompressorInputStream(fis);
             FileOutputStream fos = new FileOutputStream(dest)) {

            byte[] buffer = new byte[8192];
            int len;
            while ((len = zstdIn.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }

        logger.debug("ZSTD decompressed: {}", dest.getName());
        return dest;
    }

    private static File decompressGZIP(File source, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             GZIPInputStream gzis = new GZIPInputStream(fis);
             FileOutputStream fos = new FileOutputStream(dest)) {

            byte[] buffer = new byte[8192];
            int len;
            while ((len = gzis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }

        logger.debug("GZIP decompressed: {}", dest.getName());
        return dest;
    }
}
