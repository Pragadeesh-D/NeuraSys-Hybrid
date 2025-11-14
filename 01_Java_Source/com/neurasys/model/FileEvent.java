package com.neurasys.model;

import javafx.beans.property.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ✅ HYBRID FileEvent - Represents a file system event
 *
 * Integrated with new database schema:
 * - Maps to file_events table (from C monitor or Java WatchService)
 * - Stores event_source: NATIVE (C DLL), JAVA_WATCH, or POLLING
 * - Linked to monitor_paths for path identification
 */
public class FileEvent {
    private final IntegerProperty id = new SimpleIntegerProperty();
    private final IntegerProperty monitorPathId = new SimpleIntegerProperty();
    private final StringProperty monitorPathName = new SimpleStringProperty();
    private final StringProperty fileName = new SimpleStringProperty();
    private final StringProperty filePath = new SimpleStringProperty();
    private final StringProperty action = new SimpleStringProperty();
    private final LongProperty fileSize = new SimpleLongProperty();
    private final StringProperty timestamp = new SimpleStringProperty();
    private final StringProperty eventSource = new SimpleStringProperty("NATIVE");  // NEW: Track event source

    // Constructor 1: Basic (backward compatible)
    public FileEvent(int id, int monitorPathId, String monitorPathName, String fileName,
                     String filePath, String action, long fileSize, LocalDateTime timestamp) {
        this(id, monitorPathId, monitorPathName, fileName, filePath, action, fileSize, timestamp, "NATIVE");
    }

    // Constructor 2: With event source (NEW)
    public FileEvent(int id, int monitorPathId, String monitorPathName, String fileName,
                     String filePath, String action, long fileSize, LocalDateTime timestamp, String eventSource) {
        this.id.set(id);
        this.monitorPathId.set(monitorPathId);
        this.monitorPathName.set(monitorPathName);
        this.fileName.set(fileName);
        this.filePath.set(filePath);
        this.action.set(action);
        this.fileSize.set(fileSize);
        this.timestamp.set(timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        this.eventSource.set(eventSource);
    }

    // Constructor 3: Accept timestamp as String (for JNI callbacks)
    public FileEvent(int id, int monitorPathId, String monitorPathName, String fileName,
                     String filePath, String action, long fileSize, String timestamp, String eventSource) {
        this.id.set(id);
        this.monitorPathId.set(monitorPathId);
        this.monitorPathName.set(monitorPathName);
        this.fileName.set(fileName);
        this.filePath.set(filePath);
        this.action.set(action);
        this.fileSize.set(fileSize);
        this.timestamp.set(timestamp); // already formatted string
        this.eventSource.set(eventSource);
    }

    // ============ PROPERTIES ============

    public IntegerProperty idProperty() { return id; }
    public IntegerProperty monitorPathIdProperty() { return monitorPathId; }
    public StringProperty monitorPathNameProperty() { return monitorPathName; }
    public StringProperty fileNameProperty() { return fileName; }
    public StringProperty filePathProperty() { return filePath; }
    public StringProperty actionProperty() { return action; }
    public LongProperty fileSizeProperty() { return fileSize; }
    public StringProperty timestampProperty() { return timestamp; }
    public StringProperty eventSourceProperty() { return eventSource; }  // NEW

    // ============ GETTERS ============

    public int getId() { return id.get(); }
    public int getMonitorPathId() { return monitorPathId.get(); }
    public String getMonitorPathName() { return monitorPathName.get(); }
    public String getFileName() { return fileName.get(); }
    public String getFilePath() { return filePath.get(); }
    public String getAction() { return action.get(); }
    public long getFileSize() { return fileSize.get(); }
    public String getTimestamp() { return timestamp.get(); }
    public String getLogTime() { return getTimestamp(); }  // Alias
    public String getEventSource() { return eventSource.get(); }  // NEW

    // ============ SETTERS ============

    public void setEventSource(String source) { eventSource.set(source); }  // NEW

    @Override
    public String toString() {
        return String.format(
                "FileEvent[id=%d, path=%s, file=%s, action=%s, source=%s]",
                getId(), getMonitorPathName(), getFileName(), getAction(), getEventSource()
        );
    }
}
