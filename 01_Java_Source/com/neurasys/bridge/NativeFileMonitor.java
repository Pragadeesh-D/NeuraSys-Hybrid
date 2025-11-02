package com.neurasys.bridge;

import com.neurasys.util.Logger;

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

    static {
        try {
            // Load FileMonitor.dll from classpath (src/main/resources/)
            System.loadLibrary("FileMonitor");
            logger.info("✓ Native FileMonitor.dll loaded successfully (v14.2.0 - MSYS2 GCC)");
        } catch (UnsatisfiedLinkError e) {
            logger.error("✗ Failed to load FileMonitor.dll: " + e.getMessage());
            logger.error("Troubleshooting steps:");
            logger.error("  1. Ensure FileMonitor.dll is in: src/main/resources/");
            logger.error("  2. Check C:\\msys64\\ucrt64\\bin is in system PATH");
            logger.error("  3. Verify DLL was compiled with: gcc -shared -o FileMonitor.dll FileMonitor.c");
            logger.error("  4. Run: mvn clean package (to copy DLL to target/classes/)");
        }
    }

    /**
     * Start real-time native monitoring on a local directory
     * Uses Windows ReadDirectoryChangesW API for immediate event detection
     *
     * @param monitorPathId Database ID of monitor path (for event tracking)
     * @param path Full directory path to monitor (e.g., "D:\\Documents")
     * @param callback Java callback handler to receive native C events
     *
     * @throws IllegalArgumentException if path is invalid
     * @see NativeFileEventCallback
     */
    public native void startMonitoring(int monitorPathId, String path, NativeFileEventCallback callback);

    /**
     * Stop monitoring on a specific monitor path
     * Cleans up Windows directory handles
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
         *
         * Processing flow:
         * 1. Callback received from C native code
         * 2. Java filters system/temp files
         * 3. Database insert to file_events table
         * 4. Backup triggered (if configured)
         * 5. Dashboard updated in real-time
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

    private native String getNativeMonitorStats();

    /**
     * Utility: Check if FileMonitor.dll is properly loaded
     * @return true if DLL is available
     */
    private static boolean dllLoaded = false;  // Track DLL status

    static {
        try {
            System.loadLibrary("FileMonitor");
            dllLoaded = true;  // ✅ Set when loaded successfully
        } catch (UnsatisfiedLinkError e) {
            dllLoaded = false;  // ✅ Set on failure
        }
    }

    public static boolean isDllLoaded() {
        return dllLoaded;  // ✅ FIXED: Use static variable
    }

}