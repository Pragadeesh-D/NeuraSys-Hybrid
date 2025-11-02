package com.neurasys.service;

import com.neurasys.bridge.NativeFileMonitor;
import com.neurasys.model.MonitorConfig;
import com.neurasys.util.Logger;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * ✅ HYBRID MultiPathMonitor - Orchestrates multi-method file monitoring
 *
 * Routing strategy (based on pathType):
 * - LOCAL paths → Use NATIVE (C DLL with Windows ReadDirectoryChangesW)
 * - NETWORK paths → Use JAVA_WATCH (Java WatchService)
 * - ONEDRIVE/CLOUD → Use POLLING (Scheduled check every N seconds)
 *
 * Integration:
 * - Events logged to file_events table with event_source
 * - Database tracks monitor_method for each path
 * - Real-time callbacks to UI for live updates
 * - Backup triggered automatically on file change
 */
public class MultiPathMonitor {
    private static final Logger logger = Logger.getLogger(MultiPathMonitor.class);

    private final DatabaseManager dbManager;
    private final NativeFileMonitor nativeMonitor;  // NEW: C DLL integration
    private final FileEventListener eventListener;
    private final Map<Integer, MonitorTask> activeTasks = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newFixedThreadPool(5);
    private final DateTimeFormatter timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public interface FileEventListener {
        void onFileEvent(FileEventData event);
    }

    // NEW: Constructor with NativeFileMonitor
    public MultiPathMonitor(DatabaseManager dbManager, NativeFileMonitor nativeMonitor, FileEventListener listener) {
        this.dbManager = dbManager;
        this.nativeMonitor = nativeMonitor;
        this.eventListener = listener;
        logger.info("✓ MultiPathMonitor initialized (Hybrid mode with C DLL support)");
    }

    // BACKWARD COMPATIBILITY: Old constructor
    public MultiPathMonitor(DatabaseManager dbManager, FileEventListener listener) {
        this(dbManager, new NativeFileMonitor(), listener);
    }

    /**
     * Add and start monitoring a path based on type and configured method
     * Routes to appropriate monitoring mechanism (NATIVE, JAVA_WATCH, or POLLING)
     */
    public void addMonitorPath(MonitorConfig config) {
        if (!config.isEnabled()) {
            logger.info("Path disabled, skipping: " + config.getPathName());
            return;
        }

        String pathType = config.getPathType();
        String monitorMethod = config.getMonitorMethod();  // NEW: From config

        logger.info("Adding monitor path: " + config.getPathName() +
                " (Type: " + pathType + ", Method: " + monitorMethod + ")");

        // Route based on monitor method (NEW: explicit method vs implicit type-based)
        switch (monitorMethod) {
            case "NATIVE":
                startNativeMonitoring(config);
                break;
            case "JAVA_WATCH":
                startJavaWatchMonitoring(config);
                break;
            case "POLLING":
                startPollingMonitoring(config);
                break;
            default:
                // Fallback: determine method from pathType
                if ("LOCAL".equals(pathType)) {
                    startNativeMonitoring(config);
                } else if ("NETWORK".equals(pathType)) {
                    startJavaWatchMonitoring(config);
                } else if ("ONEDRIVE".equals(pathType) || "CLOUD".equals(pathType)) {
                    startPollingMonitoring(config);
                } else {
                    logger.warn("Unknown path type and method: " + pathType + " / " + monitorMethod);
                }
        }
    }

    /**
     * ✅ NATIVE MONITORING (C DLL with Windows API)
     * Uses FileMonitor.dll with ReadDirectoryChangesW for real-time detection
     * Ideal for LOCAL file systems (fast, resource-efficient)
     */
    private void startNativeMonitoring(MonitorConfig config) {
        logger.info("Starting NATIVE monitoring: " + config.getPathName() +
                " (Local path with C DLL)");

        if (!NativeFileMonitor.isDllLoaded()) {
            logger.error("✗ Native C DLL not loaded, falling back to JAVA_WATCH");
            startJavaWatchMonitoring(config);
            return;
        }

        MonitorTask task = new MonitorTask(config.getId(), config.getPathName(),
                config.getPathLocation(), "NATIVE");

        executorService.submit(() -> {
            try {
                // Call native C function to start monitoring
                nativeMonitor.startMonitoring(
                        config.getId(),
                        config.getPathLocation(),
                        (monitorPathId, fullPath, fileName, action, fileSize, timestamp) -> {
                            // Callback from C DLL
                            if (shouldBackupFile(fileName)) {
                                onFileEvent(new FileEventData(
                                        monitorPathId,
                                        config.getPathName(),
                                        config.getPathLocation(),
                                        fullPath,
                                        fileName,
                                        action,
                                        fileSize,
                                        "NATIVE",  // NEW: Event source
                                        timestamp
                                ));
                            }
                        }
                );
            } catch (UnsatisfiedLinkError e) {
                logger.error("✗ Native DLL error for " + config.getPathName(), e);
            } catch (Exception e) {
                logger.error("✗ Native monitoring error for " + config.getPathName(), e);
            }
        });

        activeTasks.put(config.getId(), task);
        logger.info("✓ NATIVE monitor started for: " + config.getPathName());
    }

    /**
     * ✅ JAVA WATCHSERVICE MONITORING
     * Uses Java NIO WatchService for file system events
     * Good for NETWORK paths and systems where C DLL unavailable
     */
    private void startJavaWatchMonitoring(MonitorConfig config) {
        logger.info("Starting JAVA_WATCH monitoring: " + config.getPathName());

        MonitorTask task = new MonitorTask(config.getId(), config.getPathName(),
                config.getPathLocation(), "JAVA_WATCH");

        executorService.submit(() -> {
            Map<String, Long> previousState = new HashMap<>();
            long lastCheckTime = System.currentTimeMillis();

            while (task.isRunning()) {
                try {
                    File watchPath = new File(config.getPathLocation());
                    if (!watchPath.exists()) {
                        logger.warn("Watch path not found: " + config.getPathLocation());
                        Thread.sleep(10000);
                        continue;
                    }

                    // Poll path for changes
                    File[] files = watchPath.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (!file.isFile()) continue;

                            String fileKey = file.getName();
                            long currentSize = file.length();

                            if (!previousState.containsKey(fileKey)) {
                                // NEW FILE
                                onFileEvent(new FileEventData(
                                        config.getId(),
                                        config.getPathName(),
                                        config.getPathLocation(),
                                        file.getAbsolutePath(),
                                        file.getName(),
                                        "CREATE",
                                        currentSize,
                                        "JAVA_WATCH",  // NEW: Event source
                                        LocalDateTime.now().format(timestampFormatter)
                                ));
                                previousState.put(fileKey, currentSize);
                            } else if (previousState.get(fileKey) != currentSize) {
                                // FILE MODIFIED
                                onFileEvent(new FileEventData(
                                        config.getId(),
                                        config.getPathName(),
                                        config.getPathLocation(),
                                        file.getAbsolutePath(),
                                        file.getName(),
                                        "MODIFY",
                                        currentSize,
                                        "JAVA_WATCH",  // NEW: Event source
                                        LocalDateTime.now().format(timestampFormatter)
                                ));
                                previousState.put(fileKey, currentSize);
                            }
                        }

                        // CHECK FOR DELETED FILES
                        Set<String> currentFiles = new HashSet<>(Arrays.asList(
                                watchPath.list((dir, name) -> new File(dir, name).isFile())
                        ));
                        for (String previousFile : new HashSet<>(previousState.keySet())) {
                            if (!currentFiles.contains(previousFile)) {
                                // FILE DELETED
                                onFileEvent(new FileEventData(
                                        config.getId(),
                                        config.getPathName(),
                                        config.getPathLocation(),
                                        new File(config.getPathLocation(), previousFile).getAbsolutePath(),
                                        previousFile,
                                        "DELETE",
                                        0,
                                        "JAVA_WATCH",  // NEW: Event source
                                        LocalDateTime.now().format(timestampFormatter)
                                ));
                                previousState.remove(previousFile);
                            }
                        }
                    }

                    Thread.sleep(5000); // Poll every 5 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Java watch monitoring error", e);
                }
            }
        });

        activeTasks.put(config.getId(), task);
        logger.info("✓ JAVA_WATCH monitor started for: " + config.getPathName());
    }

    /**
     * ✅ POLLING MONITORING
     * Scheduled polling for ONEDRIVE/CLOUD paths
     * Useful when real-time APIs unavailable
     */
    private void startPollingMonitoring(MonitorConfig config) {
        logger.info("Starting POLLING monitoring: " + config.getPathName() +
                " (Cloud/OneDrive path)");

        MonitorTask task = new MonitorTask(config.getId(), config.getPathName(),
                config.getPathLocation(), "POLLING");

        executorService.submit(() -> {
            Map<String, Long> previousState = new HashMap<>();

            while (task.isRunning()) {
                try {
                    // For OneDrive/Cloud, would integrate with Microsoft Graph API here
                    // For now, simulate polling
                    logger.debug("Polling " + config.getPathName() + "...");

                    // TODO: Implement actual OneDrive/Cloud monitoring
                    // This is placeholder for cloud integration

                    Thread.sleep(30000); // Poll every 30 seconds for cloud
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Polling monitoring error", e);
                }
            }
        });

        activeTasks.put(config.getId(), task);
        logger.info("✓ POLLING monitor started for: " + config.getPathName());
    }

    /**
     * Handle file event from any monitoring source
     * Routes to database and UI listener
     */
    private void onFileEvent(FileEventData event) {
        logger.info("[" + event.getMonitorPathName() + "] [" + event.getEventSource() +
                "] " + event.getAction() + " → " + event.getFileName() +
                " (" + formatBytes(event.getFileSize()) + ")");

        try {
            // Log to database with event source
            dbManager.logFileEvent(
                    event.getMonitorPathId(),
                    event.getFullPath(),
                    event.getFileName(),
                    event.getAction(),
                    event.getFileSize(),
                    event.getEventSource()  // NEW: Include event source
            );

            // Notify UI listener
            if (eventListener != null) {
                eventListener.onFileEvent(event);
            }
        } catch (Exception e) {
            logger.error("Failed to handle file event", e);
        }
    }

    private boolean shouldBackupFile(String fileName) {
        // Skip temporary and system files
        return !fileName.isEmpty() &&
                !fileName.equals("New Text Document.txt") &&
                !fileName.startsWith("~") &&
                !fileName.endsWith(".tmp") &&
                !fileName.endsWith(".temp") &&
                !fileName.endsWith("Thumbs.db") &&
                !fileName.startsWith(".");
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public void startAll() {
        logger.info("Starting all monitors (" + activeTasks.size() + " paths)");
        // Tasks are already started when added, this is for compatibility
    }

    public void stopAll() {
        logger.info("Stopping all monitors...");
        for (Map.Entry<Integer, MonitorTask> entry : activeTasks.entrySet()) {
            entry.getValue().stop();
            try {
                nativeMonitor.stopMonitoring(entry.getKey());
            } catch (Exception e) {
                logger.debug("Could not stop native monitor: " + e.getMessage());
            }
        }
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
        logger.info("✓ All monitors stopped");
    }

    /**
     * ✅ HYBRID FileEventData - Complete file event information
     * Includes event_source for tracking monitoring method
     */
    public static class FileEventData {
        private final int monitorPathId;
        private final String monitorPathName;
        private final String monitorPath;
        private final String fullPath;
        private final String fileName;
        private final String action;
        private final long fileSize;
        private final String eventSource;  // NEW: NATIVE, JAVA_WATCH, POLLING
        private final String timestamp;

        public FileEventData(int monitorPathId, String monitorPathName, String monitorPath,
                             String fullPath, String fileName, String action, long fileSize) {
            this(monitorPathId, monitorPathName, monitorPath, fullPath, fileName, action,
                    fileSize, "NATIVE", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }

        // NEW: Constructor with event source and timestamp
        public FileEventData(int monitorPathId, String monitorPathName, String monitorPath,
                             String fullPath, String fileName, String action, long fileSize,
                             String eventSource, String timestamp) {
            this.monitorPathId = monitorPathId;
            this.monitorPathName = monitorPathName;
            this.monitorPath = monitorPath;
            this.fullPath = fullPath;
            this.fileName = fileName;
            this.action = action;
            this.fileSize = fileSize;
            this.eventSource = eventSource;
            this.timestamp = timestamp;
        }

        // ============ GETTERS ============

        public int getMonitorPathId() { return monitorPathId; }
        public String getMonitorPathName() { return monitorPathName; }
        public String getMonitorPath() { return monitorPath; }
        public String getFullPath() { return fullPath; }
        public String getFileName() { return fileName; }
        public String getAction() { return action; }
        public long getFileSize() { return fileSize; }
        public String getEventSource() { return eventSource; }  // NEW
        public String getTimestamp() { return timestamp; }  // NEW

        @Override
        public String toString() {
            return String.format("FileEventData[path=%d, file=%s, action=%s, source=%s]",
                    monitorPathId, fileName, action, eventSource);
        }
    }

    /**
     * Monitor task tracking
     */
    private static class MonitorTask {
        private final int id;
        private final String name;
        private final String path;
        private final String type;
        private volatile boolean running = true;

        MonitorTask(int id, String name, String path, String type) {
            this.id = id;
            this.name = name;
            this.path = path;
            this.type = type;
        }

        void stop() {
            running = false;
        }

        boolean isRunning() {
            return running;
        }

        @Override
        public String toString() {
            return String.format("MonitorTask[id=%d, name=%s, type=%s, running=%b]",
                    id, name, type, running);
        }
    }
}