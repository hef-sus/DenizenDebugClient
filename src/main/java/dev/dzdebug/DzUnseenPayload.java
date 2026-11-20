package dev.dzdebug;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * S2C "dzdebug:unseen": errors stored on the server while nobody with the mod was online.
 * Wire: boolean reset, VarInt n, then n x { string script, string line, string message, VarInt count, long lastSeenMillis }.
 * The server may split the list over several packets; reset=true marks the first one.
 */
public record DzUnseenPayload(boolean reset, List<Entry> entries) implements CustomPacketPayload {
    public record Entry(String script, String line, String message, int count, long lastSeen) {
        public static final StreamCodec<ByteBuf, Entry> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(1024), Entry::script,
                ByteBufCodecs.stringUtf8(64), Entry::line,
                ByteBufCodecs.stringUtf8(8192), Entry::message,
                ByteBufCodecs.VAR_INT, Entry::count,
                ByteBufCodecs.LONG, Entry::lastSeen,
                Entry::new);
    }

    public static final CustomPacketPayload.Type<DzUnseenPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(DzDebugClient.MOD_ID, "unseen"));

    public static final StreamCodec<ByteBuf, DzUnseenPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, DzUnseenPayload::reset,
            Entry.CODEC.apply(ByteBufCodecs.list(64)), DzUnseenPayload::entries,
            DzUnseenPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
