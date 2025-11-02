# NeuraSys Build Scripts (Hybrid Version)

Build and compilation scripts for the NeuraSys hybrid JavaFX + C/C++ application.

## Prerequisites

### Required
- **Java JDK 18+** ([https://adoptium.net/](https://adoptium.net/)) - Matches your openjdk-18.0.1.1
- **Maven 3.8+** ([https://maven.apache.org/](https://maven.apache.org/)) - For building Java project
- **MySQL 8.0+** ([https://dev.mysql.com/](https://dev.mysql.com/)) - Database backend

### Optional (for C/C++ native monitor on Windows)
- **MinGW-w64** ([https://www.mingw-w64.org/](https://www.mingw-w64.org/)) - C compiler for Windows
- **Git Bash** ([https://git-scm.com/](https://git-scm.com/)) - To run `.sh` scripts on Windows

### Required for JavaFX
- **JavaFX SDK 17.0.17** ([https://gluonhq.com/products/javafx/](https://gluonhq.com/products/javafx/)) - UI library

## Build Instructions

### Windows

#### Option 1: Use Batch Script
```batch
cd 06_Build_Scripts
build-all.bat
```

#### Option 2: Use Git Bash (Unix-style)
```batch
cd 06_Build_Scripts
chmod +x build-all.sh
./build-all.sh
```

### Clean Previous Build
```batch
cd 06_Build_Scripts
clean.bat
build-all.bat
```

## What Gets Built

### 1. C/C++ Native Monitor (Windows Only)
- **Source:** `02_C_Source/Windows/FileMonitor.c`
- **Output:** `lib/windows/FileMonitor.dll`
- **Purpose:** File system monitoring for real-time backup operations

### 2. Java Application with JavaFX UI
- **Source:** `01_Java_Source/com/neurasys/`
- **Output:** `target/classes/` (compiled classes)
- **JAR:** `NeuraSys-1.0.0.jar`

### 3. Distribution Package
- **Location:** `dist/`
- **Contents:**
    - `NeuraSys-1.0.0.jar` - Main application
    - `neurasys-hybrid.jar` - Hybrid version with dependencies
    - `run.bat` - Windows launcher script
    - `lib/windows/` - Native DLL files
    - `config/` - Configuration filesjava --module-path "C:/javafx-sdk-17.0.17/lib" --add-modules javafx.controls,javafx.fxml
    - `logs/` - Application logs folder

## Running the Application

### From Distribution Folder
```batch
cd dist
run.bat
```

### Direct Java Command (Windows CMD - One Liner)
```batch
@echo off
REM ============================================
REM NeuraSys Application Launcher (Windows)
REM ============================================
REM Edit the paths below to match your system

set JAVAFX_PATH=C:/javafx-sdk-17.0.17/lib
set NATIVE_PATH=D:/Pragadeesh_D/Project/NeuraSys/02_C_Source/Windows
set TARGET_PATH=D:/Pragadeesh_D/Project/NeuraSys/target/classes
set M2_REPO=C:/Users/dkish/.m2/repository

echo Starting NeuraSys (Windows)...
echo.

java --module-path "%JAVAFX_PATH%" --add-modules javafx.controls,javafx.fxml ^
-Djava.library.path="%NATIVE_PATH%" ^
-cp "%TARGET_PATH%;
%M2_REPO%/org/openjfx/javafx-controls/18.0.1/javafx-controls-18.0.1.jar;
%M2_REPO%/org/openjfx/javafx-controls/18.0.1/javafx-controls-18.0.1-win.jar;
%M2_REPO%/org/openjfx/javafx-graphics/18.0.1/javafx-graphics-18.0.1.jar;
%M2_REPO%/org/openjfx/javafx-graphics/18.0.1/javafx-graphics-18.0.1-win.jar;
%M2_REPO%/org/openjfx/javafx-base/18.0.1/javafx-base-18.0.1.jar;
%M2_REPO%/org/openjfx/javafx-base/18.0.1/javafx-base-18.0.1-win.jar;
%M2_REPO%/org/openjfx/javafx-fxml/18.0.1/javafx-fxml-18.0.1.jar;
%M2_REPO%/org/openjfx/javafx-fxml/18.0.1/javafx-fxml-18.0.1-win.jar;
%M2_REPO%/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar;
%M2_REPO%/com/google/protobuf/protobuf-java/3.21.9/protobuf-java-3.21.9.jar;
%M2_REPO%/com/zaxxer/HikariCP/5.1.0/HikariCP-5.1.0.jar;
%M2_REPO%/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar;
%M2_REPO%/commons-io/commons-io/2.15.1/commons-io-2.15.1.jar;
%M2_REPO%/org/apache/commons/commons-compress/1.25.0/commons-compress-1.25.0.jar;
%M2_REPO%/org/apache/logging/log4j/log4j-api/2.22.0/log4j-api-2.22.0.jar;
%M2_REPO%/org/apache/logging/log4j/log4j-core/2.22.0/log4j-core-2.22.0.jar;
%M2_REPO%/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar;
%M2_REPO%/org/apache/logging/log4j/log4j-slf4j-impl/2.22.0/log4j-slf4j-impl-2.22.0.jar" ^
com.neurasys.Main

pause
```

### Direct Java Command (Git Bash - Multi Line)
```batch
#!/bin/bash

JAVAFX_PATH="C:/javafx-sdk-17.0.17/lib"
NATIVE_PATH="D:/Pragadeesh_D/Project/NeuraSys/02_C_Source/Windows"
TARGET_PATH="D:/Pragadeesh_D/Project/NeuraSys/target/classes"
M2_REPO="C:/Users/dkish/.m2/repository"

echo "Starting NeuraSys (Bash)..."
echo ""

java --module-path "$JAVAFX_PATH" --add-modules javafx.controls,javafx.fxml \
-Djava.library.path="$NATIVE_PATH" \
-cp "$TARGET_PATH:$M2_REPO/org/openjfx/javafx-controls/18.0.1/javafx-controls-18.0.1.jar:$M2_REPO/org/openjfx/javafx-controls/18.0.1/javafx-controls-18.0.1-win.jar:$M2_REPO/org/openjfx/javafx-graphics/18.0.1/javafx-graphics-18.0.1.jar:$M2_REPO/org/openjfx/javafx-graphics/18.0.1/javafx-graphics-18.0.1-win.jar:$M2_REPO/org/openjfx/javafx-base/18.0.1/javafx-base-18.0.1.jar:$M2_REPO/org/openjfx/javafx-base/18.0.1/javafx-base-18.0.1-win.jar:$M2_REPO/org/openjfx/javafx-fxml/18.0.1/javafx-fxml-18.0.1.jar:$M2_REPO/org/openjfx/javafx-fxml/18.0.1/javafx-fxml-18.0.1-win.jar:$M2_REPO/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar:$M2_REPO/com/google/protobuf/protobuf-java/3.21.9/protobuf-java-3.21.9.jar:$M2_REPO/com/zaxxer/HikariCP/5.1.0/HikariCP-5.1.0.jar:$M2_REPO/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar:$M2_REPO/commons-io/commons-io/2.15.1/commons-io-2.15.1.jar:$M2_REPO/org/apache/commons/commons-compress/1.25.0/commons-compress-1.25.0.jar:$M2_REPO/org/apache/logging/log4j/log4j-api/2.22.0/log4j-api-2.22.0.jar:$M2_REPO/org/apache/logging/log4j/log4j-core/2.22.0/log4j-core-2.22.0.jar:$M2_REPO/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar:$M2_REPO/org/apache/logging/log4j/log4j-slf4j-impl/2.22.0/log4j-slf4j-impl-2.22.0.jar" \
com.neurasys.Main
```

## Build Output Structure
(See full classpath in previous build outputs)
```batch
dist/
├── NeuraSys-1.0.0.jar
├── neurasys-hybrid.jar
├── run.bat
├── lib/
│ └── windows/
│ └── FileMonitor.dll
├── config/
│ ├── application.properties
│ ├── log4j2.xml
│ └── FileMonitor.dll
├── logs/
│ └── neurasys.log
└── target/
└── classes/
```

## Troubleshooting

| Issue | Solution |
|-------|----------|
| "Java not found" | Install JDK 18+ and add to PATH |
| "Maven not found" | Install Maven and add to PATH |
| "GCC not found (Windows)" | Install MinGW-w64 or skip native build |
| "MySQL connection failed" | Ensure MySQL is running; check application.properties credentials |
| "JavaFX modules not found" | Ensure JavaFX SDK 17.0.17 is installed; update module-path in scripts |
| "FileMonitor.dll not found" | Rebuild with build-all.bat or manually compile FileMonitor.c with GCC |

## Build Notes

- **Build Time:** 2-5 minutes (first build downloads dependencies)
- **Dependencies:** Maven downloads all Java dependencies automatically
- **Native Code:** Optional; Java fallback available if DLL missing
- **Database:** Must run `03_SQL_Database/neurasys_schema.sql` before first run
- **Hybrid Mode:** Combines JavaFX UI with native C monitoring for optimal performance

## Maven Phases
```batch
mvn clean
mvn compile
mvn package
mvn install
mvn clean package
```
## Git Bash Usage (Windows)
```batch
chmod +x build-all.sh
./build-all.sh
```

## Run application
```batch
cd dist
java -jar neurasys-hybrid.jar
```