# NeuraSys Configuration Files

This folder contains application configuration and setup files.

## Files

### application.properties
Main application configuration file containing:
- Database connection settings (MySQL)
- Application title and version
- Logging configuration path
- JavaFX UI settings

### log4j2.xml
Log4j2 configuration for application logging:
- Console appender
- File appender (logs/ folder)
- Log levels (DEBUG, INFO, WARN, ERROR)
- Event logging for Java and Native operations

## Configuration Steps

1. **Database Setup**
    - Update MySQL credentials in `application.properties`:
      ```
      db.url=jdbc:mysql://localhost:3306/neurasys
      db.user=root
      db.password=your_password
      ```

2. **Logging**
    - Logs are output to `logs/` folder
    - Configure log levels in `log4j2.xml` as needed

## Notes

- Keep configuration files secure (don't commit passwords)
- Logs folder must exist in project root
