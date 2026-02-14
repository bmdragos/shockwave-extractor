#!/bin/bash
set -euo pipefail

# Docker entrypoint for shockwave-extractor
# Usage: docker run --rm -v /path:/data shockwave-extractor /data/game.dcr /data/output

INSTALL_DIR="/opt/shockwave-extractor"
CLASSPATH="$INSTALL_DIR/lib/sdk-0.1.0.jar:$INSTALL_DIR/lib/cast-extractor-1.0.0.jar"

if [ $# -lt 2 ]; then
    echo "shockwave-extractor: Extract assets from Macromedia Director / Shockwave files"
    echo ""
    echo "Usage: docker run --rm -v /path/to/files:/data shockwave-extractor /data/game.dcr /data/output"
    echo ""
    echo "Supported formats: .dcr, .dir, .dxr, .cst, .cct"
    exit 1
fi

INPUT_FILE="$1"
OUTPUT_DIR="$2"

if [ ! -f "$INPUT_FILE" ]; then
    echo "Error: Input file not found: $INPUT_FILE"
    exit 1
fi

# Step 1: Decompile .dcr → .dir (if needed)
EXT="${INPUT_FILE##*.}"
DIR_FILE="$INPUT_FILE"

if [ "$EXT" = "dcr" ] || [ "$EXT" = "dxr" ] || [ "$EXT" = "cct" ]; then
    echo "=== Decompiling $EXT → .dir ==="
    DECOMPILE_DIR="$OUTPUT_DIR/decompiled"
    mkdir -p "$DECOMPILE_DIR"

    BASENAME="$(basename "$INPUT_FILE" ".$EXT")"
    projectorrays decompile "$INPUT_FILE" --dump-scripts --dump-json -o "$DECOMPILE_DIR/$BASENAME/"

    DIR_FILE=$(find "$DECOMPILE_DIR" -name "*.dir" -o -name "*.cst" | head -1)
    if [ -z "$DIR_FILE" ]; then
        echo "Error: Decompilation did not produce a .dir file"
        exit 1
    fi
    echo "Decompiled to: $DIR_FILE"
fi

# Step 2: Extract assets
echo ""
echo "=== Extracting assets ==="
mkdir -p "$OUTPUT_DIR"
java -cp "$INSTALL_DIR/tools:$CLASSPATH" ExtractAssets "$DIR_FILE" "$OUTPUT_DIR"

# Step 3: Fix WAV files
SOUNDS_DIR="$OUTPUT_DIR/sounds"
if [ -d "$SOUNDS_DIR" ] && ls "$SOUNDS_DIR"/*.wav &>/dev/null 2>&1; then
    echo ""
    echo "=== Fixing WAV files ==="
    python3 "$INSTALL_DIR/fix_sounds.py" "$SOUNDS_DIR"
fi

echo ""
echo "=== Done ==="
echo "Bitmaps: $OUTPUT_DIR/bitmaps/"
echo "Sounds:  $OUTPUT_DIR/sounds/"
[ -d "$OUTPUT_DIR/decompiled" ] && echo "Scripts: $OUTPUT_DIR/decompiled/"
