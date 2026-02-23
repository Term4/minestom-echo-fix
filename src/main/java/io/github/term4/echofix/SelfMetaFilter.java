package io.github.term4.echofix;

import net.minestom.server.entity.Metadata;
import net.minestom.server.entity.MetadataDef;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Configures which metadata entries are suppressed to the player it belongs to.
 * <p>
 * Modern Minecraft clients locally predict state changes
 * (e.g. sneaking, sprinting, crawling, item use) before the server confirms them.
 * When the server broadcasts the data back to all viewers (including
 * the player who initiated the change), the client receives duplicate or outdated data
 * and re-applies it, causing a one tick stutter.
 * <p>
 * This filter strips problematic entries from self-bound
 * metadata packets. Viewers always receive the full, unmodified packet.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Use the default filter
 * SelfMetadataFilter filter = SelfMetadataFilter.defaultPlayerFilter();
 *
 * // Or build a custom filter (useful for cross version servers)
 * SelfMetadataFilter filter = new SelfMetadataFilter()
 *         .suppressBit((MetadataDef.Entry.BitMask) MetadataDef.IS_CROUCHING)
 *         .suppressIndex(MetadataDef.POSE);
 * }</pre>
 */
public final class SelfMetaFilter {
    private boolean filterElytraStop = false;

    /**
     * Creates an empty filter. Use {@link #suppressBit}, {@link #suppressIndex},
     * and {@link #suppressAttributes(boolean)} to configure, or use
     * {@link #defaultPlayerFilter()} for a pre-configured filter.
     */
    public SelfMetaFilter() {  }

    private final Set<Integer> suppressedIndices = new HashSet<>();
    private final Map<Integer, Byte> suppressedBits = new HashMap<>();
    private boolean suppressAttributes = false;

    /**
     * Suppress an entire metadata index from being echoed back to self
     * during client input processing.
     * <p>
     * Use this for indices where the client always knows the full value,
     * such as {@link MetadataDef#POSE} or
     * {@link MetadataDef.LivingEntity#LIVING_ENTITY_FLAGS}.
     *
     * @param entry the metadata entry whose index should be suppressed
     * @return this filter for chaining
     */
    public SelfMetaFilter suppressIndex(MetadataDef.Entry<?> entry) {
        suppressedIndices.add(entry.index());
        return this;
    }

    /**
     * Suppress specific bits within a flag byte from being echoed back to self.
     * <p>
     * Other bits at the same index will pass through unmodified. This is used
     * for index 0 (entity flags) where some bits are predicted locally
     * (crouching, sprinting) and others are determined by the server (on fire,
     * invisible, glowing, elytra flying).
     *
     * @param entry the bit mask entry to suppress
     * @return this filter for chaining
     */
    public SelfMetaFilter suppressBit(MetadataDef.Entry.BitMask entry) {
        int idx = entry.index();
        byte existing = suppressedBits.getOrDefault(idx, (byte) 0);
        suppressedBits.put(idx, (byte) (existing | entry.bitMask()));
        return this;
    }

    /**
     * Suppress {@code EntityAttributesPacket} echoes.
     * <p>
     * When a player starts or stops sprinting (or otherwise updates certain attributes locally),
     * the server sends an attribute update for movement speed.
     * The client already adjusted its predicted attribute locally,
     * so this update causes the same one tick stutter seen with certain metadata.
     *
     * @param suppress true to suppress attribute echoes
     * @return this filter for chaining
     */
    public SelfMetaFilter suppressAttributes(boolean suppress) {
        this.suppressAttributes = suppress;
        return this;
    }

    /**
     * When true prevents the server from signalling players to stop flying.
     *
     * @param filter When true players will get stuck flying until they relog.
     * @return
     */
    public SelfMetaFilter filterElytraStop(boolean filter) {
        this.filterElytraStop = filter;
        return this;
    }

    /**
     * Returns whether stop flying with elytra is being filtered.
     */
    public boolean filterElytraStop() {
        return this.filterElytraStop;
    }

    /**
     * Returns whether attribute packet echoes should be suppressed.
     *
     * @return true if attribute packet echoes should be suppressed
     */
    public boolean suppressAttributes() {
        return suppressAttributes;
    }

    /**
     * Filter a metadata entry map, stripping preconfigured data.
     * <p>
     * For bit-suppressed indices, only the specified bits are removed from
     * the flag byte; remaining bits pass through.
     * For fully suppressed indices, the entire entry is removed.
     *
     * @param entries the original metadata entries from the packet
     * @return a filtered copy if any entries were modified, or {@code null}
     *         if no filtering was needed (the original packet can be sent as-is)
     */
    public Map<Integer, Metadata.Entry<?>> filter(Map<Integer, Metadata.Entry<?>> entries) {
        Map<Integer, Metadata.Entry<?>> result = null;
        boolean modified = false;

        for (var e : entries.entrySet()) {
            int idx = e.getKey();

            // Check for bit suppression
            Byte suppressMask = suppressedBits.get(idx);
            if (suppressMask != null && e.getValue().value() instanceof Byte flags) {
                byte serverOnly = (byte) (flags & ~suppressMask);
                if (!modified) {
                    result = new HashMap<>(entries);
                    modified = true;
                }
                if (serverOnly != 0) {
                    result.put(idx, Metadata.Byte(serverOnly));
                } else {
                    result.remove(idx);
                }
            }
            // Check for full index suppression
            else if (suppressedIndices.contains(idx)) {
                if (!modified) {
                    result = new HashMap<>(entries);
                    modified = true;
                }
                result.remove(idx);
            }
        }

        return result;
    }

    /**
     * Creates the default filter for player entities.
     * <p>
     * Suppresses:
     * <ul>
     *   <li>Crouching bit (0x02)</li>
     *   <li>Sprinting bit (0x08)</li>
     *   <li>Pose index</li>
     *   <li>Living entity flags index (eating, blocking, bow draw)</li>
     *   <li>Entity attribute echoes</li>
     * </ul>
     * <p>
     * Non-predicted bits always pass through (e.g. on fire, glowing)
     *
     * @return a new default filter
     */
    public static SelfMetaFilter defaultPlayerFilter() {
        return new SelfMetaFilter()
                .suppressBit((MetadataDef.Entry.BitMask) MetadataDef.IS_CROUCHING)
                .suppressBit((MetadataDef.Entry.BitMask) MetadataDef.IS_SPRINTING)
                .suppressIndex(MetadataDef.POSE)
                .suppressIndex(MetadataDef.LivingEntity.LIVING_ENTITY_FLAGS)
                .suppressAttributes(true);
    }
}
