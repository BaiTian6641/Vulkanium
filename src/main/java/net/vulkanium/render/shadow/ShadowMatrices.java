package net.vulkanium.render.shadow;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shadow projection and model-view matrix computation.
 *
 * <p>Implements both orthographic and perspective shadow projections,
 * along with grid snapping to reduce shadow shimmer when the camera moves.</p>
 *
 * <h3>Orthographic Projection (default)</h3>
 * <p>The shadow frustum is an axis-aligned box centered on the camera with
 * width and height = 2x halfPlaneLength. Near/far planes default to
 * {@code -100.05f} / {@code 156.0f} respectively.</p>
 *
 * <h3>Perspective Projection</h3>
 * <p>Used when shader pack sets {@code shadowMapFov}. Square aspect ratio
 * (1:1) for the shadow map. Uses the same near/far as orthographic.</p>
 *
 * <h3>Grid Snapping</h3>
 * <p>{@link #snapModelViewToGrid} prevents shadow "swimming" by quantizing
 * the model-view translation to multiples of {@code shadowIntervalSize}.
 * This ensures the shadow map texels don't shift sub-pixel amounts as the
 * camera moves smoothly.</p>
 *
 * <h3>Sun Angle Order of Operations</h3>
 * <ol>
 *   <li>Get sky angle from level time: {@code level.getTimeOfDay(tickDelta)}</li>
 *   <li>Compute sun angle: if skyAngle < 0.75 → skyAngle + 0.25, else skyAngle - 0.75</li>
 *   <li>Compute shadow angle: sunAngle, minus 0.5 if nighttime</li>
 *   <li>Build rotation from shadow angle → sky angle for matrix construction</li>
 * </ol>
 */
public class ShadowMatrices {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ShadowMat");

    // ── Iris-compatible defaults ──
    public static final float DEFAULT_NEAR = -100.05f;
    public static final float DEFAULT_FAR = 156.0f;

    // ── Current matrices (set per-frame) ──
    private final Matrix4f modelView = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();

    // ── Cached values for uniform upload ──
    private float currentShadowAngle;
    private float currentSunPathRotation;
    private float currentIntervalSize;

    /**
     * Creates an orthographic shadow projection matrix.
     * <p>
     * The frustum is a symmetric box: [-halfPlaneLength, +halfPlaneLength]
     * on both X and Y axes.
     *
     * @param halfPlaneLength Half-width/height of the shadow frustum (from shadowDistance)
     * @param nearPlane       Near plane distance (default -100.05)
     * @param farPlane        Far plane distance (default 156.0)
     * @return Orthographic projection matrix
     */
    public static Matrix4f createOrthoMatrix(float halfPlaneLength, float nearPlane, float farPlane) {
        float size = halfPlaneLength * 2.0f;
        return new Matrix4f().setOrthoSymmetric(size, size, nearPlane, farPlane);
    }

    /**
     * Creates a perspective shadow projection matrix.
     * <p>
     * Used when shader packs set shadowMapFov. Square 1:1 aspect ratio.
     * Uses a custom matrix construction matching the OptiFine/Iris convention
     * (W column [3][3] = 1, not 0).
     *
     * @param fov Field of view in degrees
     * @return Perspective projection matrix
     */
    public static Matrix4f createPerspectiveMatrix(float fov) {
        float yScale = (float) (1.0 / Math.tan(Math.toRadians(fov) * 0.5));
        float xScale = yScale; // aspect = 1.0 (square shadow map)
        float frustumLength = DEFAULT_FAR - DEFAULT_NEAR;

        Matrix4f mat = new Matrix4f();
        mat.m00(xScale);
        mat.m11(yScale);
        mat.m22(-((DEFAULT_FAR + DEFAULT_NEAR) / frustumLength));
        mat.m23(-1.0f);
        mat.m32(-((2.0f * DEFAULT_NEAR * DEFAULT_FAR) / frustumLength));
        mat.m33(1.0f); // Iris/OptiFine convention: 1 instead of 0
        return mat;
    }

    /**
     * Creates the shadow model-view matrix from the sun/moon angle.
     *
     * <h3>Rotation Sequence</h3>
     * <ol>
     *   <li>Identity</li>
     *   <li>Rotate 90° around X (look down the Y axis)</li>
     *   <li>Rotate {@code -skyAngle * 360°} around Z (sun orbit)</li>
     *   <li>Rotate {@code sunPathRotation} around X (axial tilt)</li>
     * </ol>
     *
     * @param shadowAngle     Shadow angle [0, 1) from {@link #computeShadowAngle}
     * @param sunPathRotation Pack's sunPathRotation in degrees
     * @return Model-view matrix
     */
    public static Matrix4f createBaselineModelView(float shadowAngle, float sunPathRotation) {
        float skyAngle;
        if (shadowAngle < 0.25f) {
            skyAngle = shadowAngle + 0.75f;
        } else {
            skyAngle = shadowAngle - 0.25f;
        }

        Matrix4f mat = new Matrix4f();
        mat.identity();
        mat.rotateX((float) Math.toRadians(90.0));                    // Look down
        mat.rotateZ((float) Math.toRadians(-skyAngle * 360.0f));      // Sun orbit
        mat.rotateX((float) Math.toRadians(sunPathRotation));          // Axial tilt
        return mat;
    }

    /**
     * Applies grid snapping to prevent shadow shimmer.
     *
     * <p>Quantizes the camera position to multiples of {@code intervalSize}
     * and applies the residual offset to the model-view matrix. This ensures
     * that shadow map texels don't jitter as the camera moves sub-texel amounts.</p>
     *
     * @param modelView    Matrix to modify in place
     * @param intervalSize Grid snap interval (shadowIntervalSize directive, default 2.0)
     * @param cameraX      Camera world X position
     * @param cameraY      Camera world Y position
     * @param cameraZ      Camera world Z position
     */
    public static void snapModelViewToGrid(Matrix4f modelView, float intervalSize,
                                            double cameraX, double cameraY, double cameraZ) {
        if (Math.abs(intervalSize) < 1e-6f) return; // No snapping

        float halfInterval = intervalSize / 2.0f;

        float offsetX = (float) (cameraX % intervalSize) - halfInterval;
        float offsetY = (float) (cameraY % intervalSize) - halfInterval;
        float offsetZ = (float) (cameraZ % intervalSize) - halfInterval;

        modelView.translate(offsetX, offsetY, offsetZ);
    }

    /**
     * Full model-view creation: baseline + grid snap.
     */
    public static Matrix4f createModelView(float shadowAngle, float intervalSize,
                                            float sunPathRotation,
                                            double cameraX, double cameraY, double cameraZ) {
        Matrix4f mat = createBaselineModelView(shadowAngle, sunPathRotation);
        snapModelViewToGrid(mat, intervalSize, cameraX, cameraY, cameraZ);
        return mat;
    }

    // ── Per-frame update ──

    /**
     * Computes and stores shadow matrices for the current frame.
     *
     * @param directives Shadow directives from pack
     * @param shadowAngle Shadow angle [0, 1)
     * @param sunPathRotation Sun path rotation in degrees
     * @param cameraPos Camera world position
     */
    public void update(ShadowDirectives directives, float shadowAngle,
                       float sunPathRotation, Vector3d cameraPos) {
        this.currentShadowAngle = shadowAngle;
        this.currentSunPathRotation = sunPathRotation;
        this.currentIntervalSize = directives.getIntervalSize();

        // Model-view
        modelView.set(createModelView(
                shadowAngle, directives.getIntervalSize(), sunPathRotation,
                cameraPos.x, cameraPos.y, cameraPos.z));

        // Projection
        Float fov = directives.getFov();
        float near = directives.getNearPlane();
        float far = directives.getFarPlane();

        if (fov != null) {
            projection.set(createPerspectiveMatrix(fov));
        } else {
            projection.set(createOrthoMatrix(directives.getDistance(), near, far));
        }
    }

    // ── Sun angle computation ──

    /**
     * Computes the sun angle from the sky angle (level time of day).
     *
     * @param skyAngle Level's getTimeOfDay(tickDelta) result [0, 1)
     * @return Sun angle [0, 1)
     */
    public static float computeSunAngle(float skyAngle) {
        return skyAngle < 0.75f ? skyAngle + 0.25f : skyAngle - 0.75f;
    }

    /**
     * Computes the shadow angle from the sun angle.
     * At night (sunAngle >= 0.5), the shadow source is the moon.
     *
     * @param sunAngle Sun angle from {@link #computeSunAngle}
     * @return Shadow angle [0, 1)
     */
    public static float computeShadowAngle(float sunAngle) {
        return sunAngle >= 0.5f ? sunAngle - 0.5f : sunAngle;
    }

    // ── Getters ──

    public Matrix4f getModelView() { return modelView; }
    public Matrix4f getProjection() { return projection; }
    public float getCurrentShadowAngle() { return currentShadowAngle; }
    public float getCurrentSunPathRotation() { return currentSunPathRotation; }
}
