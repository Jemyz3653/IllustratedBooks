package studio.detemkur.illustratedbooks;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.LoggerFactory;
import studio.detemkur.illustratedbooks.BookPackets.Image;
import studio.detemkur.illustratedbooks.BookPackets.Request;

final class BookTextures {
    private static final long TEXTURE_BYTES = 96L * 1024 * 1024;
    private static final long TIMEOUT = 15_000_000_000L;
    private static final long RETRY = 15_000_000_000L;
    private static final Map<String, Texture> TEXTURES = new LinkedHashMap<>(16, 0.75F, true);
    private static final Map<String, Long> RETRIES = new HashMap<>();
    private static Transfer transfer;
    private static long bytes;

    private BookTextures() {}

    record Texture(Identifier name, int width, int height) {
        long bytes() {
            return (long) width * height * 4;
        }
    }

    static Texture get(String id) {
        return TEXTURES.get(id);
    }

    static void request(int slot, String id) {
        if (transfer != null || !PngFile.isId(id) || TEXTURES.containsKey(id)
                || RETRIES.getOrDefault(id, 0L) > System.nanoTime()
                || !ClientPlayNetworking.canSend(Request.TYPE)) return;
        transfer = new Transfer(id);
        ClientPlayNetworking.send(new Request(slot, id));
    }

    static void receive(Image packet) {
        Transfer current = transfer;
        if (current == null || current.decoding || !current.id.equals(packet.id())) return;
        try {
            if (current.stream == null) {
                if (packet.size() < 45 || packet.size() > PngFile.MAX_BYTES) throw new IOException("Image too large");
                current.path = Files.createTempFile("cardboard-stand-", ".png");
                current.stream = new BufferedOutputStream(Files.newOutputStream(current.path));
                current.remaining = packet.size();
            }
            if (packet.bytes().length == 0 || packet.bytes().length > current.remaining) throw new IOException("Unexpected packet size");
            current.stream.write(packet.bytes());
            current.remaining -= packet.bytes().length;
            current.activity = System.nanoTime();
            if (current.remaining == 0) decode(current);
        } catch (IOException error) {
            fail(current, error);
        }
    }

    private static void decode(Transfer current) throws IOException {
        current.stream.close();
        current.stream = null;
        current.decoding = true;
        int limit = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize();
        CompletableFuture.supplyAsync(() -> {
            try {
                if (!PngFile.verify(current.path).equals(current.id)) throw new IOException("Incorrect image digest");
                return read(current.path, limit);
            } catch (IOException | RuntimeException error) {
                throw new java.util.concurrent.CompletionException(error);
            } finally {
                delete(current.path);
            }
        }).whenComplete((image, error) -> Minecraft.getInstance().execute(() -> {
            if (transfer != current) {
                if (image != null) image.close();
            } else if (error != null) {
                fail(current, error);
            } else {
                try {
                    cache(current.id, image);
                    transfer = null;
                } catch (RuntimeException uploadError) {
                    image.close();
                    fail(current, uploadError);
                }
            }
        }));
    }

    private static NativeImage read(Path path, int limit) throws IOException {
        PngFile.Header size = PngFile.header(path);
        if (size.width() <= limit && size.height() <= limit) {
            try (var input = Files.newInputStream(path)) {
                return NativeImage.read(input);
            }
        }
        // Some devices have a smaller texture limit. Decode at reduced resolution on those clients.
        try (var input = ImageIO.createImageInputStream(path.toFile())) {
            var reader = ImageIO.getImageReadersByFormatName("png").next();
            try {
                reader.setInput(input, true, true);
                int step = Math.max(1, Math.ceilDiv(Math.max(size.width(), size.height()), limit));
                var params = reader.getDefaultReadParam();
                params.setSourceSubsampling(step, step, 0, 0);
                var output = reader.read(0, params);
                var image = new NativeImage(output.getWidth(), output.getHeight(), false);
                int[] row = new int[output.getWidth()];
                for (int y = 0; y < output.getHeight(); y++) {
                    output.getRGB(0, y, row.length, 1, row, 0, row.length);
                    for (int x = 0; x < row.length; x++) image.setPixel(x, y, row[x]);
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private static void cache(String id, NativeImage image) {
        var name = IllustratedBooks.id("picture/" + id);
        var texture = new Texture(name, image.getWidth(), image.getHeight());
        var manager = Minecraft.getInstance().getTextureManager();
        var iterator = TEXTURES.values().iterator();
        while ((bytes + texture.bytes() > TEXTURE_BYTES || TEXTURES.size() >= 128) && iterator.hasNext()) {
            Texture old = iterator.next();
            manager.release(old.name());
            bytes -= old.bytes();
            iterator.remove();
        }
        manager.register(name, new DynamicTexture(() -> "Illustrated book " + id, image));
        TEXTURES.put(id, texture);
        bytes += texture.bytes();
    }

    static void tick() {
        long now = System.nanoTime();
        if (transfer != null && !transfer.decoding && now - transfer.activity > TIMEOUT) fail(transfer, null);
        RETRIES.values().removeIf(until -> until < now);
    }

    static void clear() {
        if (transfer != null && !transfer.decoding) transfer.close();
        transfer = null;
        RETRIES.clear();
        var manager = Minecraft.getInstance().getTextureManager();
        TEXTURES.values().forEach(texture -> manager.release(texture.name()));
        TEXTURES.clear();
        bytes = 0;
    }

    private static void fail(Transfer current, Throwable error) {
        current.close();
        RETRIES.put(current.id, System.nanoTime() + RETRY);
        transfer = null;
        if (error != null) LoggerFactory.getLogger(IllustratedBooks.ID).debug("Could not load book image", error);
    }

    private static void delete(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary file cleanup will be retried on exit.
            path.toFile().deleteOnExit();
        }
    }

    private static final class Transfer {
        final String id;
        Path path;
        OutputStream stream;
        long activity = System.nanoTime();
        int remaining;
        boolean decoding;

        Transfer(String id) {
            this.id = id;
        }

        void close() {
            try {
                if (stream != null) stream.close();
            } catch (IOException ignored) {
                // Still delete the incomplete image.
            }
            delete(path);
        }
    }
}
