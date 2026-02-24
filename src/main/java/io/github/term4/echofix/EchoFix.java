package io.github.term4.echofix;

import net.minestom.server.MinecraftServer;
import net.minestom.server.listener.EntityActionListener;
import net.minestom.server.listener.PlayerActionListener;
import net.minestom.server.listener.PlayerInputListener;
import net.minestom.server.listener.UseItemListener;
import net.minestom.server.listener.manager.PacketPlayListenerConsumer;
import net.minestom.server.network.PlayerProvider;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.play.ClientEntityActionPacket;
import net.minestom.server.network.packet.client.play.ClientInputPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket;
import net.minestom.server.network.packet.client.play.ClientUseItemPacket;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs the echo fix by wrapping Minestom's packet listeners
 * and setting the player provider to {@link EchoFixPlayer}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * MinecraftServer server = MinecraftServer.init();
 * EchoFix.install();
 * }</pre>
 */
public final class EchoFix {

    private static final AtomicBoolean installed = new AtomicBoolean(false);

    private EchoFix() {}

    /**
     * Install the echo fix.
     * <p>
     * This does two things:
     * <ol>
     *   <li>Sets the player provider to {@link EchoFixPlayer}</li>
     *   <li>Wraps the four client input listeners to set a flag on the player,
     *       so {@link EchoFixPlayer} can distinguish client initiated metadata
     *       changes from server initiated ones</li>
     * </ol>
     */
    public static void install() {
        install(EchoFixPlayer::new);
    }

    /**
     * Install with a custom player provider.
     * <p>
     * If the provider does not return an {@link EchoFixPlayer} or subclass,
     * or if a non-EchoFixPlayer provider is set after install, the fix will
     * silently stop working.
     *
     * @param provider player provider that returns an {@link EchoFixPlayer} or subclass
     */
    public static void install(PlayerProvider provider) {
        if (!installed.compareAndSet(false, true)) throw new IllegalStateException("EchoFix is already installed");

        MinecraftServer.getConnectionManager().setPlayerProvider(provider);

        var plm = MinecraftServer.getPacketListenerManager();

        // Sneak (via player input packet)
        plm.setPlayListener(ClientInputPacket.class, (packet, player) -> {
            if (player instanceof EchoFixPlayer efp) efp.setProcessingClientInput(true);
            try {
                PlayerInputListener.listener(packet, player);
            } finally {
                if (player instanceof EchoFixPlayer efp) efp.setProcessingClientInput(false);
            }
        });

        // Sprint
        plm.setPlayListener(ClientEntityActionPacket.class, (packet, player) -> {
            if (player instanceof EchoFixPlayer efp) efp.setProcessingClientInput(true);
            try {
                EntityActionListener.listener(packet, player);
            } finally {
                if (player instanceof EchoFixPlayer efp) efp.setProcessingClientInput(false);
            }
        });

        // Use item (eat, bow, shield)
        plm.setPlayListener(ClientUseItemPacket.class, (packet, player) -> {
            if (player instanceof EchoFixPlayer efp) efp.setProcessingClientInput(true);
            try {
                UseItemListener.useItemListener(packet, player);
            } finally {
                if (player instanceof EchoFixPlayer efp) efp.setProcessingClientInput(false);
            }
        });

        // Release item
        plm.setPlayListener(ClientPlayerActionPacket.class, (packet, player) -> {
            if (player instanceof EchoFixPlayer efp) efp.setProcessingClientInput(true);
            try {
                PlayerActionListener.playerActionListener(packet, player);
            } finally {
                if (player instanceof EchoFixPlayer efp) efp.setProcessingClientInput(false);
            }
        });
    }

    /**
     * Wrap a custom listener with the echo fix (client input) flag.
     *
     * @param packetClass the packet class to wrap
     * @param consumer the listener to wrap
     * @param <T> the packet type
     */
    public static <T extends ClientPacket> void wrapListener(
            Class<T> packetClass, PacketPlayListenerConsumer<@NotNull T> consumer) {
        MinecraftServer.getPacketListenerManager().setPlayListener(packetClass, (packet, player) -> {
            if (player instanceof EchoFixPlayer efp) efp.setProcessingClientInput(true);
            try {
                consumer.accept(packet, player);
            } finally {
                if (player instanceof EchoFixPlayer efp) efp.setProcessingClientInput(false);
            }
        });
    }
}