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
 *
 * <h2>The Problem</h2>
 * When a player performs an action (sneak, sprint, use item), the Minecraft client
 * applies the state change locally for immediate visual feedback. The client then
 * notifies the server, which updates its internal state and broadcasts the change
 * to all viewers including the player who initiated it. When a player receives
 * an update about themselves (the "echo"), the client reapplies the state, causing a visible
 * stutter for one tick.
 *
 * This affects sneaking, sprinting, item use, and pose transitions.
 *
 * <h2>The Fix</h2>
 * {@code EchoFixPlayer} intercepts outgoing metadata packets in
 * {@link #sendPacketToViewersAndSelf(SendablePacket)} and splits it into two copies.
 * The copy sent to external viewers is always unchanged, and the copy sent to self
 * is stripped of client-predicted (or otherwise preconfigured) entries.
 * The filter is only active during processing of a client input packet
 * (detected via the {@code echoingSelfInput} flag set in the method overrides).
 *
 * <h2>Server-Driven Changes</h2>
 * When a plugin or the server itself needs to override a client-authoritative state
 * (e.g., force a player to stop sneaking), use {@link #forceMetadata(Runnable)}
 * to bypass the filter:
 * <pre>{@code
 * echoFixPlayer.forceMetadata(() -> player.setSneaking(false));
 * }</pre>
 *
 * <h2>Elytra Handling</h2>
 * Elytra start ({@code setFlyingWithElytra(true)}) is predicted and filtered by default.
 * Elytra stop ({@code setFlyingWithElytra(false)}) is a server decision (player landed)
 * and always passes through to the client unfiltered (otherwise players get stuck flying
 * and are unable to leave the pose, even upon removing their elytra).
 *
 * <h2>Usage</h2>
 * Use this class instead of {@link Player} in your connection handler:
 * <pre>{@code
 * ConnectionManager connectionManager = MinecraftServer.getConnectionManager();
 * connectionManager.setPlayerProvider(EchoFixPlayer::new);
 * }</pre>
 */
public class EchoFixPlayer extends Player {

    private @Nullable SelfMetaFilter selfMetaFilter = SelfMetaFilter.defaultPlayerFilter();

    private boolean echoingSelfInput = false;
    private boolean serverForced = false;

    /**
     * Creates an EchoFixPlayer for the given connection and game profile.
     *
     * @param playerConnection the player's network connection
     * @param gameProfile the player's game profile
     */
    public EchoFixPlayer(@NotNull PlayerConnection playerConnection, @NotNull GameProfile gameProfile) {
        super(playerConnection, gameProfile);
    }

    // Public API
    /**
     * Get the current self-meta filter.
     *
     * @return the filter, or null if filter is disabled
     */
    public @Nullable SelfMetaFilter getSelfMetadataFilter() {
        return selfMetaFilter;
    }

    /**
     * Set a custom self-meta filter, or null to disable filter entirely.
     *
     * @param filter the filter to use, or null to disable
     */
    public void setSelfMetadataFilter(@Nullable SelfMetaFilter filter) {
        this.selfMetaFilter = filter;
    }

    /**
     * Execute a metadata change that bypasses the filter.
     * <p>
     * Use this when the server needs to override client-predicted state.
     * Without this wrapper, server calls to {@link #setSneaking},
     * {@link #setSprinting}, etc. will be filtered.
     *
     * <pre>{@code
     * // Override the players local sneaking state
     * echoFixPlayer.forceMetadata(() -> player.setSneaking(false));
     * }</pre>
     *
     * @param action the metadata change bypassing the filter
     */
    public void forceMetadata(@NotNull Runnable action) {
        serverForced = true;
        try {
            action.run();
        } finally {
            serverForced = false;
        }
    }


    @Override
    public void setSneaking(boolean sneaking) {
        if (!serverForced) {
            echoingSelfInput = true;
            super.setSneaking(sneaking);
            echoingSelfInput = false;
        } else {
            super.setSneaking(sneaking);
        }
    }

    @Override
    public void setSprinting(boolean sprinting) {
        if (!serverForced) {
            echoingSelfInput = true;
            super.setSprinting(sprinting);
            echoingSelfInput = false;
        } else {
            super.setSprinting(sprinting);
        }
    }

    @Override
    public void setFlyingWithElytra(boolean flying) {
        if (flying && !serverForced) {
            // Start flying with elytra IS predicted by the client
            echoingSelfInput = true;
            super.setFlyingWithElytra(true);
            echoingSelfInput = false;
        } else if (!flying && !serverForced && selfMetaFilter != null
                    && selfMetaFilter.filterElytraStop()) {
            // Server DOES NOT send stop flying meta (players get stuck flying until relog)
            echoingSelfInput = true;
            super.setFlyingWithElytra(false);
            echoingSelfInput = false;
        } else {
            super.setFlyingWithElytra(flying);
        }
    }

    @Override
    public void refreshActiveHand(boolean isHandActive, boolean offHand, boolean riptideSpinAttack) {
        if (!serverForced) {
            echoingSelfInput = true;
            super.refreshActiveHand(isHandActive, offHand, riptideSpinAttack);
            echoingSelfInput = false;
        } else {
            super.refreshActiveHand(isHandActive, offHand, riptideSpinAttack);
        }
    }

    // Outgoing packet filter
    @Override
    public void sendPacketToViewersAndSelf(@NotNull SendablePacket packet) {
        if (echoingSelfInput && selfMetaFilter != null) {
            if (packet instanceof EntityMetaDataPacket(
                    int entityId, Map<Integer, Metadata.Entry<?>> entries
            ) && entityId == getEntityId()) {
                Map<Integer, Metadata.Entry<?>> filtered = selfMetaFilter.filter(entries);

                if (filtered != null) {
                    if (!filtered.isEmpty()) {
                        sendPacket(new EntityMetaDataPacket(entityId, filtered));
                    }
                    sendPacketToViewers(packet);
                    return;
                }
            }

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
