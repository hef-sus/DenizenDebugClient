package dev.dzdebug;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S2C "dzdebug:error": string script, string line, string message, VarInt count. */
public record DzErrorPayload(String script, String line, String message, int count) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DzErrorPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(DzDebugClient.MOD_ID, "error"));

    public static final StreamCodec<ByteBuf, DzErrorPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(1024), DzErrorPayload::script,
            ByteBufCodecs.stringUtf8(64), DzErrorPayload::line,
            ByteBufCodecs.stringUtf8(65535), DzErrorPayload::message,
            ByteBufCodecs.VAR_INT, DzErrorPayload::count,
            DzErrorPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
