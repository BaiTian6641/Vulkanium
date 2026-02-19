package net.vulkanium.api;

/**
 * Defines the phases of Vulkanium's render pipeline where {@link WorldModule}s
 * can inject work.
 *
 * <p>Phases execute in the order defined here within each frame. Modules register
 * for specific phases and receive callbacks via {@link WorldModule#onPhase}.</p>
 *
 * <h3>Frame Phase Order</h3>
 * <pre>
 *   ┌─ FRAME_SETUP ────────────────┐  Uniform updates, TLAS build
 *   │                               │
 *   ├─ SHADOW_TERRAIN ─────────────┤  Shadow map: terrain pass
 *   ├─ SHADOW_ENTITIES ────────────┤  Shadow map: entities
 *   │                               │
 *   ├─ PRE_TERRAIN ────────────────┤  Before main terrain (e.g., TLAS update)
 *   ├─ TERRAIN_SOLID ──────────────┤  Opaque terrain geometry
 *   ├─ TERRAIN_CUTOUT ─────────────┤  Cutout terrain (leaves, flowers)
 *   ├─ TERRAIN_TRANSLUCENT ────────┤  Translucent terrain (water, ice)
 *   ├─ POST_TERRAIN ───────────────┤  After terrain, before entities
 *   │                               │
 *   ├─ ENTITIES ───────────────────┤  Entity rendering
 *   ├─ BLOCK_ENTITIES ─────────────┤  Tile entity rendering
 *   ├─ PARTICLES ──────────────────┤  Particle rendering
 *   ├─ SKY ────────────────────────┤  Sky, sun, moon, stars
 *   ├─ HAND ───────────────────────┤  First-person hand/item
 *   │                               │
 *   ├─ RT_DISPATCH ────────────────┤  Ray tracing dispatch (if RT active)
 *   ├─ RT_DENOISE ─────────────────┤  Ray tracing denoising pass
 *   │                               │
 *   ├─ COMPOSITE_0 .. COMPOSITE_15 ┤  16 composite passes (shader pack)
 *   ├─ DEFERRED_0 .. DEFERRED_15 ──┤  16 deferred passes (shader pack)
 *   ├─ POST_COMPOSITE ─────────────┤  After all composites (bloom, tonemap)
 *   │                               │
 *   ├─ COMPUTE_ASYNC ──────────────┤  Async compute (not frame-critical)
 *   └─ FRAME_CLEANUP ─────────────┘  End-of-frame cleanup
 * </pre>
 *
 * <h3>Phase Categories</h3>
 * <ul>
 *   <li><b>Setup/Cleanup:</b> FRAME_SETUP, FRAME_CLEANUP — resource management</li>
 *   <li><b>Shadow:</b> SHADOW_TERRAIN, SHADOW_ENTITIES — shadow map rendering</li>
 *   <li><b>Geometry:</b> *_TERRAIN, ENTITIES, BLOCK_ENTITIES, PARTICLES, SKY, HAND — G-buffer fill</li>
 *   <li><b>Ray Tracing:</b> RT_DISPATCH, RT_DENOISE — requires RT Tier 1+</li>
 *   <li><b>Post-Process:</b> COMPOSITE_*, DEFERRED_*, POST_COMPOSITE — screen-space effects</li>
 *   <li><b>Compute:</b> COMPUTE_ASYNC — non-rendering GPU work (data processing)</li>
 * </ul>
 */
public enum ModulePhase {

    // ── Frame Lifecycle ──
    FRAME_SETUP(Category.LIFECYCLE, 0),
    FRAME_CLEANUP(Category.LIFECYCLE, 1000),

    // ── Shadow Passes ──
    SHADOW_TERRAIN(Category.SHADOW, 100),
    SHADOW_ENTITIES(Category.SHADOW, 101),

    // ── Geometry Passes ──
    PRE_TERRAIN(Category.GEOMETRY, 200),
    TERRAIN_SOLID(Category.GEOMETRY, 210),
    TERRAIN_CUTOUT(Category.GEOMETRY, 220),
    TERRAIN_TRANSLUCENT(Category.GEOMETRY, 230),
    POST_TERRAIN(Category.GEOMETRY, 240),
    ENTITIES(Category.GEOMETRY, 300),
    BLOCK_ENTITIES(Category.GEOMETRY, 310),
    PARTICLES(Category.GEOMETRY, 320),
    SKY(Category.GEOMETRY, 400),
    HAND(Category.GEOMETRY, 410),

    // ── Shadow Prep ──
    PRE_SHADOW(Category.SHADOW, 99),

    // ── Ray Tracing ──
    RT_DISPATCH(Category.RAY_TRACING, 500),
    RT_DENOISE(Category.RAY_TRACING, 510),
    POST_RT(Category.RAY_TRACING, 520),

    // ── Post-Processing ──
    COMPOSITE_0(Category.POST_PROCESS, 600),
    COMPOSITE_1(Category.POST_PROCESS, 601),
    COMPOSITE_2(Category.POST_PROCESS, 602),
    COMPOSITE_3(Category.POST_PROCESS, 603),
    COMPOSITE_4(Category.POST_PROCESS, 604),
    COMPOSITE_5(Category.POST_PROCESS, 605),
    COMPOSITE_6(Category.POST_PROCESS, 606),
    COMPOSITE_7(Category.POST_PROCESS, 607),
    COMPOSITE_8(Category.POST_PROCESS, 608),
    COMPOSITE_9(Category.POST_PROCESS, 609),
    COMPOSITE_10(Category.POST_PROCESS, 610),
    COMPOSITE_11(Category.POST_PROCESS, 611),
    COMPOSITE_12(Category.POST_PROCESS, 612),
    COMPOSITE_13(Category.POST_PROCESS, 613),
    COMPOSITE_14(Category.POST_PROCESS, 614),
    COMPOSITE_15(Category.POST_PROCESS, 615),
    DEFERRED_0(Category.POST_PROCESS, 700),
    DEFERRED_1(Category.POST_PROCESS, 701),
    DEFERRED_2(Category.POST_PROCESS, 702),
    DEFERRED_3(Category.POST_PROCESS, 703),
    DEFERRED_4(Category.POST_PROCESS, 704),
    DEFERRED_5(Category.POST_PROCESS, 705),
    DEFERRED_6(Category.POST_PROCESS, 706),
    DEFERRED_7(Category.POST_PROCESS, 707),
    DEFERRED_8(Category.POST_PROCESS, 708),
    DEFERRED_9(Category.POST_PROCESS, 709),
    DEFERRED_10(Category.POST_PROCESS, 710),
    DEFERRED_11(Category.POST_PROCESS, 711),
    DEFERRED_12(Category.POST_PROCESS, 712),
    DEFERRED_13(Category.POST_PROCESS, 713),
    DEFERRED_14(Category.POST_PROCESS, 714),
    DEFERRED_15(Category.POST_PROCESS, 715),
    POST_COMPOSITE(Category.POST_PROCESS, 800),
    DEFERRED_COMPOSITE(Category.POST_PROCESS, 810),
    POST_PROCESS_COMPOSITE(Category.POST_PROCESS, 820),
    POST_PROCESS_FINAL(Category.POST_PROCESS, 900),

    // ── Async Compute ──
    COMPUTE_ASYNC(Category.COMPUTE, 900);

    public enum Category {
        LIFECYCLE,
        SHADOW,
        GEOMETRY,
        RAY_TRACING,
        POST_PROCESS,
        COMPUTE
    }

    private final Category category;
    private final int sortOrder;

    ModulePhase(Category category, int sortOrder) {
        this.category = category;
        this.sortOrder = sortOrder;
    }

    /** The category this phase belongs to. */
    public Category getCategory() { return category; }

    /** Sort order for phase execution (lower = earlier). */
    public int getSortOrder() { return sortOrder; }

    /** Whether this phase requires ray tracing support. */
    public boolean requiresRayTracing() {
        return category == Category.RAY_TRACING;
    }

    /** Whether this phase runs on the compute queue (vs graphics queue). */
    public boolean isComputePhase() {
        return category == Category.COMPUTE;
    }

    /** Get a composite phase by index (0–15). */
    public static ModulePhase composite(int index) {
        if (index < 0 || index > 15) throw new IndexOutOfBoundsException("Composite index: " + index);
        return values()[COMPOSITE_0.ordinal() + index];
    }

    /** Get a deferred phase by index (0–15). */
    public static ModulePhase deferred(int index) {
        if (index < 0 || index > 15) throw new IndexOutOfBoundsException("Deferred index: " + index);
        return values()[DEFERRED_0.ordinal() + index];
    }
}
