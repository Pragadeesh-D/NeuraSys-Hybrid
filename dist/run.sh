#!/bin/bash
SCRIPT_DIR="$(dirname "$(readlink -f "$0")")"
cd "$SCRIPT_DIR"

# Add native libraries to path
export LD_LIBRARY_PATH="$SCRIPT_DIR/lib/linux:$LD_LIBRARY_PATH"
export DYLD_LIBRARY_PATH="$SCRIPT_DIR/lib/macos:$DYLD_LIBRARY_PATH"

# Run the application
java -jar NeuraSys-1.0.0.jar "$@"
