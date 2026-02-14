import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.cast.BitmapInfo;

import java.nio.file.*;

public class DiagnoseBitmaps {
    public static void main(String[] args) throws Exception {
        Path inputFile = Path.of(args[0]);
        DirectorFile dirFile = DirectorFile.load(inputFile);

        System.out.println("Stage: " + dirFile.getStageWidth() + "x" + dirFile.getStageHeight());
        System.out.println("Version: " + dirFile.getVersion());
        System.out.println("Palettes found: " + dirFile.getPalettes().size());

        for (var pal : dirFile.getPalettes()) {
            System.out.println("  Palette ID=" + pal.id() + " colors=" + pal.colors().length);
        }

        System.out.println("\n=== BITMAP MEMBERS ===");
        for (CastMemberChunk member : dirFile.getCastMembers()) {
            if (!member.isBitmap()) continue;

            String name = member.name() != null ? member.name() : "unnamed_" + member.id();
            byte[] specData = member.specificData();

            System.out.print("  " + name + " (id=" + member.id() + ")");
            System.out.print(" specDataLen=" + (specData != null ? specData.length : 0));

            if (specData != null && specData.length > 0) {
                // Print raw bytes for analysis
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < Math.min(specData.length, 32); i++) {
                    hex.append(String.format("%02X ", specData[i] & 0xFF));
                }
                System.out.print(" raw=[" + hex.toString().trim() + "]");

                try {
                    BitmapInfo info = BitmapInfo.parse(specData);
                    System.out.print(" w=" + info.width() + " h=" + info.height());
                    System.out.print(" depth=" + info.bitDepth());
                    System.out.print(" palId=" + info.paletteId());
                    System.out.print(" regX=" + info.regX() + " regY=" + info.regY());
                } catch (Exception e) {
                    System.out.print(" PARSE_ERROR: " + e.getMessage());
                }
            }
            System.out.println();
        }
    }
}
