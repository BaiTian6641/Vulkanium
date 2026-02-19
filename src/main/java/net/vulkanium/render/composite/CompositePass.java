package net.vulkanium.render.composite;

import net.vulkanium.render.gbuffer.MRTGraphicsPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Defines a single composite or deferred post-processing pass.
 *
 * <p>Each pass executes a fullscreen triangle draw with a compiled shader program
 * that reads from G-buffer targets (set 1 samplers) and writes to specified
 * render targets via the RENDERTARGETS directive.</p>
 *
 * <h3>Pass Data Flow</h3>
 * <pre>
 *   ┌──────────────────────────────────────┐
 *   │  Read (samplers):                    │
 *   │    colortexN from flip state         │
 *   │    depthtexN                          │
 *   │    shadowtexN (if shadow pass ran)    │
 *   │    noisetex, normals, specular        │
 *   ├──────────────────────────────────────┤
 *   │  Write (via RENDERTARGETS):          │
 *   │    colortexM → write to alt/main     │
 *   │    (opposite of what's being read)   │
 *   ├──────────────────────────────────────┤
 *   │  After pass:                         │
 *   │    Flip written buffers              │
 *   │    → Next pass reads updated data    │
 *   └──────────────────────────────────────┘
 * </pre>
 *
 * <h3>Associated Compute Shaders</h3>
 * <p>Composite passes can have associated compute shaders that run before the
 * fragment pass (e.g., {@code composite2.csh} runs before {@code composite2.fsh}).
 * A memory barrier is inserted between compute and fragment execution.</p>
 */
public class CompositePass {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/CompositePass");

    /** Pass category matching Iris's pass arrays */
    public enum Category {
        BEGIN,          // begin0..begin99
        SHADOW_COMP,    // shadowcomp0..shadowcomp99
        PREPARE,        // prepare0..prepare99
        DEFERRED,       // deferred0..deferred99
        COMPOSITE,      // composite0..composite99
        FINAL           // single final pass
    }

    // ── Configuration (set at creation, immutable) ──

    private final Category category;
    private final int index;       // Pass index within category (0–99)
    private final String name;     // e.g., "composite3", "deferred0", "final"

    /** Draw buffer indices from RENDERTARGETS directive */
    private int[] drawBuffers;

    /** Compiled shader program (vertex + fragment modules) */
    private long vertexModule;
    private long fragmentModule;
    private long pipeline;
    private long pipelineLayout;

    /** Optional compute shader that runs before this pass */
    private long computeModule;
    private long computePipeline;
    private int computeWorkGroupsX = 0;
    private int computeWorkGroupsY = 0;

    /** Viewport override (from pack's viewportScale directive) */
    private float viewportScaleX = 1.0f;
    private float viewportScaleY = 1.0f;

    /** Blend override per draw buffer (from pack's blendFunc directive) */
    private MRTGraphicsPipeline.BlendOverride[] blendOverrides;

    /** Whether specific buffers should NOT be flipped after this pass */
    private boolean[] noFlip;

    /** Buffers to generate mipmaps for before this pass reads them */
    private int[] mipmapBuffers;

    /** Flip state snapshot — which buffers are flipped when this pass executes */
    private boolean[] readFlipState;

    /** Whether this pass is enabled (pack may disable passes by not providing source) */
    private boolean enabled = false;

    public CompositePass(Category category, int index) {
        this.category = category;
        this.index = index;
        this.name = category == Category.FINAL ? "final" : category.name().toLowerCase() + index;
        this.drawBuffers = new int[]{0};
        this.noFlip = new boolean[16]; // all false = flip all written buffers
        this.mipmapBuffers = new int[0];
    }

    // ── Configuration Setters (called during pack loading) ──

    public void setDrawBuffers(int[] drawBuffers) {
        this.drawBuffers = drawBuffers;
    }

    public void setShaderModules(long vertexModule, long fragmentModule) {
        this.vertexModule = vertexModule;
        this.fragmentModule = fragmentModule;
        this.enabled = true;
    }

    public void setComputeModule(long computeModule, int workGroupsX, int workGroupsY) {
        this.computeModule = computeModule;
        this.computeWorkGroupsX = workGroupsX;
        this.computeWorkGroupsY = workGroupsY;
    }

    public void setPipeline(long pipeline, long pipelineLayout) {
        this.pipeline = pipeline;
        this.pipelineLayout = pipelineLayout;
    }

    public void setComputePipeline(long computePipeline) {
        this.computePipeline = computePipeline;
    }

    public void setViewportScale(float x, float y) {
        this.viewportScaleX = x;
        this.viewportScaleY = y;
    }

    public void setBlendOverrides(MRTGraphicsPipeline.BlendOverride[] overrides) {
        this.blendOverrides = overrides;
    }

    public void setNoFlip(int bufferIndex, boolean noFlip) {
        if (bufferIndex >= 0 && bufferIndex < 16) {
            this.noFlip[bufferIndex] = noFlip;
        }
    }

    public void setMipmapBuffers(int[] buffers) {
        this.mipmapBuffers = buffers;
    }

    public void setReadFlipState(boolean[] flipState) {
        this.readFlipState = Arrays.copyOf(flipState, flipState.length);
    }

    // ── Getters ──

    public Category getCategory() { return category; }
    public int getIndex() { return index; }
    public String getName() { return name; }
    public int[] getDrawBuffers() { return drawBuffers; }
    public boolean isEnabled() { return enabled; }

    public long getVertexModule() { return vertexModule; }
    public long getFragmentModule() { return fragmentModule; }
    public long getPipeline() { return pipeline; }
    public long getPipelineLayout() { return pipelineLayout; }

    public boolean hasComputeShader() { return computeModule != 0; }
    public long getComputeModule() { return computeModule; }
    public long getComputePipeline() { return computePipeline; }
    public long getComputePipelineLayout() { return pipelineLayout; }
    public long getSamplerDescriptorSet() { return 0; /* TODO: descriptor set binding */ }
    public int getComputeWorkGroupsX() { return computeWorkGroupsX; }
    public int getComputeWorkGroupsY() { return computeWorkGroupsY; }

    public float getViewportScaleX() { return viewportScaleX; }
    public float getViewportScaleY() { return viewportScaleY; }

    public MRTGraphicsPipeline.BlendOverride[] getBlendOverrides() { return blendOverrides; }
    public int[] getMipmapBuffers() { return mipmapBuffers; }
    public boolean[] getReadFlipState() { return readFlipState; }

    /**
     * Returns which buffers should be flipped after this pass writes to them.
     */
    public int[] getBuffersToFlip() {
        return Arrays.stream(drawBuffers)
                .filter(i -> !noFlip[i])
                .toArray();
    }

    /**
     * Whether this pass has a custom viewport (not fullscreen).
     */
    public boolean hasCustomViewport() {
        return viewportScaleX != 1.0f || viewportScaleY != 1.0f;
    }

    @Override
    public String toString() {
        return String.format("CompositePass[%s, targets=%s, enabled=%s]",
                name, Arrays.toString(drawBuffers), enabled);
    }
}
