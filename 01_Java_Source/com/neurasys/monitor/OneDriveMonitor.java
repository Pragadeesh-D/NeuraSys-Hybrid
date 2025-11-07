package com.neurasys.monitor;

import com.neurasys.model.FileEvent;

import java.io.File;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * OneDriveMonitor: Polls OneDrive folder and emits FileEvent objects.
 * Uses internal listener interface to avoid external dependencies.
 */
public class OneDriveMonitor {

    public interface Listener {
        void onEvent(FileEvent event);
        void onError(String message);
    }

    private final Path oneDrivePath;
    private final long pollIntervalMs;
    private final Timer timer = new Timer(true);
    private TimerTask task;
    private final Map<String, Long> lastSeen = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();

    public OneDriveMonitor() {
        this(5000L);
    }

    public OneDriveMonitor(long pollIntervalMs) {
        this.pollIntervalMs = Math.max(1000L, pollIntervalMs);
        this.oneDrivePath = resolveOneDrivePath();
    }

    private Path resolveOneDrivePath() {
        String userHome = System.getProperty("user.home", "");
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            return Paths.get(userHome, "OneDrive");
        } else if (os.contains("mac")) {
            Path macPath = Paths.get(userHome, "Library", "CloudStorage", "OneDrive");
            return Files.exists(macPath) ? macPath : Paths.get(userHome, "OneDrive");
        } else {
            return Paths.get(userHome, "OneDrive");
        }
    }

    public void addListener(Listener l) {
        if (l != null) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public boolean isAvailable() {
        return Files.exists(oneDrivePath) && Files.isDirectory(oneDrivePath) && Files.isReadable(oneDrivePath);
    }

    public Path getOneDrivePath() {
        return oneDrivePath;
    }

    public synchronized void startMonitoring() {
        if (task != null) return;

        if (!isAvailable()) {
            notifyError("OneDrive path not found or inaccessible: " + oneDrivePath);
            return;
        }

        snapshotDirectory(oneDrivePath);

        task = new TimerTask() {
            @Override
            public void run() {
                try {
                    pollDirectory(oneDrivePath);
                } catch (Exception e) {
                    notifyError("OneDriveMonitor error: " + e.getMessage());
                }
            }
        };

        timer.scheduleAtFixedRate(task, 0, pollIntervalMs);
    }

    public synchronized void stopMonitoring() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void snapshotDirectory(Path dir) {
        File[] files = dir.toFile().listFiles();
        if (files == null) return;
        for (File f : files) {
            lastSeen.put(f.getAbsolutePath(), f.lastModified());
        }
    }

    private void pollDirectory(Path dir) {
        File[] currentFiles = dir.toFile().listFiles();
        Set<String> currentPaths = new HashSet<>();
        if (currentFiles != null) {
            for (File f : currentFiles) {
                String abs = f.getAbsolutePath();
                currentPaths.add(abs);
                long modified = f.lastModified();
                Long prev = lastSeen.get(abs);

                if (prev == null) {
                    lastSeen.put(abs, modified);
                    notifyEvent(createEvent(f, "CREATED"));
                } else if (modified != prev) {
                    lastSeen.put(abs, modified);
                    notifyEvent(createEvent(f, "MODIFIED"));
                }
            }
        }

        Iterator<Map.Entry<String, Long>> it = lastSeen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (!currentPaths.contains(e.getKey())) {
                it.remove();
                notifyEvent(createEvent(new File(e.getKey()), "DELETED"));
            }
        }
    }

    private FileEvent createEvent(File file, String action) {
        return new FileEvent(
                0, // id
                -1, // monitorPathId
                "OneDrive", // monitorPathName
                file.getName(),
                file.getAbsolutePath(),
                action,
                file.length(),
                LocalDateTime.now(),
                "ONEDRIVE"
        );
    }

    private void notifyEvent(FileEvent ev) {
        for (Listener l : listeners) {
            try { l.onEvent(ev); } catch (Exception ignored) {}
        }
    }

    private void notifyError(String msg) {
        for (Listener l : listeners) {
            try { l.onError(msg); } catch (Exception ignored) {}
        }
    }
}
