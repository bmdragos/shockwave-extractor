import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.bitmap.BitmapDecoder;

import java.nio.file.*;

public class DumpRaw2 {
    public static void main(String[] args) throws Exception {
        DirectorFile dirFile = DirectorFile.load(Path.of(args[0]));
        String targetName = args.length > 2 ? args[2] : "splashpage";

        for (CastMemberChunk member : dirFile.getCastMembers()) {
            if (!member.isBitmap()) continue;
            String name = member.name();
            if (name == null || !name.equals(targetName)) continue;

            BitmapInfo info = BitmapInfo.parse(member.specificData());
            int scanWidth = BitmapDecoder.calculateScanWidth(info.width(), info.bitDepth());
            int expectedUncompressed = scanWidth * info.height();

            var keyTable = dirFile.getKeyTable();
            for (var entry : keyTable.getEntriesForOwner(member.id())) {
                if (!entry.fourccString().equals("BITD")) continue;
                Chunk chunk = dirFile.getChunk(entry.sectionId());
                if (chunk instanceof BitmapChunk bc) {
                    byte[] raw = bc.data();
                    System.out.println("Bitmap: " + name + " " + info.width() + "x" + info.height() + " depth=" + info.bitDepth());
                    System.out.println("Raw BITD size: " + raw.length);
                    System.out.println("Expected uncompressed: " + expectedUncompressed);
                    System.out.println("Ratio: " + (raw.length * 100 / expectedUncompressed) + "%");

                    // Dump raw (undecompressed)
                    Files.write(Path.of(args[1] + "_raw.bin"), raw);
                    // Dump RLE decompressed
                    byte[] decompressed = BitmapDecoder.decompressRLE(raw, expectedUncompressed);
                    Files.write(Path.of(args[1] + "_rle.bin"), decompressed);
                    System.out.println("RLE decompressed size: " + decompressed.length);

                    // Check first few bytes of both
                    System.out.print("Raw first 20 bytes: ");
                    for (int i = 0; i < Math.min(20, raw.length); i++)
                        System.out.printf("%02X ", raw[i] & 0xFF);
                    System.out.println();

                    System.out.print("RLE first 20 bytes: ");
                    for (int i = 0; i < Math.min(20, decompressed.length); i++)
                        System.out.printf("%02X ", decompressed[i] & 0xFF);
                    System.out.println();
                }
            }
            break;
        }
    }
}
