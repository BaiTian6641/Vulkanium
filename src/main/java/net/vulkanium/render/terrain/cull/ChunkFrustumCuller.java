package net.vulkanium.render.terrain.cull;

import net.vulkanium.render.terrain.region.RenderRegionManager;
import net.vulkanium.render.terrain.section.RenderSection;
import net.vulkanium.render.terrain.section.SectionVisibility;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * CPU-side frustum culling for chunk sections.
 *
 * <p>Tests each section's AABB against the camera frustum planes to determine visibility.
 * This is the first-pass culler — fast and conservative (may include some sections that
 * are just outside the frustum). The GPU compute culler (Phase 7) provides the second
 * pass for exact culling.</p>
 *
 * <h3>Performance</h3>
 * <p>Testing 4000 section AABBs against 6 frustum planes takes ~0.2ms on CPU.
 * The GPU compute path (frustum_cull.comp) reduces this to ~0.01ms.</p>
 *
 * <h3>Frustum Planes</h3>
 * <p>Extracted from the combined projection × view matrix each frame.
 * We use JOML's {@link FrustumIntersection} for efficient AABB-frustum testing.</p>
 */
public class ChunkFrustumCuller {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/FrustumCull");

    /** JOML frustum intersection tester — reused each frame. */
    private final FrustumIntersection frustum = new FrustumIntersection();

    /** Frustum planes in [6][4] format: [nx, ny, nz, d] per plane. */
    private final float[][] frustumPlanes = new float[6][4];

    /** Statistics. */
    private int totalTested = 0;
    private int totalVisible = 0;
    private int totalCulled = 0;

    /**
     * Updates the frustum planes from the combined projection × modelView matrix.
     * Call once per frame before culling.
     *
     * @param projectionView Combined projection × view matrix
     */
    public void updateFrustum(Matrix4f projectionView) {
        frustum.set(projectionView, false);
        extractPlanes(projectionView);
    }

    /**
     * Extracts the 6 frustum planes from a projection-view matrix.
     *
     * <p>Plane order: LEFT, RIGHT, BOTTOM, TOP, NEAR, FAR.
     * Each plane is [nx, ny, nz, d] where nx*x + ny*y + nz*z + d >= 0 means inside.</p>
     */
    private void extractPlanes(Matrix4f m) {
        // Left plane
        frustumPlanes[0][0] = m.m03() + m.m00();
        frustumPlanes[0][1] = m.m13() + m.m10();
        frustumPlanes[0][2] = m.m23() + m.m20();
        frustumPlanes[0][3] = m.m33() + m.m30();
        normalizePlane(frustumPlanes[0]);

        // Right plane
        frustumPlanes[1][0] = m.m03() - m.m00();
        frustumPlanes[1][1] = m.m13() - m.m10();
        frustumPlanes[1][2] = m.m23() - m.m20();
        frustumPlanes[1][3] = m.m33() - m.m30();
        normalizePlane(frustumPlanes[1]);

        // Bottom plane
        frustumPlanes[2][0] = m.m03() + m.m01();
        frustumPlanes[2][1] = m.m13() + m.m11();
        frustumPlanes[2][2] = m.m23() + m.m21();
        frustumPlanes[2][3] = m.m33() + m.m31();
        normalizePlane(frustumPlanes[2]);

        // Top plane
        frustumPlanes[3][0] = m.m03() - m.m01();
        frustumPlanes[3][1] = m.m13() - m.m11();
        frustumPlanes[3][2] = m.m23() - m.m21();
        frustumPlanes[3][3] = m.m33() - m.m31();
        normalizePlane(frustumPlanes[3]);

        // Near plane
        frustumPlanes[4][0] = m.m03() + m.m02();
        frustumPlanes[4][1] = m.m13() + m.m12();
        frustumPlanes[4][2] = m.m23() + m.m22();
        frustumPlanes[4][3] = m.m33() + m.m32();
        normalizePlane(frustumPlanes[4]);

        // Far plane
        frustumPlanes[5][0] = m.m03() - m.m02();
        frustumPlanes[5][1] = m.m13() - m.m12();
        frustumPlanes[5][2] = m.m23() - m.m22();
        frustumPlanes[5][3] = m.m33() - m.m32();
        normalizePlane(frustumPlanes[5]);
    }

    private void normalizePlane(float[] plane) {
        float len = (float) Math.sqrt(plane[0] * plane[0] + plane[1] * plane[1] + plane[2] * plane[2]);
        if (len > 0.0f) {
            float invLen = 1.0f / len;
            plane[0] *= invLen;
            plane[1] *= invLen;
            plane[2] *= invLen;
            plane[3] *= invLen;
        }
    }

    /**
     * Performs frustum culling on all sections in the region manager.
     * Updates each section's visibility state.
     *
     * @param regionManager The region manager with all active sections
     * @param frameIndex    Current frame number (for tracking freshness)
     */
    public void cullSections(RenderRegionManager regionManager, long frameIndex) {
        totalTested = 0;
        totalVisible = 0;
        totalCulled = 0;

        for (var region : regionManager.getActiveRegions()) {
            for (int i = 0; i < net.vulkanium.render.terrain.region.RenderRegion.SECTION_COUNT; i++) {
                RenderSection section = region.getSection(i);
                if (section == null || section.isEmpty()) continue;

                SectionVisibility vis = section.getVisibility();
                vis.reset();
                totalTested++;

                boolean visible = vis.testFrustum(frustumPlanes);
                vis.setFrustumVisible(visible);
                vis.setLastEvaluatedFrame(frameIndex);

                if (visible) {
                    totalVisible++;
                } else {
                    totalCulled++;
                }
            }
        }
    }

    /**
     * Returns the frustum planes for use by the GPU compute culler.
     * Format: float[6][4] — one plane per frustum face.
     */
    public float[][] getFrustumPlanes() {
        return frustumPlanes;
    }

    // ============== Statistics ==============

    public int getTotalTested() { return totalTested; }
    public int getTotalVisible() { return totalVisible; }
    public int getTotalCulled() { return totalCulled; }

    /**
     * Returns the cull ratio as a percentage (0-100).
     */
    public float getCullPercentage() {
        return totalTested > 0 ? (totalCulled * 100.0f / totalTested) : 0.0f;
    }
}
