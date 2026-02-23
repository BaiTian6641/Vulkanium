package net.vulkanium.rt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Block material property table for ray tracing.
 *
 * <p>Maps Minecraft block IDs to PBR material properties used by closest-hit
 * shaders. Each material defines how the block interacts with light rays:
 * reflectance, roughness, emission, transparency, and IOR.</p>
 *
 * <h3>Material Properties</h3>
 * <ul>
 *   <li><b>albedo</b> — Base color from block texture atlas (sampled at hit)</li>
 *   <li><b>roughness</b> — Surface micro-roughness (0=mirror, 1=diffuse)</li>
 *   <li><b>metallic</b> — Metallic factor (0=dielectric, 1=metal)</li>
 *   <li><b>emission</b> — Self-illumination strength (0=none, 15=max)</li>
 *   <li><b>ior</b> — Index of refraction for translucent blocks (water=1.33, glass=1.5)</li>
 *   <li><b>opacity</b> — Transmission factor (1=opaque, 0=fully transparent)</li>
 *   <li><b>subsurface</b> — Subsurface scattering for organic materials (leaves, skin)</li>
 * </ul>
 *
 * <h3>SSBO Layout</h3>
 * <pre>
 *   struct Material {
 *       float roughness;
 *       float metallic;
 *       float emission;
 *       float ior;
 *       float opacity;
 *       float subsurface;
 *       uint flags;      // bitfield: isWater, isGlass, isLeaf, isEmissive
 *       uint padding;
 *   }; // 32 bytes per material
 * </pre>
 *
 * <h3>Integration with Shader Packs</h3>
 * <p>When a shader pack provides PBR data (via labPBR or similar), those values
 * override the defaults in this table. The material SSBO is uploaded once and
 * updated when the shader pack changes.</p>
 */
public class MaterialTable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Materials");

    /** Material struct size in bytes */
    public static final int MATERIAL_BYTES = 32;

    /** Maximum distinct materials (Minecraft block state count) */
    public static final int MAX_MATERIALS = 4096;

    // Material flags
    public static final int FLAG_EMISSIVE = 1;
    public static final int FLAG_WATER = 2;
    public static final int FLAG_GLASS = 4;
    public static final int FLAG_LEAF = 8;
    public static final int FLAG_ICE = 16;
    public static final int FLAG_METAL = 32;
    public static final int FLAG_SUBSURFACE = 64;
        public static final int FLAG_CUTOUT = 128;
        public static final int FLAG_TRANSLUCENT = 256;
        public static final int FLAG_FOLIAGE = 512;

    /**
     * PBR material definition.
     */
    public record Material(
            float roughness,
            float metallic,
            float emission,
            float ior,
            float opacity,
            float subsurface,
            int flags
    ) {
        public static final Material DEFAULT = new Material(
                0.8f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0
        );

        public static final Material WATER = new Material(
                0.02f, 0.0f, 0.0f, 1.333f, 0.3f, 0.0f, FLAG_WATER
        );

        public static final Material GLASS = new Material(
                0.01f, 0.0f, 0.0f, 1.5f, 0.1f, 0.0f, FLAG_GLASS
        );

        public static final Material GLOWSTONE = new Material(
                0.9f, 0.0f, 15.0f, 1.0f, 1.0f, 0.0f, FLAG_EMISSIVE
        );

        public static final Material IRON_BLOCK = new Material(
                0.3f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, FLAG_METAL
        );

        public static final Material GOLD_BLOCK = new Material(
                0.2f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, FLAG_METAL
        );

        public static final Material LEAVES = new Material(
                0.9f, 0.0f, 0.0f, 1.0f, 0.7f, 0.5f, FLAG_LEAF | FLAG_SUBSURFACE | FLAG_CUTOUT | FLAG_FOLIAGE
        );

        public static final Material FOLIAGE_CUTOUT = new Material(
                0.92f, 0.0f, 0.0f, 1.0f, 0.45f, 0.55f, FLAG_CUTOUT | FLAG_FOLIAGE | FLAG_SUBSURFACE
        );

        public static final Material ICE = new Material(
                0.05f, 0.0f, 0.0f, 1.31f, 0.5f, 0.3f, FLAG_ICE
        );
    }

    private final Material[] materials = new Material[MAX_MATERIALS];
    private final Map<String, Integer> nameToId = new HashMap<>();
    private int nextId = 0;

    public MaterialTable() {
        // Fill with defaults
        for (int i = 0; i < MAX_MATERIALS; i++) {
            materials[i] = Material.DEFAULT;
        }
    }

    /**
     * Populates the table with default Minecraft block materials.
     */
    public void buildDefaultTable() {
        registerMaterial("minecraft:water", Material.WATER);
        registerMaterial("minecraft:glass", Material.GLASS);
        registerMaterial("minecraft:glass_pane", Material.GLASS);
        registerMaterial("minecraft:white_stained_glass", Material.GLASS);
        registerMaterial("minecraft:tinted_glass", new Material(
                0.02f, 0.0f, 0.0f, 1.5f, 0.2f, 0.0f, FLAG_GLASS | FLAG_TRANSLUCENT));
        registerMaterial("minecraft:glowstone", Material.GLOWSTONE);
        registerMaterial("minecraft:sea_lantern", Material.GLOWSTONE);
        registerMaterial("minecraft:shroomlight", Material.GLOWSTONE);
        registerMaterial("minecraft:iron_block", Material.IRON_BLOCK);
        registerMaterial("minecraft:gold_block", Material.GOLD_BLOCK);
        registerMaterial("minecraft:copper_block", new Material(
                0.35f, 0.9f, 0.0f, 1.0f, 1.0f, 0.0f, FLAG_METAL));
        registerMaterial("minecraft:diamond_block", new Material(
                0.1f, 0.0f, 0.0f, 2.42f, 0.9f, 0.0f, FLAG_GLASS));
        registerMaterial("minecraft:emerald_block", new Material(
                0.1f, 0.0f, 0.0f, 1.57f, 0.8f, 0.0f, FLAG_GLASS));
        registerMaterial("minecraft:ice", Material.ICE);
        registerMaterial("minecraft:packed_ice", Material.ICE);
        registerMaterial("minecraft:blue_ice", Material.ICE);

        // Leaves (all variants)
        for (String type : new String[]{"oak", "spruce", "birch", "jungle",
                "acacia", "dark_oak", "mangrove", "cherry", "azalea"}) {
            registerMaterial("minecraft:" + type + "_leaves", Material.LEAVES);
        }

                // Grass, crops, and flowers (alpha cutout casters)
                registerMaterial("minecraft:grass", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:tall_grass", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:fern", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:large_fern", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:vine", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:weeping_vines", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:twisting_vines", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:sugar_cane", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:bamboo", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:dandelion", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:poppy", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:blue_orchid", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:allium", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:azure_bluet", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:red_tulip", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:orange_tulip", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:white_tulip", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:pink_tulip", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:oxeye_daisy", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:cornflower", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:lily_of_the_valley", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:sunflower", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:lilac", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:rose_bush", Material.FOLIAGE_CUTOUT);
                registerMaterial("minecraft:peony", Material.FOLIAGE_CUTOUT);

        // Emissive blocks
        registerMaterial("minecraft:torch", new Material(
                0.9f, 0.0f, 14.0f, 1.0f, 1.0f, 0.0f, FLAG_EMISSIVE));
        registerMaterial("minecraft:lantern", new Material(
                0.85f, 0.0f, 14.0f, 1.0f, 1.0f, 0.0f, FLAG_EMISSIVE));
        registerMaterial("minecraft:soul_lantern", new Material(
                0.85f, 0.0f, 12.0f, 1.0f, 1.0f, 0.0f, FLAG_EMISSIVE));
        registerMaterial("minecraft:end_rod", new Material(
                0.7f, 0.0f, 14.0f, 1.0f, 1.0f, 0.0f, FLAG_EMISSIVE));
        registerMaterial("minecraft:sea_pickle", new Material(
                0.7f, 0.0f, 11.0f, 1.0f, 1.0f, 0.0f, FLAG_EMISSIVE));
        registerMaterial("minecraft:lava", new Material(
                0.95f, 0.0f, 15.0f, 1.0f, 1.0f, 0.0f, FLAG_EMISSIVE));
        registerMaterial("minecraft:redstone_lamp", new Material(
                0.7f, 0.0f, 15.0f, 1.0f, 1.0f, 0.0f, FLAG_EMISSIVE));
        registerMaterial("minecraft:jack_o_lantern", new Material(
                0.8f, 0.0f, 15.0f, 1.0f, 1.0f, 0.0f, FLAG_EMISSIVE));

        LOGGER.info("Material table built with {} entries", nextId);
    }

    /**
     * Registers a material by block name.
     */
    public int registerMaterial(String blockName, Material material) {
        int id;
        if (nameToId.containsKey(blockName)) {
            id = nameToId.get(blockName);
        } else {
            id = nextId++;
            nameToId.put(blockName, id);
        }
        if (id < MAX_MATERIALS) {
            materials[id] = material;
        }
        return id;
    }

    /**
     * Gets the material ID for a block.
     */
    public int getMaterialId(String blockName) {
                Integer exact = nameToId.get(blockName);
                if (exact != null) return exact;

                Material inferred = inferMaterial(blockName);
                if (inferred != null) {
                        return registerMaterial(blockName, inferred);
                }
                return 0;
    }

        private Material inferMaterial(String blockName) {
                if (blockName == null) return null;

                if (blockName.contains("leaves") || blockName.contains("azalea")) {
                        return Material.LEAVES;
                }
                if (blockName.contains("grass") || blockName.contains("fern") || blockName.contains("flower")
                                || blockName.contains("tulip") || blockName.contains("daisy") || blockName.contains("vine")
                                || blockName.contains("crop") || blockName.contains("sapling")) {
                        return Material.FOLIAGE_CUTOUT;
                }
                if (blockName.contains("glass") || blockName.contains("ice") || blockName.contains("water")) {
                        return new Material(0.03f, 0.0f, 0.0f, 1.4f, 0.35f, 0.0f, FLAG_TRANSLUCENT);
                }
                if (blockName.contains("lantern") || blockName.contains("torch") || blockName.contains("shroomlight")
                                || blockName.contains("glow") || blockName.contains("magma") || blockName.contains("lava")) {
                        return new Material(0.85f, 0.0f, 12.0f, 1.0f, 1.0f, 0.0f, FLAG_EMISSIVE);
                }
                return null;
        }

    /**
     * Gets a material by ID.
     */
    public Material getMaterial(int id) {
        return id >= 0 && id < MAX_MATERIALS ? materials[id] : Material.DEFAULT;
    }

    /**
     * Packs the entire material table into a byte array for SSBO upload.
     *
     * @return Packed material data (MAX_MATERIALS × MATERIAL_BYTES)
     */
    public byte[] packForGPU() {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(MAX_MATERIALS * MATERIAL_BYTES)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < MAX_MATERIALS; i++) {
            Material m = materials[i];
            buffer.putFloat(m.roughness());
            buffer.putFloat(m.metallic());
            buffer.putFloat(m.emission());
            buffer.putFloat(m.ior());
            buffer.putFloat(m.opacity());
            buffer.putFloat(m.subsurface());
            buffer.putInt(m.flags());
            buffer.putInt(0); // padding
        }

        return buffer.array();
    }

    public int getMaterialCount() { return nextId; }
}
