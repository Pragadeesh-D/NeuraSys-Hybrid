package com.neurasys.service;

import com.neurasys.util.Logger;

public class OneDriveService {
    private static final Logger logger = Logger.getLogger(OneDriveService.class);

    private final String accessToken;
    private final DatabaseManager dbManager;

    public OneDriveService(String accessToken, DatabaseManager dbManager) {
        this.accessToken = accessToken;
        this.dbManager = dbManager;
        logger.info("OneDriveService initialized");
    }

    public void syncFolder(int monitorPathId, String oneDrivePath, String localPath) {
        logger.info("OneDrive sync not yet implemented: {}", oneDrivePath);
        // Implementation for future releases
    }
}
