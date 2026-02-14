# shockwave-extractor

Extract bitmaps, sounds, and Lingo scripts from Macromedia Director / Shockwave files (`.dcr`, `.dir`, `.dxr`). Built for game preservation.

Thousands of Shockwave games from the late 1990s and early 2000s are unplayable on modern browsers since the Shockwave plugin was discontinued. This toolkit extracts their assets so they can be rebuilt with modern web technologies.

## Quick Start

### Docker (recommended)

```bash
# Build the image
docker build -t shockwave-extractor .

# Extract assets from a .dcr file
docker run --rm -v /path/to/games:/data shockwave-extractor /data/game.dcr /data/output
```

### Native

**Prerequisites:** Java 21+, Python 3, [ProjectorRays](https://github.com/ProjectorRays/ProjectorRays) on PATH

```bash
git clone --recursive https://github.com/bmdragos/shockwave-extractor.git
cd shockwave-extractor
./extract.sh game.dcr output/
```

The SDK builds automatically on first run.

## What It Does

```
game.dcr (compressed Shockwave file)
    │
    ├─ ProjectorRays ──→ game.dir (decompiled, with Lingo source)
    │
    └─ ExtractAssets.java ──→ bitmaps/*.png
                           ──→ sounds/*.wav, *.mp3
    │
    └─ fix_sounds.py ──→ corrected WAV files
```

**Input:** `.dcr` (compressed), `.dir` (uncompressed), or `.dxr` (protected) Director files, versions 4-12.

**Output:**
- `bitmaps/` — All cast member bitmaps as PNG (1/2/4/8/16/32-bit, with transparency)
- `sounds/` — All audio as WAV or MP3
- `decompiled/` — Lingo scripts (`.ls` source + `.lasm` bytecode) and chunk JSON

## Tools

| Tool | Purpose |
|------|---------|
| `extract.sh` | One-command pipeline: decompile → extract → fix |
| `tools/ExtractAssets.java` | Headless CLI for bitmap + sound extraction |
| `tools/DiagnoseBitmaps.java` | Inspect bitmap metadata (dimensions, depth, palette) |
| `tools/PixelDiag.java` | Debug 16-bit pixel encoding and endianness |
| `tools/DumpRaw.java` | Dump raw decompressed bitmap data |
| `tools/DumpRaw2.java` | Compare raw vs RLE-decompressed bitmap data |
| `fix_sounds.py` | Fix 8-bit WAVs mislabeled as 16-bit by LibreShockwave |

### Manual Usage

```bash
# Build SDK (one time)
cd LibreShockwave && chmod +x gradlew && ./gradlew :sdk:jar :cast-extractor:jar && cd ..

# Set classpath
CP="LibreShockwave/sdk/build/libs/sdk-0.1.0.jar:LibreShockwave/cast-extractor/build/libs/cast-extractor-1.0.0.jar"

# Decompile .dcr to .dir (if needed)
projectorrays decompile game.dcr --dump-scripts --dump-json -o decompiled/

# Extract assets
javac -cp "$CP" tools/ExtractAssets.java
java -cp "tools:$CP" ExtractAssets game.dir output/

# Fix WAV files
python3 fix_sounds.py output/sounds/

# Inspect bitmaps (diagnostic)
javac -cp "$CP" tools/DiagnoseBitmaps.java
java -cp "tools:$CP" DiagnoseBitmaps game.dir
```

## Known Issues & Fixes

### 16-bit Bitmap Endianness
Director 16-bit bitmaps (xRGB 0-5-5-5) authored on Mac use big-endian pixel data regardless of the file container's endianness. Our patched LibreShockwave fork fixes this — upstream LibreShockwave produces corrupted colors for these bitmaps.

### 8-bit WAV Mislabeling
LibreShockwave's `SoundChunk` hardcodes `bitsPerSample=16` in WAV headers even when the audio is 8-bit unsigned PCM. `fix_sounds.py` detects affected files (byte distribution centered around 0x80) and converts them to proper 16-bit signed PCM.

### Channel-Separated 16-bit Scanlines
Director stores 16-bit bitmap rows with high bytes and low bytes separated: `[hi_0..hi_W-1][lo_0..lo_W-1]`. `ExtractAssets.java` handles this with a custom decoder that reassembles pixels correctly.

## Architecture

This project uses:
- **[LibreShockwave](https://github.com/Quackster/LibreShockwave)** (Java SDK) — Director file parsing, RLE decompression, palette handling, sound conversion. We maintain a [fork](https://github.com/bmdragos/LibreShockwave) with bitmap endianness fixes.
- **[ProjectorRays](https://github.com/ProjectorRays/ProjectorRays)** (C++) — `.dcr` decompression and Lingo script decompilation. External dependency, not bundled.

## Supported Formats

| Format | Extension | Notes |
|--------|-----------|-------|
| Director Movie | `.dir` | Uncompressed, direct extraction |
| Shockwave Movie | `.dcr` | Afterburner-compressed, needs ProjectorRays first |
| Protected Movie | `.dxr` | Protection removed during decompilation |
| Cast Library | `.cst`, `.cct` | Shared cast members |

Director versions 4 through 12 are supported by the underlying SDK.

## AI Agent Pipeline

This toolkit is designed as the first stage of an automated game preservation pipeline. The extraction output — clean PNGs, fixed WAVs, and readable Lingo scripts — is everything an LLM needs to rebuild a game in HTML5.

See **[AGENT_PIPELINE.md](AGENT_PIPELINE.md)** for the full guide, including:
- How to feed extraction output into an LLM for rebuilding
- Automated sensitivity analysis to catch threshold/physics bugs before playtesting
- Visual comparison, state machine verification, and asset coverage checks

## Credits

- **[LibreShockwave](https://github.com/Quackster/LibreShockwave)** by Quackster — the Java SDK that makes all of this possible
- **[ProjectorRays](https://github.com/ProjectorRays/ProjectorRays)** — Lingo decompiler and DCR handler
- Built during the [Junkyard Jump](https://github.com/bmdragos/junkyard-jump) preservation project (Fox Kids, 2000)

## License

MIT
