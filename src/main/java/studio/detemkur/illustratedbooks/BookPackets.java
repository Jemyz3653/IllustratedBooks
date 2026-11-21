package studio.detemkur.illustratedbooks;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class BookPackets {
    public static final int CHUNK=32*1024;
    static void register() {
        var server=PayloadTypeRegistry.serverboundPlay();
        server.register(Start.TYPE,Start.CODEC);server.register(Upload.TYPE,Upload.CODEC);
        server.register(Request.TYPE,Request.CODEC);server.register(Text.TYPE,Text.CODEC);
        var client=PayloadTypeRegistry.clientboundPlay();
        client.register(Open.TYPE,Open.CODEC);client.register(Status.TYPE,Status.CODEC);client.register(Image.TYPE,Image.CODEC);
    }
    public record Open(int slot) implements CustomPacketPayload {
        public static final Type<Open> TYPE=new Type<>(IllustratedBooks.id("open"));
        static final StreamCodec<FriendlyByteBuf,Open> CODEC=CustomPacketPayload.codec(
            (p,b) -> b.writeVarInt(p.slot),b -> new Open(b.readVarInt()));
        @Override public Type<Open> type() { return TYPE; }
    }
    public record Start(int slot,int page,int size) implements CustomPacketPayload {
        public static final Type<Start> TYPE=new Type<>(IllustratedBooks.id("start"));
        static final StreamCodec<FriendlyByteBuf,Start> CODEC=CustomPacketPayload.codec(
            (p,b) -> { b.writeVarInt(p.slot);b.writeVarInt(p.page);b.writeVarInt(p.size); },
            b -> new Start(b.readVarInt(),b.readVarInt(),b.readVarInt()));
        @Override public Type<Start> type() { return TYPE; }
    }
    public record Upload(byte[] bytes) implements CustomPacketPayload {
        public static final Type<Upload> TYPE=new Type<>(IllustratedBooks.id("upload"));
        static final StreamCodec<FriendlyByteBuf,Upload> CODEC=CustomPacketPayload.codec(
            (p,b) -> b.writeByteArray(p.bytes),b -> new Upload(b.readByteArray(CHUNK)));
        @Override public Type<Upload> type() { return TYPE; }
    }
    public record Request(int slot,String id) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(IllustratedBooks.id("request"));
        static final StreamCodec<FriendlyByteBuf,Request> CODEC=CustomPacketPayload.codec(
            (p,b) -> { b.writeVarInt(p.slot);b.writeUtf(p.id,64); },b -> new Request(b.readVarInt(),b.readUtf(64)));
        @Override public Type<Request> type() { return TYPE; }
    }
    public record Text(int slot,int page,String text) implements CustomPacketPayload {
        public static final Type<Text> TYPE=new Type<>(IllustratedBooks.id("text"));
        static final StreamCodec<FriendlyByteBuf,Text> CODEC=CustomPacketPayload.codec(
            (p,b) -> { b.writeVarInt(p.slot);b.writeVarInt(p.page);b.writeUtf(p.text,2048); },
            b -> new Text(b.readVarInt(),b.readVarInt(),b.readUtf(2048)));
        @Override public Type<Text> type() { return TYPE; }
    }
    public record Status(int code) implements CustomPacketPayload {
        public static final int READY=0,SAVED=1,CLEARED=2,DENIED=3,BAD_PNG=4,BUSY=5,IO_ERROR=6;
        public static final Type<Status> TYPE=new Type<>(IllustratedBooks.id("status"));
        static final StreamCodec<FriendlyByteBuf,Status> CODEC=CustomPacketPayload.codec(
            (p,b) -> b.writeVarInt(p.code),b -> new Status(b.readVarInt()));
        @Override public Type<Status> type() { return TYPE; }
    }
    public record Image(String id,int size,byte[] bytes) implements CustomPacketPayload {
        public static final Type<Image> TYPE=new Type<>(IllustratedBooks.id("image"));
        static final StreamCodec<FriendlyByteBuf,Image> CODEC=CustomPacketPayload.codec(
            (p,b) -> { b.writeUtf(p.id,64);b.writeVarInt(p.size);b.writeByteArray(p.bytes); },
            b -> new Image(b.readUtf(64),b.readVarInt(),b.readByteArray(CHUNK)));
        @Override public Type<Image> type() { return TYPE; }
    }
}
