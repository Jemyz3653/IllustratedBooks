package studio.detemkur.illustratedbooks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import studio.detemkur.illustratedbooks.BookPackets.Status;

public final class IllustratedBooksClient implements ClientModInitializer {
    private static Upload active;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(BookPackets.Open.TYPE, (packet, context) -> {
            if (active == null) context.client().gui.setScreen(new BookScreen(packet.slot()));
        });
        ClientPlayNetworking.registerGlobalReceiver(Status.TYPE, (packet, context) -> onStatus(packet));
        ClientPlayNetworking.registerGlobalReceiver(BookPackets.Image.TYPE,
                (packet, context) -> BookTextures.receive(packet));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            uploadTick();
            BookTextures.tick();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            reset();
            BookTextures.clear();
        }));
    }

    static boolean busy() {
        return active != null;
    }

    static int progress(BookScreen screen) {
        if (active == null || active.screen != screen || active.total == 0) return -1;
        return (int) ((active.total - (long) active.remaining) * 100 / active.total);
    }

    static void start(BookScreen screen, int slot, int page, Path file) throws IOException {
        long size = file == null ? 0 : Files.size(file);
        if (active != null || (file != null && size < 45) || size > PngFile.MAX_BYTES || !ClientPlayNetworking.canSend(BookPackets.Start.TYPE)) {
            throw new IOException("Unable to start upload");
        }
        active = new Upload(screen, file == null ? null : Files.newInputStream(file), (int) size);
        ClientPlayNetworking.send(new BookPackets.Start(slot, page, (int) size));
    }

    private static void onStatus(Status packet) {
        if (active == null) return;
        if (packet.code() == Status.READY) {
            active.ready = true;
            return;
        }
        switch (packet.code()) {
            case Status.SAVED -> finish("saved");
            case Status.CLEARED -> {
                active.screen.clearPreview();
                finish("cleared");
            }
            case Status.DENIED -> finish("denied");
            case Status.BAD_PNG -> finish("bad_png");
            case Status.BUSY -> finish("busy");
            default -> finish("io_error");
        }
    }

    private static void uploadTick() {
        if (active == null) return;
        if (System.nanoTime() - active.lastActivity > 60_000_000_000L) {
            finish("io_error");
            return;
        }
        if (!active.ready || active.stream == null || active.remaining == 0) return;
        try {
            for (int i = 0; i < 8 && active.remaining > 0; i++) {
                int size = Math.min(active.remaining, BookPackets.CHUNK);
                byte[] bytes = active.stream.readNBytes(size);
                if (bytes.length != size) throw new IOException("File changed during upload");
                ClientPlayNetworking.send(new BookPackets.Upload(bytes));
                active.remaining -= bytes.length;
                active.lastActivity = System.nanoTime();
            }
            if (active.remaining == 0) active.stream.close();
        } catch (IOException | IllegalStateException error) {
            finish("io_error");
        }
    }

    private static void finish(String result) {
        var screen = active.screen;
        reset();
        var message = Component.translatable("illustratedbooks." + result);
        if (Minecraft.getInstance().gui.screen() == screen) screen.setStatus(message);
        else if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.sendOverlayMessage(message);
    }

    private static void reset() {
        if (active != null && active.stream != null) {
            try {
                active.stream.close();
            } catch (IOException ignored) {
                // The connection or transfer is already closed.
            }
        }
        active = null;
    }

    private static final class Upload {
        final BookScreen screen;
        final InputStream stream;
        final int total;
        int remaining;
        boolean ready;
        long lastActivity = System.nanoTime();

        Upload(BookScreen screen, InputStream stream, int remaining) {
            this.screen = screen;
            this.stream = stream;
            this.total = remaining;
            this.remaining = remaining;
        }
    }
}
