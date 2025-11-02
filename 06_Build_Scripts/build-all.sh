#!/bin/bash

echo "========================================="
echo "  NeuraSys Build Script (Unix/Linux/macOS)"
echo "========================================="
echo ""

cd "$(dirname "$0")"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}✓${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

print_info() {
    echo -e "${YELLOW}ℹ${NC} $1"
}

# Step 1: Clean previous build
echo ""
print_info "Step 1: Cleaning previous build artifacts..."
./clean.sh
if [ $? -ne 0 ]; then
    print_error "Clean step failed"
    exit 1
fi

# Step 2: Compile C/C++ native libraries (Linux)
echo ""
print_info "Step 2: Building native C/C++ libraries..."
if [ -f "src/native/FileMonitor.c" ]; then
    print_status "Found FileMonitor.c"

    # Check for GCC/Clang
    if command -v gcc &> /dev/null; then
        COMPILER="gcc"
    elif command -v clang &> /dev/null; then
        COMPILER="clang"
    else
        print_error "C compiler not found (gcc or clang required)"
        exit 1
    fi

    print_info "Using compiler: $COMPILER"

    # Detect OS
    OS=$(uname -s)

    if [ "$OS" = "Linux" ]; then
        OUTPUT_DIR="lib/linux"
        OUTPUT_FILE="libneurasys_monitor.so"
        COMPILER_FLAGS="-shared -fPIC -O3"
    elif [ "$OS" = "Darwin" ]; then
        OUTPUT_DIR="lib/macos"
        OUTPUT_FILE="libneurasys_monitor.dylib"
        COMPILER_FLAGS="-shared -fPIC -O3"
    else
        print_error "Unsupported operating system: $OS"
        exit 1
    fi

    # Create output directory
    mkdir -p "$OUTPUT_DIR"

    # Compile
    print_info "Compiling for $OS..."
    $COMPILER $COMPILER_FLAGS -o "$OUTPUT_DIR/$OUTPUT_FILE" src/native/FileMonitor.c

    if [ $? -eq 0 ]; then
        print_status "Native library compiled: $OUTPUT_DIR/$OUTPUT_FILE"
    else
        print_error "Native compilation failed"
        exit 1
    fi
else
    print_info "FileMonitor.c not found, skipping native build"
fi

# Step 3: Build Java project with Maven
echo ""
print_info "Step 3: Building Java project with Maven..."

if command -v mvn &> /dev/null; then
    print_info "Maven version: $(mvn -v | head -1)"

    # Build with Maven
    mvn clean package -DskipTests

    if [ $? -eq 0 ]; then
        print_status "Maven build completed successfully"
    else
        print_error "Maven build failed"
        exit 1
    fi
else
    print_error "Maven not found. Please install Maven and add it to PATH"
    exit 1
fi

# Step 4: Create distribution package
echo ""
print_info "Step 4: Creating distribution package..."

DIST_DIR="dist"
mkdir -p "$DIST_DIR"

# Copy main jar
if [ -f "target/NeuraSys-1.0.0.jar" ]; then
    cp target/NeuraSys-1.0.0.jar "$DIST_DIR/"
    print_status "Copied JAR to dist/"
else
    print_error "JAR file not found at target/NeuraSys-1.0.0.jar"
    exit 1
fi

# Copy native libraries
if [ -d "lib" ]; then
    cp -r lib "$DIST_DIR/"
    print_status "Copied native libraries to dist/"
fi

# Copy dependencies (if needed)
if [ -d "target/dependency" ]; then
    mkdir -p "$DIST_DIR/lib"
    cp target/dependency/*.jar "$DIST_DIR/lib/" 2>/dev/null || true
    print_status "Copied dependencies to dist/lib/"
fi

# Copy config files
if [ -d "config" ]; then
    cp -r config "$DIST_DIR/"
    print_status "Copied configuration files to dist/"
fi

# Step 5: Create run script
echo ""
print_info "Step 5: Creating run scripts..."

# Create run.sh for Unix/Linux/macOS
cat > "$DIST_DIR/run.sh" << 'EOF'
#!/bin/bash
SCRIPT_DIR="$(dirname "$(readlink -f "$0")")"
cd "$SCRIPT_DIR"

# Add native libraries to path
export LD_LIBRARY_PATH="$SCRIPT_DIR/lib/linux:$LD_LIBRARY_PATH"
export DYLD_LIBRARY_PATH="$SCRIPT_DIR/lib/macos:$DYLD_LIBRARY_PATH"

# Run the application
java -jar NeuraSys-1.0.0.jar "$@"
EOF

chmod +x "$DIST_DIR/run.sh"
print_status "Created run.sh script"

# Step 6: Summary
echo ""
echo "========================================="
print_status "Build completed successfully!"
echo "========================================="
echo ""
echo "Distribution package: $DIST_DIR/"
echo "  - JAR: $DIST_DIR/NeuraSys-1.0.0.jar"
echo "  - Native libs: $DIST_DIR/lib/"
echo "  - Run script: $DIST_DIR/run.sh"
echo ""
echo "To run the application:"
echo "  cd $DIST_DIR && ./run.sh"
echo ""
