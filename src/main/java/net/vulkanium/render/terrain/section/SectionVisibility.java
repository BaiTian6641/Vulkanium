package net.vulkanium.render.terrain.section;

/**
 * Visibility state for a chunk section, tracking culling results.
 *
 * <p>Updated each frame by the frustum culler and occlusion culler.
 * Contains both CPU-computed and GPU-computed visibility flags.</p>
 */
public class SectionVisibility {

    /** Whether the section is within the frustum (CPU-side pre-cull). */
    private boolean frustumVisible = false;

    /** Whether the section is occluded by other geometry (graph-based or HW query). */
    private boolean occluded = false;

    /** The frame index when visibility was last evaluated. */
    private long lastEvaluatedFrame = -1;

    /** Distance squared from camera to section center — used for priority sorting. */
    private float distanceSq = Float.MAX_VALUE;

    /** The AABB bounds of this section in world space (minX, minY, minZ, maxX, maxY, maxZ). */
    private final float[] aabb = new float[6];

    /**
     * Returns true if this section should be rendered (inside frustum and not occluded).
     */
    public boolean isVisible() {
        return frustumVisible && !occluded;
    }

    public boolean isFrustumVisible() { return frustumVisible; }
    public void setFrustumVisible(boolean visible) { this.frustumVisible = visible; }

    public boolean isOccluded() { return occluded; }
    public void setOccluded(boolean occluded) { this.occluded = occluded; }

    public long getLastEvaluatedFrame() { return lastEvaluatedFrame; }
    public void setLastEvaluatedFrame(long frame) { this.lastEvaluatedFrame = frame; }

    public float getDistanceSq() { return distanceSq; }

    /**
     * Updates the distance from camera to section center.
     *
     * @param cameraX Camera X position in world space
     * @param cameraY Camera Y position in world space
     * @param cameraZ Camera Z position in world space
     */
    public void updateDistance(double cameraX, double cameraY, double cameraZ) {
        float centerX = (aabb[0] + aabb[3]) * 0.5f;
        float centerY = (aabb[1] + aabb[4]) * 0.5f;
        float centerZ = (aabb[2] + aabb[5]) * 0.5f;
        float dx = (float) (centerX - cameraX);
        float dy = (float) (centerY - cameraY);
        float dz = (float) (centerZ - cameraZ);
        this.distanceSq = dx * dx + dy * dy + dz * dz;
    }

    /**
     * Sets the world-space AABB for this section.
     *
     * @param sectionX Section X coordinate (in sections, not blocks)
     * @param sectionY Section Y coordinate (in sections, not blocks)
     * @param sectionZ Section Z coordinate (in sections, not blocks)
     */
    public void setAABB(int sectionX, int sectionY, int sectionZ) {
        aabb[0] = sectionX * 16.0f;
        aabb[1] = sectionY * 16.0f;
        aabb[2] = sectionZ * 16.0f;
        aabb[3] = aabb[0] + 16.0f;
        aabb[4] = aabb[1] + 16.0f;
        aabb[5] = aabb[2] + 16.0f;
    }

    /**
     * Returns the AABB array: [minX, minY, minZ, maxX, maxY, maxZ].
     */
    public float[] getAABB() {
        return aabb;
    }

    /**
     * Tests this section's AABB against 6 frustum planes.
     *
     * @param planes 6×4 float array: [nx, ny, nz, d] per plane
     * @return true if the AABB is at least partially inside the frustum
     */
    public boolean testFrustum(float[][] planes) {
        for (int i = 0; i < 6; i++) {
            float px = planes[i][0] > 0 ? aabb[3] : aabb[0];
            float py = planes[i][1] > 0 ? aabb[4] : aabb[1];
            float pz = planes[i][2] > 0 ? aabb[5] : aabb[2];

            if (planes[i][0] * px + planes[i][1] * py + planes[i][2] * pz + planes[i][3] < 0) {
                return false; // Fully outside this plane
            }
        }
        return true; // At least partially inside all planes
    }

    /**
     * Resets visibility state for a new frame.
     */
    public void reset() {
        frustumVisible = false;
        occluded = false;
    }
}
