package net.vulkanium.shaderpack.materialmap;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/**
 * Parses shaderpack {@code block.properties} and builds a
 * {@link BlockState} → {@code int} material-ID mapping.
 *
 * <h3>File format (OptiFine / Iris compatible)</h3>
 * <pre>
 *   block.10=minecraft:stone minecraft:granite
 *   block.32000=water flowing_water
 *   block.10009=oak_leaves:persistent=false birch_leaves:persistent=false
 * </pre>
 *
 * <p>First mapping wins: if a block state appears in multiple {@code block.N}
 * entries, the first one encountered is used.  Lines are processed in file
 * order.  Comments start with {@code #}; line continuations use trailing
 * backslash ({@code \}).</p>
 *
 * <p>Thread safety: the mapping is written once by the main thread
 * (during shaderpack load) and then read from chunk-build worker threads.
 * The field is {@code volatile} to ensure visibility.</p>
 */
public final class BlockPropertyIdMap {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/BlockIdMap");

    /** Immutable after {@link #load}; read by chunk-build workers. */
    private static volatile Object2IntMap<BlockState> blockStateIds;

    // ────────────────────────────────────────────────────────────────

    /**
     * Parse {@code block.properties} content and build the global mapping.
     *
     * @param content raw text of {@code shaders/block.properties} (may be null)
     */
    public static void load(String content) {
        if (content == null || content.isBlank()) {
            LOGGER.info("No block.properties — all terrain gets mc_Entity = -1");
            blockStateIds = null;
            return;
        }

        Object2IntMap<BlockState> map = new Object2IntOpenHashMap<>();
        map.defaultReturnValue(-1);

        int totalEntries = 0;

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            StringBuilder continuation = new StringBuilder();
            String rawLine;

            while ((rawLine = reader.readLine()) != null) {
                String line = rawLine.trim();

                // Skip empty, comments, preprocessor directives
                if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '!') {
                    continue;
                }

                // Handle backslash line continuation
                if (line.endsWith("\\")) {
                    continuation.append(line, 0, line.length() - 1).append(' ');
                    continue;
                }
                if (continuation.length() > 0) {
                    continuation.append(line);
                    line = continuation.toString().trim();
                    continuation.setLength(0);
                }

                // Split key=value on first '='
                int eq = line.indexOf('=');
                if (eq < 0) continue;

                String key   = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();

                // Only process "block.N" keys
                if (!key.startsWith("block.")) continue;
                // Skip render-type overrides like "layer.translucent"
                int blockId;
                try {
                    blockId = Integer.parseInt(key.substring("block.".length()));
                } catch (NumberFormatException e) {
                    continue;
                }

                // Each value is space-separated block entries
                for (String entry : value.split("\\s+")) {
                    if (entry.isEmpty()) continue;
                    totalEntries += addBlockStates(entry, map, blockId);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to read block.properties", e);
            blockStateIds = null;
            return;
        }

        blockStateIds = map;
        LOGGER.info("Loaded block.properties: {} block-state mappings from {} entries",
                map.size(), totalEntries);
    }

    // ────────────────────────────────────────────────────────────────

    /**
     * Resolve a single block entry and add its states to the map.
     *
     * <p>Entry syntax:
     * {@code [namespace:]blockname[:prop1=val1[:prop2=val2...]]}
     * <br>Tag entries starting with {@code %} are currently ignored.</p>
     *
     * @return number of states added
     */
    private static int addBlockStates(String entry, Object2IntMap<BlockState> map, int blockId) {
        // Skip tag entries (%minecraft:logs etc.)
        if (entry.startsWith("%")) return 0;

        // --- Parse namespace, block name, and optional state predicates ---
        String[] parts = entry.split(":");
        String namespace;
        String name;
        int predicateStart;

        if (parts.length == 1) {
            // "stone" → minecraft:stone
            namespace = "minecraft";
            name = parts[0];
            predicateStart = 1;
        } else if (parts[1].contains("=")) {
            // "stone:type=smooth" → minecraft:stone with predicate
            namespace = "minecraft";
            name = parts[0];
            predicateStart = 1;
        } else {
            // "minecraft:stone" or "minecraft:stone:type=smooth"
            namespace = parts[0];
            name = parts[1];
            predicateStart = 2;
        }

        // Collect state predicates
        Map<String, String> predicates = Collections.emptyMap();
        if (predicateStart < parts.length) {
            predicates = new HashMap<>();
            for (int i = predicateStart; i < parts.length; i++) {
                int eqIdx = parts[i].indexOf('=');
                if (eqIdx > 0) {
                    predicates.put(parts[i].substring(0, eqIdx),
                                   parts[i].substring(eqIdx + 1));
                }
            }
        }

        // --- Resolve block from registry ---
        ResourceLocation blockKey = new ResourceLocation(namespace, name);
        ResourceLocation defaultKey = BuiltInRegistries.BLOCK.getDefaultKey();
        Block block = BuiltInRegistries.BLOCK.get(blockKey);

        // If we got the default block (air) but the key isn't "air", it's unknown
        if (block == BuiltInRegistries.BLOCK.get(defaultKey)
                && !blockKey.equals(defaultKey)) {
            LOGGER.trace("Unknown block in block.properties: {} (ID {})", blockKey, blockId);
            return 0;
        }

        // --- Add matching states ---
        int count = 0;
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            if (!predicates.isEmpty() && !matchesPredicates(state, predicates)) {
                continue;
            }
            // First mapping wins (putIfAbsent)
            if (!map.containsKey(state)) {
                map.put(state, blockId);
                count++;
            }
        }
        return count;
    }

    /**
     * Check whether a block state satisfies all key=value predicates.
     */
    private static boolean matchesPredicates(BlockState state, Map<String, String> predicates) {
        for (Map.Entry<String, String> pred : predicates.entrySet()) {
            Property<?> prop = state.getBlock().getStateDefinition().getProperty(pred.getKey());
            if (prop == null) return false;

            Optional<?> expected = prop.getValue(pred.getValue());
            if (expected.isEmpty()) return false;

            if (!state.getValue(prop).equals(expected.get())) return false;
        }
        return true;
    }

    // ────────── Runtime access ──────────

    /**
     * Look up the shaderpack material ID for a block state.
     *
     * @return material ID (≥ 0) or {@code -1} if unmapped
     */
    public static short resolveBlockId(BlockState state) {
        Object2IntMap<BlockState> ids = blockStateIds;
        if (ids == null) return -1;
        return (short) ids.getOrDefault(state, -1);
    }

    /** {@code true} after a successful {@link #load}. */
    public static boolean isLoaded() {
        return blockStateIds != null;
    }

    /** Clears the mapping (called when shaderpack is unloaded). */
    public static void clear() {
        blockStateIds = null;
    }
}
