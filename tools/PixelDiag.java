import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.bitmap.BitmapDecoder;

import java.nio.ByteOrder;
import java.nio.file.*;

public class PixelDiag {
    public static void main(String[] args) throws Exception {
        Path inputFile = Path.of(args[0]);
        DirectorFile dirFile = DirectorFile.load(inputFile);

        System.out.println("Endian: " + dirFile.getEndian());
        System.out.println("Config: " + (dirFile.getConfig() != null ? "present" : "null"));
        if (dirFile.getConfig() != null) {
            System.out.println("Platform: " + dirFile.getConfig().platform());
            System.out.println("DirectorVersion: " + dirFile.getConfig().directorVersion());
        }

        // Find the "needle" bitmap (small, 9x32)
        for (CastMemberChunk member : dirFile.getCastMembers()) {
            if (!member.isBitmap()) continue;
            String name = member.name() != null ? member.name() : "";
            if (!name.equals("converyorwheel")) continue;  // 25x25, small enough

            BitmapInfo info = BitmapInfo.parse(member.specificData());
            System.out.println("\nBitmap: " + name + " " + info.width() + "x" + info.height() + " depth=" + info.bitDepth());

            // Get raw BITD data
            var keyTable = dirFile.getKeyTable();
            for (var entry : keyTable.getEntriesForOwner(member.id())) {
                if (!entry.fourccString().equals("BITD")) continue;

                Chunk chunk = dirFile.getChunk(entry.sectionId());
                if (chunk instanceof BitmapChunk bc) {
                    byte[] raw = bc.data();
                    System.out.println("Raw BITD size: " + raw.length);

                    // Decompress
                    int scanWidth = BitmapDecoder.calculateScanWidth(info.width(), 16);
                    int expectedSize = scanWidth * info.height();
                    byte[] data = BitmapDecoder.decompressRLE(raw, expectedSize);
                    System.out.println("Decompressed size: " + data.length + " (expected: " + expectedSize + ")");
                    System.out.println("Scan width: " + scanWidth + " bytes per row");

                    // Print first few pixels in different interpretations
                    System.out.println("\nFirst 10 pixels, row 12 (center of wheel):");
                    int rowOffset = 12 * scanWidth;
                    for (int x = 0; x < Math.min(10, info.width()); x++) {
                        int byteIdx = rowOffset + x * 2;
                        if (byteIdx + 1 >= data.length) break;

                        int b0 = data[byteIdx] & 0xFF;
                        int b1 = data[byteIdx + 1] & 0xFF;

                        // Big-endian
                        int pixBE = (b0 << 8) | b1;
                        // Little-endian
                        int pixLE = b0 | (b1 << 8);

                        System.out.printf("  [%d] bytes=%02X %02X  ", x, b0, b1);

                        // BE xRGB 1-5-5-5
                        int r_be = ((pixBE >> 10) & 0x1F) * 255 / 31;
                        int g_be = ((pixBE >> 5) & 0x1F) * 255 / 31;
                        int b_be = (pixBE & 0x1F) * 255 / 31;
                        System.out.printf("BE-RGB(%3d,%3d,%3d)  ", r_be, g_be, b_be);

                        // LE xRGB 1-5-5-5
                        int r_le = ((pixLE >> 10) & 0x1F) * 255 / 31;
                        int g_le = ((pixLE >> 5) & 0x1F) * 255 / 31;
                        int b_le = (pixLE & 0x1F) * 255 / 31;
                        System.out.printf("LE-RGB(%3d,%3d,%3d)  ", r_le, g_le, b_le);

                        // BE xBGR 1-5-5-5
                        System.out.printf("BE-BGR(%3d,%3d,%3d)  ", b_be, g_be, r_be);

                        // LE xBGR 1-5-5-5
                        System.out.printf("LE-BGR(%3d,%3d,%3d)", b_le, g_le, r_le);

                        System.out.println();
                    }
                }
            }
            break;
        }
    }
}
