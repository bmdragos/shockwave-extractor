import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.bitmap.BitmapDecoder;
import com.libreshockwave.audio.SoundConverter;
import com.libreshockwave.tools.scanning.MemberResolver;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;

public class ExtractAssets {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: ExtractAssets <input.dir> <output_dir>");
            System.exit(1);
        }

        Path inputFile = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);

        Files.createDirectories(outputDir.resolve("bitmaps"));
        Files.createDirectories(outputDir.resolve("sounds"));

        System.out.println("Loading: " + inputFile);
        DirectorFile dirFile = DirectorFile.load(inputFile);
        System.out.println("Stage: " + dirFile.getStageWidth() + "x" + dirFile.getStageHeight());
        System.out.println("Tempo: " + dirFile.getTempo() + " fps");

        int bitmapCount = 0;
        int soundCount = 0;
        int errorCount = 0;

        for (CastMemberChunk member : dirFile.getCastMembers()) {
            String name = member.name();
            if (name == null || name.isEmpty()) {
                name = "member_" + member.id();
            }
            name = name.replaceAll("[^a-zA-Z0-9_\\-\\.]", "_");

            if (member.isBitmap()) {
                try {
                    BitmapInfo info = BitmapInfo.parse(member.specificData());

                    // Find BITD chunk
                    BitmapChunk bitmapChunk = null;
                    var keyTable = dirFile.getKeyTable();
                    for (var entry : keyTable.getEntriesForOwner(member.id())) {
                        if (entry.fourccString().equals("BITD")) {
                            Chunk chunk = dirFile.getChunk(entry.sectionId());
                            if (chunk instanceof BitmapChunk bc) {
                                bitmapChunk = bc;
                                break;
                            }
                        }
                    }

                    if (bitmapChunk == null) continue;

                    int w = info.width();
                    int h = info.height();
                    int depth = info.bitDepth();

                    // Decompress RLE
                    int scanWidth = BitmapDecoder.calculateScanWidth(w, depth);
                    int expectedSize = scanWidth * h;
                    byte[] data = BitmapDecoder.decompressRLE(bitmapChunk.data(), expectedSize);

                    BufferedImage img;

                    if (depth == 16) {
                        // Director 16-bit: channel-separated scanlines (like 32-bit)
                        // Each row: [hi_byte_0..hi_byte_W-1][lo_byte_0..lo_byte_W-1]
                        // Pixel = (hi << 8) | lo, then decode as xRGB 1-5-5-5
                        img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < h; y++) {
                            int rowOffset = y * scanWidth;
                            for (int x = 0; x < w; x++) {
                                int hiIdx = rowOffset + x;
                                int loIdx = rowOffset + w + x;
                                if (hiIdx >= data.length || loIdx >= data.length) continue;
                                int hi = data[hiIdx] & 0xFF;
                                int lo = data[loIdx] & 0xFF;
                                int pixel = (hi << 8) | lo;

                                int r = ((pixel >> 10) & 0x1F) * 255 / 31;
                                int g = ((pixel >> 5) & 0x1F) * 255 / 31;
                                int b = (pixel & 0x1F) * 255 / 31;

                                // Use white (0x7FFF) as transparent
                                int a = (pixel == 0x7FFF) ? 0 : 255;
                                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                            }
                        }
                    } else {
                        // Use SDK decoder for non-16-bit (1, 4, 8, 32-bit)
                        var bitmap = dirFile.decodeBitmap(member);
                        if (bitmap.isEmpty()) continue;
                        img = bitmap.get().toBufferedImage();
                    }

                    File outFile = outputDir.resolve("bitmaps/" + name + ".png").toFile();
                    ImageIO.write(img, "PNG", outFile);
                    System.out.println("  BITMAP: " + name + " (" + img.getWidth() + "x" + img.getHeight() + ")");
                    bitmapCount++;
                } catch (Exception e) {
                    System.err.println("  ERROR bitmap " + name + ": " + e.getMessage());
                    e.printStackTrace();
                    errorCount++;
                }
            } else if (member.isSound()) {
                try {
                    SoundChunk sound = MemberResolver.findSoundForMember(dirFile, member);
                    if (sound != null) {
                        String ext;
                        byte[] audioData;
                        if (sound.isMp3()) {
                            audioData = SoundConverter.extractMp3(sound);
                            ext = ".mp3";
                        } else {
                            audioData = SoundConverter.toWav(sound);
                            ext = ".wav";
                        }
                        Files.write(outputDir.resolve("sounds/" + name + ext), audioData);
                        System.out.println("  SOUND: " + name + ext);
                        soundCount++;
                    }
                } catch (Exception e) {
                    System.err.println("  ERROR sound " + name + ": " + e.getMessage());
                    errorCount++;
                }
            } else {
                System.out.println("  SKIP: " + name + " (type=" + member.memberType() + ")");
            }
        }

        System.out.println("\n=== DONE ===");
        System.out.println("Bitmaps: " + bitmapCount);
        System.out.println("Sounds:  " + soundCount);
        System.out.println("Errors:  " + errorCount);
    }
}
