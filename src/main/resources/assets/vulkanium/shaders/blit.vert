#version 450

// Vulkanium Fullscreen Blit Vertex Shader
// Generates a fullscreen triangle from gl_VertexIndex — no vertex buffer needed.

layout(location = 0) out vec2 fragTexCoord;

void main() {
    // Generate fullscreen triangle covering [-1,1] clip space
    // Vertex 0: (-1, -1), Vertex 1: (3, -1), Vertex 2: (-1, 3)
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    fragTexCoord = pos;
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}
