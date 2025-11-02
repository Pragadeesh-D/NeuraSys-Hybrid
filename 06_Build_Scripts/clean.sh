#!/bin/bash

echo "Cleaning NeuraSys build artifacts..."
echo ""

cd "$(dirname "$0")/.."

# Clean Maven artifacts
if [ -d "target" ]; then
    echo "Removing target/..."
    rm -rf target
fi

# Clean compiled libraries
if [ -f "lib/linux/libneurasys_monitor.so" ]; then
    echo "Removing lib/linux/libneurasys_monitor.so..."
    rm -f lib/linux/libneurasys_monitor.so
fi

# Clean distribution
if [ -d "dist" ]; then
    echo "Removing dist/..."
    rm -rf dist
fi

# Clean logs
if [ -d "logs" ]; then
    echo "Removing logs/..."
    rm -rf logs
fi

echo ""
echo "✓ Clean complete!"
