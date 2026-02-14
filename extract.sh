#!/bin/bash
set -euo pipefail

# shockwave-extractor: Extract assets from Macromedia Director / Shockwave files
# Usage: ./extract.sh <input.dcr|.dir|.dxr> <output_dir>

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_JAR="$SCRIPT_DIR/LibreShockwave/sdk/build/libs/sdk-0.1.0.jar"
EXTRACTOR_JAR="$SCRIPT_DIR/LibreShockwave/cast-extractor/build/libs/cast-extractor-1.0.0.jar"
TOOLS_DIR="$SCRIPT_DIR/tools"

usage() {
    echo "Usage: $0 <input.dcr|.dir|.dxr> <output_dir>"
    echo ""
    echo "Extracts bitmaps (PNG) and sounds (WAV/MP3) from Macromedia Director files."
    echo ""
    echo "Prerequisites:"
    echo "  - Java 21+           (brew install openjdk@21)"
    echo "  - Python 3           (for WAV fixing)"
    echo "  - ProjectorRays      (for .dcr decompilation, optional if input is .dir)"
    echo "    https://github.com/ProjectorRays/ProjectorRays"
    echo ""
    echo "On first run, the LibreShockwave SDK will be built automatically."
    exit 1
}

if [ $# -lt 2 ]; then
    usage
fi

INPUT_FILE="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
OUTPUT_DIR="$2"

if [ ! -f "$INPUT_FILE" ]; then
    echo "Error: Input file not found: $INPUT_FILE"
    exit 1
fi

# Check Java
if ! command -v java &>/dev/null; then
    echo "Error: Java not found. Install Java 21+:"
    echo "  brew install openjdk@21"
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/')
if [ "$JAVA_VER" -lt 21 ] 2>/dev/null; then
    echo "Warning: Java 21+ recommended (found version $JAVA_VER)"
fi

# Step 1: Decompile .dcr → .dir (if needed)
EXT="${INPUT_FILE##*.}"
DIR_FILE="$INPUT_FILE"

if [ "$EXT" = "dcr" ] || [ "$EXT" = "dxr" ]; then
    echo "=== Step 1: Decompiling $EXT → .dir ==="

    if ! command -v projectorrays &>/dev/null; then
        echo "Error: ProjectorRays not found on PATH."
        echo "Download from: https://github.com/ProjectorRays/ProjectorRays"
        echo ""
        echo "Or if you already have a .dir file, pass that instead."
        exit 1
    fi

    DECOMPILE_DIR="$OUTPUT_DIR/decompiled"
    BASENAME="$(basename "$INPUT_FILE" ".$EXT")"
    mkdir -p "$DECOMPILE_DIR/$BASENAME"

    DIR_FILE="$DECOMPILE_DIR/$BASENAME/$BASENAME.dir"

    projectorrays decompile "$INPUT_FILE" --dump-scripts --dump-json -o "$DECOMPILE_DIR/$BASENAME/"

    if [ ! -f "$DIR_FILE" ]; then
        # Try finding the .dir file
        DIR_FILE=$(find "$DECOMPILE_DIR" -name "*.dir" -type f | head -1)
        if [ -z "$DIR_FILE" ]; then
            echo "Error: Decompilation did not produce a .dir file"
            exit 1
        fi
    fi

    echo "Decompiled to: $DIR_FILE"
else
    echo "=== Step 1: Skipped (input is already .dir) ==="
fi

# Step 2: Build LibreShockwave SDK (if needed)
if [ ! -f "$SDK_JAR" ]; then
    echo ""
    echo "=== Step 2: Building LibreShockwave SDK ==="

    if [ ! -f "$SCRIPT_DIR/LibreShockwave/gradlew" ]; then
        echo "Error: LibreShockwave submodule not initialized."
        echo "Run: git submodule update --init"
        exit 1
    fi

    cd "$SCRIPT_DIR/LibreShockwave"
    chmod +x gradlew
    ./gradlew :sdk:jar :cast-extractor:jar 2>&1 | tail -5
    cd "$SCRIPT_DIR"

    if [ ! -f "$SDK_JAR" ]; then
        echo "Error: SDK build failed"
        exit 1
    fi

    echo "SDK built successfully"
else
    echo "=== Step 2: Skipped (SDK already built) ==="
fi

# Step 3: Compile and run ExtractAssets
echo ""
echo "=== Step 3: Extracting assets ==="
mkdir -p "$OUTPUT_DIR"

CLASSPATH="$SDK_JAR:$EXTRACTOR_JAR"

# Compile if needed
if [ ! -f "$TOOLS_DIR/ExtractAssets.class" ] || [ "$TOOLS_DIR/ExtractAssets.java" -nt "$TOOLS_DIR/ExtractAssets.class" ]; then
    javac -cp "$CLASSPATH" "$TOOLS_DIR/ExtractAssets.java" 2>&1
fi

java -cp "$TOOLS_DIR:$CLASSPATH" ExtractAssets "$DIR_FILE" "$OUTPUT_DIR"

# Step 4: Fix WAV files
echo ""
echo "=== Step 4: Fixing WAV files ==="
SOUNDS_DIR="$OUTPUT_DIR/sounds"
if [ -d "$SOUNDS_DIR" ] && ls "$SOUNDS_DIR"/*.wav &>/dev/null 2>&1; then
    python3 "$SCRIPT_DIR/fix_sounds.py" "$SOUNDS_DIR"
else
    echo "No WAV files found, skipping"
fi

echo ""
echo "=== Done ==="
echo "Output: $OUTPUT_DIR"
echo "  Bitmaps: $OUTPUT_DIR/bitmaps/"
echo "  Sounds:  $OUTPUT_DIR/sounds/"
if [ -d "$OUTPUT_DIR/decompiled" ]; then
    echo "  Scripts: $OUTPUT_DIR/decompiled/"
fi
