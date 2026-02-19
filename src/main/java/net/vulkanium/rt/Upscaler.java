package net.vulkanium.rt;

/**
 * Interface for upscaling modules (FSR 3.1, DLSS, XeSS).
 *
 * <p>Upscalers render internally at a lower resolution and upscale to the target
 * resolution, significantly reducing RT cost. Each implementation wraps a vendor
 * SDK or open-source solution.</p>
 *
 * <h3>Supported Upscalers</h3>
 * <table>
 *   <tr><th>Upscaler</th><th>Vendor</th><th>GPU Support</th></tr>
 *   <tr><td>FSR 3.1</td><td>AMD (open source)</td><td>All GPUs</td></tr>
 *   <tr><td>DLSS</td><td>NVIDIA (proprietary)</td><td>RTX only</td></tr>
 *   <tr><td>XeSS</td><td>Intel (DP4a)</td><td>All GPUs (best on Arc)</td></tr>
 * </table>
 *
 * <h3>Quality Presets</h3>
 * <p>Each upscaler maps quality presets to an internal render scale factor:</p>
 * <ul>
 *   <li><b>Ultra Quality:</b> 77% (1.3× upscale)</li>
 *   <li><b>Quality:</b> 67% (1.5× upscale)</li>
 *   <li><b>Balanced:</b> 58% (1.7× upscale)</li>
 *   <li><b>Performance:</b> 50% (2.0× upscale)</li>
 *   <li><b>Ultra Performance:</b> 33% (3.0× upscale)</li>
 * </ul>
 */
public interface Upscaler {

    /**
     * Quality preset for the upscaler.
     */
    enum QualityPreset {
        ULTRA_QUALITY(0.77f, "Ultra Quality"),
        QUALITY(0.67f, "Quality"),
        BALANCED(0.58f, "Balanced"),
        PERFORMANCE(0.50f, "Performance"),
        ULTRA_PERFORMANCE(0.33f, "Ultra Performance");

        private final float renderScale;
        private final String displayName;

        QualityPreset(float renderScale, String displayName) {
            this.renderScale = renderScale;
            this.displayName = displayName;
        }

        public float getRenderScale() { return renderScale; }
        public String getDisplayName() { return displayName; }
    }

    /**
     * Upscaler type identifier.
     */
    enum Type {
        FSR3("AMD FidelityFX Super Resolution 3.1"),
        DLSS("NVIDIA Deep Learning Super Sampling"),
        XESS("Intel Xe Super Sampling"),
        NONE("No upscaling");

        private final String description;
        Type(String description) { this.description = description; }
        public String getDescription() { return description; }
    }

    /**
     * Returns the upscaler type.
     */
    Type getType();

    /**
     * Returns whether this upscaler is available on the current hardware.
     */
    boolean isAvailable();

    /**
     * Initializes the upscaler for the given resolutions.
     *
     * @param inputWidth  Render resolution width (lower)
     * @param inputHeight Render resolution height (lower)
     * @param outputWidth Display resolution width (higher)
     * @param outputHeight Display resolution height (higher)
     */
    void init(int inputWidth, int inputHeight, int outputWidth, int outputHeight);

    /**
     * Executes the upscaling pass.
     *
     * @param commandBuffer Active Vulkan command buffer
     * @param inputColor  Low-res rendered color image (RGBA16F)
     * @param outputColor High-res output image (RGBA16F or swapchain format)
     * @param depth       Low-res depth buffer (R32F)
     * @param motionVectors Low-res motion vectors (RG16F, screen-space)
     * @param jitterX     Sub-pixel jitter X offset (for TAA integration)
     * @param jitterY     Sub-pixel jitter Y offset
     * @param deltaTime   Frame delta time in seconds
     */
    void execute(long commandBuffer, long inputColor, long outputColor,
                 long depth, long motionVectors,
                 float jitterX, float jitterY, float deltaTime);

    /**
     * Sets the quality preset, which adjusts the internal render scale.
     */
    void setQualityPreset(QualityPreset preset);

    /**
     * Returns the current quality preset.
     */
    QualityPreset getQualityPreset();

    /**
     * Returns the internal render resolution for the current quality preset.
     *
     * @param displayWidth Target display width
     * @return Render width (lower)
     */
    default int getRenderWidth(int displayWidth) {
        return Math.max(1, (int) (displayWidth * getQualityPreset().getRenderScale()));
    }

    /**
     * Returns the internal render resolution for the current quality preset.
     *
     * @param displayHeight Target display height
     * @return Render height (lower)
     */
    default int getRenderHeight(int displayHeight) {
        return Math.max(1, (int) (displayHeight * getQualityPreset().getRenderScale()));
    }

    /**
     * Releases GPU resources.
     */
    void destroy();
}
