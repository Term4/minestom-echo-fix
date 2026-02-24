package io.github.term4.echofix;

import net.minestom.server.entity.Metadata;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.EntityAttributesPacket;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * A Player implementation that suppresses client-predicted metadata echoes.
 * <p>
 * Use via {@link EchoFix#install()} — do not instantiate directly unless
 * you are providing your own player provider.
 *
 * @see EchoFix#install()
 * @see SelfMetaFilter
 */
public class EchoFixPlayer extends Player {

    private @Nullable SelfMetaFilter selfMetaFilter = SelfMetaFilter.defaultPlayerFilter();
    private boolean processingClientInput = false;

    /**
     * Creates a new EchoFixPlayer.
     * @param connection the player connection
     * @param profile the player's game profile
     */
    public EchoFixPlayer(@NotNull PlayerConnection connection, @NotNull GameProfile profile) {
        super(connection, profile);
    }

    /**
     * Sets whether the packet currently being processed is due to client input.
     * @param value true when processing a client input packet
     */
    public void setProcessingClientInput(boolean value) {
        this.processingClientInput = value;
    }

    /**
     * Gets the current self-metadata filter.
     * @return the current filter
     */
    public @Nullable SelfMetaFilter getSelfMetaFilter() {
        return selfMetaFilter;
    }

    /**
     * Sets the self-metadata filter, or use null to disable filtering.
     * @param filter the filter to use
     */
    public void setSelfMetaFilter(@Nullable SelfMetaFilter filter) {
        this.selfMetaFilter = filter;
    }


    /**
     * Update player state without sending the change to this player.
     * Other viewers will still receive the updated state.
     *
     * <pre>{@code
     * player.suppressSelf(() -> player.setSneaking(true));
     * }</pre>
     *
     * @param action the state change packet to suppress from self
     */
    public void suppressSelf(@NotNull Runnable action) {
        processingClientInput = true;
        try {
            action.run();
        } finally {
            processingClientInput = false;
        }
    }

    @Override
    public void sendPacketToViewersAndSelf(@NotNull SendablePacket packet) {
        if (processingClientInput && selfMetaFilter != null) {

            // Metadata filtering (crouching, use item, start elytra fly)
            if (packet instanceof EntityMetaDataPacket(int entityId, Map<Integer, Metadata.Entry<?>> entries) && entityId == getEntityId()) {
                Map<Integer, Metadata.Entry<?>> filtered = selfMetaFilter.filter(entries);
                if (filtered != null) {
                    if (!filtered.isEmpty()) {
                        sendPacket(new EntityMetaDataPacket(entityId, filtered));
                    }
                    sendPacketToViewers(packet);
                    return;
                }
            }

            // Attribute filter (e.g. sprint)
            if (packet instanceof EntityAttributesPacket attr
                    && attr.entityId() == getEntityId()
                    && selfMetaFilter.suppressAttributes()) {
                sendPacketToViewers(packet);
                return;
            }
        }

        super.sendPacketToViewersAndSelf(packet);
    }
}