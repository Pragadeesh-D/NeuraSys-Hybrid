-- ==========================================================
-- ✅ NEURASYS HYBRID DATABASE - SCHEMA (Final Version)
-- ==========================================================
-- For Java + C Native File Monitoring System
-- Last Updated: October 31, 2025

-- Safety reset
SET FOREIGN_KEY_CHECKS = 0;
DROP DATABASE IF EXISTS NeuraSysDB;
CREATE DATABASE NeuraSysDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE NeuraSysDB;
SET FOREIGN_KEY_CHECKS = 1;

-- Create user (if not exists) and grant privileges
CREATE USER IF NOT EXISTS 'neurasys_app'@'localhost' IDENTIFIED BY 'NeuraSys@2025!';
GRANT ALL PRIVILEGES ON NeuraSysDB.* TO 'neurasys_app'@'localhost';
FLUSH PRIVILEGES;

-- ----------------------------------------------------------
-- TABLE 1: Monitor Paths (Directories being monitored)
-- ----------------------------------------------------------
CREATE TABLE monitor_paths (
    id INT AUTO_INCREMENT PRIMARY KEY,
    path_name VARCHAR(255) NOT NULL UNIQUE,
    path_location TEXT NOT NULL,
    path_type VARCHAR(50) NOT NULL COMMENT 'LOCAL, ONEDRIVE, NETWORK, EXTERNAL',
    is_enabled BOOLEAN DEFAULT TRUE,
    enable_compression BOOLEAN DEFAULT FALSE,
    enable_deduplication BOOLEAN DEFAULT FALSE,
    enable_incremental BOOLEAN DEFAULT FALSE,
    monitor_method VARCHAR(50) DEFAULT 'NATIVE' COMMENT 'NATIVE (C), JAVA_WATCH, POLLING',
    total_backups_created INT DEFAULT 0,
    total_space_saved BIGINT DEFAULT 0,
    total_files_monitored INT DEFAULT 0,
    last_scan DATETIME,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_path_enabled (is_enabled),
    INDEX idx_monitor_method (monitor_method)
);

-- ----------------------------------------------------------
-- TABLE 2: File Events (Real-time events from C monitor)
-- ----------------------------------------------------------
CREATE TABLE file_events (
    id INT AUTO_INCREMENT PRIMARY KEY,
    monitor_path_id INT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_path TEXT NOT NULL,
    action_type VARCHAR(50) NOT NULL COMMENT 'CREATE, MODIFY, DELETE, RENAME',
    file_size BIGINT DEFAULT 0,
    event_source VARCHAR(50) DEFAULT 'NATIVE' COMMENT 'NATIVE (C DLL), JAVA_WATCH, POLLING',
    detected_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (monitor_path_id) REFERENCES monitor_paths(id) ON DELETE CASCADE,
    INDEX idx_file_events_composite (monitor_path_id, action_type, detected_at),
    INDEX idx_file_path (file_path(255))
);

-- ----------------------------------------------------------
-- TABLE 3: File Versions (Backup history with compression)
-- ----------------------------------------------------------
CREATE TABLE file_versions (
    id INT AUTO_INCREMENT PRIMARY KEY,
    monitor_path_id INT NOT NULL,
    file_path TEXT NOT NULL,
    file_event_id INT,
    version_tag VARCHAR(100) NOT NULL,
    original_size BIGINT DEFAULT 0,
    compressed_size BIGINT DEFAULT 0,
    actual_disk_size BIGINT DEFAULT 0,
    space_saved BIGINT DEFAULT 0,
    compression_ratio DECIMAL(5,2) DEFAULT 0.00,
    backup_type VARCHAR(50) DEFAULT 'FULL' COMMENT 'FULL, INCREMENTAL, DIFFERENTIAL',
    compression_algo VARCHAR(50) DEFAULT 'ZIP' COMMENT 'ZIP, 7Z, GZIP, NONE',
    is_deduplicated BOOLEAN DEFAULT FALSE,
    is_deleted BOOLEAN DEFAULT FALSE,
    retention_date DATE NULL,
    content_hash VARCHAR(255) NULL COMMENT 'SHA256 for deduplication',
    backup_duration_ms INT DEFAULT 0,
    log_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (monitor_path_id) REFERENCES monitor_paths(id) ON DELETE CASCADE,
    FOREIGN KEY (file_event_id) REFERENCES file_events(id) ON DELETE SET NULL,
    INDEX idx_file_versions_composite (monitor_path_id, is_deleted, log_time),
    INDEX idx_hash_dedup (content_hash, monitor_path_id),
    INDEX idx_retention (retention_date)
);

-- ----------------------------------------------------------
-- TABLE 4: File Logs (Activity audit trail)
-- ----------------------------------------------------------
CREATE TABLE file_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,
    monitor_path_id INT NOT NULL,
    file_name VARCHAR(255),
    file_path TEXT,
    action_type VARCHAR(50) NOT NULL,
    file_size BIGINT DEFAULT 0,
    status VARCHAR(50) DEFAULT 'SUCCESS' COMMENT 'SUCCESS, FAILED, PENDING',
    error_message TEXT,
    log_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (monitor_path_id) REFERENCES monitor_paths(id) ON DELETE CASCADE,
    INDEX idx_file_logs_composite (monitor_path_id, action_type, log_time),
    INDEX idx_status (status)
);

-- ----------------------------------------------------------
-- TABLE 5: Space Optimization Stats (Daily aggregated stats)
-- ----------------------------------------------------------
CREATE TABLE space_optimization_stats (
    id INT AUTO_INCREMENT PRIMARY KEY,
    monitor_path_id INT NOT NULL,
    stat_date DATE NOT NULL,
    total_files_backed_up INT DEFAULT 0,
    total_original_size BIGINT DEFAULT 0,
    total_compressed_size BIGINT DEFAULT 0,
    compression_saved BIGINT DEFAULT 0,
    deduplication_saved BIGINT DEFAULT 0,
    incremental_saved BIGINT DEFAULT 0,
    total_space_saved BIGINT DEFAULT 0,
    compression_ratio_avg DECIMAL(5,2) DEFAULT 0.00,
    overall_savings_percent DECIMAL(5,2) DEFAULT 0.00,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (monitor_path_id) REFERENCES monitor_paths(id) ON DELETE CASCADE,
    UNIQUE KEY unique_path_date (monitor_path_id, stat_date),
    INDEX idx_stat_date (stat_date)
);

-- ----------------------------------------------------------
-- TABLE 6: Backup Metadata (Backup job tracking)
-- ----------------------------------------------------------
CREATE TABLE backup_metadata (
    id INT AUTO_INCREMENT PRIMARY KEY,
    monitor_path_id INT NOT NULL,
    backup_id VARCHAR(100) NOT NULL UNIQUE,
    backup_type VARCHAR(50),
    start_time DATETIME,
    end_time DATETIME,
    duration_seconds INT,
    files_processed INT DEFAULT 0,
    files_failed INT DEFAULT 0,
    total_size_processed BIGINT DEFAULT 0,
    status VARCHAR(50) DEFAULT 'IN_PROGRESS' COMMENT 'IN_PROGRESS, COMPLETED, FAILED, CANCELLED',
    error_log TEXT,
    FOREIGN KEY (monitor_path_id) REFERENCES monitor_paths(id) ON DELETE CASCADE,
    INDEX idx_backup_status (status),
    INDEX idx_backup_date (start_time)
);

-- ----------------------------------------------------------
-- TABLE 7: Performance Metrics (System health tracking)
-- ----------------------------------------------------------
CREATE TABLE performance_metrics (
    id INT AUTO_INCREMENT PRIMARY KEY,
    monitor_path_id INT,
    metric_date DATE NOT NULL,
    metric_type VARCHAR(50) COMMENT 'CPU, MEMORY, DISK_IO, BACKUP_SPEED',
    metric_value DECIMAL(10,2),
    unit VARCHAR(50) COMMENT 'PERCENT, MB, MB_PER_SEC',
    recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (monitor_path_id) REFERENCES monitor_paths(id) ON DELETE SET NULL,
    INDEX idx_metric_date (metric_date),
    INDEX idx_metric_type (metric_type)
);

-- ----------------------------------------------------------
-- TRIGGERS
-- ----------------------------------------------------------

-- Trigger 1: Calculate space saved and compression ratio on file_versions insert
DROP TRIGGER IF EXISTS tr_calc_space_saved;
DELIMITER //
CREATE TRIGGER tr_calc_space_saved
BEFORE INSERT ON file_versions
FOR EACH ROW
BEGIN
    SET NEW.space_saved = NEW.original_size - NEW.actual_disk_size;
    IF NEW.original_size > 0 THEN
        SET NEW.compression_ratio = ROUND(((NEW.original_size - NEW.compressed_size) / NEW.original_size) * 100, 2);
    ELSE
        SET NEW.compression_ratio = 0;
    END IF;
END //
DELIMITER ;

-- Trigger 2: Update monitor_paths stats when new version is added
DROP TRIGGER IF EXISTS tr_update_path_stats;
DELIMITER //
CREATE TRIGGER tr_update_path_stats
AFTER INSERT ON file_versions
FOR EACH ROW
BEGIN
    UPDATE monitor_paths
    SET 
        total_backups_created = COALESCE(total_backups_created, 0) + 1,
        total_space_saved = COALESCE(total_space_saved, 0) + NEW.space_saved,
        last_scan = NOW()
    WHERE id = NEW.monitor_path_id;
END //
DELIMITER ;

-- Trigger 3: Update daily stats when file_versions are inserted
DROP TRIGGER IF EXISTS tr_update_daily_stats;
DELIMITER //
CREATE TRIGGER tr_update_daily_stats
AFTER INSERT ON file_versions
FOR EACH ROW
BEGIN
    INSERT INTO space_optimization_stats (
        monitor_path_id, stat_date,
        total_files_backed_up, total_original_size, total_compressed_size,
        compression_saved, deduplication_saved, incremental_saved,
        total_space_saved, compression_ratio_avg, overall_savings_percent
    )
    SELECT 
        NEW.monitor_path_id,
        CURDATE(),
        COUNT(*),
        SUM(original_size),
        SUM(compressed_size),
        SUM(original_size - compressed_size),
        SUM(CASE WHEN is_deduplicated = TRUE THEN original_size ELSE 0 END),
        SUM(CASE WHEN backup_type = 'INCREMENTAL' THEN space_saved ELSE 0 END),
        SUM(space_saved),
        ROUND(AVG(compression_ratio), 2),
        ROUND((SUM(space_saved) / NULLIF(SUM(original_size), 0)) * 100, 2)
    FROM file_versions
    WHERE monitor_path_id = NEW.monitor_path_id AND is_deleted = FALSE AND DATE(log_time) = CURDATE()
    ON DUPLICATE KEY UPDATE
        total_files_backed_up = VALUES(total_files_backed_up),
        total_original_size = VALUES(total_original_size),
        total_compressed_size = VALUES(total_compressed_size),
        compression_saved = VALUES(compression_saved),
        deduplication_saved = VALUES(deduplication_saved),
        incremental_saved = VALUES(incremental_saved),
        total_space_saved = VALUES(total_space_saved),
        compression_ratio_avg = VALUES(compression_ratio_avg),
        overall_savings_percent = VALUES(overall_savings_percent);
END //
DELIMITER ;

-- ----------------------------------------------------------
-- INDEXES (Optimized for hybrid queries)
-- ----------------------------------------------------------
CREATE INDEX idx_monitor_paths_enabled ON monitor_paths(is_enabled, monitor_method);
CREATE INDEX idx_file_events_action ON file_events(action_type, detected_at);
CREATE INDEX idx_file_versions_backup_type ON file_versions(backup_type, log_time);
CREATE INDEX idx_file_logs_error ON file_logs(status, log_time);
CREATE INDEX idx_backup_metadata_status ON backup_metadata(status, start_time);

-- ----------------------------------------------------------
-- ✅ VERIFICATION
-- ----------------------------------------------------------
SELECT '🎯 Hybrid NeuraSys Database Schema Created Successfully!' AS status;
SELECT '✅ Tables: 7 (monitor_paths, file_events, file_versions, file_logs, space_optimization_stats, backup_metadata, performance_metrics)' AS info;
SELECT '✅ Triggers: 3 (auto-calculation and stat updates)' AS info;
SELECT '✅ Optimized for Java+C Native File Monitoring' AS info;