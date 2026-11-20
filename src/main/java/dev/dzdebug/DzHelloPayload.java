package dev.dzdebug;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S "dzdebug:hello" (empty): sent on join so the server knows to flush its unseen log to this client. */
public record DzHelloPayload() implements CustomPacketPayload {
    public static final DzHelloPayload INSTANCE = new DzHelloPayload();

    public static final CustomPacketPayload.Type<DzHelloPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(DzDebugClient.MOD_ID, "hello"));

    public static final StreamCodec<ByteBuf, DzHelloPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
