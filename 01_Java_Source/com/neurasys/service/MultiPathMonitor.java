package com.neurasys.service;

import com.neurasys.bridge.NativeFileMonitor;
import com.neurasys.model.FileEvent;
import com.neurasys.model.MonitorConfig;
import com.neurasys.monitor.OneDriveMonitor;
import com.neurasys.monitor.PollingFileMonitor;
import com.neurasys.util.Logger;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

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
    private String globalMonitorMethod = "JAVA_WATCH";
    private OneDriveMonitor oneDriveMonitor;
    private final DatabaseManager dbManager;
    private final Consumer<FileEvent> eventConsumer;
    private final NativeFileMonitor nativeMonitor;  // NEW: C DLL integration
    private final Map<Integer, MonitorTask> activeTasks = new ConcurrentHashMap<>();
    private final Map<String, Long> recentEventTimestamps = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final DateTimeFormatter timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @FunctionalInterface
    public interface FileEventCallback {
        void onFileEvent(MonitorConfig config, File file, String action, String source);
    }

    public interface FileEventListener {
        void onFileEvent(FileEvent event);
    }

    // NEW: Constructor with NativeFileMonitor
    public MultiPathMonitor(DatabaseManager dbManager, NativeFileMonitor nativeMonitor,
                            Consumer<FileEvent> eventConsumer, String globalMethod) {
        this.dbManager = dbManager;
        this.nativeMonitor = nativeMonitor;
        this.eventConsumer = eventConsumer;
        this.globalMonitorMethod = globalMethod;
        logger.info("✓ MultiPathMonitor initialized with global method: " + globalMethod);
    }

    // BACKWARD COMPATIBILITY: Old constructor
    public MultiPathMonitor(DatabaseManager dbManager, NativeFileMonitor nativeMonitor, Consumer<FileEvent> eventConsumer) {
        this(dbManager, nativeMonitor, eventConsumer, "JAVA_WATCH");
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
        String monitorMethod = config.getMonitorMethod();

        logger.info("=== ADDING MONITOR PATH ===");
        logger.info("  Name: " + config.getPathName());
        logger.info("  Location: " + config.getPathLocation());
        logger.info("  Type: " + pathType);
        logger.info("  Method: " + monitorMethod);
        logger.info("===========================");

        // 🧠 Resolve DEFAULT only for execution, not for storage
        String resolvedMethod = "DEFAULT".equalsIgnoreCase(monitorMethod)
                ? globalMonitorMethod
                : monitorMethod;

        if ("DEFAULT".equalsIgnoreCase(monitorMethod)) {
            logger.info("  → Resolved DEFAULT to: " + resolvedMethod);
        }

        // 🔁 Route based on resolved method
        switch (resolvedMethod.toUpperCase()) {
            case "NATIVE":
                logger.info("  → Starting NATIVE monitoring...");
                startNativeMonitoring(config);
                break;

            case "JAVA_WATCH":
                logger.info("  → Starting JAVA_WATCH monitoring...");
                startJavaWatchMonitoring(config);
                break;

            case "POLLING":
                logger.info("  → Starting POLLING monitoring...");
                startPollingMonitoring(config);
                break;

            case "ONEDRIVE":
                logger.info("  → Starting ONEDRIVE monitoring...");
                enableOneDriveMonitoring();;
                break;

            default:
                logger.warn("⚠ Unknown monitor method: " + resolvedMethod + " — using fallback JAVA_WATCH");
                startJavaWatchMonitoring(config);
        }
    }

    // call to enable
    public void enableOneDriveMonitoring() {
        if (oneDriveMonitor == null) {
            oneDriveMonitor = new OneDriveMonitor();
            oneDriveMonitor.addListener(new OneDriveMonitor.Listener() {
                @Override
                public void onEvent(FileEvent event) {
                    System.out.println("OneDrive event: " + event.getFilePath() + " [" + event.getAction() + "]");
                    // TODO: route to backup queue, logger, or analytics
                }

                @Override
                public void onError(String message) {
                    System.err.println("OneDriveMonitor error: " + message);
                }
            });
        }
        oneDriveMonitor.startMonitoring();
    }

    public void disableOneDriveMonitoring() {
        if (oneDriveMonitor != null) {
            oneDriveMonitor.stopMonitoring();
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
                            File file = new File(config.getPathLocation(), fileName);
                            // Callback from C DLL
                            if (shouldBackupFile(fileName)) {
                                onFileEvent(new FileEvent(
                                                0,                            // id placeholder
                                        config.getId(),               // monitorPathId
                                        config.getPathName(),         // monitorPathName
                                        file.getName(),               // fileName
                                        file.getAbsolutePath(),       // filePath
                                        "CREATE",                     // action
                                        file.length(),                // fileSize
                                        LocalDateTime.now(),          // timestamp as LocalDateTime
                                        "NATIVE"                  // eventSource
                                        )
                                );
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

            // Initialize snapshot to prevent treating existing files as CREATE on startup
            try {
                File watchPath = new File(config.getPathLocation());
                if (watchPath.exists()) {
                    File[] startFiles = watchPath.listFiles();
                    if (startFiles != null) {
                        for (File f : startFiles) {
                            if (f.isFile()) {
                                previousState.put(f.getName(), f.length());
                            }
                        }
                        logger.info("Initialized snapshot for " + config.getPathName() + " with " + previousState.size() + " files");
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to initialize snapshot for java watch: " + e.getMessage());
            }

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
                        Set<String> currentFileNames = new HashSet<>();
                        for (File file : files) {
                            if (!file.isFile()) continue;

                            String fileKey = file.getName();
                            long currentSize = file.length();
                            currentFileNames.add(fileKey);

                            if (!previousState.containsKey(fileKey)) {
                                // NEW FILE
                                onFileEvent(new FileEvent(
                                        0,
                                        config.getId(),
                                        config.getPathName(),
                                        file.getName(),
                                        file.getAbsolutePath(),
                                        "CREATE",
                                        currentSize,
                                        LocalDateTime.now(),
                                        "JAVA_WATCH"
                                ));
                                previousState.put(fileKey, currentSize);
                            } else if (previousState.get(fileKey) != currentSize) {
                                // FILE MODIFIED
                                onFileEvent(new FileEvent(
                                        0,
                                        config.getId(),
                                        config.getPathName(),
                                        file.getName(),
                                        file.getAbsolutePath(),
                                        "MODIFY",
                                        currentSize,
                                        LocalDateTime.now(),
                                        "JAVA_WATCH"
                                ));
                                previousState.put(fileKey, currentSize);
                            }
                        }

                        // CHECK FOR DELETED FILES
                        for (String previousFile : new HashSet<>(previousState.keySet())) {
                            if (!currentFileNames.contains(previousFile)) {
                                File file = new File(config.getPathLocation(), previousFile);
                                String fullPath = file.getAbsolutePath();
                                // FILE DELETED
                                onFileEvent(new FileEvent(
                                        0,
                                        config.getId(),
                                        config.getPathName(),
                                        previousFile,
                                        fullPath,
                                        "DELETE",
                                        0,
                                        LocalDateTime.now(),
                                        "JAVA_WATCH"
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
        PollingFileMonitor monitor = new PollingFileMonitor(config, 3000, eventConsumer);
        Thread pollingThread = new Thread(monitor);
        pollingThread.setDaemon(true);
        pollingThread.start();

        MonitorTask task = new MonitorTask(config.getId(), config.getPathName(),
                config.getPathLocation(), "POLLING");
        activeTasks.put(config.getId(), task);
    }

    private void startOneDriveMonitoring(MonitorConfig config) {
        logger.warn("⚠ OneDrive monitoring is not yet implemented for: " + config.getPathName());
        // Future: connect Microsoft Graph API to sync OneDrive folder changes
    }

    private boolean isDuplicateEvent(FileEvent e) {
        String key = e.getMonitorPathId() + "|" + e.getFileName() + "|" + e.getAction();
        long now = System.currentTimeMillis();
        Long prev = recentEventTimestamps.get(key);
        if (prev != null && (now - prev) < 1000) {
            return true; // duplicate within 1s
        }
        recentEventTimestamps.put(key, now);
        return false;
    }
    /**
     * Handle file event from any monitoring source
     * Routes to database and UI listener
     */
    private void onFileEvent(FileEvent event) {
        if (isDuplicateEvent(event)) {
            logger.debug("Ignored duplicate event: " + event);
            return;
        }

        logger.info("[" + event.getMonitorPathName() + "] [" + event.getEventSource() +
                "] " + event.getAction() + " → " + event.getFileName() +
                " (" + formatBytes(event.getFileSize()) + ")");

        try {
            dbManager.logFileEvent(
                    event.getMonitorPathId(),
                    event.getFilePath(),       // ✅ updated
                    event.getFileName(),
                    event.getAction(),
                    event.getFileSize(),
                    event.getEventSource()
            );

            if (eventConsumer != null) {
                eventConsumer.accept(event);  // ✅ now valid
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

    // Stop all monitors and recreate executor
    public void stopAll() {
        logger.info("Stopping all monitors...");
        for (Map.Entry<Integer, MonitorTask> entry : new HashMap<>(activeTasks).entrySet()) {
            entry.getValue().stop();
            try {
                nativeMonitor.stopMonitoring(entry.getKey());
            } catch (Exception e) {
                logger.debug("Could not stop native monitor: " + e.getMessage());
            }
            activeTasks.remove(entry.getKey());
        }
        // Do NOT shutdown the executorService here. Keep it alive so we can restart tasks.
        logger.info("✓ All monitors requested to stop (executor remains active)");
    }

    public void restartMonitorPath(MonitorConfig config) {
        try {
            int pathId = config.getId();

            MonitorTask existingTask = activeTasks.get(pathId);
            if (existingTask != null) {
                existingTask.stop();
                activeTasks.remove(pathId);
                logger.info("✓ Stopped monitor for path ID: " + pathId);
            }

            String methodToUse = config.getMonitorMethod();
            if ("DEFAULT".equalsIgnoreCase(methodToUse)) {
                methodToUse = globalMonitorMethod;
                logger.info("→ Resolved DEFAULT to: " + methodToUse);
            }

            config.setMonitorMethod(methodToUse);
            addMonitorPath(config);

            logger.info("✓ Restarted monitor for path ID: " + pathId + " with method: " + methodToUse);
        } catch (Exception e) {
            logger.error("✗ Failed to restart monitor for path ID: " + config.getId(), e);
        }
    }


    public void restartWithMethod(String newMethod) {
        logger.info("Restarting monitors with method: " + newMethod);

        // Stop all currently running monitoring tasks
        stopAll();

        activeTasks.clear();

        try {
            List<MonitorConfig> configs = dbManager.getAllActiveMonitorPaths();

            for (MonitorConfig config : configs) {
                config.setMonitorMethod(newMethod);
                addMonitorPath(config);
            }

            logger.info("✓ Restarted all monitoring paths with method: " + newMethod);
        } catch (Exception e) {
            logger.error("Failed to restart monitoring paths", e);
        }
    }

    public void removeMonitorPath(int pathId) {
        MonitorTask t = activeTasks.remove(pathId);
        if (t != null) {
            t.stop();
        }
        try {
            nativeMonitor.stopMonitoring(pathId);
        } catch (Exception e) {
            logger.debug("Could not stop native monitor for id " + pathId + ": " + e.getMessage());
        }
        logger.info("Removed monitor path ID: " + pathId);
    }

    public void stopAllMonitoring() {
        logger.info("🛑 Stopping all active monitor threads...");
        try {
            stopJavaWatchService();
            stopNativeWatchService();
            stopPollingService();
            stopOneDriveService(); // future

            logger.info("✓ All monitoring stopped successfully.");
        } catch (Exception e) {
            logger.error("❌ Error while stopping monitoring: ", e);
        }
    }

    private void stopJavaWatchService() {
        logger.info("→ Java Watch service stopped.");
        stopAll(); // ✅ This already stops JavaWatch threads and executor
    }

    private void stopNativeWatchService() {
        logger.info("→ Native monitoring service stopped.");
        for (Integer id : activeTasks.keySet()) {
            try {
                nativeMonitor.stopMonitoring(id);
            } catch (Exception e) {
                logger.debug("Native stop failed for id " + id + ": " + e.getMessage());
            }
        }
    }

    private void stopPollingService() {
        logger.info("→ Polling monitoring service stopped.");
        stopAll(); // ✅ Same executor handles polling threads
    }

    private void stopOneDriveService() {
        logger.info("→ OneDrive monitoring service stopped.");
        // Placeholder for future Graph API integration
    }

    /**
     * ✅ HYBRID FileEventData - Complete file event information
     * Includes event_source for tracking monitoring method
     */
    /*public static class FileEventData {
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
    }*/

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