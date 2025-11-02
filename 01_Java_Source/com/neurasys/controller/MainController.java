package com.neurasys.controller;

import com.neurasys.bridge.NativeFileMonitor;
import com.neurasys.model.BackupStats;
import com.neurasys.model.FileEvent;
import com.neurasys.model.MonitorConfig;
import com.neurasys.service.BackupManager;
import com.neurasys.service.DatabaseManager;
import com.neurasys.service.MultiPathMonitor;
import com.neurasys.util.Logger;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ✅ HYBRID MainController for NeuraSys
 *
 * Coordinates:
 * 1. Native C file monitoring (FileMonitor.dll) via JNI
 * 2. Java fallback monitoring (WatchService) for network/cloud paths
 * 3. Database operations with new hybrid schema
 * 4. Backup and restoration workflows
 * 5. Real-time dashboard updates
 *
 * Database Integration:
 * - monitor_paths: Track all monitored directories and their methods (NATIVE, JAVA_WATCH, POLLING)
 * - file_events: Real-time events from C monitor or Java fallback
 * - file_versions: Backup versions with compression/deduplication metrics
 * - space_optimization_stats: Daily aggregated statistics
 */
public class MainController {
    private static final Logger logger = Logger.getLogger(MainController.class);

    // ==================== UI Components ====================

    // Dashboard Labels
    @FXML private Label lblTotalBackups;
    @FXML private Label lblSpaceSaved;
    @FXML private Label lblSpaceSavedDetails;
    @FXML private Label lblFilesMonitored;
    @FXML private Label lblMonitorPaths;
    @FXML private TextArea txtActivityFeed;
    @FXML private Label lblMonitorStatus;
    @FXML private Button btnStartStop;
    @FXML private Label lblStatus;
    @FXML private Label lblDbStatus;
    @FXML private Label lblCMonitorStatus;
    @FXML private Label lblMonitorMethod;  // NEW: Show NATIVE vs JAVA

    // Monitor Paths Table
    @FXML private TableView<MonitorConfig> tblMonitorPaths;
    @FXML private TableColumn<MonitorConfig, String> colPathName;
    @FXML private TableColumn<MonitorConfig, String> colPathLocation;
    @FXML private TableColumn<MonitorConfig, String> colBackupLocation;
    @FXML private TableColumn<MonitorConfig, String> colPathType;
    @FXML private TableColumn<MonitorConfig, String> colMonitorMethod;  // NEW: Display monitor method
    @FXML private TableColumn<MonitorConfig, Boolean> colPathEnabled;

    // Settings
    @FXML private TextField txtDbHost;
    @FXML private TextField txtDbName;
    @FXML private TextField txtDbUser;
    @FXML private TextField txtBackupFilePath;
    @FXML private TextField txtRestoreLocation;
    @FXML private PasswordField txtDbPassword;
    @FXML private Label lblConnectionStatus;
    @FXML private CheckBox chkCompression;
    @FXML private CheckBox chkDeduplication;
    @FXML private CheckBox chkIncremental;
    @FXML private Spinner<Integer> spinCompressionLevel;
    @FXML private CheckBox chkRetentionEnabled;
    @FXML private Spinner<Integer> spinRetentionDays;
    @FXML private Spinner<Integer> spinMinVersions;

    // Logs
    @FXML private TableView<FileEvent> tblLogs;
    @FXML private TableColumn<FileEvent, Integer> colLogId;
    @FXML private TableColumn<FileEvent, String> colLogFile;
    @FXML private TableColumn<FileEvent, String> colLogAction;
    @FXML private TableColumn<FileEvent, Long> colLogSize;
    @FXML private TableColumn<FileEvent, String> colLogTime;
    @FXML private TextField txtSearchLogs;
    @FXML private ComboBox<String> cmbFilterAction;
    @FXML private ComboBox<String> cmbFilterEventSource;  // NEW: Filter by event source
    @FXML private Label lblTotalLogs;
    @FXML private Label lblLastUpdate;

    // Analytics
    @FXML private Label lblCompressionSavings;
    @FXML private Label lblDeduplicationSavings;
    @FXML private Label lblIncrementalSavings;
    @FXML private Label lblTotalSavings;
    @FXML private Label lblTotalSavingsDetails;
    @FXML private Label lblRestoreStatus;
    @FXML private ProgressBar pbCompression;
    @FXML private ProgressBar pbDeduplication;
    @FXML private ProgressBar pbIncremental;

    @FXML private Button btnBrowseBackup;
    @FXML private Button btnBrowseRestore;
    @FXML private Button btnRestoreBackup;

    // ==================== Service Components ====================

    private DatabaseManager dbManager;
    private BackupManager backupManager;
    private MultiPathMonitor multiPathMonitor;
    private NativeFileMonitor nativeMonitor;  // NEW: C DLL monitor

    private boolean isMonitoring = false;

    // Data
    private ObservableList<MonitorConfig> monitorPaths;
    private ObservableList<FileEvent> logsList;

    // ==================== Initialization ====================

    @FXML
    public void initialize() {
        logger.info("Initializing MainController (Hybrid Version)...");

        monitorPaths = FXCollections.observableArrayList();
        logsList = FXCollections.observableArrayList();

        // Initialize native C monitor
        nativeMonitor = new NativeFileMonitor();
        logger.info("Native monitor status: " + (NativeFileMonitor.isDllLoaded() ? "✓ Ready" : "✗ Not loaded"));

        // Try to initialize database early
        try {
            dbManager = new DatabaseManager("localhost", "NeuraSysDB", "neurasys_app", "NeuraSys@2025!");
            logger.info("✓ Initial database connection successful");
            lblDbStatus.setStyle("-fx-text-fill: #27ae60;");
        } catch (Exception e) {
            logger.warn("Could not initialize database early: " + e.getMessage());
            dbManager = null;
            lblDbStatus.setStyle("-fx-text-fill: #e74c3c;");
        }

        // Check native C monitor
        if (NativeFileMonitor.isDllLoaded()) {
            lblCMonitorStatus.setStyle("-fx-text-fill: #27ae60;");
            lblCMonitorStatus.setText("C Monitor: Ready");
        } else {
            lblCMonitorStatus.setStyle("-fx-text-fill: #e74c3c;");
            lblCMonitorStatus.setText("C Monitor: Not loaded");
        }

        setupMonitorPathsTable();
        setupLogsTable();
        setupSettings();

        // Setup filter ComboBox
        cmbFilterAction.getItems().addAll("All", "CREATE", "MODIFY", "DELETE", "RENAME");
        cmbFilterAction.setValue("All");
        cmbFilterAction.setOnAction(e -> handleFilterChange());

        // Setup event source filter (NEW)
        cmbFilterEventSource.getItems().addAll("All", "NATIVE", "JAVA_WATCH", "POLLING");
        cmbFilterEventSource.setValue("All");
        cmbFilterEventSource.setOnAction(e -> handleFilterChange());

        // Setup search field
        txtSearchLogs.textProperty().addListener((obs, oldVal, newVal) -> handleSearchLogs());

        // Initial load
        refreshLogsTable();

        updateStatus("Ready");
        logger.info("✓ MainController initialized (Hybrid mode)");
    }

    // ==================== Filter & Search ====================

    @FXML
    private void handleFilterChange() {
        if (dbManager == null) return;

        String actionFilter = cmbFilterAction.getValue();
        String sourceFilter = cmbFilterEventSource.getValue();

        try {
            ObservableList<FileEvent> logs;

            if ("All".equals(actionFilter) && "All".equals(sourceFilter)) {
                logs = dbManager.getRecentEvents(100);
            } else if ("All".equals(sourceFilter)) {
                logs = dbManager.getEventsByAction(actionFilter, 100);
            } else if ("All".equals(actionFilter)) {
                logs = dbManager.getEventsBySource(sourceFilter, 100);  // NEW: Filter by event source
            } else {
                logs = dbManager.getEventsByActionAndSource(actionFilter, sourceFilter, 100);  // NEW
            }

            tblLogs.setItems(logs);
            lblTotalLogs.setText(String.valueOf(logs.size()));
        } catch (Exception e) {
            logger.error("Failed to filter logs", e);
        }
    }
    private void handleSearchLogs() {
        String searchText = txtSearchLogs.getText().toLowerCase();
        if (searchText.isEmpty()) {
            refreshLogsTable();
            return;
        }

        ObservableList<FileEvent> allLogs = dbManager.getRecentEvents(1000);
        ObservableList<FileEvent> filtered = allLogs.filtered(log ->
                log.getFileName().toLowerCase().contains(searchText) ||
                        log.getFilePath().toLowerCase().contains(searchText)
        );
        tblLogs.setItems(filtered);
        lblTotalLogs.setText(String.valueOf(filtered.size()));
    }

    // ==================== Table Setup ====================

    private void setupMonitorPathsTable() {
        tblMonitorPaths.setEditable(true);

        colPathName.setCellValueFactory(new PropertyValueFactory<>("pathName"));
        colPathName.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        colPathName.setOnEditCommit(event -> {
            event.getRowValue().setPathName(event.getNewValue());
            addActivityLog("Updated path name to: " + event.getNewValue(), "INFO");
        });

        colPathLocation.setCellValueFactory(new PropertyValueFactory<>("pathLocation"));
        colPathLocation.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        colPathLocation.setOnEditCommit(event -> {
            event.getRowValue().setPathLocation(event.getNewValue());
            addActivityLog("Updated monitor location to: " + event.getNewValue(), "INFO");
        });

        colBackupLocation.setCellValueFactory(new PropertyValueFactory<>("backupLocation"));
        colBackupLocation.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        colBackupLocation.setOnEditCommit(event -> {
            event.getRowValue().setBackupLocation(event.getNewValue());
            addActivityLog("Updated backup location to: " + event.getNewValue(), "INFO");
        });

        colPathType.setCellValueFactory(new PropertyValueFactory<>("pathType"));
        colPathType.setCellFactory(javafx.scene.control.cell.ComboBoxTableCell.forTableColumn(
                "LOCAL", "NETWORK", "CLOUD", "ONEDRIVE"
        ));
        colPathType.setOnEditCommit(event -> {
            event.getRowValue().setPathType(event.getNewValue());
            addActivityLog("Updated path type to: " + event.getNewValue(), "INFO");
        });

        // NEW: Monitor method column (NATIVE, JAVA_WATCH, POLLING)
        colMonitorMethod.setCellValueFactory(new PropertyValueFactory<>("monitorMethod"));
        colMonitorMethod.setCellFactory(javafx.scene.control.cell.ComboBoxTableCell.forTableColumn(
                "NATIVE", "JAVA_WATCH", "POLLING"
        ));
        colMonitorMethod.setOnEditCommit(event -> {
            event.getRowValue().setMonitorMethod(event.getNewValue());
            addActivityLog("Changed monitor method to: " + event.getNewValue(), "INFO");
        });

        colPathEnabled.setCellValueFactory(new PropertyValueFactory<>("enabled"));
        colPathEnabled.setCellFactory(javafx.scene.control.cell.CheckBoxTableCell.forTableColumn(colPathEnabled));

        tblMonitorPaths.setItems(monitorPaths);
    }

    private void setupLogsTable() {
        colLogId.setCellValueFactory(new PropertyValueFactory<>("id"));
        colLogFile.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        colLogAction.setCellValueFactory(new PropertyValueFactory<>("action"));
        colLogSize.setCellValueFactory(new PropertyValueFactory<>("fileSize"));
        colLogTime.setCellValueFactory(new PropertyValueFactory<>("logTime"));

        // Format file size
        colLogSize.setCellFactory(col -> new TableCell<FileEvent, Long>() {
            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(formatBytes(item));
                }
            }
        });

        // Color code actions
        colLogAction.setCellFactory(col -> new TableCell<FileEvent, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    switch (item) {
                        case "CREATE":
                            setStyle("-fx-text-fill: #27ae60; -fx-font-weight: bold;");
                            break;
                        case "MODIFY":
                            setStyle("-fx-text-fill: #f39c12; -fx-font-weight: bold;");
                            break;
                        case "DELETE":
                            setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
                            break;
                    }
                }
            }
        });

        tblLogs.setItems(logsList);
    }

    private void setupSettings() {
        txtDbHost.setText("localhost");
        txtDbName.setText("NeuraSysDB");
        txtDbUser.setText("neurasys_app");

        chkCompression.setSelected(true);
        chkDeduplication.setSelected(true);
        chkIncremental.setSelected(true);

        spinCompressionLevel.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 9, 9)
        );

        spinRetentionDays.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 365, 90)
        );

        spinMinVersions.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100, 5)
        );

        cmbFilterAction.setItems(FXCollections.observableArrayList(
                "All", "CREATE", "MODIFY", "DELETE", "RENAME"
        ));
        cmbFilterAction.setValue("All");
    }

    // ==================== Monitoring Controls ====================

    @FXML
    private void handleStartStop() {
        if (!isMonitoring) {
            startMonitoring();
        } else {
            stopMonitoring();
        }
    }

    private void startMonitoring() {
        try {
            // Initialize database
            dbManager = new DatabaseManager(
                    txtDbHost.getText(),
                    txtDbName.getText(),
                    txtDbUser.getText(),
                    txtDbPassword.getText()
            );

            // Initialize backup manager
            backupManager = new BackupManager(dbManager);

            // Initialize multi-path monitor (hybrid: native + Java fallback)
            multiPathMonitor = new MultiPathMonitor(dbManager, nativeMonitor, this::onFileEvent);

            // Add monitor paths to database and start monitoring
            for (MonitorConfig config : monitorPaths) {
                if (config.isEnabled()) {
                    try {
                        int pathId = dbManager.addMonitorPath(config);
                        config.setId(pathId);
                        multiPathMonitor.addMonitorPath(config);
                    } catch (Exception e) {
                        logger.error("Failed to add monitor path: " + config.getPathName(), e);
                    }
                }
            }

            // Start monitoring (hybrid mode)
            multiPathMonitor.startAll();

            isMonitoring = true;
            btnStartStop.setText("Stop Monitoring");
            btnStartStop.setStyle("-fx-background-color: #e74c3c;");
            lblMonitorStatus.setText("● ACTIVE (Hybrid)");
            lblMonitorStatus.setStyle("-fx-text-fill: #27ae60; -fx-font-weight: bold;");
            lblCMonitorStatus.setStyle("-fx-text-fill: #27ae60;");
            lblDbStatus.setStyle("-fx-text-fill: #27ae60;");
            lblMonitorMethod.setText("Method: NATIVE + JAVA_WATCH");
            lblMonitorMethod.setStyle("-fx-text-fill: #3498db;");

            updateStatus("Monitoring started (Hybrid mode)");
            addActivityLog("✓ Hybrid monitoring started with " + monitorPaths.size() + " paths", "SUCCESS");

        } catch (Exception e) {
            showError("Failed to start monitoring: " + e.getMessage());
            logger.error("Failed to start monitoring", e);
            updateStatus("Error: " + e.getMessage());
        }
    }

    private void stopMonitoring() {
        if (multiPathMonitor != null) {
            multiPathMonitor.stopAll();
        }

        isMonitoring = false;
        btnStartStop.setText("Start Monitoring");
        btnStartStop.setStyle("-fx-background-color: #27ae60;");
        lblMonitorStatus.setText("● STOPPED");
        lblMonitorStatus.setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
        lblCMonitorStatus.setStyle("-fx-text-fill: #95a5a6;");
        lblMonitorMethod.setText("Method: Inactive");

        updateStatus("Monitoring stopped");
        addActivityLog("⊘ Monitoring stopped", "INFO");
        updateDashboard();
    }

    // ==================== File Event Callback (from C & Java) ====================

    /**
     * Callback from native C monitor or Java WatchService
     * Stores events to database and triggers backups
     */
    private void onFileEvent(MultiPathMonitor.FileEventData event) {
        Platform.runLater(() -> {
            if (!shouldBackupFile(event.getFileName())) {
                logger.info("File filtered out: " + event.getFileName());
                return;
            }

            addActivityLog(
                    String.format("[%s] [%s] %s → %s (%s)",
                            event.getMonitorPathName(),
                            event.getEventSource(),  // Show NATIVE or JAVA_WATCH
                            event.getAction(),
                            event.getFileName(),
                            formatBytes(event.getFileSize())),
                    event.getAction()
            );

            // Store event in database
            if (dbManager != null) {
                try {
                    dbManager.logFileEvent(
                            event.getMonitorPathId(),
                            event.getFullPath(),
                            event.getFileName(),
                            event.getAction(),
                            event.getFileSize(),
                            event.getEventSource()  // NEW: Log event source
                    );
                    logger.info("✓ File event logged to database (source: " + event.getEventSource() + ")");
                } catch (Exception e) {
                    logger.error("Failed to log file event: " + e.getMessage());
                }
            }

            // Trigger backup
            if (backupManager != null && !event.getAction().equals("DELETE")) {
                try {
                    // Create backup from file event data
                    File sourceFile = new File(event.getFullPath());
                    backupManager.createOptimizedBackup(
                            event.getMonitorPathId(),
                            event.getMonitorPathName(),
                            "D:\\Pragadeesh_D\\TestBackup",  // Or get from config
                            sourceFile,
                            event.getAction()
                    );
                } catch (Exception e) {
                    logger.error("Backup failed: " + e.getMessage());
                }
            }

            updateDashboard();
        });
    }

    // ==================== Activity & Status Management ====================

    @FXML
    private void handleClearActivity() {
        txtActivityFeed.clear();
    }

    @FXML
    private void handleAddPath() {
        MonitorConfig config = new MonitorConfig();
        config.setPathName("New Path " + (monitorPaths.size() + 1));
        config.setPathLocation("Select folder...");
        config.setBackupLocation("Select backup folder...");
        config.setPathType("LOCAL");
        config.setMonitorMethod("NATIVE");  // Default to native for local paths
        config.setEnabled(true);

        monitorPaths.add(config);
        addActivityLog("Added new monitor path (default method: NATIVE)", "INFO");
    }

    @FXML
    private void handleTestConnection() {
        updateStatus("Testing database connection...");
        try {
            DatabaseManager testDb = new DatabaseManager(
                    txtDbHost.getText(),
                    txtDbName.getText(),
                    txtDbUser.getText(),
                    txtDbPassword.getText()
            );

            lblConnectionStatus.setText("✓ Connected");
            lblConnectionStatus.setStyle("-fx-text-fill: #27ae60; -fx-font-weight: bold;");
            lblDbStatus.setStyle("-fx-text-fill: #27ae60;");
            showInfo("Database connection successful!");
            updateStatus("Database connected");
            addActivityLog("✓ Database connection successful", "SUCCESS");

        } catch (Exception e) {
            lblConnectionStatus.setText("✗ Failed");
            lblConnectionStatus.setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
            lblDbStatus.setStyle("-fx-text-fill: #e74c3c;");
            showError("Database connection failed: " + e.getMessage());
            updateStatus("Database connection failed");
            addActivityLog("✗ Database connection failed", "ERROR");
        }
    }

    @FXML
    private void handleSaveSettings() {
        showInfo("Settings saved successfully!");
        updateStatus("Settings saved");
        addActivityLog("Settings saved", "INFO");
    }

    @FXML
    private void handleResetSettings() {
        setupSettings();
        showInfo("Settings reset to defaults");
        addActivityLog("Settings reset", "INFO");
    }

    // ==================== Logs Management ====================

    @FXML
    private void handleRefreshLogs() {
        refreshLogsTable();
        updateStatus("Logs refreshed");
    }

    @FXML
    private void handleClearLogs() {
        if (dbManager == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Clear All Logs");
        confirm.setHeaderText("Are you sure you want to clear all logs?");
        confirm.setContentText("This action cannot be undone.");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                try {
                    dbManager.clearAllLogs();
                    refreshLogsTable();
                    updateStatus("All logs cleared");
                    logger.info("✓ All logs cleared");
                } catch (Exception e) {
                    logger.error("Failed to clear logs", e);
                    showError("Failed to clear logs: " + e.getMessage());
                }
            }
        });
    }

    private void refreshLogsTable() {
        if (dbManager == null) {
            logger.warn("Cannot refresh logs - dbManager is null");
            return;
        }

        try {
            ObservableList<FileEvent> logs = dbManager.getRecentEvents(100);
            tblLogs.setItems(logs);

            lblTotalLogs.setText(String.valueOf(logs.size()));
            if (!logs.isEmpty()) {
                lblLastUpdate.setText(logs.get(0).getLogTime());
            } else {
                lblLastUpdate.setText("Never");
            }

            logger.info("✓ Logs table refreshed: " + logs.size() + " entries");
        } catch (Exception e) {
            logger.error("Failed to refresh logs table", e);
            showError("Failed to load logs: " + e.getMessage());
        }
    }

    // ==================== Dashboard ====================

    private void updateDashboard() {
        if (dbManager == null) return;

        try {
            BackupStats stats = dbManager.getBackupStats();

            lblTotalBackups.setText(String.valueOf(stats.getTotalBackups()));
            lblSpaceSaved.setText(String.format("%.1f%%", stats.getSavingsPercent()));
            lblSpaceSavedDetails.setText(stats.getSpaceSavedFormatted() + " saved");
            lblFilesMonitored.setText(String.valueOf(stats.getFilesMonitored()));
            lblMonitorPaths.setText(String.valueOf(monitorPaths.size()));

            lblTotalSavings.setText(String.format("%.1f%%", stats.getSavingsPercent()));
            lblTotalSavingsDetails.setText(String.format("%s saved from %s",
                    stats.getSpaceSavedFormatted(),
                    stats.getOriginalSizeFormatted()));

        } catch (Exception e) {
            logger.error("Failed to update dashboard", e);
        }
    }

    // ==================== Utility Methods ====================

    private void addActivityLog(String message, String level) {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String logEntry = String.format("[%s] %s: %s\n", timestamp, level, message);

        Platform.runLater(() -> {
            txtActivityFeed.appendText(logEntry);
        });
    }

    private void updateStatus(String status) {
        Platform.runLater(() -> {
            lblStatus.setText(status);
        });
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Information");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private boolean shouldBackupFile(String fileName) {
        // Skip Windows system/temp files
        return !fileName.isEmpty()
                && !fileName.startsWith("~")
                && !fileName.endsWith(".tmp")
                && !fileName.endsWith(".temp")
                && !fileName.equals("Thumbs.db");
    }

    // ==================== Backup/Restore ====================

    @FXML
    private void handleBrowseBackup() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Backup File to Restore");
        fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Backup Files (*.gz)", "*.gz")
        );
        File selectedFile = fileChooser.showOpenDialog(btnBrowseBackup.getScene().getWindow());
        if (selectedFile != null) {
            txtBackupFilePath.setText(selectedFile.getAbsolutePath());
            lblRestoreStatus.setText("");
        }
    }

    @FXML
    private void handleBrowseRestore() {
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Select Restore Location");
        dirChooser.setInitialDirectory(new File(System.getProperty("user.home")));
        File selectedDir = dirChooser.showDialog(btnBrowseRestore.getScene().getWindow());
        if (selectedDir != null) {
            txtRestoreLocation.setText(selectedDir.getAbsolutePath());
            lblRestoreStatus.setText("");
        }
    }

    @FXML
    private void handleRestoreBackup() {
        String backupPath = txtBackupFilePath.getText();
        String restorePath = txtRestoreLocation.getText();

        if (backupPath.isEmpty() || restorePath.isEmpty()) {
            showError("Please select both backup file and restore location");
            lblRestoreStatus.setText("Error: Missing selections");
            lblRestoreStatus.setStyle("-fx-text-fill: #e74c3c;");
            return;
        }

        try {
            File backupFile = new File(backupPath);
            File restoreDir = new File(restorePath);

            if (!backupFile.exists()) {
                showError("Backup file not found: " + backupPath);
                lblRestoreStatus.setText("Error: File not found");
                lblRestoreStatus.setStyle("-fx-text-fill: #e74c3c;");
                return;
            }
            if (!backupFile.getName().endsWith(".gz")) {
                showError("Invalid backup file. Must be .gz format");
                lblRestoreStatus.setText("Error: Invalid format");
                lblRestoreStatus.setStyle("-fx-text-fill: #e74c3c;");
                return;
            }

            lblRestoreStatus.setText("Restoring...");
            lblRestoreStatus.setStyle("-fx-text-fill: #f39c12;");

            File restoredFile = backupManager.restoreBackup(backupFile, restoreDir);

            lblRestoreStatus.setText("✓ Restored: " + restoredFile.getName() +
                    " (Size: " + formatBytes(restoredFile.length()) + ")");
            lblRestoreStatus.setStyle("-fx-text-fill: #27ae60;");
            addActivityLog("✓ Backup restored: " + restoredFile.getName(), "SUCCESS");
            showInfo("Backup restored successfully to:\n" + restoredFile.getAbsolutePath());
        } catch (Exception e) {
            lblRestoreStatus.setText("Error: " + e.getMessage());
            lblRestoreStatus.setStyle("-fx-text-fill: #e74c3c;");
            addActivityLog("✗ Restore failed: " + e.getMessage(), "ERROR");
            showError("Restore failed: " + e.getMessage());
            logger.error("Restore failed", e);
        }
    }

    @FXML
    private void handleRefreshPaths() {
        try {
            if (dbManager == null) {
                logger.warn("Cannot refresh paths - dbManager is null");
                return;
            }
            updateStatus("Paths refreshed");
            addActivityLog("✓ Paths table refreshed", "INFO");
        } catch (Exception e) {
            logger.error("Failed to refresh paths", e);
            showError("Failed to refresh paths: " + e.getMessage());
        }
    }

}