#version 450

// Vulkanium Fullscreen Blit Fragment Shader
// Composites a texture onto the screen (final pass / post-processing blit).

layout(location = 0) in vec2 fragTexCoord;

layout(set = 1, binding = 0) uniform sampler2D inputTexture;

layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(inputTexture, fragTexCoord);
}
