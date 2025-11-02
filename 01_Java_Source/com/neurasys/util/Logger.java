package com.neurasys.util;

import org.apache.logging.log4j.LogManager;

public class Logger {
    private final org.apache.logging.log4j.Logger logger;

    private Logger(Class<?> clazz) {
        this.logger = LogManager.getLogger(clazz);
    }

    public static Logger getLogger(Class<?> clazz) {
        return new Logger(clazz);
    }

    public void info(String message) {
        logger.info(message);
    }

    public void info(String message, Object... params) {
        logger.info(message, params);
    }

    public void debug(String message) {
        logger.debug(message);
    }

    public void debug(String message, Object... params) {
        logger.debug(message, params);
    }

    public void warn(String message) {
        logger.warn(message);
    }

    public void warn(String message, Throwable t) {
        logger.warn(message, t);
    }

    public void error(String message) {
        logger.error(message);
    }

    public void error(String message, Throwable t) {
        logger.error(message, t);
    }

    public void trace(String message) {
        logger.trace(message);
    }
}
