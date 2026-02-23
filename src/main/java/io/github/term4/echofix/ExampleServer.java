package io.github.term4.echofix;

import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.EntityMeta;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;

/**
 * Example Minestom server demonstrating the echo fix library.
 * <p>
 * Run this class to start a local server with echo suppression enabled.
 * Use the {@code /selfmeta} command to test metadata toggles (sneak, sprint, etc.).
 */
public class ExampleServer {

    /** Private constructor to prevent instantiation. */
    private ExampleServer() {
    }
    static void main() {
        // Could wrap these in compatibility methods (mm.legacyProperties(mode: 1.7, 1.8, etc)

        // Enable faster socket writes
        System.setProperty("minestom.new-socket-write-lock", "true");

        // Disable interaction range enforcement (mechanics lib handles reach)
        System.setProperty( "minestom.enforce-entity-interaction-range", "false");

        // Set up required flags for legacy players (prevents visual bugs on older versions)
        System.setProperty("minestom.chunk-view-distance", "12"); // less than 12 causes players to disappear at ~150 block from spawn

        // Set server TPS (default is 20, library should work with any TPS tested up to 1000)
        System.setProperty("minestom.tps", "20");

        // Initialize the server
        //  bungee auth allows 1.7 clients to join (velocity works for all later versions, and a proxy is not required)
        MinecraftServer server = MinecraftServer.init(new Auth.Bungee());

        // Enable echo fix
        MinecraftServer.getConnectionManager().setPlayerProvider(EchoFixPlayer::new);

        // Create the instance (world)
        InstanceManager instanceManager = MinecraftServer.getInstanceManager();
        InstanceContainer instanceContainer = instanceManager.createInstanceContainer();

        // Generate the world & add lighting
        instanceContainer.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.GRASS_BLOCK));
        instanceContainer.setChunkSupplier(LightingChunk::new);

        // Register self-meta debug commands
        CommandManager cmdManager = MinecraftServer.getCommandManager();

        Command selfmeta = new Command("selfmeta");
        selfmeta.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) return;

            String input = context.get("flags");
            String[] flags = input.split(",");

            for (String flag : flags) {
                switch (flag.trim().toLowerCase()) {
                    case "fire" -> {
                        EntityMeta meta = player.getEntityMeta();
                        boolean current = meta.isOnFire();
                        meta.setOnFire(!current);
                        sender.sendMessage("fire → " + !current);
                    }
                    case "sneak" -> {
                        EchoFixPlayer pp = (EchoFixPlayer) player;
                        boolean current = player.isSneaking();
                        pp.forceMetadata(() -> player.setSneaking(!current));
                        sender.sendMessage("sneak → " + !current);
                    }
                    case "sprint" -> {
                        EchoFixPlayer pp = (EchoFixPlayer) player;
                        boolean current = player.isSprinting();
                        pp.forceMetadata(() -> player.setSprinting(!current));
                        sender.sendMessage("sprint → " + !current);
                    }
                    case "invis" -> {
                        boolean current = player.isInvisible();
                        player.setInvisible(!current);
                        sender.sendMessage("invisible → " + !current);
                    }
                    case "glow" -> {
                        boolean current = player.isGlowing();
                        player.setGlowing(!current);
                        sender.sendMessage("glowing → " + !current);
                    }
                    case "elytra" -> {
                        EchoFixPlayer pp = (EchoFixPlayer) player;
                        boolean current = player.isFlyingWithElytra();
                        pp.forceMetadata(() -> player.setFlyingWithElytra(!current));
                        sender.sendMessage("elytra → " + !current);
                    }
                    case "hand" -> {
                        EchoFixPlayer pp = (EchoFixPlayer) player;
                        pp.forceMetadata(() -> player.refreshActiveHand(true, false, false));
                        sender.sendMessage("hand → active");
                        MinecraftServer.getSchedulerManager().scheduleTask(() -> {
                            pp.forceMetadata(() -> player.refreshActiveHand(false, false, false));
                            sender.sendMessage("hand → inactive");
                            return TaskSchedule.stop();
                        }, TaskSchedule.tick(40));
                    }
                    case "pose_sneak" -> {
                        player.setPose(net.minestom.server.entity.EntityPose.SNEAKING);
                        sender.sendMessage("pose → SNEAKING");
                    }
                    case "pose_stand" -> {
                        player.setPose(net.minestom.server.entity.EntityPose.STANDING);
                        sender.sendMessage("pose → STANDING");
                    }
                    case "pose_swim" -> {
                        player.setPose(net.minestom.server.entity.EntityPose.SWIMMING);
                        sender.sendMessage("pose → SWIMMING");
                    }
                    default -> sender.sendMessage("unknown: " + flag.trim()
                            + " (options: fire,sneak,sprint,invis,glow,elytra,hand,pose_sneak,pose_stand,pose_swim)");
                }
            }
        }, ArgumentType.String("flags"));

        cmdManager.register(selfmeta);



        // Add an event handler to handle player spawning
        GlobalEventHandler globalEventHandler = MinecraftServer.getGlobalEventHandler();
        globalEventHandler.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            final Player player = event.getPlayer();
            event.setSpawningInstance(instanceContainer);
            player.setRespawnPoint(new Pos(0, 42, 0));

            player.getInventory().addItemStack(ItemStack.of(Material.SHIELD));
            player.setChestplate(ItemStack.of(Material.ELYTRA));

        });

        // Start the server
        server.start("0.0.0.0", 25566);
    }
}
