package com.neurasys.model;

import javafx.beans.property.*;

/**
 * ✅ HYBRID MonitorConfig - Configuration for a monitored folder path
 *
 * Integrated with new database schema:
 * - Maps to monitor_paths table
 * - NEW: monitor_method property (NATIVE, JAVA_WATCH, POLLING)
 * - Supports LOCAL, NETWORK, ONEDRIVE paths
 * - Tracks compression, deduplication, incremental backup settings
 */
public class MonitorConfig {
    private final IntegerProperty id = new SimpleIntegerProperty();
    private final StringProperty pathName = new SimpleStringProperty();
    private final StringProperty pathLocation = new SimpleStringProperty();
    private final StringProperty backupLocation = new SimpleStringProperty();
    private final StringProperty pathType = new SimpleStringProperty("LOCAL");
    private final StringProperty monitorMethod = new SimpleStringProperty("NATIVE");  // NEW: NATIVE, JAVA_WATCH, POLLING
    private final BooleanProperty enabled = new SimpleBooleanProperty(true);

    // NEW: Backup optimization settings
    private final BooleanProperty compressionEnabled = new SimpleBooleanProperty(true);
    private final BooleanProperty deduplicationEnabled = new SimpleBooleanProperty(true);
    private final BooleanProperty incrementalEnabled = new SimpleBooleanProperty(true);

    // Constructor
    public MonitorConfig() {
    }

    // ============ PROPERTIES ============

    public IntegerProperty idProperty() {
        return id;
    }

    public StringProperty pathNameProperty() {
        return pathName;
    }

    public StringProperty pathLocationProperty() {
        return pathLocation;
    }

    public StringProperty backupLocationProperty() {
        return backupLocation;
    }

    public StringProperty pathTypeProperty() {
        return pathType;
    }

    public StringProperty monitorMethodProperty() {  // NEW
        return monitorMethod;
    }

    public BooleanProperty enabledProperty() {
        return enabled;
    }

    public BooleanProperty compressionEnabledProperty() {  // NEW
        return compressionEnabled;
    }

    public BooleanProperty deduplicationEnabledProperty() {  // NEW
        return deduplicationEnabled;
    }

    public BooleanProperty incrementalEnabledProperty() {  // NEW
        return incrementalEnabled;
    }

    // ============ GETTERS ============

    public int getId() {
        return id.get();
    }

    public String getPathName() {
        return pathName.get();
    }

    public String getPathLocation() {
        return pathLocation.get();
    }

    public String getBackupLocation() {
        return backupLocation.get();
    }

    public String getPathType() {
        return pathType.get();
    }

    public String getMonitorMethod() {  // NEW
        return monitorMethod.get();
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public boolean isCompressionEnabled() {  // NEW
        return compressionEnabled.get();
    }

    public boolean isDeduplicationEnabled() {  // NEW
        return deduplicationEnabled.get();
    }

    public boolean isIncrementalEnabled() {  // NEW
        return incrementalEnabled.get();
    }

    // ============ SETTERS ============

    public void setId(int value) {
        id.set(value);
    }

    public void setPathName(String value) {
        pathName.set(value);
    }

    public void setPathLocation(String value) {
        pathLocation.set(value);
    }

    public void setBackupLocation(String value) {
        backupLocation.set(value);
    }

    public void setPathType(String value) {
        pathType.set(value);
    }

    public void setMonitorMethod(String value) {  // NEW
        monitorMethod.set(value);
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    public void setCompressionEnabled(boolean value) {  // NEW
        compressionEnabled.set(value);
    }

    public void setDeduplicationEnabled(boolean value) {  // NEW
        deduplicationEnabled.set(value);
    }

    public void setIncrementalEnabled(boolean value) {  // NEW
        incrementalEnabled.set(value);
    }

    @Override
    public String toString() {
        return "MonitorConfig{" +
                "id=" + id.get() +
                ", pathName='" + pathName.get() + '\'' +
                ", pathType='" + pathType.get() + '\'' +
                ", monitorMethod='" + monitorMethod.get() + '\'' +
                ", enabled=" + enabled.get() +
                ", compression=" + compressionEnabled.get() +
                ", dedup=" + deduplicationEnabled.get() +
                ", incremental=" + incrementalEnabled.get() +
                '}';
    }
}