# Technical Reference

Deep dive into Macromedia Director file formats and the extraction challenges we solved.

## Director File Format

### Container: RIFX

Director files use a RIFF-like container called RIFX (big-endian) or XFIR (little-endian). The container holds typed chunks identified by 4-character codes (FourCC).

```
RIFX <size> MV93    (Director 5+ movie)
  ├─ imap            Config/memory map
  ├─ mmap            Resource map
  ├─ KEY*            Key table (maps cast members → data chunks)
  ├─ CAS*            Cast member index
  ├─ CASt [n]        Cast member metadata (name, type, dimensions, etc.)
  ├─ BITD [n]        Bitmap data (RLE compressed)
  ├─ snd  [n]        Sound data (PCM or MP3)
  ├─ Lscr [n]        Lingo script bytecode
  ├─ Lctx            Lingo context (handler table)
  ├─ Lnam            Lingo name table (symbol strings)
  ├─ VWSC            Score/timeline data
  ├─ VWFI            Frame labels
  └─ DRCF            Director config (stage size, BG color, tempo)
```

### Afterburner Compression (.dcr)

`.dcr` files are Afterburner-compressed versions of `.dir` files, designed for web delivery. The compression is zlib-based but with a custom container format. **ProjectorRays** handles decompression; the SDK works with uncompressed `.dir` files.

### Cast Members

Each cast member has:
- **CASt chunk**: Metadata (name, type, specific data depending on type)
- **Data chunk**: Actual content (BITD for bitmaps, snd for sounds, Lscr for scripts)
- **KEY* entry**: Links CASt ID to its data chunk's section ID

Member types (from `MemberType` enum):
| ID | Type | Data Chunk |
|----|------|-----------|
| 1 | Bitmap | BITD |
| 3 | Field (text) | STXT |
| 6 | Sound | snd |
| 11 | Script | Lscr |
| 12 | Text (rich) | STXT |

## Bitmap Format

### RLE Compression

All BITD chunks use Director's RLE (Run-Length Encoding) compression. Decompression produces raw scanline data sized to `scanWidth * height`, where:

```
scanWidth = ((width * bitDepth + 15) / 16) * 2   // 16-bit aligned rows
```

### Bit Depths

| Depth | Palette | Pixel Format |
|-------|---------|-------------|
| 1-bit | Yes | 1 pixel per bit, MSB first |
| 4-bit | Yes | 2 pixels per byte |
| 8-bit | Yes | 1 byte per pixel, palette index |
| 16-bit | No | 2 bytes per pixel, xRGB 0-5-5-5 |
| 32-bit | No | 4 bytes per pixel, channel-separated ARGB |

### 16-bit Pixel Format (the hard one)

Director 16-bit bitmaps use **xRGB 0-5-5-5** format:

```
Bit:  15  14-10  9-5  4-0
       x   RRRRR GGGGG BBBBB
```

- Bit 15 is unused (always 0)
- 5 bits each for R, G, B (0-31 range, scaled to 0-255 via `value * 255 / 31`)
- White (`0x7FFF` = R31 G31 B31) is typically the transparent color

#### Channel-Separated Scanlines

For 16-bit and 32-bit bitmaps, Director stores scanline data with **channels separated**:

```
Row N: [hi_byte_0, hi_byte_1, ..., hi_byte_W-1, lo_byte_0, lo_byte_1, ..., lo_byte_W-1]
```

To reconstruct pixel X: `pixel = (hi_byte[X] << 8) | lo_byte[X]`

This is different from typical interleaved pixel storage and is the main reason naive decoders produce garbage.

#### Endianness Bug

Director 16-bit pixel data is **always big-endian** (Mac-originated format), regardless of whether the file container is RIFX (big-endian) or XFIR (little-endian). LibreShockwave's upstream code conditionally byte-swaps based on container endianness, producing corrupted colors for Mac-authored files saved in little-endian containers.

Our fix forces big-endian interpretation for all 16-bit bitmaps.

## Sound Format

### PCM Audio

Director stores PCM audio in `snd ` chunks with a variable-length header (64, 96, or 128 bytes depending on Director version). The audio data follows the header.

Key properties:
- Sample rate: Typically 22050 or 44100 Hz
- Channels: 1 (mono) or 2 (stereo)
- Bit depth: 8 or 16 bit
- Byte order: Big-endian (Mac origin) → must be converted to little-endian for WAV

`SoundConverter.toWav()` handles header detection, endian conversion, and WAV packaging.

### MP3 Audio

Some Director files embed MP3 audio. Detection is by searching for MP3 sync bytes (`0xFF` followed by `0xE0-0xFF`) within the first 512 bytes of the sound data. Valid frames are extracted and concatenated.

### The 8-bit WAV Bug

LibreShockwave's `SoundChunk` class hardcodes `bitsPerSample = 16` in the WAV header it generates, even when the actual audio data is 8-bit unsigned PCM. This produces ear-splitting distortion.

**Detection**: If >70% of bytes in the audio data fall in the range `0x40-0xC0` (centered around `0x80`, the 8-bit unsigned silence value), the data is likely 8-bit.

**Fix**: Convert each byte to 16-bit signed: `sample_16 = (byte - 128) * 256`

## Lingo Scripts

### Decompilation

Director stores Lingo as compiled bytecode in `Lscr` chunks. Two tools handle decompilation:

- **ProjectorRays**: Produces high-quality `.ls` source code with handler names, variable names, and control flow reconstruction
- **LibreShockwave**: Provides both `.ls` (source) and `.lasm` (annotated bytecode) output

### Script Types

| Type | Prefix | Scope |
|------|--------|-------|
| MovieScript | `MovieScript` | Global handlers, called from any frame |
| BehaviorScript | `BehaviorScript` | Attached to sprites, receive sprite events |
| CastScript | `CastScript` | Attached to cast members, receive click events |

### Key Structures

```lingo
-- Properties: instance variables
property speed, maxSpeed, currentState

-- Globals: shared across all scripts
global gselectedPieces, guserMoney

-- Handlers: methods
on beginSprite me
  -- called when sprite enters stage
end

on exitFrame me
  -- called every frame (the game loop)
end

on mouseUp me
  -- click handler
end
```

## Pipeline Architecture

```
┌──────────────┐     ┌──────────────┐     ┌──────────────────┐
│  game.dcr    │────▶│ ProjectorRays │────▶│  game.dir        │
│ (compressed) │     │  decompile    │     │  (uncompressed)  │
└──────────────┘     └──────────────┘     └────────┬─────────┘
                                                    │
                     ┌──────────────────────────────┘
                     │
              ┌──────▼──────┐
              │ DirectorFile │  LibreShockwave SDK
              │   .load()    │
              └──────┬───────┘
                     │
         ┌───────────┼───────────┐
         │           │           │
    ┌────▼────┐ ┌────▼────┐ ┌───▼────┐
    │ Bitmaps │ │ Sounds  │ │Scripts │
    │  BITD   │ │  snd    │ │ Lscr   │
    └────┬────┘ └────┬────┘ └───┬────┘
         │           │          │
    RLE decompress   │     (already done
    Pixel decode     │      by ProjectorRays)
    PNG export       │
         │      WAV convert
         │      MP3 extract
         │      8-bit fix
         │           │
    ┌────▼────┐ ┌────▼────┐
    │  *.png  │ │ *.wav   │
    │         │ │ *.mp3   │
    └─────────┘ └─────────┘
```
