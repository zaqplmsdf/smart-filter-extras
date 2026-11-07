package smartfilterextras.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import smartfilterextras.SmartFilterExtras;

/**
 * A payload that is never sent. Registering it (without {@code optional()}) makes NeoForge's channel
 * negotiation refuse a connection when only one side has this mod installed.
 *
 * <p>Without it, a client without the mod could join a server that has it (or the other way round). The side
 * without the mod can't parse {@code smart_filter_extras:*} filters, and edits sent to a server without the mod
 * are silently dropped by FTB Filter System.</p>
 */
public enum PresencePayload implements CustomPacketPayload {
    INSTANCE;

    /**
     * Bump whenever a version can write filter strings an older version can't read (new filter types or
     * arguments), so mismatched clients and servers are refused instead of silently losing edits.
     * 1 = 2.0.0 (items, block_tag); 2 = 2.1.0 (adds fuel); 3 = 2.2.0 (several block tags, item variants);
     * 4 = 2.3.0 (adds item_tag) and later. Since 3.0.0 the channel itself is named after the new mod ID
     * ({@code smart_filter_extras:presence}), so 2.x and 3.x never connect whatever the version.
     */
    public static final String PROTOCOL_VERSION = "4";

    public static final Type<PresencePayload> TYPE = new Type<>(SmartFilterExtras.id("presence"));
    public static final StreamCodec<ByteBuf, PresencePayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL_VERSION)
                .playToClient(TYPE, STREAM_CODEC, (payload, context) -> {
                    // Never sent; exists only for channel negotiation.
                });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
