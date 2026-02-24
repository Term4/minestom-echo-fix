# minestom-echo-fix

Fixes a visual stutter bug caused by the server echoing client-predicted metadata back to the originating player. Affects **every Minecraft server** including vanilla.

## The Bug

When a high ping player performs certain actions (sneaking, sprinting, using items) this happens:

1. **Client** predicts the state change locally
2. **Client** sends packet (metadata, attribute packet) to server
3. **Server** updates internal state & broadcasts metadata/packet to all viewers **including the player who initiated it**
4. **Client** receives "you are sneaking" from the server and **re-applies** it
5. Visible stutter when the state is applied twice, or an outdated state is re-applied

This echo affects:

| Action | Symptom                                            |
|---|----------------------------------------------------|
| **Sneaking** | Camera stutters on toggle                          |
| **Sprinting** | FOV flicker, speed change for 1 tick               |
| **Item use** (shield, bow, eating) | Animation replays on rapid clicks                  |
| **Elytra launch** | Flight animation stutter / stale animation replays |
| **Sprint start/stop** | Movement speed attribute re-applied                |

Most players experience this as normal lag, but it's a fixable bug (that's been seen
in the game for over a decade)

## The Fix

This library provides `EchoFix.install()`, which wraps Minestom's client input packet
listeners to detect when metadata changes originate from client input.
Self-bound metadata packets (that result from client input and are predicted locally)
are filtered to remove the stutter. **Viewers always receive the full, unmodified packet.**

### What gets filtered:

- **Entity flags (index 0)**: Only the crouching and sprinting bits are stripped.
Server-authoritative bits (on fire, invisible, glowing, elytra flying) always pass through.
- **Pose (index 6)**: Fully suppressed (SNEAKING, STANDING, SWIMMING transitions).
- **Living entity flags (index 8)**: Hand state (eating, blocking, bow draw) suppressed.
- **EntityAttributesPacket**: Sprint movement speed attribute echo suppressed.

### What's NOT filtered:

- Anything that isn't initiated from the client
- All metadata sent to other viewers

## Quick Start

### Dependency

**Gradle (Jitpack):**
```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.term4:minestom-echo-fix:1.0.0'
}
```

### Usage

```java
MinecraftServer server = MinecraftServer.init();
EchoFix.install();
```

### Custom Player Provider

If you have a custom Player subclass, extend `EchoFixPlayer` and pass your provider:

```java
public class MyPlayer extends EchoFixPlayer {
    public MyPlayer(PlayerConnection connection, GameProfile profile) {
        super(connection, profile);
    }
}

// Install with your custom provider
EchoFix.install(MyPlayer::new);
```

## How It Works

`EchoFix.install()` wraps four Minestom packet listeners that handle client input:

- `ClientInputPacket` — sneak
- `ClientEntityActionPacket` — sprint, elytra start, release item
- `ClientUseItemPacket` — eat, bow, shield

Each wrapper sets a `processingClientInput` flag on the player before calling the original listener, and clears it after:

```
Client                    Server                    Client (self)       Other Viewers
  |                        |                            |                    |
  |-- [sneak pressed] ---->|                            |                    |
  |   (client predicts     |                            |                    |
  |    sneak locally)      |                            |                    |
  |                        |-- wrapper sets flag ------>|                    |
  |                        |-- setSneaking(true)        |                    |
  |                        |-- sendPacketToViewersAndSelf                    |
  |                        |      flag is true:         |                    |
  |                        |-- EntityMetaDataPacket --> | (stripped)         |
  |                        |-- EntityMetaDataPacket --- |----------------->  | (full)
  |                        |-- wrapper clears flag      |                    |
```

Because the flag is only set during client packet processing,
server-driven calls (plugins, commands, schedulers, Minestom internals)
always pass through unfiltered. No special handling needed.

## Custom Filters

Customize what's filtered per player:

```java
EchoFixPlayer player = (EchoFixPlayer) event.getPlayer();

// Only suppress sneak echo
player.setSelfMetaFilter(new SelfMetaFilter()
        .suppressBit((MetadataDef.Entry.BitMask) MetadataDef.IS_CROUCHING)
        .suppressIndex(MetadataDef.POSE));

// Suppress everything including attributes
player.setSelfMetaFilter(SelfMetaFilter.defaultPlayerFilter());

// Disable filtering entirely
player.setSelfMetaFilter(null);
```

### SelfMetaFilter API

| Method | Description |
|---|---|
| `suppressBit(BitMask)` | Strip specific bits from a flag byte (e.g., sneak bit from entity flags) |
| `suppressIndex(Entry)` | Strip an entire metadata index (e.g., pose, hand state) |
| `suppressAttributes(boolean)` | Suppress `EntityAttributesPacket` echoes (sprint speed) |
| `defaultPlayerFilter()` | Factory with all standard client-predicted metadata suppressed |
| `filter(Map)` | Apply the filter to a metadata entry map |

## Wrapping Custom Listeners

If you need to replace one of the four wrapped listeners with your own logic, use `EchoFix.wrapListener()` to preserve the echo fix flag:

```java
EchoFix.wrapListener(ClientInputPacket.class, (packet, player) -> {
    // Your custom logic here
    PlayerInputListener.listener(packet, player);
});
```

Calling `setPlayListener` directly on the wrapped packet types will overwrite the echo fix wrapper.

## Compatibility

- **Minestom**: Tested with `2026.02.19-1.21.11`. Should work with any build that has the same listener classes and method signatures.
- **Minecraft**: The stutter is most visible on 1.21+ clients but the fix is safe for all versions.
- **ViaVersion**: Fully compatible. The fix operates at the metadata packet level, before any protocol translation or proxy.

## Questions

**Q: Does this affect how players see other players?**
No. Viewers always receive the full, unmodified metadata packet. Only the self-bound copy is filtered.

**Q: What if a plugin calls `setSneaking()` — will it be suppressed?**
No. Plugin calls happen outside client packet processing, so the flag is false and the packet passes through unfiltered.

**Q: Is there any performance overhead?**
Not really. The filter only runs on self-targeted metadata packets during client input processing.
It's a HashMap lookup and a few bitwise operations.

**Q: Why not fix this in Minestom itself?**
Hopefully it is, but for now this works perfectly fine.

## License

MIT