package io.github.term4.echofix;

import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
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
        EchoFix.install();

        // Create the instance (world)
        InstanceManager instanceManager = MinecraftServer.getInstanceManager();
        InstanceContainer instanceContainer = instanceManager.createInstanceContainer();

        // Generate the world & add lighting
        instanceContainer.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.WATER));
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
                    case "sneak" -> {
                        boolean current = player.isSneaking();
                        player.setSneaking(!current);
                        sender.sendMessage("sneak → " + !current);
                    }
                    case "sprint" -> {
                        boolean current = player.isSprinting();
                        player.setSprinting(!current);
                        sender.sendMessage("sprint → " + !current);
                    }
                    case "elytra" -> {
                        boolean current = player.isFlyingWithElytra();
                        player.setFlyingWithElytra(!current);
                        sender.sendMessage("elytra → " + !current);
                    }
                    case "hand" -> {
                        player.refreshActiveHand(true, false, false);
                        sender.sendMessage("hand → active");
                        MinecraftServer.getSchedulerManager().scheduleTask(() -> {
                            player.refreshActiveHand(false, false, false);
                            sender.sendMessage("hand → inactive");
                            return TaskSchedule.stop();
                        }, TaskSchedule.tick(40));
                    }
                    default -> sender.sendMessage("unknown: " + flag.trim()
                            + " (options: sneak, sprint, elytra, hand)");
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

            player.setGameMode(GameMode.CREATIVE);

            player.getInventory().addItemStack(ItemStack.of(Material.SHIELD));
            player.setChestplate(ItemStack.of(Material.ELYTRA));
        });

        // Start the server
        server.start("0.0.0.0", 25566);
    }
}
