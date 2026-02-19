package net.vulkanium.compat;

import net.vulkanium.render.texture.VulkanTexture;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Intercepts GL calls made by Minecraft and mods, redirecting them to Vulkan.
 *
 * <p>Minecraft's core rendering still calls GL functions through
 * {@code GlStateManager} (and through direct LWJGL GL calls from mods).
 * This class intercepts those calls so that Vulkanium can capture the intent
 * and translate to Vulkan equivalents.</p>
 *
 * <h3>Interception Strategy</h3>
 * <ol>
 *   <li>Mixins into {@code GlStateManager} redirect all state calls here</li>
 *   <li>For most state (blend, depth, cull): store in {@link VRenderSystem}, defer to draw time</li>
 *   <li>For buffer/texture creation: create Vulkan resources instead of GL ones</li>
 *   <li>For draw calls: flush deferred state, bind pipeline, record Vulkan commands</li>
 *   <li>For framebuffer ops: map to Vulkan render targets</li>
 * </ol>
 *
 * <h3>Compatibility</h3>
 * <p>Some GL features have no Vulkan equivalent and are emulated or silently dropped:</p>
 * <ul>
 *   <li>GL_ALPHA_TEST → handled via pipeline specialization constant or fragment shader discard</li>
 *   <li>GL_FOG → handled via UBO fog parameters + shader code</li>
 *   <li>GL_LIGHTING → not used by modern MC (1.17+)</li>
 *   <li>Display lists → not used by modern MC</li>
 *   <li>Immediate mode (glBegin/glEnd) → not used by MC 1.20+</li>
 * </ul>
 */
public class GlStateInterceptor {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/GLIntercept");
    private static boolean initialized = false;

    /** Whether GL interception is active (disabled during loading screens) */
    private static boolean intercepting = false;

    /** Counter for generating unique pseudo-GL buffer IDs */
    private static final AtomicInteger nextBufferId = new AtomicInteger(100_000);

    /** Map of pseudo-GL buffer IDs to Vulkan buffer handles */
    private static final ConcurrentHashMap<Integer, Long> bufferMap = new ConcurrentHashMap<>();

    /** Map of pseudo-GL buffer IDs to buffer sizes (for reallocation tracking) */
    private static final ConcurrentHashMap<Integer, Long> bufferSizes = new ConcurrentHashMap<>();

    /** Currently bound buffer targets: GL_ARRAY_BUFFER(0x8892), GL_ELEMENT_ARRAY_BUFFER(0x8893), etc. */
    private static final ConcurrentHashMap<Integer, Integer> boundBuffers = new ConcurrentHashMap<>();

    /** Counter for generating unique pseudo-GL FBO IDs */
    private static final AtomicInteger nextFboId = new AtomicInteger(200_000);

    /** Map of pseudo-GL FBO IDs to Vulkan render target info */
    private static final ConcurrentHashMap<Integer, Long> fboMap = new ConcurrentHashMap<>();

    /** Currently bound FBO per target (GL_FRAMEBUFFER=0x8D40, GL_READ=0x8CA8, GL_DRAW=0x8CA9) */
    private static int boundDrawFbo = 0;
    private static int boundReadFbo = 0;

    /**
     * Initialize the GL interception layer. Called once during mod startup.
     */
    public static void init() {
        if (initialized) return;
        initialized = true;
        intercepting = false;
        LOGGER.info("GL state interception layer initialized");
    }

    /**
     * Enable interception. Called when Vulkanium takes over rendering.
     */
    public static void enable() {
        intercepting = true;
    }

    /**
     * Disable interception. Called during loading screens or when Vulkanium
     * is not active (e.g. in the main menu before world load).
     */
    public static void disable() {
        intercepting = false;
    }

    public static boolean isIntercepting() { return intercepting; }

    // ─── State Calls (via MixinGlStateManager) ─────────────────────────

    public static void onEnableBlend() {
        if (!intercepting) return;
        VRenderSystem.enableBlend();
    }

    public static void onDisableBlend() {
        if (!intercepting) return;
        VRenderSystem.disableBlend();
    }

    public static void onBlendFunc(int src, int dst) {
        if (!intercepting) return;
        VRenderSystem.blendFunc(src, dst);
    }

    public static void onBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        if (!intercepting) return;
        VRenderSystem.blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    public static void onEnableDepthTest() {
        if (!intercepting) return;
        VRenderSystem.enableDepthTest();
    }

    public static void onDisableDepthTest() {
        if (!intercepting) return;
        VRenderSystem.disableDepthTest();
    }

    public static void onDepthFunc(int func) {
        if (!intercepting) return;
        VRenderSystem.depthFunc(func);
    }

    public static void onDepthMask(boolean write) {
        if (!intercepting) return;
        VRenderSystem.depthMask(write);
    }

    public static void onEnableCull() {
        if (!intercepting) return;
        VRenderSystem.enableCull();
    }

    public static void onDisableCull() {
        if (!intercepting) return;
        VRenderSystem.disableCull();
    }

    public static void onColorMask(boolean r, boolean g, boolean b, boolean a) {
        if (!intercepting) return;
        VRenderSystem.colorMask(r, g, b, a);
    }

    public static void onEnablePolygonOffset() {
        if (!intercepting) return;
        VRenderSystem.enablePolygonOffset();
    }

    public static void onDisablePolygonOffset() {
        if (!intercepting) return;
        VRenderSystem.disablePolygonOffset();
    }

    public static void onPolygonOffset(float factor, float units) {
        if (!intercepting) return;
        VRenderSystem.polygonOffset(factor, units);
    }

    public static void onViewport(int x, int y, int w, int h) {
        if (!intercepting) return;
        VRenderSystem.viewport(x, y, w, h);
    }

    public static void onClearColor(float r, float g, float b, float a) {
        if (!intercepting) return;
        VRenderSystem.clearColor(r, g, b, a);
    }

    public static void onClear(int mask, boolean getError) {
        if (!intercepting) return;
        VRenderSystem.clear(mask, getError);
    }

    public static void onActiveTexture(int unit) {
        if (!intercepting) return;
        VRenderSystem.activeTexture(unit);
    }

    // ─── Buffer Operations (intercept GL buffer creation) ──────────────

    /**
     * Intercept glGenBuffers — create a Vulkan buffer and return a pseudo-GL name.
     *
     * <p>Generates a unique integer ID that maps to a future Vulkan buffer.
     * The actual VkBuffer is not created until onBufferData() is called,
     * since we need to know the size and usage before allocating.</p>
     *
     * @return pseudo-GL buffer name that maps to a Vulkan buffer
     */
    public static int onGenBuffer() {
        if (!intercepting) return 0;

        int id = nextBufferId.getAndIncrement();
        bufferMap.put(id, 0L); // Placeholder — VkBuffer allocated on first upload
        LOGGER.debug("GL buffer generated: pseudo-ID {}", id);
        return id;
    }

    /**
     * Intercept glBufferData — allocate/reallocate the Vulkan buffer.
     *
     * <p>Maps GL buffer usage hints to Vulkan buffer usage flags:</p>
     * <ul>
     *     <li>GL_STATIC_DRAW (0x88E4) → DEVICE_LOCAL (GPU-only, uploaded via staging)</li>
     *     <li>GL_DYNAMIC_DRAW (0x88E8) → CPU_TO_GPU (persistently mapped)</li>
     *     <li>GL_STREAM_DRAW (0x88E0) → CPU_TO_GPU (frame-temporary)</li>
     * </ul>
     *
     * @param target GL buffer target (GL_ARRAY_BUFFER, GL_ELEMENT_ARRAY_BUFFER, etc.)
     * @param size   buffer size in bytes
     * @param usage  GL usage hint
     */
    public static void onBufferData(int target, long size, int usage) {
        if (!intercepting) return;

        Integer boundId = boundBuffers.get(target);
        if (boundId == null || !bufferMap.containsKey(boundId)) {
            LOGGER.warn("onBufferData called with no buffer bound to target 0x{}",
                    Integer.toHexString(target));
            return;
        }

        // Determine Vulkan usage flags from GL target and usage
        int vkBufferUsage = mapGlTargetToVkUsage(target);

        // Determine memory type from GL usage hint
        // 0x88E4 = GL_STATIC_DRAW, 0x88E8 = GL_DYNAMIC_DRAW, 0x88E0 = GL_STREAM_DRAW
        boolean deviceLocal = (usage == 0x88E4); // STATIC_DRAW → GPU-only

        // If the buffer already exists, destroy the old one before reallocating
        long existingBuffer = bufferMap.getOrDefault(boundId, 0L);
        if (existingBuffer != 0L) {
            // Defer destruction until the current frame's commands are complete
            LOGGER.debug("Reallocating GL buffer {} (old VkBuffer: 0x{})",
                    boundId, Long.toHexString(existingBuffer));
        }

        // Create the Vulkan buffer via VulkanBuffer
        // In full integration: VulkanBuffer.create(size, vkBufferUsage, memoryType)
        // For now, store the intended size for tracking
        bufferSizes.put(boundId, size);
        LOGGER.debug("onBufferData: buffer {} size={} usage=0x{} deviceLocal={}",
                boundId, size, Integer.toHexString(usage), deviceLocal);
    }

    /**
     * Intercept glBindBuffer — track which pseudo-GL buffer is bound to which target.
     */
    public static void onBindBuffer(int target, int buffer) {
        if (!intercepting) return;
        if (buffer == 0) {
            boundBuffers.remove(target);
        } else {
            boundBuffers.put(target, buffer);
        }
    }

    /**
     * Intercept glDeleteBuffers — destroy the associated Vulkan buffer.
     */
    public static void onDeleteBuffer(int buffer) {
        if (!intercepting || buffer == 0) return;
        Long vkBuffer = bufferMap.remove(buffer);
        bufferSizes.remove(buffer);
        if (vkBuffer != null && vkBuffer != 0L) {
            // Defer Vulkan buffer destruction to avoid in-flight usage
            // In full integration: FrameResourceTracker.deferDestroy(vkBuffer)
            LOGGER.debug("GL buffer deleted: {} (VkBuffer: 0x{})", buffer, Long.toHexString(vkBuffer));
        }
    }

    /**
     * Map GL buffer target constants to Vulkan buffer usage flags.
     */
    private static int mapGlTargetToVkUsage(int glTarget) {
        return switch (glTarget) {
            case 0x8892 -> 0x00000080; // GL_ARRAY_BUFFER → VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
            case 0x8893 -> 0x00000040; // GL_ELEMENT_ARRAY_BUFFER → VK_BUFFER_USAGE_INDEX_BUFFER_BIT
            case 0x8A11 -> 0x00000010; // GL_UNIFORM_BUFFER → VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
            case 0x90D2 -> 0x00000020; // GL_SHADER_STORAGE_BUFFER → VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            case 0x8C8E -> 0x00000200; // GL_TRANSFORM_FEEDBACK_BUFFER → STORAGE_BUFFER
            default -> 0x00000001 | 0x00000002; // TRANSFER_SRC | TRANSFER_DST (generic)
        };
    }

    // ─── Framebuffer Operations ────────────────────────────────────────

    /**
     * Intercept glBindFramebuffer — switch Vulkan render target.
     *
     * <p>Maps GL FBO operations to Vulkan render pass management:</p>
     * <ul>
     *     <li>FBO 0 → bind swapchain render target (default framebuffer)</li>
     *     <li>FBO N → look up the mapped Vulkan render target and begin
     *         off-screen render pass if needed</li>
     * </ul>
     *
     * <p>Framebuffer binding in Vulkan differs fundamentally from GL:
     * GL allows arbitrary bind/unbind, while Vulkan requires explicit
     * render pass begin/end. We handle this by deferring the actual
     * render pass transition until the next draw call.</p>
     *
     * @param target GL framebuffer target (GL_FRAMEBUFFER, GL_READ_FRAMEBUFFER, GL_DRAW_FRAMEBUFFER)
     * @param framebuffer the GL FBO name (0 = default)
     */
    public static void onBindFramebuffer(int target, int framebuffer) {
        if (!intercepting) return;

        // GL_FRAMEBUFFER = 0x8D40, GL_READ_FRAMEBUFFER = 0x8CA8, GL_DRAW_FRAMEBUFFER = 0x8CA9
        boolean bindDraw = (target == 0x8D40 || target == 0x8CA9);
        boolean bindRead = (target == 0x8D40 || target == 0x8CA8);

        if (bindDraw) {
            if (boundDrawFbo != framebuffer) {
                // End current render pass if active
                if (boundDrawFbo != 0) {
                    // Defer render pass end to VRenderSystem
                    LOGGER.debug("Ending render pass for FBO {}", boundDrawFbo);
                }
                boundDrawFbo = framebuffer;

                if (framebuffer == 0) {
                    // Bind swapchain render target
                    LOGGER.debug("Binding swapchain framebuffer (default)");
                } else {
                    // Look up or create the Vulkan render target for this FBO
                    Long vkTarget = fboMap.get(framebuffer);
                    if (vkTarget != null) {
                        LOGGER.debug("Binding offscreen FBO {} (VkRenderTarget: 0x{})",
                                framebuffer, Long.toHexString(vkTarget));
                    } else {
                        LOGGER.debug("Binding unknown FBO {} — will resolve at draw time",
                                framebuffer);
                    }
                }
            }
        }

        if (bindRead) {
            boundReadFbo = framebuffer;
        }
    }

    /**
     * Generate a pseudo-GL FBO name mapped to a Vulkan render target.
     */
    public static int onGenFramebuffer() {
        if (!intercepting) return 0;
        int id = nextFboId.getAndIncrement();
        fboMap.put(id, 0L); // VkFramebuffer handle set on first use
        return id;
    }

    /**
     * Delete a pseudo-GL FBO and associated Vulkan resources.
     */
    public static void onDeleteFramebuffer(int framebuffer) {
        if (!intercepting || framebuffer == 0) return;
        Long vkTarget = fboMap.remove(framebuffer);
        if (vkTarget != null && vkTarget != 0L) {
            LOGGER.debug("FBO deleted: {} (VkFramebuffer: 0x{})",
                    framebuffer, Long.toHexString(vkTarget));
        }
    }

    // ─── Getters for integration ───────────────────────────────────────

    /** Get the currently bound draw FBO (0 = default/swapchain) */
    public static int getBoundDrawFbo() { return boundDrawFbo; }

    /** Get the currently bound read FBO */
    public static int getBoundReadFbo() { return boundReadFbo; }

    /** Get the Vulkan buffer handle for a pseudo-GL buffer ID */
    public static long getVkBuffer(int glBufferId) {
        return bufferMap.getOrDefault(glBufferId, 0L);
    }

    /** Get the currently bound buffer for a target */
    public static int getBoundBuffer(int target) {
        return boundBuffers.getOrDefault(target, 0);
    }

    // ─── Singleton for instance method calls from mixins ───────────────

    private static final GlStateInterceptor INSTANCE = new GlStateInterceptor();

    public static GlStateInterceptor getInstance() { return INSTANCE; }

    // ─── Texture Operations (GL11/GlStateManager) ─────────────────────

    private static final java.util.concurrent.atomic.AtomicInteger nextTextureId =
            new java.util.concurrent.atomic.AtomicInteger(200000);
    private static final java.util.concurrent.ConcurrentHashMap<Integer, VulkanTexture> textureMap =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Currently bound texture ID per unit — tracked for upload operations. */
    private static final int[] boundTextureIds = new int[32];

    /** Per-texture GL_TEXTURE_MAX_LEVEL tracking (textureId → maxLevel). */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Integer> textureMaxLevel =
            new java.util.concurrent.ConcurrentHashMap<>();

        /** Textures that should always use clamp-to-edge to prevent UV wrap artifacts. */
        private static final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> textureForceClamp =
            new java.util.concurrent.ConcurrentHashMap<>();

    public int onGenTexture() {
        int id = nextTextureId.getAndIncrement();
        textureMap.put(id, new VulkanTexture(id));
        return id;
    }

    public void onDeleteTexture(int texture) {
        if (texture <= 0) return;
        VulkanTexture vt = textureMap.remove(texture);
        textureForceClamp.remove(texture);
        if (vt != null) {
            vt.freeGpuResources();
        }
    }

    public void onTextureFilter(int textureId, boolean blur, boolean mipmap) {
        VulkanTexture vt = textureMap.get(textureId);
        if (vt != null) {
            vt.updateFilter(blur, vt.isClamp(), mipmap);
        }
    }

    public static void onShaderTextureResource(int textureId, String resourcePath) {
        if (textureId <= 0 || resourcePath == null || resourcePath.isEmpty()) return;

        boolean forceClamp = resourcePath.endsWith("textures/misc/shadow.png")
                || resourcePath.endsWith("textures/environment/sun.png")
                || resourcePath.endsWith("textures/environment/moon_phases.png");

        if (forceClamp) {
            textureForceClamp.put(textureId, Boolean.TRUE);
            VulkanTexture vt = textureMap.get(textureId);
            if (vt != null) {
                vt.setClamp(true);
            }
            if (net.vulkanium.Vulkanium.isDebugLogging()) {
                LOGGER.info("forceClamp: tex={} resource={}", textureId, resourcePath);
            }
        }
    }

    /**
     * Intercept glTexParameteri to track GL_TEXTURE_MAX_LEVEL and filter settings.
     *
     * <p>MC calls {@code glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, maxLevel)}
     * before allocating atlas textures to indicate how many mip levels to create.</p>
     */
    public void onTexParameterI(int target, int pname, int param) {
        // Only handle GL_TEXTURE_2D
        if (target != 0x0DE1) return; // GL_TEXTURE_2D

        int boundId = VRenderSystem.getGlBoundTextureId(VRenderSystem.getActiveTextureUnit());
        if (boundId <= 0) return;

        // Log ALL parameter calls for important textures
        if (net.vulkanium.Vulkanium.isDebugLogging()) {
            LOGGER.info("texParamI: tex={} pname=0x{} param={}/0x{}",
                    boundId, Integer.toHexString(pname), param, Integer.toHexString(param));
        }

        switch (pname) {
            case 0x813D -> { // GL_TEXTURE_MAX_LEVEL
                textureMaxLevel.put(boundId, param);
                if (net.vulkanium.Vulkanium.isDebugLogging()) {
                    LOGGER.info("texParam: tex={} GL_TEXTURE_MAX_LEVEL={}", boundId, param);
                }
            }
            case 0x2801 -> { // GL_TEXTURE_MIN_FILTER
                // Track whether mipmapping is enabled
                // GL_NEAREST=0x2600 GL_LINEAR=0x2601
                // GL_NEAREST_MIPMAP_NEAREST=0x2700 GL_LINEAR_MIPMAP_NEAREST=0x2701
                // GL_NEAREST_MIPMAP_LINEAR=0x2702  GL_LINEAR_MIPMAP_LINEAR=0x2703
                VulkanTexture vt = textureMap.get(boundId);
                if (vt != null) {
                    boolean mipmap = (param >= 0x2700 && param <= 0x2703); // Any mipmap mode
                    boolean blur = (param == 0x2601 || param == 0x2701 || param == 0x2703); // LINEAR first part
                    boolean clamp = textureForceClamp.getOrDefault(boundId, Boolean.FALSE) || vt.isClamp();
                    vt.updateFilter(blur, clamp, mipmap);
                    if (net.vulkanium.Vulkanium.isDebugLogging()) {
                        LOGGER.info("texParam: tex={} MIN_FILTER=0x{} mipmap={} blur={}",
                                boundId, Integer.toHexString(param), mipmap, blur);
                    }
                }
            }
            case 0x2800 -> { // GL_TEXTURE_MAG_FILTER
                VulkanTexture vt = textureMap.get(boundId);
                if (vt != null) {
                    boolean blur = (param == 0x2601); // GL_LINEAR
                    boolean clamp = textureForceClamp.getOrDefault(boundId, Boolean.FALSE) || vt.isClamp();
                    vt.updateFilter(blur, clamp, vt.isMipmap());
                }
            }
            case 0x2802, 0x2803 -> { // GL_TEXTURE_WRAP_S, GL_TEXTURE_WRAP_T
                VulkanTexture vt = textureMap.get(boundId);
                if (vt != null) {
                    // GL_CLAMP_TO_EDGE=0x812F, GL_CLAMP=0x2900, GL_CLAMP_TO_BORDER=0x812D
                    // GL_REPEAT=0x2901, GL_MIRRORED_REPEAT=0x8370
                    boolean shouldClamp = (param == 0x812F || param == 0x2900 || param == 0x812D);
                    boolean forceClamp = textureForceClamp.getOrDefault(boundId, Boolean.FALSE);
                    vt.setClamp(forceClamp || shouldClamp);
                    if (net.vulkanium.Vulkanium.isDebugLogging()) {
                        LOGGER.info("texParam: tex={} WRAP_{}=0x{} clamp={}",
                                boundId, pname == 0x2802 ? "S" : "T",
                                Integer.toHexString(param), forceClamp || shouldClamp);
                    }
                }
            }
        }
    }

    public void onTexImage2D(int target, int level, int internalformat,
                              int width, int height, int format, int type, long pixels) {
        if (width == 0 || height == 0) return;

        // Use GL-bound texture (from _bindTexture), not shader-bound
        int boundId = VRenderSystem.getGlBoundTextureId(VRenderSystem.getActiveTextureUnit());
        VulkanTexture vt = textureMap.get(boundId);
        if (vt == null) {
            LOGGER.debug("texImage2D: no VulkanTexture for bound ID {}", boundId);
            return;
        }

        int vkFormat = glFormatToVulkan(internalformat, format, type);
        int pixelSize = glPixelSize(format, type);

        if (level == 0) {
            // Determine mip level count from tracked GL_TEXTURE_MAX_LEVEL
            int maxLevel = textureMaxLevel.getOrDefault(boundId, 0);
            int mipLevels = maxLevel + 1;
            vt.allocate(width, height, vkFormat, mipLevels);

            // MC 1.20.1's TextureUtil.prepareImage does NOT set MIN_FILTER/MAG_FILTER.
            // Those are set inside NativeImage._upload() → setFilter(), which we cancel.
            // Infer mipmap from maxLevel: if maxLevel > 0, the texture needs mipmapping.
            if (maxLevel > 0) {
                boolean clamp = textureForceClamp.getOrDefault(boundId, Boolean.FALSE) || vt.isClamp();
                vt.updateFilter(false, clamp, true);
                LOGGER.info("texImage2D: tex={} {}x{} vkFmt={} mips={} → mipmap=true (maxLevel={})",
                        boundId, width, height, vkFormat, mipLevels, maxLevel);
            } else {
                LOGGER.info("texImage2D: tex={} {}x{} vkFmt={} mips={}", boundId, width, height, vkFormat, mipLevels);
            }
        }
        // For level > 0: if VkImage has enough mip levels, upload to that level
        // (allocation already handled when level 0 was created)

        if (pixels != 0 && vt.isAllocated()) {
            if (pixelSize == 3) {
                long rgbaPixels = convertRGBtoRGBA(pixels, width, height, 0);
                vt.upload(level, 0, 0, width, height, rgbaPixels, 4);
                MemoryUtil.nmemFree(rgbaPixels);
                LOGGER.debug("texImage2D: tex={} RGB→RGBA conversion applied", boundId);
            } else {
                vt.upload(level, 0, 0, width, height, pixels, pixelSize);
            }
        }
    }

    public void onTexSubImage2D(int target, int level, int xoffset, int yoffset,
                                 int width, int height, int format, int type, long pixels) {
        if (width == 0 || height == 0 || pixels == 0) return;

        // Use GL-bound texture (from _bindTexture), not shader-bound
        int boundId = VRenderSystem.getGlBoundTextureId(VRenderSystem.getActiveTextureUnit());
        VulkanTexture vt = textureMap.get(boundId);
        if (vt == null || !vt.isAllocated()) {
            LOGGER.debug("texSubImage2D: no allocated VulkanTexture for bound ID {}", boundId);
            return;
        }

        int pixelSize = glPixelSize(format, type);
        if (pixelSize == 3) {
            long rgbaPixels = convertRGBtoRGBA(pixels, width, height, 0);
            vt.upload(level, xoffset, yoffset, width, height, rgbaPixels, 4);
            MemoryUtil.nmemFree(rgbaPixels);
            LOGGER.debug("texSubImage2D: tex={} RGB→RGBA conversion applied", boundId);
        } else {
            vt.upload(level, xoffset, yoffset, width, height, pixels, pixelSize);
        }
    }

    /**
     * Intercept glGetTexLevelParameteriv — return real texture dimensions.
     */
    public int onGetTexLevelParameter(int target, int level, int pname) {
        int boundId = VRenderSystem.getGlBoundTextureId(VRenderSystem.getActiveTextureUnit());
        VulkanTexture vt = textureMap.get(boundId);
        if (vt == null || !vt.isAllocated()) {
            return (pname == 0x1003) ? 0x8058 : 0; // GL_RGBA8 default for internal format
        }
        return switch (pname) {
            case 0x1000 -> vt.getWidth();   // GL_TEXTURE_WIDTH
            case 0x1001 -> vt.getHeight();  // GL_TEXTURE_HEIGHT
            case 0x1003 -> 0x8058;          // GL_TEXTURE_INTERNAL_FORMAT → GL_RGBA8
            default -> 0;
        };
    }

    public void destroyAllTextures() {
        for (VulkanTexture vt : textureMap.values()) {
            vt.freeGpuResources();
        }
        textureMap.clear();
        LOGGER.info("All Vulkan textures destroyed");
    }

    // ─── Draw Commands (GL11) ──────────────────────────────────────────

    public void onDrawArrays(int mode, int first, int count) {
        LOGGER.trace("drawArrays: mode=0x{} first={} count={}", Integer.toHexString(mode), first, count);
    }

    public void onDrawElements(int mode, int count, int type, long indices) {
        LOGGER.trace("drawElements: mode=0x{} count={} type=0x{}",
                Integer.toHexString(mode), count, Integer.toHexString(type));
    }

    // ─── Buffer Sub Data / Map (GL15) ──────────────────────────────────

    public void onBufferSubData(int target, long offset, java.nio.ByteBuffer data) {
        LOGGER.trace("bufferSubData: target=0x{} offset={} size={}",
                Integer.toHexString(target), offset, data != null ? data.remaining() : 0);
    }

    /** Maps of bound buffer → mapped ByteBuffer for glMapBuffer/glUnmapBuffer */
    private static final ConcurrentHashMap<Integer, java.nio.ByteBuffer> mappedBuffers =
            new ConcurrentHashMap<>();

    public java.nio.ByteBuffer onMapBuffer(int target, int access) {
        Integer boundId = boundBuffers.get(target);
        long size = 0;
        if (boundId != null) {
            size = bufferSizes.getOrDefault(boundId, 0L);
        }
        if (size <= 0) size = 65536; // fallback size
        java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc((int) Math.min(size, Integer.MAX_VALUE));
        if (boundId != null) {
            mappedBuffers.put(boundId, buf);
        }
        LOGGER.trace("mapBuffer: target=0x{} access=0x{} size={}", Integer.toHexString(target),
                Integer.toHexString(access), size);
        return buf;
    }

    public boolean onUnmapBuffer(int target) {
        Integer boundId = boundBuffers.get(target);
        if (boundId != null) {
            java.nio.ByteBuffer mapped = mappedBuffers.remove(boundId);
            if (mapped != null) {
                // Data written to mapped buffer captured here — will be uploaded to Vulkan
                mapped.flip();
                LOGGER.trace("unmapBuffer: target=0x{} buffer={} dataSize={}", Integer.toHexString(target),
                        boundId, mapped.remaining());
                org.lwjgl.system.MemoryUtil.memFree(mapped);
            }
        }
        return true;
    }

    public boolean isBuffer(int buffer) {
        return bufferMap.containsKey(buffer);
    }

    // ─── Framebuffer Attachments (GL30) ────────────────────────────────

    public void onFramebufferTexture2D(int target, int attachment, int textarget,
                                        int texture, int level) {
        LOGGER.trace("framebufferTexture2D: attach=0x{} texture={}",
                Integer.toHexString(attachment), texture);
    }

    public void onBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                   int dstX0, int dstY0, int dstX1, int dstY1,
                                   int mask, int filter) {
        LOGGER.trace("blitFramebuffer: src({},{} → {},{}) dst({},{} → {},{})",
                srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1);
    }

    public void onDrawBuffers(int[] bufs) {
        LOGGER.trace("drawBuffers: {} attachments", bufs != null ? bufs.length : 0);
    }

    // ─── Renderbuffer Operations (GL30) ────────────────────────────────

    private static final java.util.concurrent.atomic.AtomicInteger nextRenderbufferId =
            new java.util.concurrent.atomic.AtomicInteger(300000);
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Long> renderbufferMap =
            new java.util.concurrent.ConcurrentHashMap<>();

    public int onGenRenderbuffer() {
        int id = nextRenderbufferId.getAndIncrement();
        renderbufferMap.put(id, 0L);
        return id;
    }

    public void onBindRenderbuffer(int target, int renderbuffer) {
        LOGGER.trace("bindRenderbuffer: {}", renderbuffer);
    }

    public void onRenderbufferStorage(int target, int internalformat, int width, int height) {
        LOGGER.trace("renderbufferStorage: {}x{} format=0x{}", width, height,
                Integer.toHexString(internalformat));
    }

    public void onFramebufferRenderbuffer(int target, int attachment,
                                           int renderbuffertarget, int renderbuffer) {
        LOGGER.trace("framebufferRenderbuffer: attach=0x{} rb={}",
                Integer.toHexString(attachment), renderbuffer);
    }

    public void onDeleteRenderbuffer(int renderbuffer) {
        if (renderbuffer <= 0) return;
        renderbufferMap.remove(renderbuffer);
    }

    // ─── Vertex Array Operations (GL30) ────────────────────────────────

    private static final java.util.concurrent.atomic.AtomicInteger nextVaoId =
            new java.util.concurrent.atomic.AtomicInteger(400000);
    private static int boundVao = 0;

    public int onGenVertexArray() {
        return nextVaoId.getAndIncrement();
    }

    public void onBindVertexArray(int array) {
        boundVao = array;
    }

    public void onDeleteVertexArray(int array) {
        if (boundVao == array) boundVao = 0;
    }

    // ─── Mipmap Generation (GL30) ──────────────────────────────────────

    public void onGenerateMipmap(int target) {
        LOGGER.trace("generateMipmap: target=0x{}", Integer.toHexString(target));
    }

    // ─── Render Target Creation ────────────────────────────────────────

    public void onCreateRenderTarget(int fbo, int colorTexture, int depthRenderbuffer,
                                      int width, int height) {
        LOGGER.debug("createRenderTarget: fbo={} color={} depth={} {}x{}",
                fbo, colorTexture, depthRenderbuffer, width, height);
    }

    // ─── NativeImage Operations ────────────────────────────────────────

    public void onNativeImageUpload(int level, int xOffset, int yOffset,
                                     int width, int height, long pixels,
                                     int rowStride, int pixelSize,
                                     boolean blur, boolean clamp, boolean mipmap) {
        // Use GL-bound texture (from _bindTexture), not shader-bound
        int boundId = VRenderSystem.getGlBoundTextureId(VRenderSystem.getActiveTextureUnit());
        VulkanTexture vt = textureMap.get(boundId);
        if (vt == null) {
            LOGGER.debug("nativeImageUpload: no VulkanTexture for bound ID {}", boundId);
            return;
        }

        // Determine Vulkan format from pixel size (NativeImage format)
        // MC's rendering pipeline is NOT gamma-correct — use UNORM to pass
        // raw byte values through without sRGB linearization.
        int vkFormat = switch (pixelSize) {
            case 4 -> 37; // VK_FORMAT_R8G8B8A8_UNORM
            case 3 -> 37; // We'll treat RGB as RGBA (pad alpha)
            case 1 -> 9;  // VK_FORMAT_R8_UNORM
            default -> 37; // default RGBA
        };

        // Allocate if not yet allocated, or if this is the full image (level 0, offset 0,0)
        boolean justAllocated = false;
        if (!vt.isAllocated()) {
            // For NativeImage uploads, rowStride is the full image width
            // and width/height are the upload region dimensions.
            // The image needs to be at least as big as offset + upload size
            int imgW = Math.max(rowStride, xOffset + width);
            int imgH = Math.max(yOffset + height, height);
            int maxLevel = textureMaxLevel.getOrDefault(boundId, 0);
            int mipLevels = maxLevel + 1;
            vt.allocate(imgW, imgH, vkFormat, mipLevels);
            justAllocated = true;
        }

        // Set filter state only on the FIRST upload (when allocated above).
        // Subsequent uploads (animated sprite frames) must not reset the
        // sampler state that was established by AbstractTexture.setFilter()
        // via RenderSystem.texParameter() → onTexParameterI().
        //
        // MC 1.20.1's TextureUtil.prepareImage does NOT set MIN_FILTER/MAG_FILTER —
        // those are set inside NativeImage._upload() via setFilter(), which we cancel.
        // So on first allocation, use textureMaxLevel to infer mipmap: if the texture
        // was prepared with maxLevel > 0, it needs mipmapping enabled.
        if (justAllocated) {
            int maxLevel = textureMaxLevel.getOrDefault(boundId, 0);
            boolean effectiveMipmap = maxLevel > 0 || mipmap;
            boolean effectiveClamp = textureForceClamp.getOrDefault(boundId, Boolean.FALSE) || clamp;
            vt.updateFilter(blur, effectiveClamp, effectiveMipmap);
            if (effectiveMipmap != mipmap) {
                LOGGER.info("nativeImageUpload: tex={} overriding mipmap={} → {} (maxLevel={})",
                        boundId, mipmap, effectiveMipmap, maxLevel);
            }
        }

        // Upload the pixel data — convert RGB to RGBA if needed
        if (pixelSize == 3) {
            long rgbaPixels = convertRGBtoRGBA(pixels, width, height, rowStride);
            vt.upload(level, xOffset, yOffset, width, height, rgbaPixels, width, 4);
            MemoryUtil.nmemFree(rgbaPixels);
            LOGGER.debug("nativeImageUpload: tex={} {}x{} at ({},{}) ps=3→4 (RGB→RGBA)", boundId, width, height,
                    xOffset, yOffset);
        } else {
            vt.upload(level, xOffset, yOffset, width, height, pixels, rowStride, pixelSize);
            LOGGER.debug("nativeImageUpload: tex={} {}x{} at ({},{}) ps={}", boundId, width, height,
                    xOffset, yOffset, pixelSize);
        }
    }

    public void onNativeImageDownload(int level, long pixels, int width, int height,
                                       int components, boolean removeAlpha) {
        LOGGER.trace("nativeImageDownload: {}x{} level={} components={}",
                width, height, level, components);
    }

    // ─── Scissor Operations ────────────────────────────────────────────

    public static void onScissor(int x, int y, int w, int h) {
        if (!intercepting) return;
        VRenderSystem.enableScissor(x, y, w, h);
    }

    public static void onEnableScissor() {
        if (!intercepting) return;
        // Scissor test is always armed when dimensions are set
    }

    public static void onDisableScissor() {
        if (!intercepting) return;
        VRenderSystem.disableScissor();
    }

    public static void onBindTexture(int texture) {
        if (!intercepting) return;
        int slot = Math.max(0, Math.min(VRenderSystem.getActiveTextureUnit(), 31));
        boundTextureIds[slot] = texture;
        VRenderSystem.bindTexture(texture);

        VulkanTexture vt = textureMap.get(texture);
        if (vt != null && textureForceClamp.getOrDefault(texture, Boolean.FALSE)) {
            vt.setClamp(true);
        }
    }

    /**
     * Look up the VulkanTexture for a given pseudo-GL texture ID.
     */
    public static VulkanTexture getVulkanTexture(int pseudoId) {
        return textureMap.get(pseudoId);
    }

    /**
     * Dumps info about all registered textures (for diagnostics).
     */
    public static void dumpTextureRegistry() {
        LOGGER.info("[TEX-DUMP] {} textures registered:", textureMap.size());
        for (var entry : textureMap.entrySet()) {
            VulkanTexture vt = entry.getValue();
            LOGGER.info("[TEX-DUMP]  id={} {}x{} fmt={} alloc={} uploads={} bytes={} view={} sampler={}",
                    entry.getKey(),
                    vt.getWidth(), vt.getHeight(), vt.getVkFormat(),
                    vt.isAllocated(),
                    vt.getUploadCount(), vt.getTotalUploadBytes(),
                    vt.getImageView() != 0 ? "yes" : "NO",
                    vt.getSampler() != 0 ? "yes" : "NO");
        }
    }

    // ─── GL Format Helpers ──────────────────────────────────────────────

    /**
     * Converts RGB (3 bytes/pixel) data to RGBA (4 bytes/pixel) by padding alpha = 0xFF.
     * Returns a newly allocated native buffer that the caller must free with
     * {@code MemoryUtil.nmemFree()}.
     *
     * @param srcPixels   Native pointer to source RGB pixel data
     * @param width       Width of the image region in pixels
     * @param height      Height of the image region in pixels
     * @param srcRowStride Row stride in pixels (0 or equals width means tightly packed)
     * @return native pointer to the newly allocated RGBA pixel data
     */
    private static long convertRGBtoRGBA(long srcPixels, int width, int height, int srcRowStride) {
        int effectiveStride = (srcRowStride > 0 && srcRowStride != width) ? srcRowStride : width;
        long dstSize = (long) width * height * 4;
        long dst = MemoryUtil.nmemAlloc(dstSize);
        for (int y = 0; y < height; y++) {
            long srcRow = srcPixels + (long) y * effectiveStride * 3;
            long dstRow = dst + (long) y * width * 4;
            for (int x = 0; x < width; x++) {
                long srcOff = srcRow + (long) x * 3;
                long dstOff = dstRow + (long) x * 4;
                MemoryUtil.memPutByte(dstOff,     MemoryUtil.memGetByte(srcOff));     // R
                MemoryUtil.memPutByte(dstOff + 1, MemoryUtil.memGetByte(srcOff + 1)); // G
                MemoryUtil.memPutByte(dstOff + 2, MemoryUtil.memGetByte(srcOff + 2)); // B
                MemoryUtil.memPutByte(dstOff + 3, (byte) 0xFF);                       // A
            }
        }
        LOGGER.debug("convertRGBtoRGBA: {}x{} → {} bytes RGBA", width, height, dstSize);
        return dst;
    }

    /**
     * Maps GL internalFormat/format/type to a Vulkan format.
     */
    private static int glFormatToVulkan(int internalFormat, int format, int type) {
        // GL_RGBA8=0x8058  GL_RGBA=0x1908  GL_RGB8=0x8051  GL_RGB=0x1907
        // GL_R8=0x8229    GL_RED=0x1903
        // Use UNORM formats — MC's pipeline is not gamma-correct
        return switch (internalFormat) {
            case 0x8058, 0x1908 -> 37;  // GL_RGBA8, GL_RGBA → VK_FORMAT_R8G8B8A8_UNORM
            case 0x8051, 0x1907 -> 37;  // GL_RGB8, GL_RGB → treat as RGBA UNORM
            case 0x8229, 0x1903 -> 9;   // GL_R8, GL_RED → VK_FORMAT_R8_UNORM
            default -> 37; // Default to RGBA8 UNORM
        };
    }

    /**
     * Returns bytes per pixel for a GL format/type combination.
     */
    private static int glPixelSize(int format, int type) {
        // type: GL_UNSIGNED_BYTE = 0x1401
        return switch (format) {
            case 0x1908 -> 4; // GL_RGBA
            case 0x1907 -> 3; // GL_RGB
            case 0x1903 -> 1; // GL_RED
            case 0x190A -> 2; // GL_RG
            default -> 4;
        };
    }
}
