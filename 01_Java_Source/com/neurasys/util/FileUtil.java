package com.neurasys.util;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.*;

public class FileUtil {
    private static final Logger logger = Logger.getLogger(FileUtil.class);

    public static void copyFile(File source, File dest) throws IOException {
        FileUtils.copyFile(source, dest);
        logger.info("File copied: {} -> {}", source.getName(), dest.getName());
    }

    public static void deleteQuietly(File file) {
        FileUtils.deleteQuietly(file);
        logger.debug("Deleted: {}", file.getName());
    }

    public static String getHumanReadableSize(long bytes) {
        return FileUtils.byteCountToDisplaySize(bytes);
    }

    public static String getExtension(String filename) {
        return FilenameUtils.getExtension(filename);
    }

    public static String getBaseName(String filename) {
        return FilenameUtils.getBaseName(filename);
    }

    public static void ensureDirectoryExists(String path) throws IOException {
        File dir = new File(path);
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                throw new IOException("Failed to create directory: " + path);
            }
        }
    }

    public static long getDirectorySize(File directory) {
        return FileUtils.sizeOfDirectory(directory);
    }

    public static void cleanDirectory(File directory) throws IOException {
        FileUtils.cleanDirectory(directory);
        logger.info("Cleaned directory: {}", directory.getName());
    }

    public static String readFileToString(File file) throws IOException {
        return FileUtils.readFileToString(file, "UTF-8");
    }

    public static void writeStringToFile(File file, String content) throws IOException {
        FileUtils.writeStringToFile(file, content, "UTF-8");
    }
}
