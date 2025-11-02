-- ==========================================================
-- ✅ NEURASYS HYBRID DATABASE - STORED PROCEDURES
-- ==========================================================
-- For Java + C Native File Monitoring System
-- Last Updated: October 31, 2025

USE NeuraSysDB;

DELIMITER //

-- ===========================================================================
-- PROCEDURE 1: Get Comprehensive System Statistics
-- ===========================================================================
DROP PROCEDURE IF EXISTS sp_get_comprehensive_stats//
CREATE PROCEDURE sp_get_comprehensive_stats()
BEGIN
    SELECT 
        COUNT(DISTINCT mp.id) AS total_monitor_paths,
        COUNT(DISTINCT fv.id) AS total_backups,
        COUNT(DISTINCT fv.file_path) AS unique_files,
        SUM(fv.original_size) AS total_original_size,
        SUM(fv.actual_disk_size) AS total_disk_used,
        SUM(fv.space_saved) AS total_space_saved,
        ROUND(AVG(fv.compression_ratio), 2) AS avg_compression_ratio,
        ROUND((SUM(fv.space_saved) / NULLIF(SUM(fv.original_size), 0)) * 100, 2) AS overall_savings_percent,
        MAX(fv.log_time) AS last_backup_time,
        SUM(mp.total_files_monitored) AS total_files_monitored,
        COUNT(CASE WHEN mp.monitor_method = 'NATIVE' THEN 1 END) AS native_monitored_paths,
        COUNT(CASE WHEN mp.is_enabled = TRUE THEN 1 END) AS active_paths
    FROM monitor_paths mp
    LEFT JOIN file_versions fv 
        ON mp.id = fv.monitor_path_id AND fv.is_deleted = FALSE;
END //

-- ===========================================================================
-- PROCEDURE 2: Get Path-Specific Statistics
-- ===========================================================================
DROP PROCEDURE IF EXISTS sp_get_path_stats//
CREATE PROCEDURE sp_get_path_stats(IN p_path_id INT)
BEGIN
    SELECT 
        mp.id,
        mp.path_name,
        mp.path_location,
        mp.path_type,
        mp.monitor_method,
        mp.is_enabled,
        COUNT(fv.id) AS total_backups,
        COUNT(fe.id) AS total_events,
        SUM(fv.original_size) AS total_original_size,
        SUM(fv.actual_disk_size) AS total_disk_used,
        SUM(fv.space_saved) AS total_space_saved,
        ROUND((SUM(fv.space_saved) / NULLIF(SUM(fv.original_size), 0)) * 100, 2) AS savings_percent,
        ROUND(AVG(fv.compression_ratio), 2) AS avg_compression_ratio,
        COUNT(CASE WHEN fv.is_deduplicated = TRUE THEN 1 END) AS deduplicated_count,
        COUNT(CASE WHEN fv.backup_type = 'INCREMENTAL' THEN 1 END) AS incremental_count,
        COUNT(CASE WHEN fv.backup_type = 'FULL' THEN 1 END) AS full_count,
        MAX(fv.log_time) AS last_backup_time,
        mp.last_scan,
        mp.total_files_monitored
    FROM monitor_paths mp
    LEFT JOIN file_versions fv 
        ON mp.id = fv.monitor_path_id AND fv.is_deleted = FALSE
    LEFT JOIN file_events fe
        ON mp.id = fe.monitor_path_id
    WHERE mp.id = p_path_id
    GROUP BY mp.id;
END //

-- ===========================================================================
-- PROCEDURE 3: Get Recent File Events (Real-time monitoring)
-- ===========================================================================
DROP PROCEDURE IF EXISTS sp_get_recent_events//
CREATE PROCEDURE sp_get_recent_events(IN p_limit INT)
BEGIN
    SELECT 
        fe.id,
        fe.monitor_path_id,
        mp.path_name,
        fe.file_name,
        fe.file_path,
        fe.action_type,
        fe.file_size,
        fe.event_source,
        fe.detected_at
    FROM file_events fe
    JOIN monitor_paths mp 
        ON fe.monitor_path_id = mp.id
    WHERE mp.is_enabled = TRUE
    ORDER BY fe.detected_at DESC
    LIMIT p_limit;
END //

-- ===========================================================================
-- PROCEDURE 4: Get Daily Space Optimization Statistics
-- ===========================================================================
DROP PROCEDURE IF EXISTS sp_update_space_stats//
CREATE PROCEDURE sp_update_space_stats()
BEGIN
    INSERT INTO space_optimization_stats (
        monitor_path_id, stat_date,
        total_files_backed_up, total_original_size, total_compressed_size,
        compression_saved, deduplication_saved, incremental_saved,
        total_space_saved, compression_ratio_avg, overall_savings_percent
    )
    SELECT 
        fv.monitor_path_id,
        CURDATE(),
        COUNT(fv.id),
        SUM(fv.original_size),
        SUM(fv.compressed_size),
        SUM(fv.original_size - fv.compressed_size),
        SUM(CASE WHEN fv.is_deduplicated = TRUE THEN fv.original_size ELSE 0 END),
        SUM(CASE WHEN fv.backup_type = 'INCREMENTAL' THEN fv.space_saved ELSE 0 END),
        SUM(fv.space_saved),
        ROUND(AVG(fv.compression_ratio), 2),
        ROUND((SUM(fv.space_saved) / NULLIF(SUM(fv.original_size), 0)) * 100, 2)
    FROM file_versions fv
    WHERE fv.is_deleted = FALSE
      AND DATE(fv.log_time) = CURDATE()
    GROUP BY fv.monitor_path_id
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

-- ===========================================================================
-- PROCEDURE 5: Cleanup Expired Backups
-- ===========================================================================
DROP PROCEDURE IF EXISTS sp_cleanup_old_backups//
CREATE PROCEDURE sp_cleanup_old_backups()
BEGIN
    DECLARE deleted_count INT DEFAULT 0;

    UPDATE file_versions
    SET is_deleted = TRUE
    WHERE is_deleted = FALSE
      AND retention_date IS NOT NULL
      AND retention_date < CURDATE();

    SELECT ROW_COUNT() INTO deleted_count;
    SELECT deleted_count AS deleted_count, NOW() AS cleanup_time;
END //

-- ===========================================================================
-- PROCEDURE 6: Get All Monitor Paths with Statistics
-- ===========================================================================
DROP PROCEDURE IF EXISTS sp_get_all_paths//
CREATE PROCEDURE sp_get_all_paths()
BEGIN
    SELECT 
        mp.id,
        mp.path_name,
        mp.path_location,
        mp.path_type,
        mp.monitor_method,
        mp.is_enabled,
        mp.enable_compression,
        mp.enable_deduplication,
        mp.enable_incremental,
        COUNT(fv.id) AS total_backups,
        SUM(fv.original_size) AS total_size,
        SUM(fv.space_saved) AS space_saved,
        ROUND((SUM(fv.space_saved) / NULLIF(SUM(fv.original_size), 0)) * 100, 2) AS savings_percent,
        COUNT(fe.id) AS total_events,
        mp.last_scan,
        mp.created_at
    FROM monitor_paths mp
    LEFT JOIN file_versions fv 
        ON mp.id = fv.monitor_path_id AND fv.is_deleted = FALSE
    LEFT JOIN file_events fe
        ON mp.id = fe.monitor_path_id
    GROUP BY mp.id
    ORDER BY mp.created_at DESC;
END //

-- ===========================================================================
-- PROCEDURE 7: Get File Version History
-- ===========================================================================
DROP PROCEDURE IF EXISTS sp_get_file_history//
CREATE PROCEDURE sp_get_file_history(IN p_file_path TEXT)
BEGIN
    SELECT 
        id, monitor_path_id, version_tag, original_size,
        compressed_size, actual_disk_size, space_saved,
        backup_type, compression_algo, compression_ratio,
        is_deduplicated, content_hash, backup_duration_ms,
        log_time
    FROM file_versions
    WHERE file_path = p_file_path
      AND is_deleted = FALSE
    ORDER BY log_time DESC;
END //

-- ===========================================================================
-- PROCEDURE 8: Export Statistics (for reporting)
-- ===========================================================================
DROP PROCEDURE IF EXISTS sp_export_statistics//
CREATE PROCEDURE sp_export_statistics()
BEGIN
    SELECT 
        mp.path_name,
        mp.path_type,
        mp.monitor_method,
        COUNT(fv.id) AS backups,
        ROUND(SUM(fv.original_size) / 1024 / 1024 / 1024, 2) AS original_gb,
        ROUND(SUM(fv.actual_disk_size) / 1024 / 1024 / 1024, 2) AS disk_gb,
        ROUND(SUM(fv.space_saved) / 1024 / 1024 / 1024, 2) AS saved_gb,
        ROUND((SUM(fv.space_saved) / NULLIF(SUM(fv.original_size), 0)) * 100, 2) AS savings_percent,
        ROUND(AVG(fv.compression_ratio), 2) AS avg_compression,
        COUNT(CASE WHEN fv.is_deduplicated = TRUE THEN 1 END) AS deduplicated_files,
        MAX(fv.log_time) AS last_backup
    FROM monitor_paths mp
    LEFT JOIN file_versions fv 
        ON mp.id = fv.monitor_path_id AND fv.is_deleted = FALSE
    GROUP BY mp.id
    ORDER BY saved_gb DESC;
END //

-- ===========================================================================
-- PROCEDURE 9: Get Backup Job Details
-- ===========================================================================
DROP PROCEDURE IF EXISTS sp_get_backup_jobs//
CREATE PROCEDURE sp_get_backup_jobs(IN p_limit INT)
BEGIN
    SELECT 
        bm.id,
        bm.backup_id,
        mp.path_name,
        bm.backup_type,
        bm.start_time,
        bm.end_time,
        bm.duration_seconds,
        bm.files_processed,
        bm.files_failed,
        ROUND(bm.total_size_processed / 1024 / 1024 / 1024, 2) AS size_processed_gb,
        bm.status
    FROM backup_metadata bm
    JOIN monitor_paths mp ON bm.monitor_path_id = mp.id
    ORDER BY bm.start_time DESC
    LIMIT p_limit;
END //

-- ===========================================================================
-- PROCEDURE 10: Get Compression Algorithm Performance
-- ===========================================================================
DROP PROCEDURE IF EXISTS sp_get_compression_performance//
CREATE PROCEDURE sp_get_compression_performance()
BEGIN
    SELECT 
        compression_algo,
        COUNT(*) as file_count,
        ROUND(AVG(compression_ratio), 2) as avg_compression_ratio,
        ROUND(SUM(space_saved) / 1024 / 1024 / 1024, 2) as total_space_saved_gb,
        ROUND(AVG(backup_duration_ms), 0) as avg_duration_ms,
        MIN(backup_duration_ms) as min_duration_ms,
        MAX(backup_duration_ms) as max_duration_ms
    FROM file_versions
    WHERE is_deleted = FALSE
    GROUP BY compression_algo
    ORDER BY total_space_saved_gb DESC;
END //

-- ===========================================================================
-- PROCEDURE 11: Get Deduplication Statistics
-- ===========================================================================
DROP PROCEDURE IF EXISTS sp_get_deduplication_stats//
CREATE PROCEDURE sp_get_deduplication_stats()
BEGIN
    SELECT 
        COUNT(*) as deduplicated_files,
        ROUND(SUM(original_size) / 1024 / 1024 / 1024, 2) as total_original_gb,
        ROUND(SUM(space_saved) / 1024 / 1024 / 1024, 2) as space_saved_gb,
        ROUND((SUM(space_saved) / NULLIF(SUM(original_size), 0)) * 100, 2) as dedup_savings_percent
    FROM file_versions
    WHERE is_deduplicated = TRUE AND is_deleted = FALSE;
END //

-- ===========================================================================
-- PROCEDURE 12: Get Duplicate Files by Content Hash
-- ===========================================================================
DROP PROCEDURE IF EXISTS sp_get_duplicate_files//
CREATE PROCEDURE sp_get_duplicate_files()
BEGIN
    SELECT 
        content_hash,
        COUNT(*) as duplicate_count,
        GROUP_CONCAT(DISTINCT file_path SEPARATOR '; ') as file_paths,
        ROUND(SUM(original_size) / 1024 / 1024, 2) as total_size_mb
    FROM file_versions
    WHERE is_deleted = FALSE
      AND content_hash IS NOT NULL
    GROUP BY content_hash
    HAVING COUNT(*) > 1
    ORDER BY COUNT(*) DESC
    LIMIT 100;
END //

-- ===========================================================================
-- PROCEDURE 13: Get Recent Activity (Last N hours)
-- ===========================================================================
DROP PROCEDURE IF EXISTS sp_get_recent_activity//
CREATE PROCEDURE sp_get_recent_activity(IN p_hours INT)
BEGIN
    SELECT 
        mp.path_name,
        fl.file_name,
        fl.action_type,
        fl.status,
        ROUND(fl.file_size / 1024 / 1024, 2) as file_size_mb,
        fl.log_time
    FROM file_logs fl
    JOIN monitor_paths mp ON fl.monitor_path_id = mp.id
    WHERE fl.log_time >= DATE_SUB(NOW(), INTERVAL p_hours HOUR)
    ORDER BY fl.log_time DESC
    LIMIT 500;
END //

-- ===========================================================================
-- PROCEDURE 14: Get Monitor Method Performance
-- ===========================================================================
DROP PROCEDURE IF EXISTS sp_get_monitor_method_performance//
CREATE PROCEDURE sp_get_monitor_method_performance()
BEGIN
    SELECT 
        mp.monitor_method,
        COUNT(DISTINCT mp.id) as monitor_count,
        COUNT(fe.id) as total_events,
        COUNT(DISTINCT fv.id) as total_backups,
        ROUND(SUM(fv.space_saved) / 1024 / 1024 / 1024, 2) as space_saved_gb,
        COUNT(CASE WHEN mp.is_enabled = TRUE THEN 1 END) as active_monitors
    FROM monitor_paths mp
    LEFT JOIN file_events fe ON mp.id = fe.monitor_path_id
    LEFT JOIN file_versions fv ON mp.id = fv.monitor_path_id AND fv.is_deleted = FALSE
    GROUP BY mp.monitor_method;
END //

DELIMITER ;

-- ===========================================================================
-- ✅ VERIFICATION
-- ===========================================================================
SELECT '🎯 Stored Procedures Created Successfully!' AS status;
SELECT '✅ Total Procedures: 14' AS info;
SELECT '✅ Categories: Statistics, Reporting, Maintenance, Performance Analysis' AS info;
