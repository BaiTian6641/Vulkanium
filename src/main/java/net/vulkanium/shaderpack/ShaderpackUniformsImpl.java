package net.vulkanium.shaderpack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Concrete implementation of ShaderpackUniforms that reads from MC game state.
 *
 * <p>Called once per frame to capture the current game state (camera, time,
 * weather, etc.) and expose it to shaderpack programs via uniform buffers.</p>
 */
public class ShaderpackUniformsImpl implements ShaderpackUniforms {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ShaderUniforms");

    // ── Cached matrices ──
    private final Matrix4f modelViewMatrix = new Matrix4f();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f prevModelViewMatrix = new Matrix4f();
    private final Matrix4f prevProjectionMatrix = new Matrix4f();
    private final Matrix4f shadowProjectionMatrix = new Matrix4f();
    private final Matrix4f shadowModelViewMatrix = new Matrix4f();

    // ── Cached vectors ──
    private final Vector3f sunPosition = new Vector3f();
    private final Vector3f moonPosition = new Vector3f();
    private final Vector3f upPosition = new Vector3f(0, 1, 0);
    private final Vector3f cameraPosition = new Vector3f();
    private final Vector3f prevCameraPosition = new Vector3f();

    // ── Time ──
    private int worldTime = 0;
    private int worldDay = 0;
    private float frameTimeCounter = 0;
    private float sunAngle = 0;
    private float shadowAngle = 0;

    // ── Atmosphere ──
    private float rainStrength = 0;
    private float wetness = 0;
    private final float[] fogColor = {1, 1, 1, 1};
    private final float[] skyColor = {0.5f, 0.7f, 1.0f};

    // ── Screen ──
    private float viewWidth = 1920;
    private float viewHeight = 1080;
    private float near = 0.05f;
    private float far = 1000f;

    // ── Misc ──
    private int heldItemId = -1;
    private int heldItemId2 = -1;
    private int isEyeInWater = 0;
    private int renderDistance = 12;
    private float biomeTemperature = 0.5f;
    private float biomeRainfall = 0.5f;

    // ── Frame timing ──
    private long lastUpdateNs = System.nanoTime();

    /**
     * Updates all uniform values from the current MC game state.
     * Called once per frame from the shaderpack pipeline.
     */
    public void updateFromGameState() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return;

        ClientLevel level = mc.level;
        Camera camera = mc.gameRenderer.getMainCamera();

        // Time
        long now = System.nanoTime();
        float deltaSec = (now - lastUpdateNs) / 1_000_000_000.0f;
        lastUpdateNs = now;
        frameTimeCounter += deltaSec;

        worldTime = (int) (level.getDayTime() % 24000L);
        worldDay = (int) (level.getDayTime() / 24000L);
        // Use partial tick from world render start for smooth interpolation
        float partialTick = net.vulkanium.Vulkanium.getCurrentPartialTick();
        float skyAngle = level.getTimeOfDay(partialTick);
        // Iris conversion: skyAngle → sunAngle (0 = noon, 0.5 = midnight)
        sunAngle = skyAngle < 0.75f ? skyAngle + 0.25f : skyAngle - 0.75f;
        shadowAngle = sunAngle < 0.5f ? sunAngle : sunAngle - 0.5f;

        // Camera position
        prevCameraPosition.set(cameraPosition);
        cameraPosition.set(
                (float) camera.getPosition().x,
                (float) camera.getPosition().y,
                (float) camera.getPosition().z);

        // Matrices from VRenderSystem — capture BEFORE sun position calculation
        // so we have the correct modelView for eye-space transform.
        // Use world-render snapshots to avoid GUI-overwritten matrices.
        prevModelViewMatrix.set(modelViewMatrix);
        prevProjectionMatrix.set(projectionMatrix);
        modelViewMatrix.set(net.vulkanium.compat.VRenderSystem.getWorldRenderModelView());
        projectionMatrix.set(net.vulkanium.compat.VRenderSystem.getWorldRenderProjection());

        // Sun/Moon position — use Iris CelestialUniforms algorithm:
        // Start with (0, 100, 0), transform by gbufferModelView * rotateY(-90°) *
        // rotateZ(sunPathRotation) * rotateX(skyAngle * 360°)
        Matrix4f celestial = new Matrix4f(modelViewMatrix);
        celestial.rotate((float) Math.toRadians(-90.0f), 0.0f, 1.0f, 0.0f);
        // sunPathRotation: default 0.0f, TODO: parse from shaderpack properties
        celestial.rotate((float) Math.toRadians(0.0f), 0.0f, 0.0f, 1.0f);
        celestial.rotate((float) Math.toRadians(skyAngle * 360.0f), 1.0f, 0.0f, 0.0f);

        org.joml.Vector4f sunVec = new org.joml.Vector4f(0.0f, 100.0f, 0.0f, 0.0f);
        celestial.transform(sunVec);
        sunPosition.set(sunVec.x, sunVec.y, sunVec.z);

        org.joml.Vector4f moonVec = new org.joml.Vector4f(0.0f, -100.0f, 0.0f, 0.0f);
        celestial.transform(moonVec);
        moonPosition.set(moonVec.x, moonVec.y, moonVec.z);

        // Up position: gbufferModelView * rotateY(-90°) only (no skyAngle rotation)
        Matrix4f preCelestial = new Matrix4f(modelViewMatrix);
        preCelestial.rotate((float) Math.toRadians(-90.0f), 0.0f, 1.0f, 0.0f);
        org.joml.Vector4f upVec = new org.joml.Vector4f(0.0f, 100.0f, 0.0f, 0.0f);
        preCelestial.transform(upVec);
        upPosition.set(upVec.x, upVec.y, upVec.z);

        // Shadow matrices (orthographic from sun/moon world-space direction)
        // Use celestial rotation WITHOUT gbufferModelView for world-space direction
        Matrix4f celestialWorld = new Matrix4f();
        celestialWorld.rotate((float) Math.toRadians(-90.0f), 0.0f, 1.0f, 0.0f);
        celestialWorld.rotate((float) Math.toRadians(skyAngle * 360.0f), 1.0f, 0.0f, 0.0f);
        org.joml.Vector4f shadowDir = new org.joml.Vector4f(0.0f, 1.0f, 0.0f, 0.0f);
        celestialWorld.transform(shadowDir);
        float sDirX = sunAngle <= 0.5f ? shadowDir.x : -shadowDir.x;
        float sDirY = sunAngle <= 0.5f ? shadowDir.y : -shadowDir.y;
        float sDirZ = sunAngle <= 0.5f ? shadowDir.z : -shadowDir.z;
        float shadowDist = 128.0f;
        shadowProjectionMatrix.identity().ortho(-shadowDist, shadowDist,
                -shadowDist, shadowDist, -shadowDist * 2, shadowDist * 2);
        shadowModelViewMatrix.identity().lookAt(
                sDirX * shadowDist, sDirY * shadowDist, sDirZ * shadowDist,
                0, 0, 0,
                0, 1, 0);

        // Weather
        rainStrength = level.getRainLevel(1.0f);
        wetness = level.getRainLevel(1.0f); // Simplified — Iris tracks wetness separately

        // Sky color — read from the actual level, matching Iris CommonUniforms.getSkyColor()
        if (mc.cameraEntity != null) {
            net.minecraft.world.phys.Vec3 sc = level.getSkyColor(mc.cameraEntity.position(), partialTick);
            skyColor[0] = (float) sc.x;
            skyColor[1] = (float) sc.y;
            skyColor[2] = (float) sc.z;
        }

        // Fog color — capture from RenderSystem fog state
        fogColor[0] = net.vulkanium.compat.VRenderSystem.getFogColorR();
        fogColor[1] = net.vulkanium.compat.VRenderSystem.getFogColorG();
        fogColor[2] = net.vulkanium.compat.VRenderSystem.getFogColorB();
        fogColor[3] = net.vulkanium.compat.VRenderSystem.getFogColorA();

        // Screen
        viewWidth = mc.getWindow().getWidth();
        viewHeight = mc.getWindow().getHeight();
        renderDistance = mc.options.renderDistance().get();
        far = renderDistance * 16.0f;

        // Eye status
        isEyeInWater = camera.getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE ? 1 : 0;
    }

    // ─── Matrix getters ──────────────────────────────────────────────

    @Override public Matrix4f getModelViewMatrix() { return modelViewMatrix; }
    @Override public Matrix4f getProjectionMatrix() { return projectionMatrix; }
    @Override public Matrix4f getPreviousModelViewMatrix() { return prevModelViewMatrix; }
    @Override public Matrix4f getPreviousProjectionMatrix() { return prevProjectionMatrix; }
    @Override public Matrix4f getShadowProjectionMatrix() { return shadowProjectionMatrix; }
    @Override public Matrix4f getShadowModelViewMatrix() { return shadowModelViewMatrix; }

    // ─── Vector getters ──────────────────────────────────────────────

    @Override public Vector3f getSunPosition() { return sunPosition; }
    @Override public Vector3f getMoonPosition() { return moonPosition; }
    @Override public Vector3f getUpPosition() { return upPosition; }
    @Override public Vector3f getCameraPosition() { return cameraPosition; }
    @Override public Vector3f getPreviousCameraPosition() { return prevCameraPosition; }

    // ─── Time getters ────────────────────────────────────────────────

    @Override public int getWorldTime() { return worldTime; }
    @Override public int getWorldDay() { return worldDay; }
    @Override public float getFrameTimeCounter() { return frameTimeCounter; }
    @Override public float getSunAngle() { return sunAngle; }
    @Override public float getShadowAngle() { return shadowAngle; }

    // ─── Atmosphere getters ──────────────────────────────────────────

    @Override public float getRainStrength() { return rainStrength; }
    @Override public float getWetness() { return wetness; }
    @Override public float[] getFogColor() { return fogColor; }
    @Override public float[] getSkyColor() { return skyColor; }

    // ─── Screen getters ──────────────────────────────────────────────

    @Override public float getViewWidth() { return viewWidth; }
    @Override public float getViewHeight() { return viewHeight; }
    @Override public float getAspectRatio() { return viewWidth / viewHeight; }
    @Override public float getNear() { return near; }
    @Override public float getFar() { return far; }

    // ─── Misc getters ────────────────────────────────────────────────

    @Override public int getHeldItemId() { return heldItemId; }
    @Override public int getHeldItemId2() { return heldItemId2; }
    @Override public int getIsEyeInWater() { return isEyeInWater; }
    @Override public int getRenderDistance() { return renderDistance; }
    @Override public float getBiomeTemperature() { return biomeTemperature; }
    @Override public float getBiomeRainfall() { return biomeRainfall; }
}
