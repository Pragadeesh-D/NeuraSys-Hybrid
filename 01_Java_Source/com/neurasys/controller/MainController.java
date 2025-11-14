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
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

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

    private final DatabaseManager databaseManager = new DatabaseManager("localhost", "NeuraSysDB", "neurasys_app", "NeuraSys@2025!");
    private static final Logger logger = Logger.getLogger(MainController.class);

    // ==================== UI Components ====================

    // Dashboard Labels
    @FXML private Label lblTotalBackups;
    @FXML private Label lblSpaceSaved;
    @FXML private Label lblSpaceSavedDetails;
    @FXML private Label lblFilesMonitored;
    @FXML private Label lblMonitorPaths;

    // NEW: Additional Dashboard Labels
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
    @FXML
    private ComboBox<String> cmbMonitorMethod;

    // ==================== Service Components ====================

    private DatabaseManager dbManager;
    private BackupManager backupManager;
    private MultiPathMonitor multiPathMonitor;
    private long monitoringStartTime = 0; // Track when monitoring began

    private NativeFileMonitor nativeMonitor;  // NEW: C DLL monitor

    private boolean isMonitoring = false;

    private String globalMonitorMethod = "JAVA_WATCH";
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

            try {
                ObservableList<MonitorConfig> loadedPaths = dbManager.getAllMonitorPaths();
                if (loadedPaths != null && !loadedPaths.isEmpty()) {
                    monitorPaths.addAll(loadedPaths);
                    logger.info("✓ Loaded " + loadedPaths.size() + " monitor paths from database");
                } else {
                    logger.info("No monitor paths found in database");
                }
            } catch (Exception e) {
                logger.warn("Could not load monitor paths: " + e.getMessage());
            }
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

        // ============================================================
        // Setup Monitor Method ComboBox (Settings Tab)
        // ============================================================
        cmbMonitorMethod.getItems().clear();
        cmbMonitorMethod.getItems().addAll("NATIVE", "JAVA_WATCH", "POLLING");

        // ✅ Platform-aware default selection with OneDrive support
        // when initialising or when platform decision is made
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win") || osName.contains("mac")) {
            cmbMonitorMethod.getItems().add("ONEDRIVE");
            cmbMonitorMethod.setValue("NATIVE");
            globalMonitorMethod = "NATIVE";
            // enable OneDrive monitoring through your service
            if (multiPathMonitor != null) {
                multiPathMonitor.enableOneDriveMonitoring();
            }
        } else {
            cmbMonitorMethod.setValue("JAVA_WATCH");
            globalMonitorMethod = "JAVA_WATCH";
        }


        cmbMonitorMethod.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.equals(oldVal)) {
                logger.info("Global monitoring method changed: " + oldVal + " -> " + newVal);
                updateMonitoringMethod(newVal); // ✅ centralized restart logic
            }
        });

        // ============================================================
        // Setup Monitor Method Column (Per-path method editing)
        // ============================================================
        tblMonitorPaths.setEditable(true); // ✅ make table editable

        colMonitorMethod.setCellFactory(ComboBoxTableCell.forTableColumn("DEFAULT", "JAVA_WATCH", "NATIVE", "POLLING", "ONEDRIVE"));
        colMonitorMethod.setOnEditCommit(event -> {
            MonitorConfig config = event.getRowValue();
            String newMethod = event.getNewValue();
            onPathMethodChanged(config, newMethod); // ✅ restart logic for per-path change
        });

        // Setup filter ComboBox
        cmbFilterAction.getItems().clear();
        cmbFilterAction.getItems().addAll("All", "CREATE", "MODIFY", "DELETE", "RENAME");
        cmbFilterAction.setValue("All");
        cmbFilterAction.setOnAction(this::handleFilterChange);

        // Setup event source filter
        cmbFilterEventSource.getItems().clear();
        cmbFilterEventSource.getItems().addAll("All", "NATIVE", "JAVA_WATCH", "POLLING");
        cmbFilterEventSource.setValue("All");
        cmbFilterEventSource.setOnAction(this::handleFilterChange);

        // Setup search field
        txtSearchLogs.textProperty().addListener((obs, oldVal, newVal) -> handleSearchLogs());

        // Initial load
        refreshLogsTable();

        updateStatus("Ready");
        logger.info("✓ MainController initialized (Hybrid mode)");

        updateDashboardStats();   // ✅ show dashboard stats on launch
        updateAnalyticsStats();   // ✅ show analytics stats on launch

    }

    // ============================
    // 🔧 FIXED MONITOR METHOD UPDATE
    // ============================
    private void updateMonitoringMethod(String newMethod) {
        logger.info("=== UPDATING MONITORING METHOD TO: " + newMethod + " ===");

        if (multiPathMonitor == null || !isMonitoring) {
            logger.warn("Monitoring not active. Method will apply on next monitoring start.");
            globalMonitorMethod = newMethod;
            cmbMonitorMethod.setValue(newMethod);
            lblMonitorMethod.setText("Method: " + newMethod);
            lblMonitorMethod.setStyle("-fx-text-fill: #3498db;");
            addActivityLog("Monitoring method set to: " + newMethod + " (will apply on next start)", "INFO");
            return;
        }

        try {
            logger.info("Stopping all active monitors...");
            multiPathMonitor.stopAll();

            globalMonitorMethod = newMethod;
            logger.info("✓ Stopped all monitors. Global method is now: " + globalMonitorMethod);

            // Re-add all paths with the NEW method
            logger.info("Re-starting monitors with method: " + newMethod);
            for (MonitorConfig config : monitorPaths) {
                if (config.isEnabled()) {
                    String methodToUse = config.getMonitorMethod();

                    // If path uses DEFAULT, resolve to the NEW global method
                    if ("DEFAULT".equalsIgnoreCase(methodToUse)) {
                        methodToUse = newMethod;
                    }

                    MonitorConfig updatedConfig = new MonitorConfig();
                    updatedConfig.setId(config.getId());
                    updatedConfig.setPathName(config.getPathName());
                    updatedConfig.setPathLocation(config.getPathLocation());
                    updatedConfig.setBackupLocation(config.getBackupLocation());
                    updatedConfig.setPathType(config.getPathType());
                    updatedConfig.setMonitorMethod(methodToUse);
                    updatedConfig.setEnabled(true);

                    multiPathMonitor.addMonitorPath(updatedConfig);
                    logger.info("  ✓ Re-added path with method: " + methodToUse);
                }
            }

            // Start all monitors with NEW method
            multiPathMonitor.startAll();

            // Update UI
            globalMonitorMethod = newMethod;
            cmbMonitorMethod.setValue(newMethod);
            lblMonitorMethod.setText("Method: " + newMethod);
            lblMonitorMethod.setStyle("-fx-text-fill: #27ae60;");

            updateStatus("Monitoring method updated: " + newMethod);
            addActivityLog("✓ Monitoring method changed to: " + newMethod + " (Active on " +
                    monitorPaths.stream().filter(MonitorConfig::isEnabled).count() + " paths)", "SUCCESS");

            logger.info("=== MONITORING METHOD UPDATE COMPLETE ===");

        } catch (Exception e) {
            logger.error("Failed to update monitoring method", e);
            addActivityLog("✗ Failed to update monitoring method: " + e.getMessage(), "ERROR");
            showError("Failed to update monitoring method: " + e.getMessage());
        }
    }

    /*private void applyGlobalMethodToDefaultPaths(String newGlobalMethod) {
        if (multiPathMonitor == null || !isMonitoring) {
            logger.warn("Monitoring not active. Global method will apply on next start.");
            return;
        }

        logger.info("Applying global method to DEFAULT paths: " + newGlobalMethod);

        for (MonitorConfig config : monitorPaths) {
            if (config.isEnabled() && "DEFAULT".equalsIgnoreCase(config.getMonitorMethod())) {
                MonitorConfig updatedConfig = new MonitorConfig();
                updatedConfig.setId(config.getId());
                updatedConfig.setPathName(config.getPathName());
                updatedConfig.setPathLocation(config.getPathLocation());
                updatedConfig.setBackupLocation(config.getBackupLocation());
                updatedConfig.setPathType(config.getPathType());
                updatedConfig.setMonitorMethod(newGlobalMethod);
                updatedConfig.setEnabled(true);

                multiPathMonitor.restartMonitorPath(updatedConfig);
                logger.info("✓ Restarted DEFAULT path with new method: " + newGlobalMethod);
            }
        }

        updateStatus("Global method applied to DEFAULT paths");
        addActivityLog("✓ DEFAULT paths updated with method: " + newGlobalMethod, "SUCCESS");
    }*/

    // Placeholder method to get current monitoring method (can retrieve from config or DB)
    /*private String getCurrentMonitoringMethod() {
        return "NATIVE"; // For now default to NATIVE
    }*/


    // ==================== Filter & Search ====================

    @FXML
    private void handleFilterChange(ActionEvent event) {
        if (dbManager == null) return;

        String actionFilter = cmbFilterAction.getValue();
        String sourceFilter = cmbFilterEventSource.getValue();

        if (actionFilter == null || actionFilter.isEmpty()) {
            actionFilter = "All";
        }
        if (sourceFilter == null || sourceFilter.isEmpty()) {
            sourceFilter = "All";
        }

        try {
            ObservableList<FileEvent> logs;

            if ("All".equals(actionFilter) && "All".equals(sourceFilter)) {
                logs = dbManager.getRecentEvents(100);
            } else if ("All".equals(sourceFilter)) {
                logs = dbManager.getEventsByAction(actionFilter, 100);
            } else if ("All".equals(actionFilter)) {
                logs = dbManager.getEventsBySource(sourceFilter, 100);
            } else {
                logs = dbManager.getEventsByActionAndSource(actionFilter, sourceFilter, 100);
            }

            tblLogs.setItems(logs);
            lblTotalLogs.setText(String.valueOf(logs.size()));

            if (!logs.isEmpty()) {
                lblLastUpdate.setText(logs.get(0).getLogTime());
            } else {
                lblLastUpdate.setText("Never");
            }

            logger.info("✓ Logs filtered → Action: " + actionFilter + ", Source: " + sourceFilter);

        } catch (Exception e) {
            logger.error("Failed to filter logs", e);
            showError("Failed to filter logs: " + e.getMessage());
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

        // CRITICAL: Monitor method column with DEFAULT option
        colMonitorMethod.setCellValueFactory(new PropertyValueFactory<>("monitorMethod"));
        colMonitorMethod.setCellFactory(javafx.scene.control.cell.ComboBoxTableCell.forTableColumn(
                "DEFAULT", "NATIVE", "JAVA_WATCH", "POLLING"
        ));
        colMonitorMethod.setOnEditCommit(event -> {
            MonitorConfig cfg = event.getRowValue();
            String old = cfg.getMonitorMethod();
            String newMethod = event.getNewValue();
            cfg.setMonitorMethod(newMethod);
            addActivityLog("Path '" + cfg.getPathName() + "' method changed to: " + newMethod, "INFO");

            // If monitoring is active, apply change to the running monitor for this path
            if (isMonitoring && multiPathMonitor != null && cfg.getId() > 0) {
                try {
                    // Remove the running monitor task and re-add with new method (resolve DEFAULT)
                    multiPathMonitor.removeMonitorPath(cfg.getId());

                    // If user set DEFAULT, resolve to global method before re-adding
                    String methodToUse = "DEFAULT".equalsIgnoreCase(newMethod) ? globalMonitorMethod : newMethod;
                    cfg.setMonitorMethod(methodToUse);

                    multiPathMonitor.addMonitorPath(cfg);
                    addActivityLog("Applied method change for path: " + cfg.getPathName() + " → " + methodToUse, "SUCCESS");
                } catch (Exception e) {
                    logger.error("Failed to update monitor method for path: " + cfg.getPathName(), e);
                    addActivityLog("✗ Failed to apply method change for: " + cfg.getPathName(), "ERROR");
                }
            }
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
        colLogSize.setCellFactory(col -> new TableCell<>() {
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
        colLogAction.setCellFactory(col -> new TableCell<>() {
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
        chkRetentionEnabled.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            spinRetentionDays.setDisable(!isSelected);
            spinMinVersions.setDisable(!isSelected);
        });

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
       // runNativeHarnessTest();
        if (isMonitoring) {
            stopMonitoring();
        } else {
            startMonitoring();
        }
    }

    private void runNativeHarnessTest() {
        try {
            NativeFileMonitor nfm = new NativeFileMonitor(); // or new NativeFileMonitor() if you instantiate per-use
            nfm.triggerTestCallback(99, "C:\\Temp", (id, fullPath, fileName, action, size, ts) -> {
                logger.info("HARNESS → id=" + id + ", action=" + action + ", file=" + fileName +
                        ", size=" + size + ", ts=" + ts);
            });
            showInfo("Native harness test dispatched. Check logs for HARNESS entry.");
        } catch (Throwable t) {
            logger.error("Native harness test failed", t);
            showError("Native harness test failed: " + t.getMessage());
        }
    }

    private void startMonitoring() {
        try {
            dbManager = new DatabaseManager(
                    txtDbHost.getText(),
                    txtDbName.getText(),
                    txtDbUser.getText(),
                    txtDbPassword.getText()
            );

            backupManager = new BackupManager(dbManager);

            nativeMonitor = new NativeFileMonitor(); // ✅ Ensure fresh instance
            multiPathMonitor = new MultiPathMonitor(dbManager, nativeMonitor, this::handleFileEvent, globalMonitorMethod);

            monitoringStartTime = System.currentTimeMillis();
            logger.info("✓ Monitoring start timestamp recorded: " + monitoringStartTime);

            int pathsAdded = 0;
            for (MonitorConfig config : monitorPaths) {
                if (config.isEnabled()) {
                    try {
                        String pathLocation = config.getPathLocation();
                        String backupLocation = config.getBackupLocation();

                        if (pathLocation == null || pathLocation.isEmpty() || pathLocation.equals("Select folder...")) {
                            addActivityLog("✗ Invalid path location for: " + config.getPathName(), "ERROR");
                            continue;
                        }
                        if (backupLocation == null || backupLocation.isEmpty() || backupLocation.equals("Select backup folder...")) {
                            addActivityLog("✗ Invalid backup location for: " + config.getPathName(), "ERROR");
                            continue;
                        }

                        File pathDir = new File(pathLocation);
                        File backupDir = new File(backupLocation);
                        if (!pathDir.exists()) {
                            addActivityLog("✗ Monitor path does not exist: " + pathLocation, "ERROR");
                            continue;
                        }
                        if (!backupDir.exists() && !backupDir.mkdirs()) {
                            addActivityLog("✗ Could not create backup directory: " + backupLocation, "ERROR");
                            continue;
                        }

                        String methodToUse = config.getMonitorMethod();
                        if (methodToUse == null || methodToUse.isEmpty() || "DEFAULT".equalsIgnoreCase(methodToUse)) {
                            methodToUse = globalMonitorMethod;
                            logger.info("→ Path '" + config.getPathName() + "' using global fallback: " + methodToUse);
                        } else {
                            logger.info("→ Path '" + config.getPathName() + "' using explicit method: " + methodToUse);
                        }

                        int pathId = dbManager.addMonitorPath(config);
                        config.setId(pathId);

                        if ("NATIVE".equalsIgnoreCase(methodToUse)) {
                            nativeMonitor.startMonitoring(config.getId(), config.getPathLocation(),
                                    (id, fullPath, fileName, action, size, ts) -> {
                                        FileEvent event = new FileEvent(
                                                0, id, config.getPathName(), fileName, fullPath,
                                                action, size, ts, "NATIVE"
                                        );
                                        onFileEvent(event);
                                    });

                            logger.info("✓ Native monitoring enabled for: " + config.getPathName());
                        } else {
                            MonitorConfig resolvedConfig = new MonitorConfig();
                            resolvedConfig.setId(config.getId());
                            resolvedConfig.setPathName(config.getPathName());
                            resolvedConfig.setPathLocation(config.getPathLocation());
                            resolvedConfig.setBackupLocation(config.getBackupLocation());
                            resolvedConfig.setPathType(config.getPathType());
                            resolvedConfig.setMonitorMethod(methodToUse);
                            resolvedConfig.setEnabled(true);

                            multiPathMonitor.addMonitorPath(resolvedConfig);
                            logger.info("✓ Java/Polling/OneDrive monitoring enabled for: "
                                    + config.getPathName() + " (Method: " + methodToUse + ")");
                        }

                        pathsAdded++;
                        addActivityLog("✓ Monitoring enabled for: " + config.getPathName()
                                + " (Method: " + methodToUse + ")", "SUCCESS");

                    } catch (Exception e) {
                        logger.error("Failed to add monitor path: " + config.getPathName(), e);
                        addActivityLog("✗ Failed to add: " + config.getPathName() + " - " + e.getMessage(), "ERROR");
                    }
                }
            }

            if (pathsAdded == 0) {
                showError("No enabled monitor paths to start!");
                updateStatus("No valid paths to monitor");
                return;
            }

            multiPathMonitor.startAll();

            isMonitoring = true;
            btnStartStop.setText("Stop Monitoring");
            btnStartStop.setStyle("-fx-background-color: #e74c3c;");
            lblMonitorStatus.setText("● ACTIVE (Hybrid)");
            lblMonitorStatus.setStyle("-fx-text-fill: #27ae60; -fx-font-weight: bold;");
            lblCMonitorStatus.setText(nativeMonitor.getNativeMonitorStats());
            lblCMonitorStatus.setStyle("-fx-text-fill: #27ae60;");
            lblDbStatus.setStyle("-fx-text-fill: #27ae60;");
            lblMonitorMethod.setText("Method: " + globalMonitorMethod);
            lblMonitorMethod.setStyle("-fx-text-fill: #3498db;");

            updateStatus("Monitoring started (" + pathsAdded + " paths)");
            addActivityLog("✓ Monitoring started with " + pathsAdded
                    + " paths (Global Method: " + globalMonitorMethod + ")", "SUCCESS");

        } catch (Exception e) {
            showError("Failed to start monitoring: " + e.getMessage());
            logger.error("Failed to start monitoring", e);
            updateStatus("Error: " + e.getMessage());
        }
    }

    private void stopMonitoring() {
        try {
            for (MonitorConfig config : monitorPaths) {
                if (config.isEnabled() && "NATIVE".equalsIgnoreCase(config.getMonitorMethod())) {
                    nativeMonitor.stopMonitoring(config.getId());
                    logger.info("✓ Native monitor stopped for: " + config.getPathName());
                }
            }

            if (multiPathMonitor != null) {
                multiPathMonitor.stopAll();
                multiPathMonitor = null;
            }

            isMonitoring = false;

            btnStartStop.setText("Start Monitoring");
            btnStartStop.setStyle("-fx-background-color: #27ae60;");
            lblMonitorStatus.setText("● STOPPED");
            lblMonitorStatus.setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
            lblCMonitorStatus.setText("Stopped");
            lblCMonitorStatus.setStyle("-fx-text-fill: #95a5a6;");
            lblMonitorMethod.setText("Method: Inactive");

            updateStatus("Monitoring stopped");
            addActivityLog("⊘ Monitoring stopped", "INFO");
            updateDashboard();

            logger.info("✓ Monitoring stopped successfully");

        } catch (Exception e) {
            logger.error("Failed to stop monitoring", e);
            showError("Failed to stop monitoring: " + e.getMessage());
        }
    }



    // ==================== File Event Callback (from C & Java) ====================

    /**
     * Callback from native C monitor or Java WatchService
     * Stores events to database and triggers backups
     */
    private void onFileEvent(FileEvent event) {
        Platform.runLater(() -> {

            // NEW: IGNORE events that occurred BEFORE or VERY CLOSE TO monitoring start
            // (within 2 seconds to account for system delays)
            long eventTime = System.currentTimeMillis();
            long timeSinceStart = eventTime - monitoringStartTime;

            if (timeSinceStart < 2000) {
                logger.info("✓ Ignoring pre-startup event (age: " + timeSinceStart + "ms): " + event.getFileName());
                return; // SKIP THIS EVENT
            }

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
                            event.getFilePath(),
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
                    File sourceFile = new File(event.getFilePath());
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
        // IMPORTANT: use global default from Settings
        config.setMonitorMethod("DEFAULT");
        config.setEnabled(true);

        monitorPaths.add(config);
        addActivityLog("Added new monitor path (default method: " + config.getMonitorMethod() + ")", "INFO");
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
        try {
            String selectedMethod = cmbMonitorMethod.getValue(); // or getSelectedItem()

            // Use centralized method to apply and restart monitoring
            updateMonitoringMethod(selectedMethod);

            showInfo("Settings saved successfully!");
            updateStatus("Settings saved");
            addActivityLog("Settings saved", "INFO");

        } catch (Exception e) {
            logger.error("Error saving settings or restarting monitoring", e);
            showError("Failed to apply settings: " + e.getMessage());
        }
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

    @FXML
    private void handleRefreshStats() {
        updateDashboardStats();
        updateAnalyticsStats();
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
            BackupStats stats = dbManager.getBackupStatsFromOptimization();

            // Dashboard Cards
            lblTotalBackups.setText(String.valueOf(stats.getTotalBackups()));
            lblSpaceSaved.setText(String.format("%.1f%%", stats.getSavingsPercent()));
            lblSpaceSavedDetails.setText(stats.getSpaceSavedFormatted() + " saved");
            lblFilesMonitored.setText(String.valueOf(stats.getFilesMonitored()));
            lblMonitorPaths.setText(String.valueOf(monitorPaths.size()));

            // Analytics Tab
            lblTotalSavings.setText(String.format("%.1f%%", stats.getSavingsPercent()));
            lblTotalSavingsDetails.setText(String.format("%s saved from %s",
                    stats.getSpaceSavedFormatted(),
                    stats.getOriginalSizeFormatted()));

            // Optional: Progress bars (if declared)
            if (pbCompression != null && pbDeduplication != null && pbIncremental != null) {
                long originalSize = stats.getTotalOriginalSize();
                pbCompression.setProgress(originalSize > 0 ? (double) stats.getCompressionSaved() / originalSize : 0);
                pbDeduplication.setProgress(originalSize > 0 ? (double) stats.getDeduplicationSaved() / originalSize : 0);
                pbIncremental.setProgress(originalSize > 0 ? (double) stats.getIncrementalSaved() / originalSize : 0);
            }

            logger.info("✓ Dashboard updated with latest backup stats");
        } catch (Exception e) {
            logger.error("✗ Failed to update dashboard", e);
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

    // 📦 Byte Formatter
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

    /**
     * Restarts monitoring with the current path configurations
     * Used when monitoring is running and you change a path's method or global method
     */
    private void restartMonitoringWithCurrentMethods() {
        if (!isMonitoring || multiPathMonitor == null) {
            logger.warn("Monitoring is not active, cannot restart");
            return;
        }

        try {
            logger.info("=== RESTARTING MONITORING WITH UPDATED METHODS ===");

            // Stop all current monitors
            multiPathMonitor.stopAll();

            // Also stop native monitors explicitly
            for (MonitorConfig config : monitorPaths) {
                if (config.isEnabled() && "NATIVE".equalsIgnoreCase(config.getMonitorMethod())) {
                    nativeMonitor.stopMonitoring(config.getId());
                    logger.info("✓ Native monitor stopped for path: " + config.getPathName());
                }
            }

            Thread.sleep(500); // allow native cleanup

            // Recreate monitor with current global method
            multiPathMonitor = new MultiPathMonitor(
                    dbManager,
                    nativeMonitor,
                    this::handleFileEvent,
                    globalMonitorMethod
            );

            int pathsReAdded = 0;

            for (MonitorConfig config : monitorPaths) {
                if (config.isEnabled()) {
                    // ✅ FIX: Explicit per-path method wins; DEFAULT falls back to global
                    String methodToUse = config.getMonitorMethod();
                    if (methodToUse == null || methodToUse.isEmpty() || "DEFAULT".equalsIgnoreCase(methodToUse)) {
                        methodToUse = globalMonitorMethod;
                        logger.info("→ Path '" + config.getPathName() + "' using global fallback: " + methodToUse);
                    } else {
                        logger.info("→ Path '" + config.getPathName() + "' using explicit method: " + methodToUse);
                    }

                    if ("NATIVE".equalsIgnoreCase(methodToUse)) {
                        nativeMonitor.startMonitoring(config.getId(), config.getPathLocation(),
                                (id, fullPath, fileName, action, size, ts) -> {
                                    FileEvent event = new FileEvent(
                                            0,                        // logId placeholder
                                            id,                       // monitorPathId
                                            config.getPathName(),     // monitorPathName
                                            fileName,                 // fileName
                                            fullPath,                 // filePath
                                            action,                   // action
                                            size,                     // fileSize
                                            ts,                       // timestamp string from JNI
                                            "NATIVE"                  // eventSource
                                    );
                                    onFileEvent(event);
                                });
                        logger.info("✓ Native monitor restarted for: " + config.getPathName());
                    } else {
                        // Clone config with resolved method for Java/Polling/OneDrive
                        MonitorConfig resolvedConfig = new MonitorConfig();
                        resolvedConfig.setId(config.getId());
                        resolvedConfig.setPathName(config.getPathName());
                        resolvedConfig.setPathLocation(config.getPathLocation());
                        resolvedConfig.setBackupLocation(config.getBackupLocation());
                        resolvedConfig.setPathType(config.getPathType());
                        resolvedConfig.setMonitorMethod(methodToUse);
                        resolvedConfig.setEnabled(true);
                        resolvedConfig.setCompressionEnabled(config.isCompressionEnabled());
                        resolvedConfig.setDeduplicationEnabled(config.isDeduplicationEnabled());
                        resolvedConfig.setIncrementalEnabled(config.isIncrementalEnabled());

                        multiPathMonitor.addMonitorPath(resolvedConfig);
                        logger.info("✓ Java/Polling/OneDrive monitor re-added for: "
                                + config.getPathName() + " (Method: " + methodToUse + ")");
                    }

                    pathsReAdded++;
                }
            }

            // Start all non-native monitors again
            multiPathMonitor.startAll();

            updateStatus("Monitoring restarted with updated methods (" + pathsReAdded + " paths)");
            addActivityLog("✓ Monitoring restarted with updated methods (" + pathsReAdded + " paths)", "SUCCESS");

        } catch (Exception e) {
            logger.error("Failed to restart monitoring", e);
            addActivityLog("✗ Failed to restart monitoring: " + e.getMessage(), "ERROR");
            showError("Failed to restart monitoring: " + e.getMessage());
        }
    }


    // 📊 Dashboard Stats
    private void updateDashboardStats() {
        BackupStats stats = databaseManager.getBackupStats();

        lblTotalBackups.setText(String.valueOf(stats.getTotalBackups()));
        lblSpaceSaved.setText(stats.getSavingsPercent() + "%");

        long spaceSavedBytes = stats.getTotalOriginalSize() - stats.getTotalDiskUsed();
        lblSpaceSavedDetails.setText(formatBytes(spaceSavedBytes) + " saved");

        lblFilesMonitored.setText(String.valueOf(stats.getUniqueFiles()));
        lblMonitorPaths.setText(String.valueOf(stats.getNativePaths()));

    }

    // 📈 Analytics Stats
    private void updateAnalyticsStats() {
        BackupStats stats = databaseManager.getBackupStatsFromOptimization();

        long totalSaved = stats.getTotalSpaceSaved();
        long compressionSaved = stats.getCompressionSaved();
        long dedupSaved = stats.getDeduplicationSaved();
        long incrementalSaved = stats.getIncrementalSaved();
        long totalOriginal = stats.getTotalOriginalSize();

        lblCompressionSavings.setText(formatBytes(compressionSaved));
        lblDeduplicationSavings.setText(formatBytes(dedupSaved));
        lblIncrementalSavings.setText(formatBytes(incrementalSaved));

        lblTotalSavings.setText(stats.getSavingsPercent() + "%");
        lblTotalSavingsDetails.setText(formatBytes(totalSaved) + " saved from " + formatBytes(totalOriginal));

        pbCompression.setProgress(totalOriginal > 0 ? (double) compressionSaved / totalOriginal : 0);
        pbDeduplication.setProgress(totalOriginal > 0 ? (double) dedupSaved / totalOriginal : 0);
        pbIncremental.setProgress(totalOriginal > 0 ? (double) incrementalSaved / totalOriginal : 0);

        // ✅ Native monitor health check
        if (nativeMonitor.isMonitoringActive()) {
            logger.info("✓ Native monitor is active");
        } else {
            logger.warn("✗ Native monitor not responding");
        }

        // ✅ Native monitor stats
        String monitorStats = nativeMonitor.getMonitorStats();
        logger.info("Monitor stats: " + monitorStats);
        //analyticsPanel.updateStats(monitorStats);
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

    // When global method changes
    @FXML
    private void handleGlobalMethodChange(String newMethod) {
        globalMonitorMethod = newMethod;
        logger.info("✓ Global monitoring method changed to: " + newMethod);

        if (isMonitoring && multiPathMonitor != null) {
            restartMonitoringWithCurrentMethods(); // auto-stop + restart
        }
    }

    // When a path’s method changes
    private void onPathMethodChanged(MonitorConfig config, String newMethod) {
        try {
            config.setMonitorMethod(newMethod);
            logger.info("Path '" + config.getPathName() + "' method changed to: " + newMethod);
            addActivityLog("✓ Method updated for path: " + config.getPathName() + " → " + newMethod, "INFO");

            if (isMonitoring && multiPathMonitor != null) {
                multiPathMonitor.stopAllMonitoring();
                Thread.sleep(500); // brief pause to ensure threads exit
                restartMonitoringWithCurrentMethods();
                logger.info("✓ Monitoring restarted after per-path method change");
            }

            updateStatus("Method updated for: " + config.getPathName());
        } catch (Exception e) {
            logger.error("Failed to update path method for: " + config.getPathName(), e);
            showError("Error updating method for " + config.getPathName() + ": " + e.getMessage());
        }
    }

    private void handleBackupComplete(BackupStats stats) {
        // Space savings
        lblCompressionSavings.setText(String.format("Compression: %.1f%%", stats.getAvgCompressionRatio() * 100));
        pbCompression.setProgress(stats.getAvgCompressionRatio());

        lblDeduplicationSavings.setText("Deduplicated Files: " + stats.getDeduplicatedFiles());
        pbDeduplication.setProgress(stats.getDeduplicatedFiles() / (double) stats.getFilesMonitored());

        lblIncrementalSavings.setText("Space Saved: " + stats.getSpaceSavedFormatted());
        pbIncremental.setProgress(stats.getSavingsPercent() / 100.0);

        logger.info("✓ Backup stats updated: " + stats);
    }

    private void handleFileEvent(FileEvent event) {
        logsList.add(event); // or process however you like
        updateDashboardStats();
    }

    /*private void refreshBackupDashboard() {
        try {
            BackupStats stats = dbManager.getLatestBackupStats();
            handleBackupComplete(stats);
        } catch (Exception e) {
            logger.warn("Could not refresh backup dashboard", e);
        }
    }*/
}