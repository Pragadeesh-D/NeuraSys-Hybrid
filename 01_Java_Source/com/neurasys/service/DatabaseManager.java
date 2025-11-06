package com.neurasys.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.neurasys.model.BackupStats;
import com.neurasys.model.FileEvent;
import com.neurasys.model.MonitorConfig;
import com.neurasys.util.Logger;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * ✅ HYBRID DatabaseManager - Database operations for NeuraSys
 *
 * Integrated with hybrid schema:
 * - monitor_paths (with monitor_method column)
 * - file_events (with event_source column)
 * - file_versions (with is_deduplicated, content_hash, backup_duration_ms)
 * - space_optimization_stats (daily aggregates)
 * - backup_metadata (job tracking)
 */
public class DatabaseManager {
    private static final Logger logger = Logger.getLogger(DatabaseManager.class);

    private Connection connection;
    private final HikariDataSource dataSource;

    public DatabaseManager(String host, String database, String username, String password) {
        logger.info("Initializing hybrid database...");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":3306/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        config.setUsername(username);
        config.setPassword(password);

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setPoolName("NeuraSysHybridPool");

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        this.dataSource = new HikariDataSource(config);

        testConnection();
        logger.info("✓ Hybrid database initialized");
    }

    private void testConnection() {
        try (Connection conn = dataSource.getConnection()) {
            logger.info("✓ Database connection successful");
        } catch (SQLException e) {
            logger.error("Database connection failed", e);
            throw new RuntimeException("Database connection failed", e);
        }
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public List<MonitorConfig> getAllActiveMonitorPaths() throws SQLException {
        List<MonitorConfig> activePaths = new ArrayList<>();

        String sql = "SELECT id, path_name, path_location, backup_location, path_type, monitor_method, enabled " +
                "FROM monitor_paths WHERE enabled = true";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                MonitorConfig config = new MonitorConfig();
                config.setId(rs.getInt("id"));
                config.setPathName(rs.getString("path_name"));
                config.setPathLocation(rs.getString("path_location"));
                config.setBackupLocation(rs.getString("backup_location"));
                config.setPathType(rs.getString("path_type"));
                config.setMonitorMethod(rs.getString("monitor_method"));
                config.setEnabled(rs.getBoolean("enabled"));
                activePaths.add(config);
            }
        } catch (SQLException e) {
            logger.error("Failed to load active monitor paths", e);
            throw e;
        }
        return activePaths;
    }

    // Example fix in getAllMonitorPaths()
    public ObservableList<MonitorConfig> getAllMonitorPaths() throws SQLException {
        ObservableList<MonitorConfig> paths = FXCollections.observableArrayList();

        String sql = "SELECT id, path_name, path_location, backup_location, path_type, monitor_method, enabled " +
                "FROM monitor_paths";

        logger.info("Executing query: " + sql);

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            int count = 0;
            while (rs.next()) {
                MonitorConfig config = new MonitorConfig();
                config.setId(rs.getInt("id"));
                config.setPathName(rs.getString("path_name"));
                config.setPathLocation(rs.getString("path_location"));
                config.setBackupLocation(rs.getString("backup_location"));
                config.setPathType(rs.getString("path_type"));
                config.setMonitorMethod(rs.getString("monitor_method"));
                config.setEnabled(rs.getBoolean("enabled"));

                logger.info("Loaded path #" + (count + 1) + ": " + config.getPathName());
                paths.add(config);
                count++;
            }
            logger.info("✓ Total paths loaded: " + count);
        } catch (SQLException e) {
            logger.error("Failed to load monitor paths from database", e);
            throw e;
        }

        return paths;
    }

    // ============================================================
    // NEW: Add Monitor Path and return database ID
    // ============================================================
    public int addMonitorPath(MonitorConfig config) throws SQLException {
        String selectSql = "SELECT id FROM monitor_paths WHERE path_name = ?";

        try (Connection conn = getConnection();
             PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {

            selectStmt.setString(1, config.getPathName());
            ResultSet rs = selectStmt.executeQuery();

            // Determine what to store in the database
            String methodToStore = config.getMonitorMethod().equalsIgnoreCase("DEFAULT")
                    ? "DEFAULT"
                    : config.getMonitorMethod();

            // If path already exists, update it
            if (rs.next()) {
                int existingId = rs.getInt("id");
                logger.info("Path '" + config.getPathName() + "' already exists (ID: " + existingId + "). Updating...");

                String updateSql = "UPDATE monitor_paths SET path_location = ?, path_type = ?, " +
                        "monitor_method = ?, is_enabled = ? WHERE id = ?";

                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, config.getPathLocation());
                    updateStmt.setString(2, config.getPathType());
                    updateStmt.setString(3, methodToStore); // ✅ Store DEFAULT if selected
                    updateStmt.setBoolean(4, config.isEnabled());
                    updateStmt.setInt(5, existingId);

                    updateStmt.executeUpdate();
                    logger.info("✓ Monitor path updated successfully (ID: " + existingId + ")");
                    return existingId;
                }
            }

            // If path doesn't exist, insert it
            String insertSql = "INSERT INTO monitor_paths (path_name, path_location, backup_location, path_type, monitor_method, enabled) " +
                    "VALUES (?, ?, ?, ?, ?, ?)";

            try (PreparedStatement insertStmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                insertStmt.setString(1, config.getPathName());
                insertStmt.setString(2, config.getPathLocation());
                insertStmt.setString(3, config.getBackupLocation());
                insertStmt.setString(4, config.getPathType());
                insertStmt.setString(5, methodToStore); // ✅ Store DEFAULT if selected
                insertStmt.setBoolean(6, config.isEnabled());

                insertStmt.executeUpdate();

                ResultSet generatedKeys = insertStmt.getGeneratedKeys();
                if (generatedKeys.next()) {
                    int pathId = generatedKeys.getInt(1);
                    logger.info("✓ Monitor path added: " + config.getPathName() +
                            " (ID: " + pathId + ", Method: " + methodToStore + ")");
                    return pathId;
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to add/update monitor path", e);
            throw e;
        }
        return -1;
    }



    // ============================================================
    // NEW: Log File Event with event_source (NATIVE, JAVA_WATCH, POLLING)
    // ============================================================
    public void logFileEvent(int monitorPathId, String filePath, String fileName,
                             String actionType, long fileSize, String eventSource) {
        String sql = "INSERT INTO file_events (monitor_path_id, file_path, file_name, " +
                "action_type, file_size, event_source, detected_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, NOW())";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, monitorPathId);
            stmt.setString(2, filePath);
            stmt.setString(3, fileName);
            stmt.setString(4, actionType);
            stmt.setLong(5, fileSize);
            stmt.setString(6, eventSource);  // NEW: NATIVE, JAVA_WATCH, POLLING

            stmt.executeUpdate();
            logger.debug("Logged: [{}] {} - {}", eventSource, actionType, fileName);

        } catch (SQLException e) {
            logger.error("Failed to log event", e);
        }
    }

    // BACKWARD COMPATIBILITY: Old method without eventSource
    public void logFileEvent(int monitorPathId, String filePath, String fileName,
                             String actionType, long fileSize) {
        logFileEvent(monitorPathId, filePath, fileName, actionType, fileSize, "NATIVE");
    }

    // ============================================================
    // UPDATED: Log File Version with backup_duration_ms
    // ============================================================
    public void logFileVersion(int monitorPathId, String filePath, String backupPath, String versionTag,
                               long fileSize, long compressedSize, long actualDiskSize, long spaceSaved,
                               String backupType, double compressionRatio, boolean isDeduplicated,
                               String contentHash, long backupDurationMs) {
        String sql = "INSERT INTO file_versions (monitor_path_id, file_path, version_tag, " +
                "original_size, compressed_size, actual_disk_size, space_saved, backup_type, " +
                "compression_algo, compression_ratio, is_deduplicated, content_hash, backup_duration_ms) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, monitorPathId);
            stmt.setString(2, filePath);
            stmt.setString(3, versionTag);
            stmt.setLong(4, fileSize);
            stmt.setLong(5, compressedSize);
            stmt.setLong(6, actualDiskSize);
            stmt.setLong(7, spaceSaved);
            stmt.setString(8, backupType);
            stmt.setString(9, "GZIP");  // Default compression algorithm
            stmt.setDouble(10, compressionRatio);
            stmt.setBoolean(11, isDeduplicated);
            stmt.setString(12, contentHash);
            stmt.setLong(13, backupDurationMs);  // NEW: Track backup duration

            stmt.executeUpdate();
            logger.info("Logged backup: {} ({} - {:.1f}%, {}ms)",
                    versionTag, backupType, compressionRatio, backupDurationMs);

        } catch (SQLException e) {
            logger.error("Failed to log version", e);
        }
    }

    // BACKWARD COMPATIBILITY: Old method without backupDurationMs
    public void logFileVersion(int monitorPathId, String filePath, String backupPath, String versionTag,
                               long fileSize, long compressedSize, long actualDiskSize, long spaceSaved,
                               String backupType, double compressionRatio, boolean isDeduplicated, String contentHash) {
        logFileVersion(monitorPathId, filePath, backupPath, versionTag, fileSize, compressedSize,
                actualDiskSize, spaceSaved, backupType, compressionRatio, isDeduplicated, contentHash, 0);
    }

    // ============================================================
    // NEW: Record Backup Job Metadata
    // ============================================================
    public void recordBackupMetadata(String backupId, int monitorPathId, String backupType,
                                     long filesProcessed, long filesFailed, long totalSize,
                                     long durationSeconds, String status) {
        String sql = "INSERT INTO backup_metadata (backup_id, monitor_path_id, backup_type, " +
                "files_processed, files_failed, total_size_processed, duration_seconds, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, backupId);
            stmt.setInt(2, monitorPathId);
            stmt.setString(3, backupType);
            stmt.setLong(4, filesProcessed);
            stmt.setLong(5, filesFailed);
            stmt.setLong(6, totalSize);
            stmt.setLong(7, durationSeconds);
            stmt.setString(8, status);

            stmt.executeUpdate();
            logger.debug("Backup metadata recorded: {} ({})", backupId, status);

        } catch (SQLException e) {
            logger.error("Failed to record backup metadata", e);
        }
    }

    // ============================================================
    // UPDATED: Get Backup Stats with Hybrid Metrics
    // ============================================================
    public BackupStats getBackupStats() {
        String sql = "SELECT " +
                "COUNT(*) as total_backups, " +
                "COUNT(DISTINCT fv.file_path) as unique_files, " +
                "COALESCE(SUM(CASE WHEN fv.original_size > 0 THEN fv.original_size ELSE 0 END), 0) as total_original, " +
                "COALESCE(SUM(CASE WHEN fv.original_size > 0 THEN fv.actual_disk_size ELSE 0 END), 0) as total_disk, " +
                "COALESCE(SUM(CASE WHEN fv.original_size > 0 THEN fv.space_saved ELSE 0 END), 0) as total_saved, " +
                "COUNT(CASE WHEN fv.is_deduplicated = TRUE THEN 1 END) as dedup_count, " +
                "AVG(fv.compression_ratio) as avg_compression, " +
                "COUNT(DISTINCT CASE WHEN mp.monitor_method = 'NATIVE' THEN mp.id END) as native_paths, " +
                "CASE " +
                "  WHEN SUM(CASE WHEN fv.original_size > 0 THEN fv.original_size ELSE 0 END) = 0 THEN 0 " +
                "  ELSE ROUND((SUM(CASE WHEN fv.original_size > 0 THEN fv.space_saved ELSE 0 END) / " +
                "             SUM(CASE WHEN fv.original_size > 0 THEN fv.original_size ELSE 0 END)) * 100, 2) " +
                "END as savings_percent, " +
                "MAX(fv.log_time) as last_time " +
                "FROM file_versions fv " +
                "LEFT JOIN monitor_paths mp ON fv.monitor_path_id = mp.id " +
                "WHERE fv.is_deleted = FALSE AND fv.backup_type IN ('FULL', 'INCREMENTAL', 'DEDUPLICATED')";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                long totalOriginal = rs.getLong("total_original");
                long totalDisk = rs.getLong("total_disk");
                double savingsPercent = totalOriginal > 0 ? rs.getDouble("savings_percent") : 0;

                if (savingsPercent < 0) savingsPercent = 0;
                if (savingsPercent > 100) savingsPercent = 100;

                return new BackupStats(
                        rs.getInt("total_backups"),
                        totalOriginal,
                        totalDisk,
                        savingsPercent,
                        rs.getInt("unique_files"),
                        rs.getString("last_time"),
                        rs.getInt("dedup_count"),          // NEW: Deduplication count
                        rs.getInt("native_paths"),         // NEW: Native monitored paths
                        rs.getDouble("avg_compression")    // NEW: Avg compression ratio
                );
            }
        } catch (SQLException e) {
            logger.error("Failed to get stats", e);
        }

        return new BackupStats(0, 0, 0, 0, 0, "Never");
    }

    // ============================================================
    // NEW: Get Recent Events (from file_events table)
    // ============================================================
    public ObservableList<FileEvent> getRecentEvents(int limit) {
        ObservableList<FileEvent> events = FXCollections.observableArrayList();
        String sql = "SELECT fe.id, fe.monitor_path_id, mp.path_name, fe.file_name, fe.file_path, " +
                "fe.action_type, fe.file_size, fe.event_source, fe.detected_at " +
                "FROM file_events fe " +
                "JOIN monitor_paths mp ON fe.monitor_path_id = mp.id " +
                "ORDER BY fe.detected_at DESC LIMIT ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                events.add(new FileEvent(
                        rs.getInt("id"),
                        rs.getInt("monitor_path_id"),
                        rs.getString("path_name"),
                        rs.getString("file_name"),
                        rs.getString("file_path"),
                        rs.getString("action_type"),
                        rs.getLong("file_size"),
                        rs.getTimestamp("detected_at").toLocalDateTime(),
                        rs.getString("event_source")  // NEW: Event source
                ));
            }

        } catch (SQLException e) {
            logger.error("Failed to get recent events", e);
        }

        return events;
    }

    // ============================================================
    // NEW: Filter Events by Action Type
    // ============================================================
    public ObservableList<FileEvent> getEventsByAction(String actionType, int limit) {
        ObservableList<FileEvent> events = FXCollections.observableArrayList();
        String sql = "SELECT fe.id, fe.monitor_path_id, mp.path_name, fe.file_name, fe.file_path, " +
                "fe.action_type, fe.file_size, fe.event_source, fe.detected_at " +
                "FROM file_events fe " +
                "JOIN monitor_paths mp ON fe.monitor_path_id = mp.id " +
                "WHERE fe.action_type = ? ORDER BY fe.detected_at DESC LIMIT ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, actionType);
            stmt.setInt(2, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                events.add(new FileEvent(
                        rs.getInt("id"),
                        rs.getInt("monitor_path_id"),
                        rs.getString("path_name"),
                        rs.getString("file_name"),
                        rs.getString("file_path"),
                        rs.getString("action_type"),
                        rs.getLong("file_size"),
                        rs.getTimestamp("detected_at").toLocalDateTime(),
                        rs.getString("event_source")
                ));
            }

        } catch (SQLException e) {
            logger.error("Failed to get events by action", e);
        }

        return events;
    }

    // ============================================================
    // NEW: Filter Events by Source (NATIVE, JAVA_WATCH, POLLING)
    // ============================================================
    public ObservableList<FileEvent> getEventsBySource(String eventSource, int limit) {
        ObservableList<FileEvent> events = FXCollections.observableArrayList();
        String sql = "SELECT fe.id, fe.monitor_path_id, mp.path_name, fe.file_name, fe.file_path, " +
                "fe.action_type, fe.file_size, fe.event_source, fe.detected_at " +
                "FROM file_events fe " +
                "JOIN monitor_paths mp ON fe.monitor_path_id = mp.id " +
                "WHERE fe.event_source = ? ORDER BY fe.detected_at DESC LIMIT ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, eventSource);
            stmt.setInt(2, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                events.add(new FileEvent(
                        rs.getInt("id"),
                        rs.getInt("monitor_path_id"),
                        rs.getString("path_name"),
                        rs.getString("file_name"),
                        rs.getString("file_path"),
                        rs.getString("action_type"),
                        rs.getLong("file_size"),
                        rs.getTimestamp("detected_at").toLocalDateTime(),
                        rs.getString("event_source")
                ));
            }

        } catch (SQLException e) {
            logger.error("Failed to get events by source", e);
        }

        return events;
    }

    // ============================================================
    // NEW: Filter by Action AND Source
    // ============================================================
    public ObservableList<FileEvent> getEventsByActionAndSource(String actionType, String eventSource, int limit) {
        ObservableList<FileEvent> events = FXCollections.observableArrayList();
        String sql = "SELECT fe.id, fe.monitor_path_id, mp.path_name, fe.file_name, fe.file_path, " +
                "fe.action_type, fe.file_size, fe.event_source, fe.detected_at " +
                "FROM file_events fe " +
                "JOIN monitor_paths mp ON fe.monitor_path_id = mp.id " +
                "WHERE fe.action_type = ? AND fe.event_source = ? " +
                "ORDER BY fe.detected_at DESC LIMIT ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, actionType);
            stmt.setString(2, eventSource);
            stmt.setInt(3, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                events.add(new FileEvent(
                        rs.getInt("id"),
                        rs.getInt("monitor_path_id"),
                        rs.getString("path_name"),
                        rs.getString("file_name"),
                        rs.getString("file_path"),
                        rs.getString("action_type"),
                        rs.getLong("file_size"),
                        rs.getTimestamp("detected_at").toLocalDateTime(),
                        rs.getString("event_source")
                ));
            }

        } catch (SQLException e) {
            logger.error("Failed to get events by action and source", e);
        }

        return events;
    }

    // ============================================================
// NEW: Get Backup Statistics from space_optimization_stats
// ============================================================
    public BackupStats getBackupStatsFromOptimization() {
        String sql = "SELECT " +
                "COUNT(*) as total_records, " +
                "SUM(total_files_backed_up) as total_files, " +
                "SUM(total_original_size) as total_original, " +
                "SUM(total_compressed_size) as total_compressed, " +
                "SUM(compression_saved) as compression_saved, " +
                "SUM(deduplication_saved) as deduplication_saved, " +
                "SUM(incremental_saved) as incremental_saved, " +
                "SUM(total_space_saved) as total_space_saved, " +
                "AVG(overall_savings_percent) as avg_savings_percent, " +
                "MAX(stat_date) as last_date " +
                "FROM space_optimization_stats " +
                "WHERE stat_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                long totalOriginal = rs.getLong("total_original");
                long totalCompressed = rs.getLong("total_compressed");
                long totalSaved = rs.getLong("total_space_saved");
                double savingsPercent = rs.getDouble("avg_savings_percent");

                if (savingsPercent < 0) savingsPercent = 0;
                if (savingsPercent > 100) savingsPercent = 100;

                logger.info("✓ Backup stats loaded: {} files, {} original, {}% savings",
                        rs.getLong("total_files"),
                        formatBytes(totalOriginal),
                        savingsPercent);

                return new BackupStats(
                        rs.getInt("total_records"),
                        totalOriginal,
                        totalCompressed,
                        savingsPercent,
                        rs.getInt("total_files"),
                        rs.getString("last_date") != null ? rs.getString("last_date") : "Never"
                );
            }
        } catch (SQLException e) {
            logger.error("Failed to get backup stats from optimization table", e);
        }

        return new BackupStats(0, 0, 0, 0, 0, "Never");
    }

    public BackupStats getLatestBackupStats() throws SQLException {
        String query = "SELECT " +
                "COUNT(*) AS total_backups, " +
                "SUM(original_size) AS total_original_size, " +
                "SUM(actual_disk_size) AS total_disk_used, " +
                "AVG(compression_ratio) AS avg_compression, " +
                "COUNT(DISTINCT file_path) AS files_monitored, " +
                "MAX(log_time) AS last_backup_time, " +
                "SUM(CASE WHEN is_deduplicated = TRUE THEN 1 ELSE 0 END) AS deduplicated_files, " +
                "(SELECT COUNT(*) FROM monitor_paths WHERE monitor_method = 'NATIVE') AS native_paths " +
                "FROM file_versions WHERE is_deleted = FALSE";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                int totalBackups = rs.getInt("total_backups");
                long originalSize = rs.getLong("total_original_size");
                long diskUsed = rs.getLong("total_disk_used");
                double savingsPercent = originalSize > 0 ? ((originalSize - diskUsed) * 100.0 / originalSize) : 0;
                int filesMonitored = rs.getInt("files_monitored");
                String lastBackupTime = rs.getString("last_backup_time");
                int dedupFiles = rs.getInt("deduplicated_files");
                int nativePaths = rs.getInt("native_paths");
                double avgCompression = rs.getDouble("avg_compression");

                return new BackupStats(totalBackups, originalSize, diskUsed, savingsPercent, filesMonitored,
                        lastBackupTime, dedupFiles, nativePaths, avgCompression);
            }
        }

        return new BackupStats(0, 0, 0, 0, 0, "Never");
    }

    // Helper method to format bytes
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ============================================================
    // EXISTING: Clear All Logs
    // ============================================================
    public void clearAllLogs() throws SQLException {
        String sql = "DELETE FROM file_events";  // Use file_events instead of file_logs
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
            logger.info("✓ All event logs cleared from database");
        }
    }

    // ============================================================
    // EXISTING: Close Database Connection
    // ============================================================
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("Database pool closed");
        }
    }
}