package net.vulkanium.render.vertex;

import net.vulkanium.render.vertex.views.QuadView;
import net.vulkanium.render.vertex.views.TriView;
import org.joml.Vector3f;

/**
 * Computes per-quad face normals and tangent vectors from vertex position and
 * UV data.  Used during mesh building to fill the extended terrain vertex
 * attributes ({@code at_tangent}, overwritten face normals).
 *
 * <h3>Face normal</h3>
 * Computed via cross product of quad diagonals: (v2−v0) × (v3−v1).
 * This gives a consistent outward normal for convex, coplanar quads
 * regardless of vertex winding order within the quad.
 *
 * <h3>Tangent</h3>
 * Derived from UV edge gradients (Lengyel's method):
 * <ol>
 *   <li>Compute tangent T from UV deltas on edges (v0→v1) and (v0→v2)</li>
 *   <li>Compute bitangent B similarly</li>
 *   <li>Handedness W = sign(dot(B, T×N)) — +1 or -1</li>
 *   <li>Pack (T.x, T.y, T.z, W) into 4 signed bytes</li>
 * </ol>
 *
 * <p>Ported from Iris NormalHelper, licensed under LGPLv3.</p>
 */
public final class NormalHelper {

    private NormalHelper() {}

    /**
     * Compute the face normal of a quad via diagonal cross product.
     * Result is normalized and written into {@code saveTo}.
     */
    public static void computeFaceNormal(Vector3f saveTo, QuadView q) {
        final float dx0 = q.x(2) - q.x(0);
        final float dy0 = q.y(2) - q.y(0);
        final float dz0 = q.z(2) - q.z(0);
        final float dx1 = q.x(3) - q.x(1);
        final float dy1 = q.y(3) - q.y(1);
        final float dz1 = q.z(3) - q.z(1);

        float normX = dy0 * dz1 - dz0 * dy1;
        float normY = dz0 * dx1 - dx0 * dz1;
        float normZ = dx0 * dy1 - dy0 * dx1;

        float l = (float) Math.sqrt(normX * normX + normY * normY + normZ * normZ);
        if (l != 0) {
            normX /= l;
            normY /= l;
            normZ /= l;
        }
        saveTo.set(normX, normY, normZ);
    }

    /**
     * Compute tangent vector for a quad/tri using flat shading (face normal).
     * Returns packed (tangent.xyz, handedness) as a 32-bit NormI8.
     *
     * @param normalX face normal X
     * @param normalY face normal Y
     * @param normalZ face normal Z
     * @param t       polygon view with at least 3 vertices
     * @return packed tangent (NormI8 format) with handedness in W
     */
    public static int computeTangent(float normalX, float normalY, float normalZ, TriView t) {
        float x0 = t.x(0), y0 = t.y(0), z0 = t.z(0);
        float x1 = t.x(1), y1 = t.y(1), z1 = t.z(1);
        float x2 = t.x(2), y2 = t.y(2), z2 = t.z(2);

        float edge1x = x1 - x0, edge1y = y1 - y0, edge1z = z1 - z0;
        float edge2x = x2 - x0, edge2y = y2 - y0, edge2z = z2 - z0;

        float u0 = t.u(0), v0 = t.v(0);
        float u1 = t.u(1), v1 = t.v(1);
        float u2 = t.u(2), v2 = t.v(2);

        float deltaU1 = u1 - u0, deltaV1 = v1 - v0;
        float deltaU2 = u2 - u0, deltaV2 = v2 - v0;

        float fdenom = deltaU1 * deltaV2 - deltaU2 * deltaV1;
        float f = (fdenom == 0.0f) ? 1.0f : 1.0f / fdenom;

        float tangentx = f * (deltaV2 * edge1x - deltaV1 * edge2x);
        float tangenty = f * (deltaV2 * edge1y - deltaV1 * edge2y);
        float tangentz = f * (deltaV2 * edge1z - deltaV1 * edge2z);
        float tcoeff = rsqrt(tangentx * tangentx + tangenty * tangenty + tangentz * tangentz);
        tangentx *= tcoeff;
        tangenty *= tcoeff;
        tangentz *= tcoeff;

        float bitangentx = f * (-deltaU2 * edge1x + deltaU1 * edge2x);
        float bitangenty = f * (-deltaU2 * edge1y + deltaU1 * edge2y);
        float bitangentz = f * (-deltaU2 * edge1z + deltaU1 * edge2z);
        float bitcoeff = rsqrt(bitangentx * bitangentx + bitangenty * bitangenty + bitangentz * bitangentz);
        bitangentx *= bitcoeff;
        bitangenty *= bitcoeff;
        bitangentz *= bitcoeff;

        // predicted bitangent = tangent × normal
        float pbitangentx = tangenty * normalZ - tangentz * normalY;
        float pbitangenty = tangentz * normalX - tangentx * normalZ;
        float pbitangentz = tangentx * normalY - tangenty * normalX;

        // handedness = sign of dot(actual bitangent, predicted bitangent)
        float dot = bitangentx * pbitangentx + bitangenty * pbitangenty + bitangentz * pbitangentz;
        float tangentW = (dot < 0) ? -1.0f : 1.0f;

        return NormI8.pack(tangentx, tangenty, tangentz, tangentW);
    }

    /**
     * Compute tangent for smooth-shaded triangles.  Projects vertex positions
     * onto the plane perpendicular to the per-vertex normal before computing
     * the UV-gradient tangent.  This gives correct tangents even when the
     * per-vertex normal differs from the geometric face normal.
     */
    public static int computeTangentSmooth(float normalX, float normalY, float normalZ, TriView t) {
        // Project vertex positions onto the plane defined by the per-vertex normal
        float x0 = t.x(0), y0 = t.y(0), z0 = t.z(0);
        float x1 = t.x(1), y1 = t.y(1), z1 = t.z(1);
        float x2 = t.x(2), y2 = t.y(2), z2 = t.z(2);

        // Project each vertex onto the normal plane
        float d0 = x0 * normalX + y0 * normalY + z0 * normalZ;
        float d1 = x1 * normalX + y1 * normalY + z1 * normalZ;
        float d2 = x2 * normalX + y2 * normalY + z2 * normalZ;

        float px0 = x0 - d0 * normalX, py0 = y0 - d0 * normalY, pz0 = z0 - d0 * normalZ;
        float px1 = x1 - d1 * normalX, py1 = y1 - d1 * normalY, pz1 = z1 - d1 * normalZ;
        float px2 = x2 - d2 * normalX, py2 = y2 - d2 * normalY, pz2 = z2 - d2 * normalZ;

        float edge1x = px1 - px0, edge1y = py1 - py0, edge1z = pz1 - pz0;
        float edge2x = px2 - px0, edge2y = py2 - py0, edge2z = pz2 - pz0;

        float u0 = t.u(0), v0 = t.v(0);
        float u1 = t.u(1), v1 = t.v(1);
        float u2 = t.u(2), v2 = t.v(2);

        float deltaU1 = u1 - u0, deltaV1 = v1 - v0;
        float deltaU2 = u2 - u0, deltaV2 = v2 - v0;

        float fdenom = deltaU1 * deltaV2 - deltaU2 * deltaV1;
        float f = (fdenom == 0.0f) ? 1.0f : 1.0f / fdenom;

        float tangentx = f * (deltaV2 * edge1x - deltaV1 * edge2x);
        float tangenty = f * (deltaV2 * edge1y - deltaV1 * edge2y);
        float tangentz = f * (deltaV2 * edge1z - deltaV1 * edge2z);
        float tcoeff = rsqrt(tangentx * tangentx + tangenty * tangenty + tangentz * tangentz);
        tangentx *= tcoeff;
        tangenty *= tcoeff;
        tangentz *= tcoeff;

        float bitangentx = f * (-deltaU2 * edge1x + deltaU1 * edge2x);
        float bitangenty = f * (-deltaU2 * edge1y + deltaU1 * edge2y);
        float bitangentz = f * (-deltaU2 * edge1z + deltaU1 * edge2z);
        float bitcoeff = rsqrt(bitangentx * bitangentx + bitangenty * bitangenty + bitangentz * bitangentz);
        bitangentx *= bitcoeff;
        bitangenty *= bitcoeff;
        bitangentz *= bitcoeff;

        float pbitangentx = tangenty * normalZ - tangentz * normalY;
        float pbitangenty = tangentz * normalX - tangentx * normalZ;
        float pbitangentz = tangentx * normalY - tangenty * normalX;

        float dot = bitangentx * pbitangentx + bitangenty * pbitangenty + bitangentz * pbitangentz;
        float tangentW = (dot < 0) ? -1.0f : 1.0f;

        return NormI8.pack(tangentx, tangenty, tangentz, tangentW);
    }

    /**
     * Inverse square root with safe zero handling.
     */
    private static float rsqrt(float value) {
        if (value == 0.0f) return 1.0f;
        return (float) (1.0 / Math.sqrt(value));
    }
}
