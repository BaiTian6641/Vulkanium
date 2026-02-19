package net.vulkanium.shaderpack;

import javax.annotation.Nullable;

/**
 * Holds the raw GLSL source code for a single shaderpack program.
 *
 * <p>Each program corresponds to a {@link ProgramId} and may have vertex,
 * fragment, geometry, tessellation control, tessellation evaluation, and
 * compute shader stages. The most common combination is vertex + fragment.</p>
 *
 * <h3>Resolution Order</h3>
 * <p>Source files are resolved from the shaderpack's {@code shaders/} directory
 * using the {@link ProgramId}'s source name with appropriate extensions:</p>
 * <ul>
 *   <li>{@code .vsh} — Vertex shader</li>
 *   <li>{@code .fsh} — Fragment shader</li>
 *   <li>{@code .gsh} — Geometry shader</li>
 *   <li>{@code .csh} — Compute shader</li>
 *   <li>{@code .tcs} — Tessellation control shader</li>
 *   <li>{@code .tes} — Tessellation evaluation shader</li>
 * </ul>
 */
public record ProgramSource(
        ProgramId programId,
        @Nullable String vertexSource,
        @Nullable String fragmentSource,
        @Nullable String geometrySource,
        @Nullable String computeSource,
        @Nullable String tessControlSource,
        @Nullable String tessEvalSource
) {
    /**
     * Creates a simple vertex + fragment program source.
     */
    public static ProgramSource of(ProgramId id, String vertex, String fragment) {
        return new ProgramSource(id, vertex, fragment, null, null, null, null);
    }

    /**
     * Creates a vertex + fragment + geometry program source.
     */
    public static ProgramSource withGeometry(ProgramId id, String vertex, String fragment, String geometry) {
        return new ProgramSource(id, vertex, fragment, geometry, null, null, null);
    }

    /**
     * Creates a compute-only program source (for composite/deferred compute passes).
     */
    public static ProgramSource computeOnly(ProgramId id, String compute) {
        return new ProgramSource(id, null, null, null, compute, null, null);
    }

    /** Whether this source has a vertex shader */
    public boolean hasVertex() { return vertexSource != null && !vertexSource.isEmpty(); }

    /** Whether this source has a fragment shader */
    public boolean hasFragment() { return fragmentSource != null && !fragmentSource.isEmpty(); }

    /** Whether this source has a geometry shader */
    public boolean hasGeometry() { return geometrySource != null && !geometrySource.isEmpty(); }

    /** Whether this source has a compute shader */
    public boolean hasCompute() { return computeSource != null && !computeSource.isEmpty(); }

    /** Whether this source has tessellation shaders */
    public boolean hasTessellation() {
        return (tessControlSource != null && !tessControlSource.isEmpty())
                || (tessEvalSource != null && !tessEvalSource.isEmpty());
    }

    /** Whether this is a valid graphics program (at minimum vertex + fragment) */
    public boolean isValidGraphics() { return hasVertex() && hasFragment(); }

    /** Whether this is a valid compute program */
    public boolean isValidCompute() { return hasCompute(); }

    /** Whether this source has any shader stage */
    public boolean hasAnyStage() { return hasVertex() || hasFragment() || hasGeometry() || hasCompute() || hasTessellation(); }
}
