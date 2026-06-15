package io.github.term4.echofix;

import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
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
        MinecraftServer server = MinecraftServer.init();

        // Enable echo fix
        EchoFix.install();

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
                    case "suppress_self" -> {
                        EchoFixPlayer efp = (EchoFixPlayer) player;
                        efp.suppressSelf(() -> player.setSneaking(true));
                        sender.sendMessage("Server side sneaking");
                    }
                    default -> sender.sendMessage("unknown: " + flag.trim()
                            + " (options: sneak, sprint, elytra, hand)");
                }
            }
        }, ArgumentType.String("flags"));

        cmdManager.register(selfmeta);

        // Crawl test: drops a closed trapdoor in the block at head height (forcing a crawl),
        // then toggles it open/closed on each subsequent run. Opening it pops you back to
        // standing — the exact crawl→stand transition the echo fix now handles via updatePose().
        Command crawl = new Command("crawl");
        crawl.setDefaultExecutor((sender, context) -> {
            if (!(sender instanceof Player player)) return;

            final Pos pos = player.getPosition();
            final int bx = pos.blockX();
            final int by = pos.blockY() + 1; // block occupying the player's head while standing
            final int bz = pos.blockZ();

            final Block current = instanceContainer.getBlock(bx, by, bz);
            if (current.name().endsWith("_trapdoor")) {
                final boolean open = "true".equals(current.getProperty("open"));
                instanceContainer.setBlock(bx, by, bz, current.withProperty("open", open ? "false" : "true"));
                sender.sendMessage("trapdoor → " + (open ? "closed (crawl)" : "open (stand)"));
            } else {
                instanceContainer.setBlock(bx, by, bz, Block.OAK_TRAPDOOR
                        .withProperty("half", "bottom")
                        .withProperty("open", "false"));
                sender.sendMessage("placed a closed trapdoor overhead → crawl. Run /crawl again to open it.");
            }
        });
        cmdManager.register(crawl);

        // Toggle any trapdoor open/closed on right-click (Minestom has no vanilla interaction
        // logic). Hold a non-block item (or empty hand) to avoid placing instead of toggling.
        GlobalEventHandler globalEventHandler = MinecraftServer.getGlobalEventHandler();
        globalEventHandler.addListener(PlayerBlockInteractEvent.class, event -> {
            if (event.getHand() != PlayerHand.MAIN) return;
            final Block block = event.getBlock();
            if (!block.name().endsWith("_trapdoor")) return;

            final boolean open = "true".equals(block.getProperty("open"));
            event.getInstance().setBlock(event.getBlockPosition(), block.withProperty("open", open ? "false" : "true"));
            event.setBlockingItemUse(true); // don't also place a new trapdoor on top
        });

        // Add an event handler to handle player spawning
        globalEventHandler.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            final Player player = event.getPlayer();
            event.setSpawningInstance(instanceContainer);
            player.setRespawnPoint(new Pos(0, 42, 0));

            player.setGameMode(GameMode.CREATIVE);

            player.getInventory().addItemStack(ItemStack.of(Material.SHIELD));
            player.getInventory().addItemStack(ItemStack.of(Material.OAK_TRAPDOOR, 64));
            player.setChestplate(ItemStack.of(Material.ELYTRA));
        });

        // Print test instructions once the player is in-game
        globalEventHandler.addListener(PlayerSpawnEvent.class, event -> {
            if (!event.isFirstSpawn()) return;
            event.getPlayer().sendMessage("Echo fix test server.");
            event.getPlayer().sendMessage("Crawl test: stand still and run /crawl to drop a trapdoor overhead "
                    + "(forces a crawl), then /crawl again to open it and pop back to standing.");
            event.getPlayer().sendMessage("Or place trapdoors yourself and right-click them to open/close.");
        });

        // Start the server
        server.start("0.0.0.0", 25567);
    }
}
