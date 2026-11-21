package studio.detemkur.illustratedbooks;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

public final class PngFile {
    public static final int MAX_EDGE = 4096;
    public static final int MAX_BYTES = 72 * 1024 * 1024;
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final int IHDR = 0x49484452;
    private static final int IDAT = 0x49444154;
    private static final int IEND = 0x49454e44;
    private static final Pattern ID = Pattern.compile("[a-f0-9]{64}");

    private PngFile() {}

    public record Header(int width, int height) {}

    public static boolean isId(String value) {
        return ID.matcher(value).matches();
    }

    public static Header header(Path path) throws IOException {
        long bytes = Files.size(path);
        if (bytes > MAX_BYTES || bytes < 45) throw new IOException("Invalid PNG file size");
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            return header(input);
        }
    }

    private static Header header(DataInputStream input) throws IOException {
        if (!Arrays.equals(input.readNBytes(8), SIGNATURE) || input.readInt() != 13 || input.readInt() != IHDR) {
            throw new IOException("Expected a PNG with IHDR");
        }
        int width = input.readInt();
        int height = input.readInt();
        if (width < 1 || height < 1 || width > MAX_EDGE || height > MAX_EDGE) {
            throw new IOException("PNG exceeds 4096 pixels per side");
        }
        return new Header(width, height);
    }

    public static String verify(Path path) throws IOException {
        long length = Files.size(path);
        if (length < 45 || length > MAX_BYTES) throw new IOException("Invalid PNG file size");
        var digest = sha256();
        try (var file = new java.io.BufferedInputStream(Files.newInputStream(path));
             var checksum = new DigestInputStream(file, digest);
             var input = new DataInputStream(checksum)) {
            Header size = header(input);
            byte[] format = input.readNBytes(5);
            var crc = new CRC32();
            update(crc, IHDR);
            update(crc, size.width());
            update(crc, size.height());
            crc.update(format);
            if (input.readInt() != (int) crc.getValue()) throw new IOException("Incorrect PNG header checksum");

            long remaining = length - 33;
            byte[] buffer = new byte[32768];
            boolean hasData = false;
            int chunks = 0;
            while (remaining >= 12 && chunks++ < 32768) {
                int bytes = input.readInt();
                int type = input.readInt();
                if (bytes < 0 || bytes > remaining - 12 || type == IHDR) {
                    throw new IOException("Invalid PNG chunk");
                }
                crc.reset();
                update(crc, type);
                for (int left = bytes; left > 0;) {
                    int count = Math.min(left, buffer.length);
                    input.readFully(buffer, 0, count);
                    crc.update(buffer, 0, count);
                    left -= count;
                }
                if (input.readInt() != (int) crc.getValue()) throw new IOException("Incorrect PNG chunk checksum");
                remaining -= bytes + 12L;
                if (type == IDAT && bytes > 0) hasData = true;
                if (type == IEND && hasData && bytes == 0 && remaining == 0) {
                    return HexFormat.of().formatHex(digest.digest());
                }
                if (type == IEND) break;
            }
            throw new IOException("Incomplete PNG");
        }
    }

    private static void update(CRC32 crc, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) crc.update(value >>> shift);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
