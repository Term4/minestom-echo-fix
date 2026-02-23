# minestom-echo-fix

Fixes a visual stutter bug caused by the server echoing client-predicted metadata back to the originating player. Affects **every Minecraft server** — vanilla included.

## The Bug

When a player performs an action like sneaking, the following happens:

1. **Client** predicts the state change instantly (camera drops, hitbox shrinks)
2. **Client** sends input packet to server
3. **Server** updates internal state, broadcasts metadata to all viewers — **including the player who initiated it**
4. **Client** receives "you are sneaking" from the server and **re-applies** it
5. Visible stutter / jitter as the state is applied twice

This echo loop affects:

| Action | Symptom |
|---|---|
| **Sneaking** | Camera/hitbox jitter on toggle |
| **Sprinting** | FOV flicker, speed rubber-banding |
| **Item use** (shield, bow, eating) | Animation restart on rapid clicks |
| **Elytra launch** | Flight animation stutter |
| **Sprint start/stop** | Movement speed attribute re-applied |

Most players experience this as "normal lag" and don't realize it's a fixable bug.

## The Fix

This library provides `EchoFixPlayer`, a drop-in `Player` subclass that intercepts outgoing metadata packets and strips client-predicted entries from the self-bound copy. **Viewers always receive the full, unmodified packet** — only the originating player's copy is filtered.

### What gets filtered (self only):

- **Entity flags (index 0)**: Only the crouching and sprinting bits are stripped. Server-authoritative bits (on fire, invisible, glowing, elytra flying) always pass through.
- **Pose (index 6)**: Fully suppressed during client input (SNEAKING, STANDING transitions).
- **Living entity flags (index 8)**: Hand state (eating, blocking, bow draw) suppressed.
- **EntityAttributesPacket**: Sprint movement speed attribute echo suppressed.

### What's NOT filtered:

- Metadata sent outside client input processing (plugins, commands, scheduled tasks)
- Server-authoritative flags (fire, invisible, glowing)
- Elytra stop (`setFlyingWithElytra(false)`) — this is a server decision (player landed), not a client prediction
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
// One line — use EchoFixPlayer as your player provider
MinecraftServer.getConnectionManager().setPlayerProvider(EchoFixPlayer::new);
```

That's it. Every player will have echo suppression enabled with sensible defaults.

### Extending EchoFixPlayer

If you already have a custom Player subclass, extend `EchoFixPlayer` instead of `Player`:

```java
public class MyPlayer extends EchoFixPlayer {
    public MyPlayer(PlayerConnection connection, GameProfile profile) {
        super(connection, profile);
    }

    // Your custom logic here
}
```

## Server-Driven Overrides

The filter detects client-driven changes by hooking `setSneaking`, `setSprinting`, `setFlyingWithElytra`, and `refreshActiveHand`. If **your code** calls these methods (not triggered by client input), the filter will incorrectly suppress them.

Wrap server-driven state changes in `forceMetadata()`:

```java
EchoFixPlayer player = (EchoFixPlayer) event.getPlayer();

// Force stop sprinting (e.g., after taking damage)
player.forceMetadata(() -> player.setSprinting(false));

// Force sneak state from a plugin
player.forceMetadata(() -> player.setSneaking(true));

// Force elytra stop
player.forceMetadata(() -> player.setFlyingWithElytra(false));
```

> **Note:** Elytra stop (`setFlyingWithElytra(false)`) is automatically detected as server-driven and does NOT need `forceMetadata()`. Minestom calls this from `PlayerPositionListener.refreshOnGround` when the player lands. Only use `forceMetadata()` if you're calling it from your own code and it's being filtered.

## Custom Filters

Customize what's filtered per player:

```java
EchoFixPlayer player = (EchoFixPlayer) event.getPlayer();

// Only suppress sneak echo
player.setSelfMetadataFilter(new SelfMetadataFilter()
        .suppressBit((MetadataDef.Entry.BitMask) MetadataDef.IS_CROUCHING)
        .suppressIndex(MetadataDef.POSE));

// Suppress everything including attributes
player.setSelfMetadataFilter(SelfMetadataFilter.defaultPlayerFilter());

// Disable filtering entirely
player.setSelfMetadataFilter(null);
```

### SelfMetadataFilter API

| Method | Description |
|---|---|
| `suppressBit(BitMask)` | Strip specific bits from a flag byte (e.g., sneak bit from entity flags) |
| `suppressIndex(Entry)` | Strip an entire metadata index (e.g., pose, hand state) |
| `suppressAttributes(boolean)` | Suppress `EntityAttributesPacket` echoes (sprint speed) |
| `defaultPlayerFilter()` | Factory with all standard client-predicted metadata suppressed |
| `filter(Map)` | Apply the filter to a metadata entry map |

## How It Works

```
Client                    Server                    Client (self)       Other Viewers
  |                         |                           |                    |
  |-- [sneak pressed] ---->|                           |                    |
  |   (client predicts     |                           |                    |
  |    sneak locally)      |                           |                    |
  |                        |-- setSneaking(true) ----->|                    |
  |                        |   (echoingSelfInput=true) |                    |
  |                        |                           |                    |
  |                        |-- EntityMetaDataPacket -->| (stripped!)        |
  |                        |-- EntityMetaDataPacket ---|----------------->  | (full)
  |                        |                           |                    |
  |                        |   (echoingSelfInput=false)|                    |
```

The key insight: `setSneaking`, `setSprinting`, etc. are called synchronously during client packet processing. By setting a flag before `super.setSneaking()` and clearing it after, the `sendPacketToViewersAndSelf` override knows to filter.

For elytra, `setFlyingWithElytra(true)` is always client-initiated (start flying), while `setFlyingWithElytra(false)` is always server-initiated (landing detection). So we only filter the `true` case.

## Compatibility

- **Minestom**: Tested with `2026.02.19-1.21.11`. Should work with any build that has the same method signatures for `setSneaking`, `setSprinting`, `setFlyingWithElytra`, `refreshActiveHand`, and `sendPacketToViewersAndSelf`.
- **Minecraft**: The stutter is most visible on 1.21+ clients but the fix is safe for all versions.
- **ViaVersion**: Fully compatible. The fix operates at the metadata packet level, before any protocol translation.

## FAQ

**Q: Does this affect how other players see me?**
No. Viewers always receive the full, unmodified metadata packet. Only the self-bound copy is filtered.

**Q: What if a plugin calls `setSneaking()` and the echo is suppressed?**
Wrap it in `forceMetadata()`. See [Server-Driven Overrides](#server-driven-overrides).

**Q: Is there any performance overhead?**
Negligible. The filter only runs on self-targeted metadata packets that contain client-authoritative indices. It's a HashMap lookup and a few bitwise operations — far cheaper than the packets themselves.

**Q: Why not fix this in Minestom itself?**
That's the plan. This library exists as a standalone fix until Minestom merges a native solution. See the [Minestom issue](#) (link TBD).

## License

MIT
