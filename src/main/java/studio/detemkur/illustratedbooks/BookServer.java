package studio.detemkur.illustratedbooks;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import studio.detemkur.illustratedbooks.BookPackets.Status;

final class BookServer {
    private static final Map<ServerPlayer,Upload> UPLOADS=new HashMap<>();
    private static final Map<ServerPlayer,Download> DOWNLOADS=new HashMap<>();
    static void register() {
        ServerPlayNetworking.registerGlobalReceiver(BookPackets.Start.TYPE,(p,c) -> c.server().execute(() -> start(c.player(),p)));
        ServerPlayNetworking.registerGlobalReceiver(BookPackets.Upload.TYPE,(p,c) -> c.server().execute(() -> upload(c.player(),p.bytes())));
        ServerPlayNetworking.registerGlobalReceiver(BookPackets.Request.TYPE,(p,c) -> c.server().execute(() -> request(c.player(),p)));
        ServerPlayNetworking.registerGlobalReceiver(BookPackets.Text.TYPE,(p,c) -> c.server().execute(() -> {
            var book=book(c.player(),p.slot());
            if (!book.isEmpty() && p.page()>=0 && p.page()<IllustratedBooks.PAGES && p.text().length()<=2048) {
                IllustratedBooks.page(book,p.page(),null,p.text());sync(c.player());
            }
        }));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            UPLOADS.entrySet().removeIf(entry -> {
                var transfer=entry.getValue();
                if (System.nanoTime()-transfer.activity<60_000_000_000L) return false;
                transfer.close();status(entry.getKey(),Status.IO_ERROR);return true;
            });
            DOWNLOADS.entrySet().removeIf(entry -> !send(entry.getKey(),entry.getValue()));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server) -> clear(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            UPLOADS.values().forEach(Upload::close);UPLOADS.clear();
            DOWNLOADS.values().forEach(Download::close);DOWNLOADS.clear();
        });
    }
    private static void clear(ServerPlayer player) {
        var upload=UPLOADS.remove(player);if (upload!=null) upload.close();
        var download=DOWNLOADS.remove(player);if (download!=null) download.close();
    }
    static ItemStack book(ServerPlayer player,int slot) {
        if (slot<0 || slot>=player.getInventory().getContainerSize() || player.isSpectator()) return ItemStack.EMPTY;
        var stack=player.getInventory().getItem(slot);
        return stack.is(IllustratedBooks.BOOK) ? stack : ItemStack.EMPTY;
    }
    private static Path directory(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("illustratedbooks");
    }
    private static void start(ServerPlayer player,BookPackets.Start packet) {
        if (UPLOADS.containsKey(player)) { status(player,Status.BUSY);return; }
        var book=book(player,packet.slot());
        if (book.isEmpty() || packet.page()<0 || packet.page()>=IllustratedBooks.PAGES) { status(player,Status.DENIED);return; }
        if (packet.size()==0) {
            IllustratedBooks.page(book,packet.page(),"",null);sync(player);status(player,Status.CLEARED);return;
        }
        if (packet.size()<45 || packet.size()>PngFile.MAX_BYTES) { status(player,Status.BAD_PNG);return; }
        Path temp=null;
        try {
            temp=Files.createTempFile(Files.createDirectories(directory(player.level())),"upload-",".tmp");
            UPLOADS.put(player,new Upload(player.level(),book,packet.slot(),packet.page(),temp,packet.size()));
            status(player,Status.READY);
        } catch (IOException error) { delete(temp);status(player,Status.IO_ERROR); }
    }
    private static void upload(ServerPlayer player,byte[] bytes) {
        var transfer=UPLOADS.get(player);
        if (transfer==null || transfer.stream==null) return;
        if (book(player,transfer.slot)!=transfer.book || player.level()!=transfer.level
            || bytes.length==0 || bytes.length>transfer.remaining) {
            UPLOADS.remove(player);transfer.close();status(player,Status.DENIED);return;
        }
        try {
            transfer.stream.write(bytes);transfer.remaining-=bytes.length;transfer.activity=System.nanoTime();
            if (transfer.remaining>0) return;
            transfer.stream.close();transfer.stream=null;
        } catch (IOException error) { UPLOADS.remove(player);transfer.close();status(player,Status.IO_ERROR);return; }
        var server=transfer.level.getServer();
        CompletableFuture.supplyAsync(() -> {
            try {
                String id=PngFile.verify(transfer.file);
                var decoded=ImageIO.read(transfer.file.toFile());
                if (decoded==null) return "";
                decoded.flush();return id;
            } catch (IOException|RuntimeException error) { return ""; }
        }).whenComplete((id,error) -> {
            if (server.isStopped()) { delete(transfer.file);return; }
            server.execute(() -> finish(player,transfer,error==null ? id : ""));
        });
    }
    private static void finish(ServerPlayer player,Upload transfer,String id) {
        try {
            if (!UPLOADS.remove(player,transfer)) return;
            if (book(player,transfer.slot)!=transfer.book || player.level()!=transfer.level) { status(player,Status.DENIED);return; }
            if (!PngFile.isId(id)) { status(player,Status.BAD_PNG);return; }
            var destination=directory(transfer.level).resolve(id+".png");
            if (!Files.exists(destination)) {
                try { Files.move(transfer.file,destination,StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException error) { Files.move(transfer.file,destination); }
            }
            IllustratedBooks.page(transfer.book,transfer.page,id,null);sync(player);status(player,Status.SAVED);
        } catch (IOException error) { status(player,Status.IO_ERROR); }
        finally { delete(transfer.file); }
    }
    private static boolean contains(ItemStack book,String id) {
        for (int page=0;page<IllustratedBooks.PAGES;page++) if (IllustratedBooks.picture(book,page).equals(id)) return true;
        return false;
    }
    private static void request(ServerPlayer player,BookPackets.Request packet) {
        var book=book(player,packet.slot());
        if (DOWNLOADS.containsKey(player) || !PngFile.isId(packet.id()) || book.isEmpty() || !contains(book,packet.id())
            || !ServerPlayNetworking.canSend(player,BookPackets.Image.TYPE)) return;
        try {
            var file=directory(player.level()).resolve(packet.id()+".png");long size=Files.size(file);
            if (size>=45 && size<=PngFile.MAX_BYTES) DOWNLOADS.put(player,new Download(player.level(),book,packet.slot(),packet.id(),(int)size,Files.newInputStream(file)));
        } catch (IOException ignored) { /* Missing server images remain blank in the book. */ }
    }
    private static boolean send(ServerPlayer player,Download download) {
        if (player.level()==download.level && book(player,download.slot)==download.book && contains(download.book,download.id)) {
            try {
                for (int i=0;i<8 && download.remaining>0;i++) {
                    int size=Math.min(download.remaining,BookPackets.CHUNK);byte[] data=download.stream.readNBytes(size);
                    if (data.length!=size) break;
                    ServerPlayNetworking.send(player,new BookPackets.Image(download.id,download.size,data));download.remaining-=size;
                }
                if (download.remaining>0 && download.stream.available()>0) return true;
            } catch (IOException ignored) { /* A disconnect closes the pending transfer. */ }
        }
        download.close();return false;
    }
    private static void sync(ServerPlayer player) { player.inventoryMenu.broadcastChanges();player.containerMenu.broadcastChanges(); }
    private static void status(ServerPlayer player,int code) {
        if (ServerPlayNetworking.canSend(player,Status.TYPE)) ServerPlayNetworking.send(player,new Status(code));
    }
    private static void delete(Path file) {
        if (file!=null) try { Files.deleteIfExists(file); } catch (IOException ignored) { file.toFile().deleteOnExit(); }
    }
    private static final class Upload {
        final ServerLevel level;final ItemStack book;final int slot,page;final Path file;
        OutputStream stream;int remaining;long activity=System.nanoTime();
        Upload(ServerLevel level,ItemStack book,int slot,int page,Path file,int size) throws IOException {
            this.level=level;this.book=book;this.slot=slot;this.page=page;this.file=file;remaining=size;stream=Files.newOutputStream(file);
        }
        void close() {
            try { if (stream!=null) stream.close(); } catch (IOException ignored) {}
            delete(file);
        }
    }
    private static final class Download {
        final ServerLevel level;final ItemStack book;final int slot,size;final String id;final InputStream stream;int remaining;
        Download(ServerLevel level,ItemStack book,int slot,String id,int size,InputStream stream) {
            this.level=level;this.book=book;this.slot=slot;this.id=id;this.size=size;this.stream=stream;remaining=size;
        }
        void close() { try { stream.close(); } catch (IOException ignored) {} }
    }
}
