-- ==========================================================
-- ✅ NEURASYS HYBRID DATABASE - SAMPLE QUERIES
-- ==========================================================
-- For Java + C Native File Monitoring System
-- Last Updated: October 31, 2025

USE NeuraSysDB;

-- ===========================================================================
-- SECTION 1: OVERVIEW & MONITORING QUERIES
-- ===========================================================================

-- 1. Get overall system statistics (all paths combined)
SELECT 
    COUNT(DISTINCT mp.id) as monitor_paths,
    COUNT(DISTINCT fv.id) as total_backups,
    COUNT(DISTINCT fl.file_path) as unique_files,
    ROUND(SUM(fv.space_saved) / 1024 / 1024 / 1024, 2) as space_saved_gb,
    ROUND(SUM(fv.original_size) / 1024 / 1024 / 1024, 2) as original_size_gb,
    ROUND((SUM(fv.space_saved) / NULLIF(SUM(fv.original_size), 0)) * 100, 2) as overall_savings_percent,
    COUNT(CASE WHEN mp.monitor_method = 'NATIVE' THEN 1 END) as native_monitors,
    COUNT(CASE WHEN mp.is_enabled = TRUE THEN 1 END) as active_monitors
FROM monitor_paths mp
LEFT JOIN file_versions fv ON mp.id = fv.monitor_path_id AND fv.is_deleted = FALSE
LEFT JOIN file_logs fl ON mp.id = fl.monitor_path_id;

-- 2. Get top 10 most backed up files (largest candidates for deduplication)
SELECT 
    file_path,
    COUNT(*) as backup_count,
    ROUND(SUM(original_size) / 1024 / 1024, 2) as total_size_mb,
    ROUND(SUM(space_saved) / 1024 / 1024, 2) as total_space_saved_mb,
    AVG(compression_ratio) as avg_compression
FROM file_versions
WHERE is_deleted = FALSE
GROUP BY file_path
ORDER BY backup_count DESC
LIMIT 10;

-- 3. Get deduplication effectiveness analysis
SELECT 
    COUNT(*) as deduplicated_files,
    ROUND(SUM(original_size) / 1024 / 1024 / 1024, 2) as total_original_gb,
    ROUND(SUM(space_saved) / 1024 / 1024 / 1024, 2) as space_saved_gb,
    ROUND((SUM(space_saved) / NULLIF(SUM(original_size), 0)) * 100, 2) as dedup_savings_percent
FROM file_versions
WHERE is_deduplicated = TRUE AND is_deleted = FALSE;

-- 4. Get compression algorithm statistics
SELECT 
    compression_algo,
    COUNT(*) as file_count,
    AVG(compression_ratio) as avg_compression_ratio,
    ROUND(SUM(space_saved) / 1024 / 1024 / 1024, 2) as space_saved_gb,
    ROUND(AVG(backup_duration_ms), 0) as avg_duration_ms
FROM file_versions
WHERE is_deleted = FALSE
GROUP BY compression_algo
ORDER BY space_saved_gb DESC;

-- 5. Get backup type breakdown (FULL vs INCREMENTAL)
SELECT 
    backup_type,
    COUNT(*) as count,
    ROUND(SUM(original_size) / 1024 / 1024 / 1024, 2) as original_gb,
    ROUND(SUM(actual_disk_size) / 1024 / 1024 / 1024, 2) as disk_used_gb,
    ROUND(AVG(compression_ratio), 2) as avg_compression,
    ROUND(AVG(backup_duration_ms), 0) as avg_duration_ms
FROM file_versions
WHERE is_deleted = FALSE
GROUP BY backup_type
ORDER BY count DESC;

-- ===========================================================================
-- SECTION 2: NATIVE vs JAVA MONITORING ANALYSIS
-- ===========================================================================

-- 6. Compare performance: NATIVE (C DLL) vs JAVA monitoring methods
SELECT 
    mp.monitor_method,
    COUNT(DISTINCT mp.id) as monitor_count,
    COUNT(fe.id) as total_events,
    ROUND(SUM(fv.space_saved) / 1024 / 1024 / 1024, 2) as space_saved_gb,
    COUNT(CASE WHEN mp.is_enabled = TRUE THEN 1 END) as active_monitors,
    ROUND(AVG(fv.backup_duration_ms), 0) as avg_backup_duration_ms
FROM monitor_paths mp
LEFT JOIN file_events fe ON mp.id = fe.monitor_path_id
LEFT JOIN file_versions fv ON mp.id = fv.monitor_path_id AND fv.is_deleted = FALSE
GROUP BY mp.monitor_method;

-- 7. Get Native (C DLL) events by action type
SELECT 
    fe.action_type,
    COUNT(*) as event_count,
    COUNT(DISTINCT fe.monitor_path_id) as paths_affected,
    ROUND(AVG(fe.file_size) / 1024 / 1024, 2) as avg_file_size_mb,
    MAX(fe.detected_at) as last_event
FROM file_events fe
WHERE fe.event_source = 'NATIVE'
GROUP BY fe.action_type
ORDER BY event_count DESC;

-- 8. Compare event sources (NATIVE vs JAVA_WATCH vs POLLING)
SELECT 
    fe.event_source,
    COUNT(*) as event_count,
    COUNT(DISTINCT fe.monitor_path_id) as paths,
    DATE(MAX(fe.detected_at)) as last_activity_date
FROM file_events fe
GROUP BY fe.event_source
ORDER BY event_count DESC;

-- ===========================================================================
-- SECTION 3: STORAGE & SPACE MANAGEMENT
-- ===========================================================================

-- 9. Get monitor paths sorted by storage usage
SELECT 
    mp.path_name,
    mp.path_type,
    mp.monitor_method,
    ROUND(SUM(fv.actual_disk_size) / 1024 / 1024 / 1024, 2) as disk_used_gb,
    ROUND(SUM(fv.original_size) / 1024 / 1024 / 1024, 2) as original_gb,
    ROUND(SUM(fv.space_saved) / 1024 / 1024 / 1024, 2) as space_saved_gb,
    COUNT(fv.id) as backup_count,
    ROUND((SUM(fv.space_saved) / NULLIF(SUM(fv.original_size), 0)) * 100, 2) as savings_percent
FROM monitor_paths mp
LEFT JOIN file_versions fv ON mp.id = fv.monitor_path_id AND fv.is_deleted = FALSE
GROUP BY mp.id
ORDER BY disk_used_gb DESC;

-- 10. Get files pending deletion (retention expired)
SELECT 
    id,
    file_path,
    retention_date,
    DATEDIFF(CURDATE(), retention_date) as days_overdue,
    ROUND(original_size / 1024 / 1024, 2) as size_mb
FROM file_versions
WHERE is_deleted = FALSE
  AND retention_date IS NOT NULL
  AND retention_date < CURDATE()
ORDER BY retention_date ASC
LIMIT 100;

-- 11. Get daily backup statistics for last 30 days
SELECT 
    DATE(log_time) as backup_date,
    COUNT(*) as files_backed_up,
    ROUND(SUM(original_size) / 1024 / 1024 / 1024, 2) as original_size_gb,
    ROUND(SUM(actual_disk_size) / 1024 / 1024 / 1024, 2) as disk_used_gb,
    ROUND(SUM(space_saved) / 1024 / 1024 / 1024, 2) as space_saved_gb,
    ROUND((SUM(space_saved) / SUM(original_size)) * 100, 2) as savings_percent
FROM file_versions
WHERE is_deleted = FALSE
  AND log_time >= DATE_SUB(NOW(), INTERVAL 30 DAY)
GROUP BY DATE(log_time)
ORDER BY backup_date DESC;

-- ===========================================================================
-- SECTION 4: DUPLICATE FILES & DEDUPLICATION
-- ===========================================================================

-- 12. Get duplicate files by content hash
SELECT 
    content_hash,
    COUNT(*) as duplicate_count,
    GROUP_CONCAT(DISTINCT file_path SEPARATOR ' | ') as file_paths,
    ROUND(SUM(original_size) / 1024 / 1024, 2) as total_size_mb,
    ROUND(SUM(original_size) * (COUNT(*) - 1) / 1024 / 1024, 2) as potential_savings_mb
FROM file_versions
WHERE is_deleted = FALSE
  AND content_hash IS NOT NULL
GROUP BY content_hash
HAVING COUNT(*) > 1
ORDER BY COUNT(*) DESC
LIMIT 50;

-- 13. Find large duplicate files (over 100MB with duplicates)
SELECT 
    content_hash,
    COUNT(*) as duplicate_count,
    ROUND(original_size / 1024 / 1024, 2) as file_size_mb,
    GROUP_CONCAT(DISTINCT file_path SEPARATOR ' | ') as locations
FROM file_versions
WHERE is_deleted = FALSE
  AND content_hash IS NOT NULL
  AND original_size > 104857600
GROUP BY content_hash, original_size
HAVING COUNT(*) > 1
ORDER BY file_size_mb DESC
LIMIT 50;

-- ===========================================================================
-- SECTION 5: RECENT ACTIVITY & MONITORING
-- ===========================================================================

-- 14. Get recent activity (last 24 hours)
SELECT 
    mp.path_name,
    fl.file_name,
    fl.action_type,
    fl.status,
    ROUND(fl.file_size / 1024 / 1024, 2) as file_size_mb,
    fl.log_time
FROM file_logs fl
JOIN monitor_paths mp ON fl.monitor_path_id = mp.id
WHERE fl.log_time >= DATE_SUB(NOW(), INTERVAL 1 DAY)
ORDER BY fl.log_time DESC
LIMIT 200;

-- 15. Get failed backup operations
SELECT 
    fl.id,
    mp.path_name,
    fl.file_path,
    fl.action_type,
    fl.error_message,
    fl.log_time
FROM file_logs fl
JOIN monitor_paths mp ON fl.monitor_path_id = mp.id
WHERE fl.status = 'FAILED'
  AND fl.log_time >= DATE_SUB(NOW(), INTERVAL 7 DAY)
ORDER BY fl.log_time DESC;

-- 16. Get real-time file events from Native C monitor
SELECT 
    fe.id,
    mp.path_name,
    fe.file_name,
    fe.action_type,
    ROUND(fe.file_size / 1024 / 1024, 2) as file_size_mb,
    fe.event_source,
    fe.detected_at
FROM file_events fe
JOIN monitor_paths mp ON fe.monitor_path_id = mp.id
WHERE fe.event_source = 'NATIVE'
  AND fe.detected_at >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
ORDER BY fe.detected_at DESC
LIMIT 500;

-- ===========================================================================
-- SECTION 6: BACKUP JOB ANALYSIS
-- ===========================================================================

-- 17. Get backup job performance metrics
SELECT 
    bm.backup_id,
    mp.path_name,
    bm.backup_type,
    bm.start_time,
    bm.end_time,
    ROUND(bm.duration_seconds / 60, 2) as duration_minutes,
    bm.files_processed,
    bm.files_failed,
    ROUND(bm.total_size_processed / 1024 / 1024 / 1024, 2) as size_processed_gb,
    ROUND((bm.total_size_processed / 1024 / 1024 / 1024) / NULLIF(bm.duration_seconds / 60, 0), 2) as speed_gb_per_min,
    bm.status
FROM backup_metadata bm
JOIN monitor_paths mp ON bm.monitor_path_id = mp.id
WHERE bm.start_time >= DATE_SUB(NOW(), INTERVAL 7 DAY)
ORDER BY bm.start_time DESC
LIMIT 100;

-- 18. Get backup job success/failure rates
SELECT 
    DATE(start_time) as backup_date,
    status,
    COUNT(*) as job_count,
    ROUND(AVG(duration_seconds / 60), 2) as avg_duration_minutes,
    ROUND(AVG(files_processed), 0) as avg_files_processed
FROM backup_metadata
GROUP BY DATE(start_time), status
ORDER BY backup_date DESC, job_count DESC;

-- ===========================================================================
-- SECTION 7: PERFORMANCE METRICS
-- ===========================================================================

-- 19. Get backup speed trends (GB per minute)
SELECT 
    DATE(bm.start_time) as backup_date,
    ROUND(AVG((bm.total_size_processed / 1024 / 1024 / 1024) / NULLIF(bm.duration_seconds / 60, 0)), 2) as avg_speed_gb_per_min,
    MIN(ROUND((bm.total_size_processed / 1024 / 1024 / 1024) / NULLIF(bm.duration_seconds / 60, 0), 2)) as min_speed,
    MAX(ROUND((bm.total_size_processed / 1024 / 1024 / 1024) / NULLIF(bm.duration_seconds / 60, 0), 2)) as max_speed,
    COUNT(*) as job_count
FROM backup_metadata bm
WHERE bm.status = 'COMPLETED'
  AND bm.start_time >= DATE_SUB(NOW(), INTERVAL 30 DAY)
GROUP BY DATE(bm.start_time)
ORDER BY backup_date DESC;

-- 20. Get compression ratio trends
SELECT 
    DATE(log_time) as date,
    compression_algo,
    AVG(compression_ratio) as avg_compression_ratio,
    COUNT(*) as file_count,
    ROUND(SUM(space_saved) / 1024 / 1024 / 1024, 2) as space_saved_gb
FROM file_versions
WHERE is_deleted = FALSE
  AND log_time >= DATE_SUB(NOW(), INTERVAL 30 DAY)
GROUP BY DATE(log_time), compression_algo
ORDER BY date DESC, space_saved_gb DESC;

-- ===========================================================================
-- SECTION 8: SYSTEM HEALTH & OPTIMIZATION
-- ===========================================================================

-- 21. Get monitor path health status
SELECT 
    mp.id,
    mp.path_name,
    mp.path_type,
    mp.monitor_method,
    mp.is_enabled,
    TIMESTAMPDIFF(HOUR, mp.last_scan, NOW()) as hours_since_last_scan,
    mp.total_files_monitored,
    COUNT(fe.id) as recent_events_24h,
    CASE 
        WHEN mp.last_scan IS NULL THEN 'NO_SCAN'
        WHEN TIMESTAMPDIFF(HOUR, mp.last_scan, NOW()) > 24 THEN 'OUTDATED'
        WHEN TIMESTAMPDIFF(HOUR, mp.last_scan, NOW()) > 1 THEN 'STALE'
        ELSE 'CURRENT'
    END as health_status
FROM monitor_paths mp
LEFT JOIN file_events fe ON mp.id = fe.monitor_path_id 
    AND fe.detected_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
GROUP BY mp.id
ORDER BY hours_since_last_scan DESC;

-- 22. Get daily space optimization statistics for all paths
SELECT 
    sos.stat_date,
    COUNT(DISTINCT sos.monitor_path_id) as paths,
    SUM(sos.total_files_backed_up) as total_files,
    ROUND(SUM(sos.total_original_size) / 1024 / 1024 / 1024, 2) as original_gb,
    ROUND(SUM(sos.total_disk_used) / 1024 / 1024 / 1024, 2) as disk_used_gb,
    ROUND(SUM(sos.total_space_saved) / 1024 / 1024 / 1024, 2) as space_saved_gb,
    ROUND(AVG(sos.overall_savings_percent), 2) as avg_savings_percent
FROM space_optimization_stats sos
WHERE sos.stat_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
GROUP BY sos.stat_date
ORDER BY sos.stat_date DESC;

-- ===========================================================================
-- SECTION 9: AUDIT & REPORTING
-- ===========================================================================

-- 23. Export comprehensive statistics for reporting
SELECT 
    mp.path_name,
    mp.path_type,
    mp.monitor_method,
    COUNT(DISTINCT fv.id) as total_backups,
    COUNT(DISTINCT fe.id) as total_events,
    ROUND(SUM(fv.original_size) / 1024 / 1024 / 1024, 2) as original_gb,
    ROUND(SUM(fv.actual_disk_size) / 1024 / 1024 / 1024, 2) as disk_used_gb,
    ROUND(SUM(fv.space_saved) / 1024 / 1024 / 1024, 2) as space_saved_gb,
    ROUND((SUM(fv.space_saved) / NULLIF(SUM(fv.original_size), 0)) * 100, 2) as savings_percent,
    COUNT(CASE WHEN fv.is_deduplicated = TRUE THEN 1 END) as deduplicated_files,
    MAX(fv.log_time) as last_backup_time,
    mp.created_at
FROM monitor_paths mp
LEFT JOIN file_versions fv ON mp.id = fv.monitor_path_id AND fv.is_deleted = FALSE
LEFT JOIN file_events fe ON mp.id = fe.monitor_path_id
GROUP BY mp.id
ORDER BY space_saved_gb DESC;

-- 24. Get enabled paths ready for hybrid monitoring
SELECT 
    mp.id,
    mp.path_name,
    mp.path_location,
    mp.path_type,
    mp.monitor_method,
    mp.enable_compression,
    mp.enable_deduplication,
    mp.enable_incremental,
    mp.created_at
FROM monitor_paths mp
WHERE mp.is_enabled = TRUE
ORDER BY mp.created_at DESC;

-- ===========================================================================
-- ✅ VERIFICATION & SAMPLE CALLS
-- ===========================================================================

-- Test calling stored procedures:
-- CALL sp_get_comprehensive_stats();
-- CALL sp_get_all_paths();
-- CALL sp_get_path_stats(1);
-- CALL sp_get_recent_events(100);
-- CALL sp_export_statistics();
-- CALL sp_get_monitor_method_performance();
