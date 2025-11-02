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

    // ============================================================
    // NEW: Add Monitor Path and return database ID
    // ============================================================
    public int addMonitorPath(MonitorConfig config) throws SQLException {
        String sql = "INSERT INTO monitor_paths (path_name, path_location, path_type, " +
                "monitor_method, is_enabled, enable_compression, enable_deduplication, " +
                "enable_incremental) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, config.getPathName());
            stmt.setString(2, config.getPathLocation());
            stmt.setString(3, config.getPathType());
            stmt.setString(4, config.getMonitorMethod());  // NEW: NATIVE, JAVA_WATCH, POLLING
            stmt.setBoolean(5, config.isEnabled());
            stmt.setBoolean(6, config.isCompressionEnabled());
            stmt.setBoolean(7, config.isDeduplicationEnabled());
            stmt.setBoolean(8, config.isIncrementalEnabled());

            stmt.executeUpdate();

            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                int pathId = rs.getInt(1);
                logger.info("✓ Monitor path added: {} (ID: {}, Method: {})",
                        config.getPathName(), pathId, config.getMonitorMethod());
                return pathId;
            }
        } catch (SQLException e) {
            logger.error("Failed to add monitor path", e);
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