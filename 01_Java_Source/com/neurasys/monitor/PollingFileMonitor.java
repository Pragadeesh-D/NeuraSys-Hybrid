package com.neurasys.monitor;

import com.neurasys.model.FileEvent;
import com.neurasys.model.MonitorConfig;

import java.io.File;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class PollingFileMonitor implements Runnable {
    private final File folder;
    private final long intervalMillis;
    private final Consumer<FileEvent> eventConsumer;
    private final MonitorConfig config;

    public PollingFileMonitor(MonitorConfig config, long intervalMillis, Consumer<FileEvent> eventConsumer) {
        this.folder = new File(config.getPathLocation());
        this.intervalMillis = intervalMillis;
        this.eventConsumer = eventConsumer;
        this.config = config;
    }

    @Override
    public void run() {
        Map<String, Long> lastModifiedMap = new HashMap<>();

        while (!Thread.currentThread().isInterrupted()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    String path = file.getAbsolutePath();
                    long lastModified = file.lastModified();

                    if (!lastModifiedMap.containsKey(path)) {
                        lastModifiedMap.put(path, lastModified);
                        eventConsumer.accept(createEvent(file, "CREATE"));
                    } else if (lastModifiedMap.get(path) != lastModified) {
                        lastModifiedMap.put(path, lastModified);
                        eventConsumer.accept(createEvent(file, "MODIFY"));
                    }
                }

                lastModifiedMap.keySet().removeIf(path -> {
                    File f = new File(path);
                    if (!f.exists()) {
                        eventConsumer.accept(createEvent(f, "DELETE"));
                        return true;
                    }
                    return false;
                });
            }

            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ✅ This is where your line goes
    private FileEvent createEvent(File file, String action) {
        return new FileEvent(
                0, // id placeholder
                config.getId(),
                config.getPathName(),
                file.getName(),
                file.getAbsolutePath(),
                action,
                file.length(),
                LocalDateTime.now(),
                "POLLING"
        );
    }
}
