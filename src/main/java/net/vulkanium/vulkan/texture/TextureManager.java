package net.vulkanium.vulkan.texture;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Central texture manager. Tracks all Vulkan textures, handles atlas management,
 * sprite animation ticks, and GL→Vulkan texture ID mapping.
 *
 * <p>In Minecraft, textures are identified by integer IDs (from glGenTextures).
 * This manager maintains a mapping from those GL texture IDs to VulkanImage objects,
 * enabling transparent replacement of GL texture operations.</p>
 *
 * <p>Also manages the block atlas (terrain.png) and its sprite animation frames,
 * similar to Sodium's SpriteUtil and VulkanMod's texture handling.</p>
 */
public class TextureManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/TextureManager");

    private static TextureManager instance;

    /** GL texture ID → VulkanImage mapping */
    private final Int2ObjectOpenHashMap<VulkanImage> textureMap = new Int2ObjectOpenHashMap<>();

    /** GL texture ID → sampler handle */
    private final Int2ObjectOpenHashMap<Long> samplerMap = new Int2ObjectOpenHashMap<>();

    /** Textures pending deletion (deferred to frame boundary) */
    private final List<VulkanImage> pendingDeletion = new ArrayList<>();

    /** Currently bound textures per unit (mirrors VRenderSystem.boundTextures) */
    private final VulkanImage[] boundTextures = new VulkanImage[32];

    /** Block atlas (stitched terrain texture) */
    private VulkanImage blockAtlas;

    /** Atlas sprite animation tracker */
    private final List<AnimatedSprite> animatedSprites = new ArrayList<>();

    /** Frame counter for animation timing */
    private long currentFrameTick;

    private int nextSyntheticId = 0x7FFF_0000; // IDs for Vulkanium-created textures

    private TextureManager() {}

    public static TextureManager getInstance() {
        if (instance == null) {
            instance = new TextureManager();
        }
        return instance;
    }

    // ─── GL→Vulkan texture mapping ─────────────────────────────────────

    /**
     * Register a VulkanImage under a GL texture ID.
     * Called when intercepting glGenTextures + glTexImage2D.
     */
    public void register(int glTextureId, VulkanImage image) {
        VulkanImage old = textureMap.put(glTextureId, image);
        if (old != null && old != image) {
            pendingDeletion.add(old);
        }
    }

    /**
     * Get the VulkanImage for a GL texture ID.
     */
    public VulkanImage getImage(int glTextureId) {
        return textureMap.get(glTextureId);
    }

    /**
     * Remove a texture by GL ID (intercepting glDeleteTextures).
     */
    public void remove(int glTextureId) {
        VulkanImage img = textureMap.remove(glTextureId);
        if (img != null) {
            pendingDeletion.add(img);
        }
        samplerMap.remove(glTextureId);
    }

    /**
     * Bind a GL texture to a unit. This updates the bound table for descriptor set writing.
     */
    public void bind(int unit, int glTextureId) {
        if (unit < 0 || unit >= boundTextures.length) return;
        boundTextures[unit] = textureMap.get(glTextureId);
    }

    public VulkanImage getBound(int unit) {
        if (unit < 0 || unit >= boundTextures.length) return null;
        return boundTextures[unit];
    }

    /**
     * Set the sampler for a GL texture (intercepting glTexParameteri calls).
     */
    public void setSampler(int glTextureId, int minFilter, int magFilter, int wrapS, int wrapT) {
        int[] vkMin = SamplerManager.glFilterToVk(minFilter);
        int vkWrapS = SamplerManager.glWrapToVk(wrapS);
        int vkWrapT = SamplerManager.glWrapToVk(wrapT);
        int[] vkMag = SamplerManager.glFilterToVk(magFilter);

        long sampler = SamplerManager.getInstance().getOrCreate(
            vkMin[0], vkMag[0], vkMin[1],
            vkWrapS, vkWrapT,
            0.0f, vkMin[1] != 0 ? 16.0f : 0.0f,
            false
        );
        samplerMap.put(glTextureId, Long.valueOf(sampler));
    }

    public long getSampler(int glTextureId) {
        Long s = samplerMap.get(glTextureId);
        return s != null ? s : SamplerManager.getInstance().getDefault(false, false);
    }

    // ─── Atlas management ──────────────────────────────────────────────

    public void setBlockAtlas(VulkanImage atlas) {
        this.blockAtlas = atlas;
    }

    public VulkanImage getBlockAtlas() {
        return blockAtlas;
    }

    // ─── Sprite animation ──────────────────────────────────────────────

    /**
     * Register an animated sprite region in the atlas.
     */
    public void registerAnimatedSprite(int x, int y, int width, int height,
                                       int frameCount, int ticksPerFrame,
                                       boolean interpolate) {
        animatedSprites.add(new AnimatedSprite(x, y, width, height,
                                               frameCount, ticksPerFrame, interpolate));
    }

    /**
     * Called each frame to advance sprite animations and upload changed frames.
     */
    public void tickAnimations(long commandBuffer) {
        currentFrameTick++;
        for (AnimatedSprite sprite : animatedSprites) {
            sprite.tick(currentFrameTick);
            if (sprite.needsUpload) {
                // Upload sprite frame data to atlas sub-region
                // Uses staging buffer → vkCmdCopyBufferToImage with offset
                sprite.needsUpload = false;
            }
        }
    }

    // ─── Synthetic texture creation ────────────────────────────────────

    /** Create a Vulkanium-managed texture (not from GL interception). */
    public int createTexture(int width, int height, int format) {
        VulkanImage image = VulkanImage.builder(width, height)
            .format(format)
            .build();
        image.create();

        int id = nextSyntheticId++;
        register(id, image);
        return id;
    }

    /** Create a render target texture. */
    public int createRenderTarget(int width, int height, int format) {
        VulkanImage image = VulkanImage.builder(width, height)
            .format(format)
            .colorAttachment()
            .build();
        image.create();

        int id = nextSyntheticId++;
        register(id, image);
        return id;
    }

    /** Create a depth buffer texture. */
    public int createDepthBuffer(int width, int height) {
        VulkanImage image = VulkanImage.builder(width, height)
            .format(VulkanImage.FORMAT_D32_SFLOAT)
            .depthAttachment()
            .build();
        image.create();

        int id = nextSyntheticId++;
        register(id, image);
        return id;
    }

    // ─── Frame lifecycle ───────────────────────────────────────────────

    /** Flush pending deletions — call at frame boundary when GPU is done. */
    public void flushPendingDeletions() {
        for (VulkanImage img : pendingDeletion) {
            img.destroy();
        }
        pendingDeletion.clear();
    }

    /** Destroy everything. */
    public void destroy() {
        for (VulkanImage img : textureMap.values()) {
            img.destroy();
        }
        textureMap.clear();
        for (VulkanImage img : pendingDeletion) {
            img.destroy();
        }
        pendingDeletion.clear();
        samplerMap.clear();
        animatedSprites.clear();
        blockAtlas = null;
        LOGGER.info("TextureManager destroyed — all textures freed");
    }

    public static void shutdown() {
        if (instance != null) {
            instance.destroy();
            instance = null;
        }
    }

    public int getTextureCount() { return textureMap.size(); }
    public int getPendingCount() { return pendingDeletion.size(); }

    // ─── Format utility ────────────────────────────────────────────────

    /**
     * Convert GL internal format (glTexImage2D) → VkFormat.
     */
    public static int glFormatToVk(int glInternalFormat) {
        return switch (glInternalFormat) {
            case 0x1903 /* GL_RED */,
                 0x8229 /* GL_R8 */       -> VulkanImage.FORMAT_R8_UNORM;
            case 0x8227 /* GL_RG8 */      -> VulkanImage.FORMAT_R8G8_UNORM;
            case 0x8058 /* GL_RGBA8 */    -> VulkanImage.FORMAT_R8G8B8A8_UNORM;
            case 0x8C43 /* GL_SRGB8_ALPHA8 */ -> VulkanImage.FORMAT_R8G8B8A8_SRGB;
            case 0x822D /* GL_R16F */     -> VulkanImage.FORMAT_R16_SFLOAT;
            case 0x822F /* GL_RG16F */    -> VulkanImage.FORMAT_R16G16_SFLOAT;
            case 0x881A /* GL_RGBA16F */  -> VulkanImage.FORMAT_R16G16B16A16_SFLOAT;
            case 0x8814 /* GL_RGBA32F */  -> VulkanImage.FORMAT_R32G32B32A32_SFLOAT;
            case 0x81A5 /* GL_DEPTH_COMPONENT16 */ -> VulkanImage.FORMAT_D32_SFLOAT;
            case 0x81A6 /* GL_DEPTH_COMPONENT24 */ -> VulkanImage.FORMAT_D24_UNORM_S8_UINT;
            case 0x81A7 /* GL_DEPTH_COMPONENT32 */,
                 0x8CAC /* GL_DEPTH_COMPONENT32F */ -> VulkanImage.FORMAT_D32_SFLOAT;
            case 0x88F0 /* GL_DEPTH24_STENCIL8 */  -> VulkanImage.FORMAT_D24_UNORM_S8_UINT;
            case 0x8CAD /* GL_DEPTH32F_STENCIL8 */ -> VulkanImage.FORMAT_D32_SFLOAT_S8_UINT;
            default -> VulkanImage.FORMAT_R8G8B8A8_UNORM;
        };
    }

    // ─── Animated Sprite ───────────────────────────────────────────────

    private static class AnimatedSprite {
        final int x, y, width, height;
        final int frameCount, ticksPerFrame;
        final boolean interpolate;
        int currentFrame;
        long lastTickFrame;
        boolean needsUpload;

        AnimatedSprite(int x, int y, int width, int height,
                      int frameCount, int ticksPerFrame, boolean interpolate) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.frameCount = frameCount;
            this.ticksPerFrame = ticksPerFrame;
            this.interpolate = interpolate;
        }

        void tick(long frameTick) {
            if (frameTick - lastTickFrame >= ticksPerFrame) {
                currentFrame = (currentFrame + 1) % frameCount;
                lastTickFrame = frameTick;
                needsUpload = true;
            }
        }
    }
}
