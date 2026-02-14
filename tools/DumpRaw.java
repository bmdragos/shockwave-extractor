import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.bitmap.BitmapDecoder;

import java.nio.file.*;

public class DumpRaw {
    public static void main(String[] args) throws Exception {
        DirectorFile dirFile = DirectorFile.load(Path.of(args[0]));
        String targetName = args.length > 2 ? args[2] : "splashpage";

        for (CastMemberChunk member : dirFile.getCastMembers()) {
            if (!member.isBitmap()) continue;
            String name = member.name();
            if (name == null || !name.equals(targetName)) continue;

            BitmapInfo info = BitmapInfo.parse(member.specificData());
            System.out.println(info.width() + " " + info.height() + " " + info.bitDepth());

            var keyTable = dirFile.getKeyTable();
            for (var entry : keyTable.getEntriesForOwner(member.id())) {
                if (!entry.fourccString().equals("BITD")) continue;
                Chunk chunk = dirFile.getChunk(entry.sectionId());
                if (chunk instanceof BitmapChunk bc) {
                    int scanWidth = BitmapDecoder.calculateScanWidth(info.width(), info.bitDepth());
                    int expectedSize = scanWidth * info.height();
                    byte[] data = BitmapDecoder.decompressRLE(bc.data(), expectedSize);
                    System.out.println("decompressed: " + data.length + " expected: " + expectedSize);
                    Files.write(Path.of(args[1]), data);
                    System.out.println("Written to " + args[1]);
                }
            }
            break;
        }
    }
}
