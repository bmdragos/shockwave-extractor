#!/usr/bin/env python3
"""Fix corrupted WAV files extracted by LibreShockwave.

The SoundChunk hardcodes bitsPerSample=16 even when the actual audio data
is 8-bit unsigned PCM. This script converts each byte to proper 16-bit
signed PCM: (byte - 128) * 256.
"""
import struct
import os
import sys

SOUNDS_DIR = None  # Set via command line or default to cwd

def fix_wav(filepath):
    with open(filepath, 'rb') as f:
        data = f.read()

    # Parse WAV header
    if data[:4] != b'RIFF' or data[8:12] != b'WAVE':
        print(f"  SKIP (not WAV): {filepath}")
        return False

    # Find data chunk
    pos = 12
    fmt_data = None
    audio_data = None
    audio_offset = None

    while pos < len(data) - 8:
        chunk_id = data[pos:pos+4]
        chunk_size = struct.unpack('<I', data[pos+4:pos+8])[0]

        if chunk_id == b'fmt ':
            fmt_data = data[pos+8:pos+8+chunk_size]
        elif chunk_id == b'data':
            audio_offset = pos + 8
            audio_data = data[pos+8:pos+8+chunk_size]
            break

        pos += 8 + chunk_size
        if pos % 2 == 1:
            pos += 1  # word alignment

    if fmt_data is None or audio_data is None:
        print(f"  SKIP (missing chunks): {filepath}")
        return False

    # Parse fmt chunk
    audio_format = struct.unpack('<H', fmt_data[0:2])[0]
    num_channels = struct.unpack('<H', fmt_data[2:4])[0]
    sample_rate = struct.unpack('<I', fmt_data[4:8])[0]
    bits_per_sample = struct.unpack('<H', fmt_data[14:16])[0]

    print(f"  Original: {audio_format=} {num_channels=} {sample_rate=} {bits_per_sample=} data_len={len(audio_data)}")

    if bits_per_sample != 16 or audio_format != 1:
        print(f"  SKIP (not PCM 16-bit)")
        return False

    # Check if data looks like 8-bit unsigned (values clustered around 0x80)
    # Sample every 100th byte and check distribution
    sample_bytes = [audio_data[i] for i in range(0, min(len(audio_data), 10000), 1)]
    avg = sum(sample_bytes) / len(sample_bytes)
    in_8bit_range = sum(1 for b in sample_bytes if 0x40 <= b <= 0xC0) / len(sample_bytes)

    print(f"  Byte avg: {avg:.1f} (128=8bit center), {in_8bit_range*100:.0f}% in 8-bit active range")

    if in_8bit_range < 0.7:
        print(f"  SKIP (doesn't look like 8-bit data)")
        return False

    # Convert: treat each byte as 8-bit unsigned, convert to 16-bit signed
    # 8-bit unsigned: 0=min, 128=center, 255=max
    # 16-bit signed: -32768=min, 0=center, 32767=max
    new_audio = bytearray()
    for byte in audio_data:
        sample_16 = (byte - 128) * 256
        sample_16 = max(-32768, min(32767, sample_16))
        new_audio.extend(struct.pack('<h', sample_16))

    # Write new WAV file with correct header
    new_sample_rate = sample_rate
    new_bits = 16
    new_channels = num_channels
    byte_rate = new_sample_rate * new_channels * (new_bits // 8)
    block_align = new_channels * (new_bits // 8)

    fmt_chunk = struct.pack('<HHIIHH',
        1,                  # PCM
        new_channels,
        new_sample_rate,
        byte_rate,
        block_align,
        new_bits
    )

    data_size = len(new_audio)
    riff_size = 4 + (8 + len(fmt_chunk)) + (8 + data_size)

    with open(filepath, 'wb') as f:
        f.write(b'RIFF')
        f.write(struct.pack('<I', riff_size))
        f.write(b'WAVE')
        f.write(b'fmt ')
        f.write(struct.pack('<I', len(fmt_chunk)))
        f.write(fmt_chunk)
        f.write(b'data')
        f.write(struct.pack('<I', data_size))
        f.write(new_audio)

    print(f"  Fixed: {len(audio_data)} bytes -> {data_size} bytes (16-bit proper)")
    return True

def main():
    global SOUNDS_DIR
    if len(sys.argv) > 1:
        SOUNDS_DIR = sys.argv[1]
    else:
        SOUNDS_DIR = os.getcwd()

    if not os.path.isdir(SOUNDS_DIR):
        print(f"Error: {SOUNDS_DIR} is not a directory")
        sys.exit(1)

    fixed = 0
    for fname in sorted(os.listdir(SOUNDS_DIR)):
        if not fname.endswith('.wav'):
            continue
        path = os.path.join(SOUNDS_DIR, fname)
        print(f"Processing {fname}...")
        if fix_wav(path):
            fixed += 1

    print(f"\nDone. Fixed {fixed} files.")

if __name__ == '__main__':
    main()
