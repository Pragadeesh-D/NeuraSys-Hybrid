package com.neurasys.bridge;

import com.neurasys.service.MultiPathMonitor;
import com.neurasys.util.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ✅ HYBRID JNI Bridge to native C file monitoring library
 * Provides high-performance file system monitoring using Windows API + FileMonitor.dll
 * Integrated with new database schema: file_events, file_versions, space_optimization_stats
 *
 * Supports:
 * - Real-time native file monitoring (Windows ReadDirectoryChangesW API)
 * - Direct callback to Java for event processing
 * - File event persistence to database
 * - Performance metrics tracking
 */
public class NativeFileMonitor {
    private static final Logger logger = Logger.getLogger(NativeFileMonitor.class);

    // Single-thread executor for safe native attach/detach off the JavaFX thread
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private MultiPathMonitor.FileEventCallback callback;

    // Track DLL status
    private static boolean dllLoaded;

    static {
        try {
            // Load FileMonitor.dll from system library path (ensure PATH contains its directory)
            System.loadLibrary("FileMonitor");
            dllLoaded = true;
            logger.info("✓ Native FileMonitor.dll loaded successfully (v14.2.0 - MSYS2 GCC)");
        } catch (UnsatisfiedLinkError e) {
            dllLoaded = false;
            logger.error("✗ Failed to load FileMonitor.dll: " + e.getMessage());
            logger.error("Troubleshooting steps:");
            logger.error("  1. Place FileMonitor.dll where the process can load it (on PATH or java.library.path)");
            logger.error("  2. Ensure C:\\msys64\\ucrt64\\bin is in system PATH if linked against MSYS2 runtimes");
            logger.error("  3. Verify DLL compiled as: gcc -shared -o FileMonitor.dll FileMonitor.c");
            logger.error("  4. If using Maven, copy DLL to target runtime location or set -Djava.library.path");
        }
    }

    /**
     * Start real-time native monitoring on a local directory (unsafe direct JNI call).
     *
     * @param monitorPathId Database ID of monitor path (for event tracking)
     * @param path Full directory path to monitor (e.g., "D:\\Documents")
     * @param callback Java callback handler to receive native C events
     */
    public native void startMonitoring(int monitorPathId, String path, NativeFileEventCallback callback);

    /**
     * Stop monitoring on a specific monitor path (unsafe direct JNI call).
     *
     * @param monitorPathId Database ID of monitor path to stop
     */
    public native void stopMonitoring(int monitorPathId);

    /**
     * Get native C monitor status/health
     * @return true if C DLL is active and responding
     */
    public native boolean isMonitoringActive();

    /**
     * Safe wrapper to start monitoring:
     * - Validates DLL loaded
     * - Sanitizes and validates path
     * - Verifies callback
     * - Executes native attach on background thread
     * - Catches exceptions and logs instead of crashing JVM
     */

    // For test harness: direct native-triggered callback
    public native void triggerTestCallback(int monitorPathId, String path, NativeFileEventCallback callback);
    public native String getNativeMonitorStats();
    public void startMonitoringSafe(int monitorPathId, String rawPath, NativeFileEventCallback callback) {
        // DLL must be loaded
        if (!dllLoaded) {
            logger.error("✗ Cannot start native monitoring: FileMonitor.dll not loaded");
            return;
        }

        // Validate callback
        if (callback == null) {
            logger.error("✗ Cannot start native monitoring: callback is null");
            return;
        }

        // Sanitize and validate path
        String pathStr = sanitizePath(rawPath);
        if (pathStr == null || pathStr.isEmpty()) {
            logger.error("✗ Cannot start native monitoring: path is empty or invalid (raw='" + rawPath + "')");
            return;
        }

        Path monitorPath = Paths.get(pathStr);
        if (!Files.exists(monitorPath)) {
            logger.error("✗ Monitor path does not exist: " + monitorPath);
            return;
        }
        if (!Files.isDirectory(monitorPath)) {
            logger.error("✗ Monitor path is not a directory: " + monitorPath);
            return;
        }

        // Execute native attach off UI thread with safety
        executor.submit(() -> {
            try {
                logger.info("→ Attaching native monitor (id=" + monitorPathId + ") to: " + monitorPath);
                startMonitoring(monitorPathId, monitorPath.toString(), callback);
                logger.info("✓ Native monitor attached: " + monitorPath);
            } catch (Throwable t) {
                // Catch Throwable to prevent JVM crash caused by JNI errors
                logger.error("✗ Native monitor attach failed: " + t.getMessage());
                logger.error("Hint: Validate path correctness, library versions, and handle lifetimes");
                // No automatic fallback here to avoid double-registration; let caller decide strategy
            }
        });
    }

    /**
     * Safe wrapper to stop monitoring:
     * - Runs native stop on background thread
     * - Catches exceptions to avoid crashing JVM
     */
    public void stopMonitoringSafe(int monitorPathId) {
        executor.submit(() -> {
            try {
                stopMonitoring(monitorPathId);
                logger.info("✓ Native monitor stopped: id=" + monitorPathId);
            } catch (Throwable t) {
                logger.error("✗ Native monitor stop failed (id=" + monitorPathId + "): " + t.getMessage());
            }
        });
    }

    /**
     * Advanced: Get native monitor performance metrics
     * @return Performance stats from C DLL (event processing speed, etc.)
     */
    public String getMonitorStats() {
        try {
            return getNativeMonitorStats();
        } catch (Exception e) {
            logger.warn("Could not retrieve native monitor stats: " + e.getMessage());
            return "Stats unavailable";
        }
    }


    /**
     * Utility: Check if FileMonitor.dll is properly loaded
     * @return true if DLL is available
     */
    public static boolean isDllLoaded() {
        return dllLoaded;
    }

    /**
     * Sanitize a Windows path string:
     * - Trim whitespace
     * - Normalize backslashes
     * - Reject control characters
     */
    private static String sanitizePath(String raw) {
        if (raw == null) return null;
        String s = raw.trim();

        // Replace forward slashes with backslashes for consistency
        s = s.replace('/', '\\');

        // Basic control character check
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c)) {
                return null;
            }
        }
        return s;
    }

    /**
     * Java callback interface for C DLL events
     * Called from C code via JNI when file events are detected
     *
     * Integration with database:
     * - Events stored in `file_events` table
     * - Real-time triggers update `space_optimization_stats`
     */
    @FunctionalInterface
    public interface NativeFileEventCallback {
        /**
         * Invoked by C DLL when file system event is detected
         *
         * @param monitorPathId Database ID of the monitored path (from monitor_paths table)
         * @param fullPath Complete path to affected file
         * @param fileName Simple file name (not full path)
         * @param action Type of file event: CREATE, MODIFY, DELETE, RENAME
         * @param fileSize Size of file in bytes
         * @param timestamp Event timestamp (ISO format or Unix timestamp)
         */
        void onNativeFileEvent(
                int monitorPathId,
                String fullPath,
                String fileName,
                String action,
                long fileSize,
                String timestamp
        );
    }
}
