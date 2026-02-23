# Vulkanium — Next-Generation Vulkan Rendering Engine for Minecraft

> **Date:** 2026-02-21  
> **Status:** IN PROGRESS — All 12 phases have substantial code scaffolding; runtime integration is partially complete  
> **Codename:** Vulkanium  
> **Based on:** VulkanMod (Vulkan core) + Sodium (optimization patterns) + Iris (shader compat layer)  
> **Goal:** A ground-up reconstruction of VulkanMod that delivers massive Vulkan performance gains while maintaining full OptiFine/Iris shader pack compatibility

---

## Table of Contents

1. [Vision & Goals](#vision--goals)
2. [Why VulkanMod Needs Reconstruction](#why-vulkanmod-needs-reconstruction)
3. [Reference Architecture Analysis](#reference-architecture-analysis)
4. [Iris ↔ Vulkanium Comparison](#iris-↔-vulkanium-comparison)
5. [Vulkanium Architecture](#vulkanium-architecture)
5. [Module Structure](#module-structure)
6. [Phase Plan](#phase-plan)
   - [Phase 0 — Foundation: Core Vulkan Abstraction](#phase-0--foundation-core-vulkan-abstraction)
   - [Phase 1 — Chunk Rendering Engine](#phase-1--chunk-rendering-engine)
   - [Phase 2 — GLSL Compatibility Layer (OptiFine Bridge)](#phase-2--glsl-compatibility-layer-optifine-bridge)
   - [Phase 3 — Multi-Render Target (MRT) & G-Buffer System](#phase-3--multi-render-target-mrt--g-buffer-system)
   - [Phase 4 — Composite/Deferred Pass Engine](#phase-4--compositedeferred-pass-engine)
   - [Phase 5 — Shadow Mapping System](#phase-5--shadow-mapping-system)
   - [Phase 6 — Entity/Sky/Particle/Hand/Weather Pipeline](#phase-6--entityskyparticlehandweather-pipeline)
   - [Phase 7 — Vulkan-Native Performance Optimizations](#phase-7--vulkan-native-performance-optimizations)
   - [Phase 8 — Polish, Caching & UX](#phase-8--polish-caching--ux)
7. [OptiFine GLSL Compatibility Strategy](#optifine-glsl-compatibility-strategy)
8. [Sodium vs VulkanMod vs Vulkanium Comparison](#sodium-vs-vulkanmod-vs-vulkanium-comparison)
9. [File Inventory](#file-inventory)
10. [Risk Assessment & Mitigations](#risk-assessment--mitigations)
11. [Performance Targets](#performance-targets)
12. [Current Progress Checklist (Audit: 2026-02-19)](#current-progress-checklist-audit-2026-02-19)

---

## Current Progress Checklist (Audit: 2026-02-21)

> **Audit basis:** `src/main/java/net/vulkanium` + `src/main/resources/assets/vulkanium/shaders` + `src/main/resources/vulkanium.mixins.json`  
> **Verification:** `./gradlew classes -x test` → BUILD SUCCESSFUL  
> **Measured inventory:** 244 Java files, 37 mixin classes, 12 shader files

### Checklist Snapshot

- [x] Core project builds successfully
- [x] Core subsystem scaffolding is present (core/resource/render/world/api/compute/rt)
- [x] Expanded mixin surface is present in config and sources
- [x] Shaderpack loading, preprocessing, and compiler/cache classes are present
- [x] External shaderpack discovery and load path works at runtime (zip + directory sources)
- [x] Real-pack smoke load succeeded (`iterationRP Alpha 0.8.11`: 48/48 programs compiled, 0 failed)
- [x] Chunk shadow layer-mask path and frustum-aware terrain filtering are implemented
- [x] G-buffer depth copy/clear commands and mip-level tracking are implemented
- [x] Shadow renderer command path now includes pass begin/end hooks + mipmap generation + image cleanup
- [x] Shadow entity rendering and pipeline management are implemented
- [x] G-buffer resizing and texture binding updates are implemented
- [x] Compute shader support with layout transitions and descriptor pools are implemented
- [x] HDR support with color space conversion is implemented
- [x] Shaderpack custom image and SSBO management are implemented
- [x] Feature flags for shader pack compatibility and blending logic are implemented
- [x] Shaderpack slider support and preprocessor conditionals are implemented
- [x] Front face winding and viewport handling for positive-height rendering are implemented
- [ ] Vulkan command recording path is fully implemented end-to-end
- [ ] Descriptor set lifecycle is fully implemented across all passes
- [ ] Shaderpack runtime compatibility validated against real pack matrix
- [ ] Ray tracing path is production-ready (BLAS/TLAS/SBT/trace dispatch)

### Phase-by-Phase Status

| Phase | Status | Audit Notes |
|------|--------|-------------|
| 0 — Core Vulkan | 🟡 Partial | Core classes are in place and compiling; several Vulkan command/barrier paths still marked TODO. |
| 1 — Chunk Rendering | 🟡 Partial | Region/section/cull/upload/build structure exists; shadow-layer dispatch and frustum-aware shadow filtering are now wired, but broader runtime validation remains. |
| 2 — GLSL Compat | 🟡 Partial | Transformer/preprocessor/compiler/UBO bridge exist; external pack discovery/load works and at least one real pack compiles cleanly. Feature flags, slider support, and preprocessor conditionals are implemented. |
| 3 — MRT/G-Buffer | 🟡 Partial | MRT target/pass/pipeline classes exist; depth copy/clear command recording, mip-level handling, G-buffer resizing, and texture binding updates are implemented. |
| 4 — Composite/Deferred | 🟡 Partial | Composite pass manager/final pass exist; descriptor and binding completion remains. |
| 5 — Shadows | 🟡 Partial | Shadow structures exist; pass begin/end hooks, viewport restore, shadow mipmap generation, shadow image cleanup, and shadow entity rendering are implemented. |
| 6 — Entity/Sky/Particle | 🟡 Partial | Program and renderer scaffolding exist; shadow entity rendering is implemented, but full pass-by-pass runtime parity still pending. |
| 7 — Vulkan Perf | 🟡 Partial | Async transfer/parallel recording/culling modules exist; deeper Vulkan integration still pending. |
| 8 — Polish/UX | 🟡 Partial | Cache/progress/debug/config/overlay components exist and compile. HDR support with color space conversion is implemented. |
| 9 — Compute Platform | 🟡 Partial | Compute scheduler/allocator/modules exist; compute shader support with layout transitions and descriptor pools are implemented. |
| 10 — Ray Tracing | 🟠 Scaffolded | RT classes/shaders exist, but BLAS/TLAS/SBT/trace pipeline has significant TODO coverage. |
| 11 — Module System | 🟡 Partial | Module API and built-ins exist; runtime conflict handling and full integration require more validation. |

---

## Vision & Goals

### The Problem

Minecraft's rendering is held back by OpenGL — a 30-year-old API with:
- **Single-threaded command submission** (CPU bottleneck)
- **Driver overhead** on every draw call (state validation, hazard tracking)
- **No explicit memory control** (driver guesses allocation strategies)
- **No multi-queue** (can't upload textures while rendering)

**Sodium** optimizes within OpenGL's constraints (batching, better vertex formats, async chunk building) but fundamentally can't escape the API's overhead.

**VulkanMod** replaces OpenGL with Vulkan but:
- Only supports single color attachment (no MRT)
- No shader pack compatibility (no OptiFine GLSL support)
- No shadow maps, no composite passes
- Compressed vertex format missing normals/tangents
- Single descriptor set layout
- No multi-threaded command recording

### The Vision

**Vulkanium** is a from-scratch Vulkan rendering engine that:

1. **Eliminates the OpenGL bottleneck** — 100% Vulkan rendering path
2. **Supports full OptiFine/Iris shader packs** — GLSL→SPIR-V compilation with an intelligent compatibility layer that handles legacy OptiFine GLSL idioms
3. **Leverages Vulkan's unique strengths** — multi-threaded command recording, explicit memory management, compute shaders for chunk building, async texture uploads
4. **Achieves Sodium-level optimization philosophy** — compressed vertex formats, region-based chunk management, occlusion culling, but on Vulkan
5. **Maintains a clean abstraction** — shader packs don't need to know they're running on Vulkan

### Performance Targets

| Scenario | Sodium (OpenGL) | VulkanMod (Current) | Vulkanium (Target) |
|----------|----------------|--------------------|--------------------|
| Vanilla (no shaders), 16 chunk RD | 400 fps | 450 fps | **600+ fps** |
| BSL Shaders, 16 chunk RD | 120 fps | N/A (unsupported) | **180+ fps** |
| Complementary Reimagined, 12 RD | 80 fps | N/A (unsupported) | **130+ fps** |
| SEUS Renewed, 12 RD | 60 fps | N/A (unsupported) | **100+ fps** |
| Vanilla, 32 chunk RD | 150 fps | 180 fps | **280+ fps** |

*(Baseline: RTX 3060, Ryzen 5 5600X, 1080p)*

---

## Why VulkanMod Needs Reconstruction

### VulkanMod's Current Architecture Problems

After thorough analysis of VulkanMod's codebase (`/VulkanMod/src/`), these are the fundamental issues:

| Problem | VulkanMod Code | Impact |
|---------|---------------|--------|
| **Single color attachment** | `Framebuffer.Builder` validates `colorAttachments <= 1` | No MRT → no G-buffer → no deferred shading |
| **Single descriptor set** | `Pipeline` creates one `VkDescriptorSetLayout` (set 0) | All UBOs + samplers in one set → descriptor pressure |
| **No geometry/tessellation stages** | `GraphicsPipeline` only vertex + fragment | Geometry shader packs fail entirely |
| **Compressed terrain format missing data** | `COMPRESSED_TERRAIN`: `ivec4`+`vec4`+`uvec2` only | No normals, tangents, entity IDs, mid-block offsets → PBR/normal mapping impossible |
| **Single command buffer** | `Renderer.currentCmdBuffer` — one per frame | No multi-threaded recording, no secondary buffers |
| **Monolithic renderer** | `Renderer.java` (717 lines) manages everything | Frame sync, pipeline binding, draw dispatch all coupled |
| **No render pass abstraction** | `RenderPass` tightly coupled to `Framebuffer` | Can't create custom pass configurations |
| **30 MB staging buffers** | Fixed 30 MB per frame (`StagingBuffer`) | Wastes memory or too small for large scenes |
| **No transfer queue** | All uploads on graphics queue | Uploads stall rendering |
| **Fixed frame queue (2)** | `Initializer.CONFIG.frameQueueSize` (configurable but untested >2) | Triple buffering not reliable |

### What VulkanMod Gets Right (Keep These)

| Strength | Code | Why Keep |
|----------|------|----------|
| **VMA integration** | `Vulkan.createVma()` → `vmaCreateAllocator` | Correct memory allocation strategy |
| **Shaderc integration** | `SPIRVUtils` — `shaderc_compile_into_spv()` | Runtime GLSL→SPIR-V compilation works |
| **GLFW/Vulkan surface** | `glfwCreateWindowSurface()` | Standard surface creation |
| **Swapchain management** | `SwapChain.java` | Correct present mode selection |
| **Pipeline state hashing** | `GraphicsPipeline.getHandle(PipelineState)` | Avoids redundant pipeline recreation |
| **Chunk area management** | `ChunkArea`/`ChunkAreaManager` | Spatial organization of chunks |
| **Indirect draw buffers** | `IndirectBuffer.java` | Multi-draw-indirect ready |

---

## Reference Architecture Analysis

### From Sodium: Optimization Patterns to Adopt

| Sodium Pattern | Code Location | Vulkanium Adaptation |
|---------------|---------------|---------------------|
| **Region-based chunk storage** | `RenderRegion`/`RenderRegionManager` | Vulkan buffer sub-allocation per region → one `vkCmdDrawIndexedIndirect` per region |
| **Occlusion culling** | `OcclusionCuller` (breadth-first graph traversal) | Vulkan occlusion queries for hardware-accelerated culling |
| **Compact vertex format** | `ChunkMeshFormats.COMPACT` (20 bytes/vertex) | Match Sodium's format + add packed normal/tangent (24 bytes) |
| **Multi-threaded chunk building** | `ChunkBuilder` (thread pool) | Keep CPU-side, but use compute shader for mesh sorting |
| **Terrain render passes** | `DefaultTerrainRenderPasses` (SOLID/CUTOUT/TRANSLUCENT) | Same pass structure, but as Vulkan subpasses |
| **Sprite animation tracking** | `SpriteUtil` in `RenderSectionManager` | Async texture updates via transfer queue |
| **Sorted render lists** | `SortedRenderLists` | GPU-side sorting via compute shader for translucents |
| **Shader binding points** | `ChunkShaderBindingPoints` | Map to Vulkan descriptor set bindings |

### From VulkanMod: Vulkan Patterns to Keep/Improve

| VulkanMod Pattern | Keep/Improve | Vulkanium Approach |
|-------------------|-------------|-------------------|
| VMA allocation | Keep | Same VMA, but with dedicated allocation pools |
| Shaderc compilation | Keep + Improve | Add caching, parallel compilation, error recovery |
| Swapchain | Keep + Improve | Add HDR support, VK_EXT_swapchain_maintenance1 |
| Pipeline cache | Keep + Improve | Persist to disk, cross-session caching |
| Staging buffer | Improve | Dynamic sizing, transfer queue, ring buffer |
| Descriptor sets | Rebuild | Multi-set layout (set 0: UBOs, set 1: samplers, set 2: images) |
| Framebuffer | Rebuild | Full MRT support, dynamic render passes |
| Renderer | Rebuild | Decompose into FrameOrchestrator + PassRecorder + DrawDispatcher |

### From Iris: Shader Pack Compatibility to Integrate

| Iris Component | Purpose | Vulkanium Integration |
|---------------|---------|----------------------|
| `TransformPatcher` (ANTLR4) | AST-based GLSL transformation | Core of the GLSL compatibility layer |

---

## Iris ↔ Vulkanium Comparison

For a more extensive, narrative comparison of functional differences between the original Iris renderer and the Vulkanium project, see [iris-vulkanium-differences.md](iris-vulkanium-differences.md).  
This companion document covers rendering backend changes, shader compatibility, new Vulkan‑native features, and the high‑level roadmap.

---

| `VulkanRenderingPipeline` | Extracts pack GLSL without GL calls | Direct reuse — pack loading is API-agnostic |
| `VulkanTerrainPipeline` | Stores transformed terrain GLSL | Feeds into Vulkanium's shader compiler |
| `IrisPackShaderAdapter` | Maps pack uniforms to UBO members | Data tables reused; regex logic replaced by AST |
| `IrisUniformDataManager` | 720-byte UBO with 21 fields | Expanded to ~2048 bytes with 60+ fields |
| `CompositeRenderer` pattern | Fullscreen post-processing passes | Reimplemented with Vulkan compute + graphics |
| `RenderTargets` pattern | Offscreen G-buffer management | Reimplemented with MRT framebuffers |
| `ShadowRenderer` pattern | Shadow map from light perspective | Reimplemented with Vulkan depth-only passes |

---

## Vulkanium Architecture

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Minecraft Client                                 │
│  (Fabric mixins replace vanilla rendering entry points)                  │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
                     ┌───────────▼───────────┐
                     │   Vulkanium Engine     │
                     │   (Entry Point)        │
                     └───────────┬───────────┘
                                 │
          ┌──────────────────────┼──────────────────────┐
          │                      │                      │
   ┌──────▼──────┐     ┌────────▼────────┐    ┌───────▼────────┐
   │  Vulkan     │     │  Render         │    │  Shader Pack   │
   │  Core       │     │  Engine         │    │  Compat Layer  │
   │  (device,   │     │  (world, entity │    │  (GLSL→SPIR-V, │
   │   memory,   │     │   sky, shadow,  │    │   OptiFine     │
   │   queue,    │     │   composite)    │    │   bridge)      │
   │   swapchain)│     │                 │    │                │
   └──────┬──────┘     └────────┬────────┘    └───────┬────────┘
          │                     │                     │
   ┌──────▼─────────────────────▼─────────────────────▼──────┐
   │                  Resource Management                      │
   │  (pipelines, descriptors, textures, buffers, targets)     │
   └──────────────────────────────────────────────────────────┘
```

### Detailed Component Architecture

```
┌─ Vulkan Core ──────────────────────────────────────────────────────────┐
│                                                                         │
│  VulkaniumDevice          VulkaniumMemory          VulkaniumQueue       │
│  ├─ Physical device       ├─ VMA allocator         ├─ Graphics queue   │
│  ├─ Logical device        ├─ Buffer pools          ├─ Transfer queue   │
│  ├─ Feature detection     ├─ Image allocator       ├─ Compute queue    │
│  └─ Extension mgmt       ├─ Staging ring          └─ Present queue    │
│                           └─ Dynamic UBO ring                           │
│                                                                         │
│  VulkaniumSwapchain       VulkaniumSync             VulkaniumCommand    │
│  ├─ Triple buffering      ├─ Per-frame fences      ├─ Primary cmd bufs │
│  ├─ HDR support           ├─ Binary semaphores     ├─ Secondary pools  │
│  ├─ Mailbox present       ├─ Timeline semaphores   └─ Per-thread pools │
│  └─ Dynamic resize        └─ Transfer sync                             │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─ Render Engine ────────────────────────────────────────────────────────┐
│                                                                         │
│  FrameOrchestrator (replaces VulkanMod's monolithic Renderer)          │
│  ├─ beginFrame() → acquire image, reset per-frame resources            │
│  ├─ recordPasses() → dispatches to PassRecorder                        │
│  ├─ endFrame() → submit, present                                       │
│  └─ manages frame-in-flight isolation (triple buffer)                  │
│                                                                         │
│  PassRecorder (records Vulkan commands per render pass)                 │
│  ├─ ShadowPass → depth-only rendering from light POV                   │
│  ├─ GBufferPass → terrain/entity/sky MRT rendering                     │
│  ├─ DeferredPass → fullscreen passes reading G-buffer                  │
│  ├─ CompositePass → post-processing chain (bloom, DOF, etc.)          │
│  ├─ FinalPass → tone mapping + output to swapchain                     │
│  └─ Each pass: secondary command buffer (parallelizable)               │
│                                                                         │
│  ChunkRenderer (Sodium-inspired terrain renderer)                      │
│  ├─ Region-based chunk storage (Sodium's RenderRegion pattern)         │
│  ├─ Multi-draw-indirect per region                                     │
│  ├─ Extended vertex format (24 bytes: pos+color+uv+light+normal)      │
│  ├─ Occlusion culling (Vulkan occlusion queries)                       │
│  ├─ Frustum culling (compute shader)                                   │
│  └─ Translucent sort (compute shader per frame)                        │
│                                                                         │
│  EntityRenderer                                                         │
│  ├─ Batch by shader → single draw per shader type                      │
│  ├─ Dynamic vertex buffer per frame                                     │
│  └─ Pack entity shaders (gbuffers_entities, gbuffers_hand, etc.)       │
│                                                                         │
│  ShadowRenderer                                                         │
│  ├─ Shadow map framebuffer (depth + optional color)                    │
│  ├─ Cascaded shadow maps (optional, pack-configured)                   │
│  ├─ Shadow frustum culling                                              │
│  └─ Re-renders terrain chunks from light perspective                   │
│                                                                         │
│  CompositeRenderer                                                      │
│  ├─ Up to 15 composite + 13 deferred + 1 final pass                   │
│  ├─ Ping-pong buffer management (BufferFlipper)                        │
│  ├─ Fullscreen triangle (no vertex buffer)                             │
│  └─ Each pass: reads colortex[N] + depth → writes colortex[M]         │
│                                                                         │
│  SkyRenderer / WeatherRenderer / ParticleRenderer                      │
│  └─ Separate pipelines with pack shaders when available                │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─ Shader Pack Compatibility Layer ──────────────────────────────────────┐
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ GLSL Compatibility Pipeline (the "OptiFine Bridge")             │   │
│  │                                                                  │   │
│  │  Pack GLSL (#version 120/330/430 compatibility)                 │   │
│  │       │                                                          │   │
│  │       ▼                                                          │   │
│  │  OptiFinePreprocessor                                            │   │
│  │  ├─ Resolve #include directives                                 │   │
│  │  ├─ Expand #define / #ifdef chains                              │   │
│  │  ├─ Handle OptiFine-specific extensions:                        │   │
│  │  │   ├─ /* DRAWBUFFERS:0123 */ → MRT output mapping             │   │
│  │  │   ├─ /* RENDERTARGETS:0,1,2 */ → MRT output mapping         │   │
│  │  │   ├─ const int shadowMapResolution = 1024;                   │   │
│  │  │   ├─ const float shadowDistance = 128.0;                     │   │
│  │  │   ├─ const int colortex1Format = R16F;                       │   │
│  │  │   └─ uniform sampler2D colortex0..7 / shadow / noise        │   │
│  │  └─ Strip #extension (Vulkan handles extensions differently)    │   │
│  │       │                                                          │   │
│  │       ▼                                                          │   │
│  │  VulkanTransformer (ANTLR4 AST-based, in TransformPatcher)     │   │
│  │  ├─ #version → 450                                              │   │
│  │  ├─ attribute/varying → layout(location=N) in/out               │   │
│  │  │   (cross-shader location coordination)                       │   │
│  │  ├─ gl_Vertex → decoded position from compressed format         │   │
│  │  ├─ gl_Color → vertex color attribute                           │   │
│  │  ├─ gl_Normal → packed normal decode (NEW in Vulkanium)         │   │
│  │  ├─ gl_MultiTexCoord0 → UV decode                              │   │
│  │  ├─ gl_MultiTexCoord1 → lightmap decode                        │   │
│  │  ├─ mc_Entity → block ID attribute (NEW in Vulkanium)          │   │
│  │  ├─ at_tangent → tangent vector (NEW in Vulkanium)             │   │
│  │  ├─ at_midBlock → mid-block offset (NEW in Vulkanium)          │   │
│  │  ├─ gl_ModelViewProjectionMatrix → UBO matrix                  │   │
│  │  ├─ gl_FragData[N] → layout(location=N) out vec4               │   │
│  │  ├─ texture2D/texture3D → texture()                            │   │
│  │  ├─ shadow2D → texture() with comparison sampler               │   │
│  │  ├─ uniform sampler → layout(set=1, binding=N)                 │   │
│  │  ├─ uniform <pack> → UBO member expression                     │   │
│  │  └─ gl_FogFragCoord / gl_Fog → UBO fog parameters              │   │
│  │       │                                                          │   │
│  │       ▼                                                          │   │
│  │  Vulkan-compatible GLSL 450                                     │   │
│  │       │                                                          │   │
│  │       ▼                                                          │   │
│  │  ShadercCompiler (with caching)                                 │   │
│  │  ├─ GLSL 450 → SPIR-V 1.5                                     │   │
│  │  ├─ Optimization level: performance                             │   │
│  │  ├─ Target: Vulkan 1.2                                          │   │
│  │  ├─ #include resolve via custom includer                        │   │
│  │  └─ SHA-256 cache: <hash>.spv on disk                          │   │
│  │       │                                                          │   │
│  │       ▼                                                          │   │
│  │  VkShaderModule → VkGraphicsPipeline                            │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  UniformBridge (expanded UBO system)                                   │
│  ├─ ~2048-byte UBO with 60+ uniform fields                            │
│  ├─ All standard OptiFine uniforms:                                    │
│  │   ├─ Matrices: MV, Proj, MV⁻¹, P⁻¹, Previous MV/P, Shadow MV/P  │
│  │   ├─ Time: worldTime, frameTime, frameCounter                      │
│  │   ├─ Camera: cameraPosition, previousCameraPosition                │
│  │   ├─ Light: sunPosition, moonPosition, shadowLightPosition         │
│  │   ├─ Atmosphere: fogColor, fogDensity, skyColor                    │
│  │   ├─ Weather: rainStrength, wetness, thunderStrength               │
│  │   ├─ Screen: viewWidth, viewHeight, aspectRatio, near, far        │
│  │   ├─ Player: eyeBrightness, nightVision, blindness, playerMood    │
│  │   ├─ World: biome temp/rainfall, moonPhase, isEyeInWater          │
│  │   └─ Custom: centerDepthSmooth, darknessFactor                     │
│  └─ Dynamic per-draw uniforms via push constants (MVP override)       │
│                                                                         │
│  TextureManager                                                         │
│  ├─ colortex0-7 (from MRT render targets)                             │
│  ├─ depthtex0-2 (depth buffers)                                       │
│  ├─ shadowtex0/1, shadowcolor0/1 (from shadow renderer)              │
│  ├─ noisetex (blue noise / perlin)                                     │
│  ├─ normals, specular (PBR atlas textures)                            │
│  └─ Custom textures from pack                                          │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─ Resource Management ──────────────────────────────────────────────────┐
│                                                                         │
│  PipelineManager                  DescriptorManager                    │
│  ├─ VkPipelineCache (disk)       ├─ Set 0: Per-frame UBOs            │
│  ├─ State-hash dedup             ├─ Set 1: Samplers (16 slots)       │
│  ├─ Per-pass pipeline            ├─ Set 2: Storage images (MRT)       │
│  └─ Compute pipelines           └─ Auto-growing pools                 │
│                                                                         │
│  RenderTargetManager              BufferManager                        │
│  ├─ colortex0-7 (configurable   ├─ Per-frame vertex ring             │
│  │   format per pack directives) ├─ Per-frame index ring              │
│  ├─ depthtex0-2                  ├─ Per-frame uniform ring            │
│  ├─ Shadow depth + color         ├─ Indirect draw buffer              │
│  ├─ Ping-pong for composites     └─ Staging ring (dynamic size)       │
│  └─ Resize on window change                                            │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Module Structure

### Source Set Layout

```
src/vulkanium/
├── java/net/irisshaders/vulkanium/
│   ├── Vulkanium.java                          # Mod entry point, initialization
│   ├── VulkaniumConfig.java                    # Configuration (frame queue, shadow res, etc.)
│   │
│   ├── core/                                   # Vulkan abstraction layer
│   │   ├── VulkaniumInstance.java              # VkInstance + debug messenger
│   │   ├── VulkaniumDevice.java               # Physical/logical device + features
│   │   ├── VulkaniumQueues.java               # 4 queue types (graphics/present/transfer/compute)
│   │   ├── VulkaniumSwapchain.java            # Swapchain + resize + present
│   │   ├── VulkaniumMemory.java               # VMA + buffer/image allocation
│   │   ├── VulkaniumCommand.java              # Command buffer/pool management
│   │   ├── VulkaniumSync.java                 # Fences, semaphores, barriers
│   │   └── VulkaniumCapabilities.java         # Feature/extension detection
│   │
│   ├── resource/                               # GPU resource management
│   │   ├── pipeline/
│   │   │   ├── VulkaniumPipelineCache.java    # Disk-persistent pipeline cache
│   │   │   ├── VulkaniumGraphicsPipeline.java # Graphics pipeline builder
│   │   │   ├── VulkaniumComputePipeline.java  # Compute pipeline builder
│   │   │   └── PipelineStateKey.java          # Hashable pipeline state
│   │   ├── descriptor/
│   │   │   ├── DescriptorSetManager.java      # Multi-set layout management
│   │   │   ├── DescriptorPoolManager.java     # Auto-growing pools
│   │   │   └── DescriptorBinding.java         # Binding specification
│   │   ├── buffer/
│   │   │   ├── VulkaniumBuffer.java           # Buffer abstraction (VMA-backed)
│   │   │   ├── StagingRing.java               # CPU→GPU staging ring buffer
│   │   │   ├── UniformRing.java               # Per-frame UBO sub-allocation
│   │   │   ├── VertexRing.java                # Per-frame VB ring
│   │   │   ├── IndexRing.java                 # Per-frame IB ring
│   │   │   └── IndirectDrawBuffer.java        # Multi-draw-indirect commands
│   │   ├── texture/
│   │   │   ├── VulkaniumImage.java            # VkImage + VkImageView + VMA
│   │   │   ├── VulkaniumSampler.java          # Sampler cache
│   │   │   └── TextureUploader.java           # Async texture upload via transfer queue
│   │   └── target/
│   │       ├── RenderTarget.java              # Single color/depth target
│   │       ├── RenderTargetSet.java           # G-buffer (colortex0-7 + depth0-2)
│   │       ├── MRTFramebuffer.java            # Multi-attachment framebuffer
│   │       ├── MRTRenderPass.java             # Multi-attachment render pass
│   │       └── BufferFlipper.java             # Ping-pong state for composites
│   │
│   ├── render/                                 # Rendering engine
│   │   ├── FrameOrchestrator.java             # Frame lifecycle (acquire→record→submit→present)
│   │   ├── PassRecorder.java                  # Records commands per render pass
│   │   ├── DrawDispatcher.java                # Batches and submits draw commands
│   │   │
│   │   ├── terrain/                            # Chunk/terrain rendering (Sodium-inspired)
│   │   │   ├── ChunkRenderer.java             # Main terrain renderer
│   │   │   ├── ChunkRegion.java               # Region-based storage (256³ blocks)
│   │   │   ├── ChunkRegionManager.java        # Region lifecycle
│   │   │   ├── ChunkSection.java              # Single 16³ section
│   │   │   ├── ChunkBuildTask.java            # Async mesh generation
│   │   │   ├── ChunkBuildWorker.java          # Thread pool worker
│   │   │   ├── ChunkMeshFormat.java           # 24-byte vertex format
│   │   │   ├── ChunkSortCompute.java          # Compute shader for translucent sort
│   │   │   ├── ChunkFrustumCuller.java        # Frustum + occlusion culling
│   │   │   └── ChunkUploadManager.java        # Async GPU upload via transfer queue
│   │   │
│   │   ├── entity/                             # Entity rendering
│   │   │   ├── EntityRenderer.java            # Batch entity draws by shader type
│   │   │   ├── EntityVertexBuffer.java        # Dynamic per-frame VB
│   │   │   └── EntityShaderBinder.java        # Maps entity type → pack program
│   │   │
│   │   ├── shadow/                             # Shadow mapping
│   │   │   ├── ShadowRenderer.java            # Orchestrates shadow pass
│   │   │   ├── ShadowFramebuffer.java         # Depth-only FBO
│   │   │   ├── ShadowFrustum.java             # Shadow-specific frustum
│   │   │   └── ShadowMatrices.java            # Shadow MVP computation
│   │   │
│   │   ├── composite/                          # Post-processing
│   │   │   ├── CompositeRenderer.java         # Composite/deferred/final passes
│   │   │   ├── CompositePass.java             # Single composite pass record
│   │   │   └── DeferredPass.java              # Single deferred pass record
│   │   │
│   │   ├── sky/                                # Sky rendering
│   │   │   ├── SkyRenderer.java               # Pack-aware sky
│   │   │   └── CloudRenderer.java             # Custom cloud rendering
│   │   │
│   │   └── misc/                               # Other renderers
│   │       ├── ParticleRenderer.java           # Pack-aware particles
│   │       ├── WeatherRenderer.java            # Rain/snow
│   │       └── HandRenderer.java               # First/third person hand
│   │
│   ├── shader/                                 # Shader compilation & compatibility
│   │   ├── ShaderCompiler.java                # GLSL→SPIR-V (shaderc wrapper)
│   │   ├── ShaderCache.java                   # Disk-persistent SPIR-V cache
│   │   ├── ShaderModuleManager.java           # VkShaderModule lifecycle
│   │   ├── OptiFineGlslBridge.java            # OptiFine GLSL compatibility transforms
│   │   ├── UniformBridge.java                 # ~2048-byte UBO with 60+ fields
│   │   ├── UniformDataWriter.java             # Writes per-frame uniform data
│   │   └── PackTextureManager.java            # Pack texture binding management
│   │
│   ├── compat/                                 # Mod compatibility
│   │   ├── iris/                               # Iris integration
│   │   │   ├── IrisIntegration.java           # Bridge Iris pack loading → Vulkanium
│   │   │   └── IrisPipelineAdapter.java       # Adapts VulkanRenderingPipeline
│   │   └── sodium/                             # Sodium optimization reuse
│   │       └── SodiumPatterns.java            # Chunk building patterns
│   │
│   └── mixin/                                  # Fabric mixins
│       ├── VulkaniumMixinPlugin.java          # Conditional mixin application
│       ├── core/                               # Minecraft core rendering overrides
│       │   ├── MixinWindow.java               # Replace GLFW GL context with Vulkan
│       │   ├── MixinGameRenderer.java         # Hook frame lifecycle
│       │   └── MixinLevelRenderer.java        # Hook world rendering
│       ├── render/                             # Render dispatch overrides
│       │   ├── MixinBufferUploader.java       # Redirect vertex uploads
│       │   ├── MixinRenderSystem.java         # Replace GL state management
│       │   └── MixinVertexBuffer.java         # Replace GL vertex buffers
│       └── compat/                             # VulkanMod interop (if coexisting)
│           └── MixinVulkanModDetect.java      # Detect and defer to Vulkanium
│
├── resources/
│   ├── fabric.mod.json                         # Mod descriptor
│   ├── vulkanium.mixins.json                  # Mixin config
│   ├── vulkanium.accesswidener                # Access widener
│   └── assets/vulkanium/
│       ├── shaders/                            # Built-in shaders
│       │   ├── include/                        # Shared GLSL includes
│       │   │   ├── vulkanium_common.glsl      # Common uniforms/macros
│       │   │   ├── vertex_decode.glsl         # Compressed vertex decode
│       │   │   └── fog.glsl                   # Fog computation
│       │   ├── terrain/                        # Default terrain shaders
│       │   │   ├── terrain.vert               # Default terrain vertex
│       │   │   └── terrain.frag               # Default terrain fragment
│       │   ├── entity/                         # Default entity shaders
│       │   ├── sky/                            # Default sky shaders
│       │   ├── fullscreen/                     # Fullscreen pass shaders
│       │   │   ├── blit.vert                  # Fullscreen triangle
│       │   │   └── blit.frag                  # Simple copy
│       │   └── compute/                        # Compute shaders
│       │       ├── frustum_cull.comp          # GPU frustum culling
│       │       └── translucent_sort.comp      # Translucent sort
│       └── lang/
│           └── en_us.json
```

---

## Phase Plan

### Phase Dependencies

```
Phase 0 (Core) ──┬──► Phase 1 (Chunks) ──┬──► Phase 2 (GLSL Compat) ──► Phase 3 (MRT)
                  │                        │                                    │
                  │                        │                              Phase 4 (Composite)
                  │                        │                                    │
                  │                        └──────────────────────► Phase 5 (Shadows)
                  │                                                       │
                  │                                                 Phase 6 (Entity/Sky)
                  │                                                       │
                  └──────────────────────────────────────────► Phase 7 (Vulkan Perf)
                                                                    │         │
                                                              Phase 8 (UX)    │
                                                                    │         │
                                                              Phase 9 (Compute Platform)
                                                                    │
                                                              Phase 10 (Ray Tracing)
                                                                    │
                                                              Phase 11 (Module System)
```

> **Vision:** Vulkanium is not just a Vulkan graphics renderer — it is a **compute platform**
> for Minecraft. GP-computing (Phase 9) enables GPU-accelerated lighting, pathfinding,
> and world generation. Hardware ray-tracing (Phase 10) enables path-traced GI and
> physically-based rendering. The module system (Phase 11) lets mods plug into the
> render pipeline as first-class stages (denoiser, upscaler, custom passes).

---

### Phase 0 — Foundation: Core Vulkan Abstraction 🟡 PARTIALLY IMPLEMENTED

**Complexity:** HIGH  
**Files:** 28 implemented (build system, entry points, core abstractions, resource layer, mixins, built-in shaders)  
**Goal:** Clean Vulkan abstraction layer that all other phases build on  
**Duration:** ~2 weeks

#### Overview

Build a clean, reusable Vulkan abstraction layer from VulkanMod's core but restructured for modularity. This replaces VulkanMod's monolithic `Vulkan.java` + `Renderer.java` + `Drawer.java`.

#### Key Components

**`VulkaniumInstance.java`** — VkInstance creation
- Vulkan 1.2+ required (for descriptor indexing, timeline semaphores)
- Optional: Vulkan 1.3 features (dynamic rendering, synchronization2)
- Debug messenger with severity-based logging
- GLFW extension integration

**`VulkaniumDevice.java`** — Device management
- Physical device scoring: discrete > integrated > CPU
- Required features: `samplerAnisotropy`, `fragmentStoresAndAtomics`, `multiDrawIndirect`, `shaderInt16`, `fillModeNonSolid`
- Optional features: `geometryShader`, `tessellationShader`, `multiViewport`
- Feature flags exposed for conditional rendering paths

**`VulkaniumQueues.java`** — Multi-queue architecture
```java
public class VulkaniumQueues {
    QueueHandle graphics;   // Main rendering
    QueueHandle present;    // Swapchain presentation (may alias graphics)
    QueueHandle transfer;   // Dedicated data upload (if available)
    QueueHandle compute;    // Compute shader dispatch (if available)
    
    // Each QueueHandle owns its command pool → thread-safe
}
```
- Dedicated transfer queue for async uploads (staging → device-local)
- Dedicated compute queue for GPU-side sorting and culling
- Falls back to graphics queue if dedicated queues unavailable

**`VulkaniumMemory.java`** — VMA-based memory management
- Separate allocation pools: vertex data, index data, uniform data, staging, images
- Dynamic staging ring: starts at 16MB, grows to min(256MB, 25% VRAM)
- Per-frame uniform ring with `minUniformBufferOffsetAlignment` padding
- Helper: `transitionImageLayout()` with proper stage/access mask inference

**`VulkaniumSwapchain.java`** — Swapchain management
- Triple buffering preferred (3 images, mailbox present mode)
- Fallback: double buffer (2 images, FIFO)
- Format preference: B8G8R8A8_SRGB → B8G8R8A8_UNORM → first available
- HDR: optional `VK_EXT_swapchain_colorspace` for HDR10 output
- Automatic recreation on resize/suboptimal

**`VulkaniumCommand.java`** — Command buffer management
- Primary command buffers: one per frame-in-flight
- Secondary command buffer pools: per-thread, for parallel pass recording
- Reset strategy: `VK_COMMAND_POOL_RESET_COMMAND_BUFFER_BIT` for individual reset

**`VulkaniumSync.java`** — Synchronization
- Per-frame: image-available semaphore, render-finished semaphore, in-flight fence
- Transfer sync: timeline semaphore for upload completion tracking
- Pipeline barriers: helper methods for common layout transitions

**`FrameOrchestrator.java`** — Frame lifecycle (replaces VulkanMod's `Renderer`)
```java
public class FrameOrchestrator {
    void beginFrame() {
        // Wait for frame N-2 fence
        // Acquire swapchain image
        // Reset frame resources (UBO ring, staging, descriptors, buffers)
        // Begin primary command buffer
    }
    
    void recordPasses(RenderGraph graph) {
        // Dispatch to PassRecorder for each pass in the render graph
        // Passes can be recorded in parallel using secondary cmd buffers
    }
    
    void endFrame() {
        // End primary command buffer
        // Submit to graphics queue (wait: imageAvailable, signal: renderFinished)
        // Present (wait: renderFinished)
        // Advance frame index
    }
}
```

#### What's Kept from VulkanMod

| Component | Source | Changes |
|-----------|--------|---------|
| VMA initialization | `Vulkan.createVma()` | Wrapped in `VulkaniumMemory` |
| Surface creation | `Vulkan.createSurface()` | Moved to `VulkaniumInstance` |
| Device selection | `DeviceManager.init()` | Enhanced scoring + feature detection |
| Swapchain | `SwapChain.java` | Triple buffering + HDR support |
| Debug messenger | `Vulkan.debugCallback()` | Severity-based logging |

#### What's New

| Component | Purpose |
|-----------|---------|
| Multi-queue | Dedicated transfer + compute queues |
| Dynamic staging | Auto-sizing staging ring buffer |
| Per-thread command pools | Thread-safe secondary recording |
| Timeline semaphores | Fine-grained transfer sync |
| Feature capability flags | Conditional rendering paths |

---

### Phase 1 — Chunk Rendering Engine 🟡 PARTIALLY IMPLEMENTED (18 files)

**Complexity:** HIGH  
**Files:** ~12  
**Goal:** Sodium-quality chunk rendering on Vulkan  
**Depends on:** Phase 0  
**Duration:** ~3 weeks

#### Overview

Port Sodium's chunk rendering architecture to Vulkan. This is the single biggest performance win: Sodium's region-based batching + Vulkan's multi-draw-indirect = minimal CPU overhead per frame.

#### Key Design: Extended Vertex Format

**Current VulkanMod** (20 bytes, missing data):
```
ivec4 position  (16 bytes) — 12-bit XYZ + packed lightmap in .w
vec4  color     (4 bytes, packed)
uvec2 uv0       (4 bytes, 16-bit fixed)
// Total: 24 bytes BUT missing: normal, tangent, entity ID, mid-block
```

**Vulkanium** (32 bytes, full data for shader packs):
```
    Offset  Size  Field              Description
    ──────  ────  ─────              ──────────
     0       4    position_x         float16 + block-relative offset (12-bit)
     4       4    position_y         float16 + block-relative offset (12-bit)  
     8       4    position_z         float16 + block-relative offset (12-bit)
    12       4    color              RGBA8 packed
    16       4    uv0                UV (2×float16)
    20       2    lightmap           2×uint8 (block, sky)
    22       2    normal_packed      octahedral normal (2×snorm8)
    24       2    tangent_packed     octahedral tangent (2×snorm8) + sign bit
    26       2    mc_entity          block ID (uint16)
    28       2    mid_block          2×int8 mid-block offset  
    30       2    mid_uv             mid-texture UV (2×uint8)
    ──────────────────────────────────────────────────────
    Total: 32 bytes per vertex
```

This format provides **all data shader packs need** while remaining compact:
- **Normal**: Octahedral encoding (2 bytes → full vec3 decoded in vertex shader)
- **Tangent**: Same encoding + handedness bit
- **mc_Entity**: Block state ID for per-block shader effects
- **at_midBlock**: Mid-block offset for wave animation anchoring

**Comparison:**
- Sodium COMPACT: 20 bytes (no normal/tangent/entity)
- VulkanMod COMPRESSED: 24 bytes (no normal/tangent/entity)
- Vulkanium: 32 bytes (all pack data, only 60% larger than Sodium)
- Vanilla Minecraft: 36 bytes (unoptimized)

#### Region-Based Multi-Draw-Indirect

Adapted from Sodium's `RenderRegion` pattern:

```java
public class ChunkRegion {
    // 8×4×8 sections = 128×64×128 blocks per region
    static final int REGION_SIZE = 8;
    
    VulkaniumBuffer vertexBuffer;    // All section meshes concatenated
    VulkaniumBuffer indexBuffer;     // All section indices concatenated
    IndirectDrawBuffer drawBuffer;   // VkDrawIndexedIndirectCommand per section
    
    // Single draw call renders entire region:
    // vkCmdDrawIndexedIndirect(cmd, drawBuffer, 0, sectionCount, stride)
}
```

**Performance impact:** Instead of VulkanMod's per-section `vkCmdDrawIndexed()` (thousands of draw calls), Vulkanium issues one `vkCmdDrawIndexedIndirect()` per visible region (~20-50 regions for 16 chunk render distance) → **95% fewer draw calls**.

#### Compute-Backed Operations

1. **Frustum culling** (`frustum_cull.comp`): GPU-side frustum test on section AABB → writes visible sections to indirect draw buffer → zero CPU readback
2. **Translucent sorting** (`translucent_sort.comp`): Per-frame sort of translucent triangles by camera distance → correct alpha blending without CPU sort

#### Async Chunk Upload

```
CPU thread pool (ChunkBuildWorker)
    → Build mesh → write to staging ring
    → Signal transfer queue
Transfer queue
    → Copy staging → device-local (vkCmdCopyBuffer)
    → Signal graphics queue (timeline semaphore)
Graphics queue
    → Wait for transfer complete → region now renderable
```

No stalls on graphics queue for chunk uploads.

---

### Phase 2 — GLSL Compatibility Layer (OptiFine Bridge) 🟡 PARTIALLY IMPLEMENTED (5 files)

**Complexity:** HIGH  
**Files:** ~6  
**Goal:** Full OptiFine/Iris GLSL shader pack compatibility via intelligent GLSL→SPIR-V pipeline  
**Depends on:** Phase 0, Phase 1 (vertex format)  
**Duration:** ~3 weeks

#### The Problem with Legacy OptiFine GLSL

OptiFine shader packs were written for OpenGL 1.2–4.3 and use:

1. **Deprecated built-ins**: `gl_Vertex`, `gl_Color`, `gl_Normal`, `gl_ModelViewProjectionMatrix`, `gl_ModelViewMatrix`, `gl_NormalMatrix`
2. **Fixed-function fog**: `gl_Fog.color`, `gl_Fog.start`, `gl_Fog.end`, `gl_FogFragCoord`
3. **Legacy texture functions**: `texture2D()`, `texture3D()`, `shadow2D()`
4. **Legacy varying/attribute keywords**: `attribute`, `varying` (instead of `in`/`out`)
5. **OpenGL-specific extensions**: `#extension GL_EXT_gpu_shader4`, `GL_ARB_shading_language_420pack`
6. **OptiFine-specific magic comments**: `/* DRAWBUFFERS:0123 */`, `/* RENDERTARGETS:0,1,2 */`
7. **OptiFine-specific uniforms**: `gbufferModelView`, `sunPosition`, `rainStrength` etc. (100+ uniforms)
8. **Implicit binding conventions**: samplers bound by name (`colortex0`, `shadow`, `noisetex`)
9. **gl_FragData[N] arrays**: MRT output via array indexing (not explicit layout qualifiers)
10. **`#version compatibility` profile**: Allows mixing legacy and modern GLSL

#### Solution: Multi-Stage Transform Pipeline

**Stage 1: OptiFine Preprocessor** (`OptiFineGlslBridge.java`)
- Runs BEFORE ANTLR parsing (text-level, since some constructs aren't valid GLSL 450)
- Handles:
  - Detect and strip `#version NNN compatibility` → save version info
  - Parse `/* DRAWBUFFERS:XXXX */` and `/* RENDERTARGETS:X,X,X */` → extract target list
  - Parse `const int` directives (shadow resolution, buffer formats, etc.)
  - Strip unsupported `#extension` directives
  - Replace `gl_FragData[N]` with named outputs (text replacement before AST)
  - Handle `#ifdef MC_NORMAL_MAP` / `#ifdef MC_SPECULAR_MAP` etc.

**Stage 2: AST Transform** (`VulkanTransformer.java` — plugs into Iris's `TransformPatcher`)
- ANTLR4-based, structurally correct, can't match inside comments/strings
- Registered as `VULKAN` patch type alongside `SODIUM`/`VANILLA`
- Transforms:
  - `#version` → `450`
  - `attribute`/`varying` → `layout(location=N) in/out` with cross-shader location coordination
  - GL built-in variables → vertex attribute decode or UBO member
  - Sampler uniforms → `layout(set=1, binding=N) uniform sampler2D`
  - Pack uniforms (100+) → UBO member expressions
  - texture functions → modern equivalents
  - Inject vertex decode preamble for compressed format
  - Inject UBO layout declarations

**Stage 3: SPIR-V Compilation** (`ShaderCompiler.java`)
- shaderc with `shaderc_env_version_vulkan_1_2`
- Optimization: `shaderc_optimization_level_performance`
- Custom #include resolver for pack-included files
- Detailed error reporting: maps SPIR-V errors back to original pack GLSL

**Stage 4: Pipeline Creation** 
- SPIR-V → VkShaderModule → VkGraphicsPipeline
- Per-pass pipeline (solid/cutout/translucent/shadow/composite)
- MRT-aware blend state from RENDERTARGETS metadata

#### OptiFine Uniform → UBO Member Mapping (Complete List)

```java
// ~60 uniforms mapped to a 2048-byte UBO
// This is the FULL list that Vulkanium supports (vs VulkanMod's 21)

// Matrices (16×4 = 64 bytes each, 12 matrices = 768 bytes)
gbufferModelView         → iris_ModelViewMatrix
gbufferModelViewInverse  → iris_ModelViewMatrixInverse
gbufferProjection        → iris_ProjectionMatrix
gbufferProjectionInverse → iris_ProjectionMatrixInverse
gbufferPreviousModelView → iris_PreviousModelViewMatrix     // NEW: real prev frame
gbufferPreviousProjection→ iris_PreviousProjectionMatrix    // NEW: real prev frame
shadowModelView          → iris_ShadowModelView
shadowProjection         → iris_ShadowProjection
shadowModelViewInverse   → iris_ShadowModelViewInverse
shadowProjectionInverse  → iris_ShadowProjectionInverse
// + normal matrix (mat3, stored as mat4 padded) = 2 more

// Vectors (16 bytes each)
cameraPosition           → iris_CameraPosition
previousCameraPosition   → iris_PreviousCameraPosition
sunPosition              → iris_SunPosition
moonPosition             → iris_MoonPosition
shadowLightPosition      → iris_ShadowLightPosition
upPosition               → iris_UpPosition
skyColor                 → iris_SkyColor
fogColor                 → iris_FogColor

// Scalars (4 bytes each, packed in vec4 groups)
viewWidth, viewHeight    → iris_ScreenSize.xy
aspectRatio, near, far   → iris_ViewParams.xyz
frameTimeCounter         → iris_Time.x
worldTime                → iris_Time.y
frameCounter             → iris_Time.z
sunAngle                 → iris_Time.w
fogStart, fogEnd         → iris_FogParams.xy
fogDensity, fogShape     → iris_FogParams.zw
rainStrength             → iris_Weather.x
wetness                  → iris_Weather.y
thunderStrength          → iris_Weather.z
nightVision              → iris_PlayerState.x
blindness                → iris_PlayerState.y
darknessFactor           → iris_PlayerState.z
playerMood               → iris_PlayerState.w
eyeBrightness            → iris_EyeBrightness                // NEW: real value
eyeBrightnessSmooth      → iris_EyeBrightnessSmooth          // NEW: smoothed
moonPhase                → iris_WorldState.x
isEyeInWater             → iris_WorldState.y
biomeTemperature         → iris_WorldState.z                  // NEW: real biome
biomeRainfall            → iris_WorldState.w                  // NEW: real biome
centerDepthSmooth        → iris_DepthParams.x                 // NEW: real depth
```

---

### Phase 3 — Multi-Render Target (MRT) & G-Buffer System 🟡 PARTIALLY IMPLEMENTED (5 files)

**Complexity:** HIGH  
**Files:** ~5  
**Goal:** Full G-buffer with 8 color + 3 depth targets for deferred rendering  
**Depends on:** Phase 0, Phase 2  
**Duration:** ~2 weeks

#### Overview

Shader packs write to multiple render targets simultaneously (up to 8 color + 3 depth). This is the fundamental feature that VulkanMod lacks. Vulkanium implements this natively with Vulkan render passes.

#### Render Target Configuration

```java
public class RenderTargetSet {
    // Up to 8 color targets (configurable format per pack directives)
    RenderTarget[] colorTargets = new RenderTarget[8];
    // 3 depth targets
    RenderTarget depthTarget0;  // Main scene depth
    RenderTarget depthTarget1;  // Copy at terrain complete
    RenderTarget depthTarget2;  // Copy at translucent complete
    
    // Format defaults (overrideable by pack's const int declarations)
    // colortex0: RGBA8
    // colortex1: RGBA16F (G-buffer: normals)
    // colortex2: RGBA16F (G-buffer: specular)
    // colortex3: RGBA8   (G-buffer: material IDs)
    // colortex4-7: as needed by pack
    // depth: D32_SFLOAT
}
```

#### MRT Render Pass

```java
public class MRTRenderPass {
    // Create Vulkan render pass with N color attachments
    // Each attachment has configurable:
    //   - loadOp (CLEAR / LOAD / DONT_CARE)
    //   - storeOp (STORE / DONT_CARE)
    //   - initialLayout / finalLayout
    
    // Subpass dependencies for correct write-then-read ordering
    // All color attachments transition to SHADER_READ_ONLY at end
}
```

#### MRT Graphics Pipeline

```java
public class MRTGraphicsPipeline extends VulkaniumGraphicsPipeline {
    // N VkPipelineColorBlendAttachmentState entries (one per active target)
    // Inactive targets: write mask = 0
    // Active set determined by /* RENDERTARGETS: X,Y,Z */ in fragment shader
    // References MRTRenderPass for compatible render pass
}
```

---

### Phase 4 — Composite/Deferred Pass Engine 🟡 PARTIALLY IMPLEMENTED (3 files)

**Complexity:** MEDIUM-HIGH  
**Files:** ~4  
**Goal:** Execute fullscreen post-processing passes (bloom, DOF, tone mapping, etc.)  
**Depends on:** Phase 3 (MRT targets to read/write)  
**Duration:** ~2 weeks

#### Pass Types

| Pass Type | Count | When | Input | Output |
|-----------|-------|------|-------|--------|
| **Prepare** | 0-15 | Before terrain | Prev. frame data | colortex |
| **Deferred** | 0-13 | After terrain G-buffer | colortex + depth | colortex |
| **Composite** | 0-15 | After deferred | All colortex + depth + shadow | colortex |
| **Final** | 1 | Last pass | Everything | Screen |

#### Execution Model

```
for each pass in [deferred0..deferred13, composite0..composite14, final]:
    1. Pipeline barrier (write → read transition)
    2. Transition read targets → SHADER_READ_ONLY
    3. Begin MRT render pass (write targets from RENDERTARGETS)
    4. Bind read targets as samplers (set 1)
    5. Bind UBO (set 0) — includes all standard uniforms
    6. Draw fullscreen triangle (3 verts, gl_VertexIndex, no VB)
    7. End render pass
    8. Flip affected buffers (ping-pong)
```

#### Fullscreen Triangle (No Vertex Buffer)

```glsl
// vertex shader — computed entirely from gl_VertexIndex
#version 450
layout(location = 0) out vec2 texCoord;
void main() {
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    texCoord = pos;
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}
```

---

### Phase 5 — Shadow Mapping System 🟡 PARTIALLY IMPLEMENTED (4 files)

**Complexity:** MEDIUM  
**Files:** ~4  
**Goal:** Real shadow maps from light perspective  
**Depends on:** Phase 1 (chunk data), Phase 2 (shadow shader compilation)  
**Duration:** ~2 weeks

#### Shadow Pipeline

```
Each frame (before terrain rendering):
    1. Compute shadow matrices (ortho projection from sun/moon direction)
    2. Frustum cull terrain sections against shadow frustum
    3. Begin shadow render pass (depth-only framebuffer)
    4. Bind shadow pipeline (compiled from pack's shadow.vsh/fsh)
    5. Re-render visible chunks from light POV
    6. End render pass → shadowtex0 now contains shadow depth
    7. Bind shadowtex0 as sampler for terrain + composite passes
```

#### Shadow Configuration (from Pack)

```java
// Parsed from pack's fragment shaders (const declarations)
int shadowMapResolution;    // default 1024, up to 8192
float shadowDistance;       // default 128.0 blocks
float shadowDistanceRenderMul; // default 1.0 (fraction of chunks to render)
boolean shadowHardwareFiltering; // use VK_COMPARE_OP_LESS sampler
```

---

### Phase 6 — Entity/Sky/Particle/Hand/Weather Pipeline 🟡 PARTIALLY IMPLEMENTED (5 files)

**Complexity:** MEDIUM  
**Files:** ~8  
**Goal:** Pack shaders for all non-terrain rendering  
**Depends on:** Phase 2 (GLSL compat), Phase 5 (shadow data for entity lighting)  
**Duration:** ~2 weeks

#### Program Mapping

| Shader Pack Program | Vulkanium Renderer | Vertex Format |
|--------------------|--------------------|---------------|
| `gbuffers_entities` | EntityRenderer | POSITION_TEX_COLOR_NORMAL |
| `gbuffers_entities_translucent` | EntityRenderer | Same, alpha blend |
| `gbuffers_block` | EntityRenderer (block entities) | Same as entities |
| `gbuffers_hand` | HandRenderer | Same as terrain (1st person) |
| `gbuffers_hand_water` | HandRenderer | Same, alpha blend |
| `gbuffers_skybasic` | SkyRenderer | POSITION_COLOR |
| `gbuffers_skytextured` | SkyRenderer | POSITION_TEX_COLOR |
| `gbuffers_weather` | WeatherRenderer | POSITION_TEX_COLOR_LIGHT |
| `gbuffers_textured` | ParticleRenderer | POSITION_TEX_COLOR |
| `gbuffers_textured_lit` | ParticleRenderer | POSITION_TEX_COLOR_LIGHT |
| `gbuffers_clouds` | CloudRenderer | POSITION_TEX_COLOR |

---

### Phase 7 — Vulkan-Native Performance Optimizations 🟡 PARTIALLY IMPLEMENTED (6 files)

**Complexity:** MEDIUM-HIGH  
**Files:** ~6  
**Goal:** Leverage Vulkan features that have no OpenGL equivalent  
**Depends on:** Phase 0-6 functional  
**Duration:** ~3 weeks

These are the optimizations that make Vulkanium genuinely faster than Sodium:

#### 7A: Multi-Threaded Command Recording

```java
// Record secondary command buffers in parallel per render pass
ExecutorService passRecordPool = Executors.newFixedThreadPool(4);

Future<VkCommandBuffer> shadowCmds = passRecordPool.submit(() -> {
    VkCommandBuffer secondary = allocateSecondary();
    recordShadowPass(secondary);
    return secondary;
});
Future<VkCommandBuffer> terrainCmds = passRecordPool.submit(() -> {
    VkCommandBuffer secondary = allocateSecondary();
    recordTerrainPass(secondary);
    return secondary;
});
// Execute all secondaries in primary
vkCmdExecuteCommands(primary, shadowCmds.get(), terrainCmds.get());
```

**Impact:** Spreads command recording across 4 CPU cores instead of 1 → better CPU utilization for complex shader packs with many passes.

#### 7B: Async Transfer Queue

All buffer/texture uploads happen on the dedicated transfer queue without stalling the graphics queue:

```
Frame N: Graphics queue rendering
         Transfer queue simultaneously uploading:
           - New chunk meshes
           - Texture atlas updates  
           - Sprite animation frames
         Timeline semaphore signals when transfer complete
Frame N+1: Graphics queue waits for transfer semaphore → new data visible
```

**Impact:** Chunk loading/unloading no longer causes stutters.

#### 7C: Compute Shader Frustum Culling

```glsl
// frustum_cull.comp
layout(local_size_x = 64) in;

layout(set = 0, binding = 0) uniform FrustumData {
    vec4 planes[6];
};

layout(set = 0, binding = 1) readonly buffer SectionAABBs {
    vec4 aabbs[];  // min.xyz, max.xyz per section
};

layout(set = 0, binding = 2) writeonly buffer DrawCommands {
    VkDrawIndexedIndirectCommand commands[];
};

layout(set = 0, binding = 3) buffer DrawCount {
    uint count;
};

void main() {
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= sectionCount) return;
    
    vec3 aabbMin = aabbs[idx * 2 + 0].xyz;
    vec3 aabbMax = aabbs[idx * 2 + 1].xyz;
    
    // Test against all 6 frustum planes
    bool visible = true;
    for (int i = 0; i < 6; i++) {
        vec3 p = mix(aabbMin, aabbMax, greaterThan(planes[i].xyz, vec3(0)));
        if (dot(planes[i].xyz, p) + planes[i].w < 0.0) {
            visible = false;
            break;
        }
    }
    
    if (visible) {
        uint slot = atomicAdd(count, 1);
        commands[slot] = originalDrawCommands[idx];
    }
}
```

**Impact:** Frustum culling on GPU instead of CPU → zero CPU cost for culling, computed in <0.1ms on GPU.

#### 7D: Compute Shader Translucent Sorting

Sort translucent triangles by distance to camera entirely on GPU:

```glsl
// translucent_sort.comp — bitonic merge sort on GPU
// Input: triangle centroids + indices
// Output: sorted index buffer for back-to-front rendering
```

**Impact:** Translucent sorting (often a CPU bottleneck in water-heavy scenes) moves to GPU → constant cost regardless of triangle count.

#### 7E: Vulkan Occlusion Queries

```java
// Hardware occlusion query per region
vkCmdBeginQuery(cmd, occlusionPool, regionIndex, 0);
drawRegionBoundingBox(cmd, region); // Simple box, no fragment shader
vkCmdEndQuery(cmd, occlusionPool, regionIndex);

// Next frame: read results
vkGetQueryPoolResults(device, occlusionPool, ..., results);
for (int i = 0; i < regionCount; i++) {
    if (results[i] == 0) skipRegion(i); // Fully occluded → skip
}
```

**Impact:** Accurate hardware occlusion culling → skip rendering for hidden regions (caves, behind mountains).

#### 7F: Pipeline Cache Persistence

```java
// Save pipeline cache to disk on shutdown
byte[] cacheData = vkGetPipelineCacheData(device, pipelineCache);
Files.write(Paths.get("vulkanium_pipeline_cache.bin"), cacheData);

// Load on next startup → compiled pipelines reused by driver
VkPipelineCacheCreateInfo cacheInfo = ...;
cacheInfo.pInitialData(loadedCacheData);
vkCreatePipelineCache(device, cacheInfo, null, pCache);
```

**Impact:** First launch may take 5-10s for pipeline compilation → subsequent launches near-instant.

---

### Phase 8 — Polish, Caching & UX 🟡 PARTIALLY IMPLEMENTED (5 files)

**Complexity:** LOW-MEDIUM  
**Files:** ~5  
**Goal:** User experience, diagnostics, caching, configuration UI  
**Depends on:** All previous phases  
**Duration:** ~1 week

#### 8A: SPIR-V Caching

Cache compiled SPIR-V bytecode to disk:
- Key: `SHA-256(transformed_glsl + shaderc_version + target_env)`
- Storage: `vulkanium_shader_cache/<pack_name>/<hash>.spv`
- Invalidation: pack file modification time + Vulkanium version
- **Expected speedup:** 5-10× on shader pack reload

#### 8B: Compilation Progress Screen

Visual progress during pack loading:
```
╔══════════════════════════════════════╗
║  Vulkanium — Compiling Shaders      ║
║                                      ║
║  [████████████░░░░░░░░]  60%        ║
║                                      ║
║  Transforming GLSL:    24/24 ✓      ║
║  Compiling SPIR-V:     14/24        ║
║  Creating Pipelines:   0/24         ║
║                                      ║
║  Current: composite5.fsh (BSL)      ║
║  Elapsed: 2.1s                      ║
╚══════════════════════════════════════╝
```

#### 8C: Shader Compilation Report

Detailed per-pass report dumped to `vulkanium_shader_debug/`:
- Transformed GLSL 450 source (for debugging)
- SPIR-V disassembly (optional)
- Per-pass timing
- Error messages with original line mapping
- Pack compatibility score

#### 8D: Configuration UI

Integration with Iris's settings screen:
- Vulkan device info display (GPU name, driver, VRAM)
- Frame queue size (2/3)
- Shadow resolution override
- MRT buffer format override
- Pipeline cache management (clear cache button)
- Performance overlay toggle (GPU time, VRAM usage)

#### 8E: Performance Overlay

Real-time GPU metrics via `VK_EXT_debug_utils`:
```
┌─ Vulkanium Performance ──────────┐
│ GPU: RTX 3060 | Driver: 555.58  │
│ FPS: 245 | Frame: 4.1ms         │
│ GPU: 3.2ms | CPU: 0.9ms         │
│ VRAM: 612 MB / 12288 MB         │
│ Draw calls: 47 (38 terrain)      │
│ Triangles: 2.4M                  │
│ Chunks: 4920 (2847 visible)      │
│ Shadow: 0.8ms (1024×1024)       │
│ Composite: 1.1ms (12 passes)    │
└──────────────────────────────────┘
```

---

## OptiFine GLSL Compatibility Strategy

### Complete Compatibility Matrix

This table shows every OptiFine GLSL feature and how Vulkanium handles it:

| Feature | OptiFine GLSL | Vulkanium Translation | Status |
|---------|--------------|----------------------|--------|
| `#version 120` | Old GLSL | → `#version 450` | ✅ Phase 2 |
| `#version 330 core` | Modern GLSL | → `#version 450` | ✅ Phase 2 |
| `#version 430 compatibility` | Mixed profile | → `#version 450` + compat shims | ✅ Phase 2 |
| `attribute vec4 pos` | Legacy input | → `layout(location=N) in vec4 pos` | ✅ Phase 2 |
| `varying vec4 color` | Legacy varying | → `layout(location=N) out/in vec4 color` | ✅ Phase 2 |
| `gl_Vertex` | Built-in vertex | → Decode from compressed format | ✅ Phase 1+2 |
| `gl_Color` | Built-in color | → Vertex attribute | ✅ Phase 1+2 |
| `gl_Normal` | Built-in normal | → Decode from octahedral packed | ✅ Phase 1+2 |
| `gl_MultiTexCoord0` | Texture coord | → Decode from uint16 | ✅ Phase 1+2 |
| `gl_MultiTexCoord1` | Lightmap | → Decode from uint8 pair | ✅ Phase 1+2 |
| `mc_Entity` | Block ID | → Vertex attribute (uint16) | ✅ Phase 1+2 |
| `at_tangent` | Tangent vector | → Decode from octahedral packed | ✅ Phase 1+2 |
| `at_midBlock` | Mid-block pos | → Vertex attribute (int8 pair) | ✅ Phase 1+2 |
| `mc_midTexCoord` | Mid-texture UV | → Vertex attribute (uint8 pair) | ✅ Phase 1+2 |
| `gl_ModelViewProjectionMatrix` | MVP | → UBO matrix product | ✅ Phase 2 |
| `gl_ModelViewMatrix` | MV | → UBO | ✅ Phase 2 |
| `gl_ProjectionMatrix` | Proj | → UBO | ✅ Phase 2 |
| `gl_NormalMatrix` | Normal matrix | → UBO (derived from MV) | ✅ Phase 2 |
| `texture2D()` / `texture3D()` | Legacy tex | → `texture()` | ✅ Phase 2 |
| `shadow2D()` | Shadow lookup | → `texture()` (comparison sampler) | ✅ Phase 2 |
| `gl_FragData[N]` | MRT output | → `layout(location=N) out vec4` | ✅ Phase 2+3 |
| `gl_FragColor` | Single output | → `layout(location=0) out vec4` | ✅ Phase 2 |
| `/* DRAWBUFFERS:0123 */` | MRT targets | → Pipeline blend state | ✅ Phase 3 |
| `/* RENDERTARGETS:0,1,2 */` | MRT targets | → Pipeline blend state | ✅ Phase 3 |
| `uniform sampler2D colortex0-7` | Color buffers | → Descriptor set 1 | ✅ Phase 3+4 |
| `uniform sampler2D depthtex0-2` | Depth buffers | → Descriptor set 1 | ✅ Phase 3 |
| `uniform sampler2D shadowtex0/1` | Shadow depth | → Descriptor set 1 | ✅ Phase 5 |
| `uniform sampler2D shadowcolor0/1` | Shadow color | → Descriptor set 1 | ✅ Phase 5 |
| `uniform sampler2D noisetex` | Noise texture | → Descriptor set 1 | ✅ Phase 2 |
| `uniform sampler2D normals` | PBR normal | → Descriptor set 1 | ✅ Phase 2 |
| `uniform sampler2D specular` | PBR specular | → Descriptor set 1 | ✅ Phase 2 |
| `gbufferModelView` + 50 uniforms | Pack uniforms | → UBO member expressions | ✅ Phase 2 |
| Geometry shaders | Optional | VkGraphicsPipeline geometry stage | ⚠️ Phase 7 |
| Compute shaders | Modern packs | VkComputePipeline | ⚠️ Phase 7 |
| `image2D` / `imageStore` | Image writes | Storage image descriptors | ⚠️ Phase 7 |

---

## Sodium vs VulkanMod vs Vulkanium Comparison

| Feature | Sodium | VulkanMod | Vulkanium |
|---------|--------|-----------|-----------|
| **API** | OpenGL 3.2+ | Vulkan 1.2 | Vulkan 1.2+ |
| **Multi-Draw-Indirect** | ✅ GL 4.3 | ❌ Per-section draws | ✅ Per-region MDI |
| **Multi-threaded commands** | ❌ (GL is single-thread) | ❌ (single cmd buffer) | ✅ Per-pass secondary |
| **Async uploads** | ❌ (same thread) | ❌ (same queue) | ✅ Transfer queue |
| **GPU frustum culling** | ❌ (CPU) | ❌ (CPU) | ✅ Compute shader |
| **GPU translucent sort** | ❌ (CPU) | ❌ (CPU) | ✅ Compute shader |
| **Occlusion culling** | ✅ (graph traversal) | ✅ (similar) | ✅ Hardware queries + graph |
| **Vertex format** | 20 bytes (no normal) | 24 bytes (no normal) | 32 bytes (all data) |
| **Region batching** | ✅ 8×4×8 sections | ❌ Per-section | ✅ 8×4×8 + indirect |
| **Shader packs** | ✅ Full (via Iris) | ❌ None | ✅ Full (GLSL→SPIR-V) |
| **MRT (G-buffer)** | ✅ 8 color + 3 depth | ❌ 1 color only | ✅ 8 color + 3 depth |
| **Composites** | ✅ 15 + 13 + 1 | ❌ None | ✅ 15 + 13 + 1 |
| **Shadows** | ✅ Full shadow maps | ❌ None | ✅ Full shadow maps |
| **Entity shaders** | ✅ Full | ❌ Vanilla only | ✅ Full |
| **Pipeline cache** | N/A (GL driver) | ✅ (in-session) | ✅ (disk-persistent) |
| **Memory control** | ❌ (driver) | ✅ VMA | ✅ VMA + pools |
| **VRAM monitoring** | ❌ | ❌ | ✅ Real-time stats |
| **Triple buffering** | ❌ (driver) | ✅ Configurable | ✅ Default |
| **HDR output** | ❌ | ❌ | ⚠️ Optional |

---

## File Inventory

### Total File Count by Phase

| Phase | New Files | Modified | Deleted | Description |
|-------|-----------|----------|---------|-------------|
| 0 | 12 | 0 | 0 | Core Vulkan abstraction |
| 1 | 12 | 0 | 0 | Chunk rendering engine |
| 2 | 6 | 2 | 1 | GLSL compatibility layer |
| 3 | 5 | 0 | 0 | MRT & G-buffer |
| 4 | 4 | 0 | 0 | Composite/deferred passes |
| 5 | 4 | 0 | 0 | Shadow mapping |
| 6 | 8 | 0 | 0 | Entity/sky/particle |
| 7 | 6 | 0 | 0 | Vulkan-native perf |
| 8 | 5 | 0 | 0 | Polish & UX |
| **Total** | **~62** | **2** | **1** | |

Plus ~12 mixin files, ~5 resource files (shaders, JSON configs).

**Grand total: ~80 files** for a complete Vulkan rendering engine with full shader pack support.

---

## Risk Assessment & Mitigations

| Risk | Severity | Phase | Mitigation |
|------|----------|-------|------------|
| **Vulkan driver bugs** (especially AMD on Linux) | HIGH | 0 | Extensive device capability checking; graceful fallback to OpenGL |
| **GLSL packs using unsupported features** (image load/store, compute) | HIGH | 2 | Feature detection + graceful fallback per-pass; generated shader fallback |
| **Memory leaks from raw Vulkan calls** | HIGH | 0-3 | Strict RAII pattern; validation layers in dev; frame-deferred cleanup |
| **Performance regression vs. Sodium** | MEDIUM | 1 | Benchmark suite; profiling with GPU timing queries; optimize hot path |
| **VulkanMod codebase changes upstream** | MEDIUM | All | Minimal dependency on VulkanMod internals; abstract away VulkanMod specifics |
| **ANTLR parse failures on exotic GLSL** | MEDIUM | 2 | Keep regex fallback; progressively expand grammar |
| **Shadow rendering too slow** | MEDIUM | 5 | Configurable resolution; aggressive culling; skip distant chunks |
| **Descriptor pool exhaustion** | LOW | 3-4 | Auto-growing pools; pool-per-pipeline isolation |
| **SPIR-V cache invalidation failures** | LOW | 8 | Pack hash + version in cache key; manual clear button |

---

## Performance Targets

### Micro-Benchmarks

| Operation | Sodium (OpenGL) | VulkanMod | Vulkanium (Target) |
|-----------|----------------|-----------|-------------------|
| Draw call overhead | ~1μs/call | ~0.3μs/call | **~0.05μs/call** (MDI) |
| Command recording | N/A (immediate) | 2ms/frame | **0.5ms/frame** (parallel) |
| Chunk upload | 1-3ms stall | 1-3ms stall | **0ms stall** (async) |
| Frustum culling | 0.2ms (CPU) | 0.3ms (CPU) | **<0.01ms** (GPU compute) |
| Translucent sort | 0.5-2ms (CPU) | 0.5-2ms (CPU) | **<0.1ms** (GPU compute) |
| Shader pack load | 1-3s (GL compile) | 2-5s (GLSL→SPIR-V) | **0.3-1s** (SPIR-V cache) |
| Pipeline creation | N/A (GL programs) | 50-200ms | **<10ms** (disk cache) |

### Memory Budget

| Resource | VulkanMod | Vulkanium |
|----------|-----------|-----------|
| Staging buffers | 60 MB (2×30MB) | 48-256 MB (dynamic) |
| Vertex buffers | ~200 MB | ~200 MB (same geometry) |
| Render targets (vanilla) | ~50 MB | ~50 MB |
| Render targets (shader pack) | N/A | ~200-400 MB (8 color + 3 depth, 1080p) |
| Shadow maps | N/A | ~16-64 MB (1024-4096 resolution) |
| Descriptor pools | ~5 MB | ~10 MB |
| Pipeline cache | ~2 MB | ~5-20 MB (disk persistent) |
| **Total VRAM** | **~320 MB** | **~550-1000 MB** |

Vulkanium uses more VRAM due to render targets and shadows — this is expected and matches Sodium+Iris OpenGL VRAM usage. The additional VRAM is well-utilized for visual quality.

---

## Project Timeline (Estimated)

| Phase | Duration | Cumulative | Milestone |
|-------|----------|------------|-----------|
| 0 — Core Vulkan | 2 weeks | Week 2 | Window + triangle renders on Vulkan |
| 1 — Chunk Rendering | 3 weeks | Week 5 | Terrain renders at Sodium-like quality |
| 2 — GLSL Compat | 3 weeks | Week 8 | Simple shader packs show effects |
| 3 — MRT/G-Buffer | 2 weeks | Week 10 | Deferred packs write G-buffer data |
| 4 — Composites | 2 weeks | Week 12 | Post-processing (bloom, DOF) works |
| 5 — Shadows | 2 weeks | Week 14 | Shadow maps functional |
| 6 — Entity/Sky | 2 weeks | Week 16 | All render phases have pack shaders |
| 7 — Vulkan Perf | 3 weeks | Week 19 | Multi-thread + compute optimizations |
| 8 — Polish | 1 week | Week 20 | Cache, progress, diagnostics |

**Total estimated development: ~20 weeks (5 months)**

---

## Getting Started

### Prerequisites

1. Vulkan SDK installed (for validation layers during development)
2. LWJGL 3.3.1+ with Vulkan, VMA, and shaderc modules
3. Fabric Loader / Fabric API
4. Iris Shaders source (for pack loading infrastructure)
5. GPU with Vulkan 1.2+ support

### First Steps

1. Start with Phase 0: Get `VulkaniumInstance` creating a VkInstance + debug messenger
2. Create `VulkaniumDevice` with proper device selection
3. Set up swapchain and clear-screen test (just `vkCmdClearColorImage` to solid color)
4. Verify the base works on NVIDIA, AMD, Intel, and Steam Deck (AMD integrated)
5. Proceed to Phase 1 with confidence that the core abstractions are solid

---

---

## Phase 9 — Compute Pipeline Platform (GP-Computing) 🟡 PARTIALLY IMPLEMENTED

**Complexity:** HIGH  
**Files:** 12 implemented (5 core Java + 4 compute module Java + 3 GLSL compute shaders)  
**Goal:** General-purpose Vulkan compute infrastructure for non-graphics optimization  
**Depends on:** Phase 0 (compute queue), Phase 7 (compute shader infrastructure)  
**Duration:** ~4 weeks

### Vision

Vulkanium is not just a graphics renderer — it's a **Vulkan compute platform** for Minecraft.
Modern GPUs have thousands of cores sitting idle during most game logic. Vulkanium exposes
a clean compute API that mods can use for parallel workloads, inspired by C2ME's threading
model but moved to the GPU where appropriate.

### Architecture: Compute Task System

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      VulkaniumCompute (Public API)                       │
│                                                                         │
│  submitTask(ComputeTask) → ComputeFuture                               │
│  submitBatch(List<ComputeTask>) → List<ComputeFuture>                  │
│  queryCapabilities() → ComputeCapabilities                             │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────┐  │
│  │ ComputeScheduler │  │ ComputeAllocator │  │ ComputePipelinePool  │  │
│  │                  │  │                  │  │                      │  │
│  │ Priority queue   │  │ SSBO ring buffer │  │ Cached compute       │  │
│  │ Dependency graph │  │ Readback staging │  │ pipelines by         │  │
│  │ Timeline sync    │  │ Device-local     │  │ shader + specializ.  │  │
│  └──────────────────┘  └──────────────────┘  └──────────────────────┘  │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                     Built-in Compute Modules                            │
│                                                                         │
│  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌────────────┐ │
│  │ ChunkLighting │ │ PathFinding   │ │ PhysicsAccel  │ │ WorldGen   │ │
│  │               │ │               │ │               │ │ Accel      │ │
│  │ Parallel      │ │ GPU A* for    │ │ Collision     │ │ Noise &    │ │
│  │ light flood   │ │ entity AI     │ │ broadphase    │ │ structure  │ │
│  │ propagation   │ │ pathfinding   │ │ on GPU        │ │ placement  │ │
│  └───────────────┘ └───────────────┘ └───────────────┘ └────────────┘ │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Core Compute API

```java
// Public API — mods register compute tasks
public interface ComputeTask {
    ComputeShaderSource getShader();          // GLSL/SPIR-V source
    BufferBinding[] getInputBindings();        // Input SSBOs
    BufferBinding[] getOutputBindings();       // Output SSBOs
    int[] getWorkGroupSize();                  // local_size_x/y/z
    int[] getDispatchSize();                   // Dispatch group count
    ComputeTaskPriority getPriority();         // CRITICAL, HIGH, NORMAL, LOW
    List<ComputeTask> getDependencies();       // Task graph edges
}

public interface ComputeFuture {
    boolean isDone();
    void await();                              // Block until complete
    ByteBuffer getResult(int bindingIndex);    // Read back output
    long getGpuTimeNanos();                    // Profiling
}
```

### Built-in GP-Compute Modules (C2ME-Inspired)

**9A: Parallel Chunk Lighting** (`chunk_light_propagate.comp`)
- Block light + sky light flood fill on GPU
- Each workgroup handles one 16³ section
- Iterative wavefront propagation (6 iterations typical)
- **C2ME equivalent:** `ThreadedAnvilChunkStorage` light engine parallelism
- **Expected speedup:** 10-50× vs CPU single-threaded light updates

**9B: GPU-Accelerated Pathfinding** (`pathfind_astar.comp`)
- Batch A* pathfinding for entities on the compute queue
- Voxel grid stored as 3D texture (passability flags)
- 64 paths computed in parallel per dispatch
- **Expected speedup:** 100+ entities pathfinding simultaneously

**9C: Physics Broadphase** (`physics_broadphase.comp`)
- Sort-and-sweep collision detection on GPU
- AABB pairs output for CPU narrowphase
- Useful for entity-dense scenes (mob farms, PvP)

**9D: World Generation Acceleration** (`worldgen_noise.comp`)
- Perlin/Simplex noise generation on GPU
- Density field computation for terrain shaping
- Post-generation decoration placement candidates
- **C2ME equivalent:** Parallel chunk generation with custom executors

### Compute Queue Management

```java
public class ComputeScheduler {
    // Dedicated compute queue (separate from graphics)
    // Tasks submitted as timeline-semaphore-dependent chains
    // Graphics queue can wait on compute results if needed
    
    void submitFrame(List<ComputeTask> tasks) {
        // 1. Sort by priority + dependency order
        // 2. Batch compatible tasks (same pipeline)
        // 3. Record compute command buffer
        // 4. Submit to compute queue with timeline semaphore
        // 5. Return futures for each task
    }
}
```

### Data Transfer Patterns

```
CPU → GPU (Input):
    ComputeAllocator.upload(data) → SSBO binding
    Uses staging ring (shared with graphics uploads)
    
GPU → GPU (Inter-task):
    Output SSBO of task A = Input SSBO of task B
    Zero-copy, just barrier between dispatches
    
GPU → CPU (Readback):
    ComputeAllocator.readback(outputBinding) → ByteBuffer
    Uses readback ring buffer (HOST_VISIBLE | HOST_CACHED)
    Non-blocking: result available next frame
```

---

## Phase 10 — Ray-Tracing Pipeline (Hardware RT) 🟠 SCAFFOLDED / PARTIAL

**Complexity:** VERY HIGH  
**Files:** 15 implemented (11 Java + 4 RT shaders: .rgen, .rchit, 2×.rmiss)  
**Goal:** Optional hardware ray-tracing for path-traced GI, reflections, shadows  
**Depends on:** Phase 0 (device), Phase 1 (chunk data), Phase 9 (compute infrastructure)  
**Duration:** ~6 weeks

### Prerequisites

Hardware RT requires these Vulkan extensions (probed at device creation):
- `VK_KHR_acceleration_structure` — BLAS/TLAS management
- `VK_KHR_ray_tracing_pipeline` — RT shader stages
- `VK_KHR_spirv_1_4` — Extended SPIR-V for RT shaders
- `VK_KHR_deferred_host_operations` — Async BLAS builds
- `VK_KHR_synchronization2` — Enhanced sync (also useful for rasterization)

**Minimum hardware:** NVIDIA RTX 2060 / AMD RX 6600 / Intel Arc A750

### Architecture: MCVR-Inspired WorldModule System

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        RT Pipeline Manager                                │
│                                                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────┐ │
│  │ BLAS Manager │  │ TLAS Builder │  │ SBT Manager  │  │ RT Pipeline │ │
│  │              │  │              │  │              │  │             │ │
│  │ Per-chunk    │  │ Per-frame    │  │ raygen       │  │ VkRayTr.    │ │
│  │ geometry     │  │ scene        │  │ miss         │  │ Pipeline    │ │
│  │ BLAS builds  │  │ assembly     │  │ closest-hit  │  │ creation    │ │
│  │ + compaction │  │ (instances)  │  │ any-hit      │  │ + cache     │ │
│  └──────────────┘  └──────────────┘  └──────────────┘  └─────────────┘ │
│                                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│                      World Modules (Pluggable Stages)                    │
│                                                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────┐ │
│  │ RT Module    │  │ Denoiser     │  │ Upscaler     │  │ Tone Map    │ │
│  │              │  │              │  │              │  │             │ │
│  │ Path trace   │  │ SVGF compute │  │ FSR 3.1      │  │ ACES / PBR │ │
│  │ GI, reflect, │  │ temporal     │  │ DLSS (opt.)  │  │ HDR        │ │
│  │ shadows, AO  │  │ accumulation │  │ XeSS (opt.)  │  │ tonemapper │ │
│  └──────────────┘  └──────────────┘  └──────────────┘  └─────────────┘ │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### 10A: Acceleration Structure Management

**BLAS (Bottom-Level Acceleration Structure) — Per Chunk**

```java
public class BLASManager {
    // Each chunk section (16³) gets its own BLAS
    // Built from chunk mesh data (same vertex buffer as rasterization)
    
    // PBR vertex format for RT (extends terrain format):
    // position(12) + normal(4) + uv(4) + tangent(4)
    // + color(4) + emission(2) + materialId(2) = 32 bytes
    // (Same as rasterization vertex — reuse buffer!)
    
    void buildBLAS(ChunkSection section) {
        VkAccelerationStructureGeometryKHR geometry = ...;
        geometry.geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR);
        geometry.geometry().triangles()
            .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
            .vertexData(section.getVertexDeviceAddress())
            .vertexStride(32)
            .maxVertex(section.getVertexCount())
            .indexType(VK_INDEX_TYPE_UINT32)
            .indexData(section.getIndexDeviceAddress());
        
        // Build with VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
        // Compaction pass to reclaim memory after build
    }
    
    // Batched builds: BLASBatchBuilder groups multiple chunk builds
    // into a single vkCmdBuildAccelerationStructuresKHR call
    void buildBatch(List<ChunkSection> sections) {
        // Score by priority: distance + time-since-last-build
        // Submit batched build command
        // Deferred host operations for async CPU-side prep
    }
}
```

**TLAS (Top-Level Acceleration Structure) — Per Frame**

```java
public class TLASBuilder {
    // Rebuilt every frame with current chunk BLAS instances
    // + entity BLAS instances if applicable
    
    void rebuildTLAS() {
        List<VkAccelerationStructureInstanceKHR> instances = new ArrayList<>();
        
        for (ChunkSection section : visibleSections) {
            VkAccelerationStructureInstanceKHR instance = ...;
            instance.transform(section.getWorldTransform());
            instance.accelerationStructureReference(section.getBLASAddress());
            instance.instanceCustomIndex(section.getMaterialOffset());
            instance.mask(0xFF);
            instance.flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR);
            instances.add(instance);
        }
        
        // Upload instance buffer → build TLAS
        // VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR
        // (TLAS rebuilt every frame, prefer build speed over trace speed)
    }
}
```

### 10B: Shader Binding Table (SBT)

```java
public class ShaderBindingTable {
    // Regions:
    //   raygen:      1 entry (world.rgen — primary rays)
    //   miss:        2 entries (sky.rmiss, shadow.rmiss)
    //   closest-hit: N entries (per material type)
    //   callable:    M entries (utility shaders)
    
    // SBT record = handleSize + customData
    // handleSize from physicalDeviceRTProperties.shaderGroupHandleSize
    
    void build(VkPipeline rtPipeline) {
        // Query shader group handles
        // Lay out in aligned buffer regions
        // Upload to device-local buffer
    }
}
```

### 10C: RT Shader Programs

```glsl
// world.rgen — Ray generation shader (primary camera rays)
#version 460
#extension GL_EXT_ray_tracing : require

layout(set = 0, binding = 0) uniform accelerationStructureEXT topLevelAS;
layout(set = 0, binding = 1, rgba16f) uniform image2D outImage;
layout(set = 0, binding = 2) uniform CameraData { ... } camera;

layout(location = 0) rayPayloadEXT RayPayload payload;

void main() {
    vec2 pixelCenter = vec2(gl_LaunchIDEXT.xy) + vec2(0.5);
    vec2 uv = pixelCenter / vec2(gl_LaunchSizeEXT.xy);
    vec3 origin = camera.position;
    vec3 direction = calculateRayDirection(uv, camera);
    
    traceRayEXT(topLevelAS,
        gl_RayFlagsOpaqueEXT,
        0xFF,           // cull mask
        0,              // SBT offset (closest-hit index)
        0,              // SBT stride
        0,              // miss index
        origin,
        0.001,          // tMin
        direction,
        1000.0,         // tMax
        0);             // payload location
    
    imageStore(outImage, ivec2(gl_LaunchIDEXT.xy), vec4(payload.color, 1.0));
}
```

```glsl
// world_solid.rchit — Closest-hit shader for solid blocks
#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require

layout(location = 0) rayPayloadInEXT RayPayload payload;
hitAttributeEXT vec2 baryCoords;

void main() {
    // Fetch vertex data using gl_PrimitiveID
    // Compute interpolated position, normal, UV
    // Sample block texture atlas
    // Compute direct lighting (sun/moon shadow ray)
    // Compute indirect lighting (bounce ray)
    // Return accumulated color in payload
    
    payload.color = directLighting + indirectLighting;
    payload.distance = gl_HitTEXT;
}
```

### 10D: Denoiser Module (SVGF)

```java
public class SVGFDenoiser implements WorldModule {
    // Spatiotemporal Variance-Guided Filtering
    // Compute shader pipeline:
    //   1. Temporal accumulation (reproject previous frame)
    //   2. Variance estimation (spatial + temporal)
    //   3. Wavelet À-trous filter (3-5 iterations)
    //   4. Temporal anti-aliasing
    
    // Input: noisy RT output + motion vectors + depth + normals
    // Output: denoised image suitable for display
    
    ComputePipeline temporalAccum;
    ComputePipeline varianceEstimate;
    ComputePipeline atrousFilter;
    
    void execute(VulkaniumCommand cmd, RenderTarget noisyInput, RenderTarget output) {
        // Dispatch temporal accumulation
        // Dispatch variance estimation
        // Dispatch À-trous filter (3-5 iterations, ping-pong buffers)
        // Output clean image
    }
}
```

### 10E: Upscaler Integration

```java
public interface Upscaler extends WorldModule {
    void init(int inputWidth, int inputHeight, int outputWidth, int outputHeight);
    void execute(VulkaniumCommand cmd, RenderTarget input, RenderTarget output,
                 RenderTarget depth, RenderTarget motionVectors);
    UpscalerType getType();
}

// Implementations:
// - FSR3Upscaler (AMD FidelityFX — open source, works on all GPUs)
// - DLSSUpscaler (NVIDIA only — requires DLSS SDK + RTX hardware)
// - XeSSUpscaler (Intel — works on all GPUs via DP4a)
```

### RT Capability Tiers

| Tier | Hardware | Features |
|------|----------|----------|
| **Tier 0** | No RT extensions | Rasterization only (default) |
| **Tier 1** | RT + 4GB VRAM | RT shadows only (1 bounce) |
| **Tier 2** | RT + 8GB VRAM | RT shadows + reflections (2 bounces) |
| **Tier 3** | RT + 12GB+ VRAM | Full path tracing (4+ bounces) + SVGF |

---

## Phase 11 — Module/Plugin Architecture 🟡 PARTIALLY IMPLEMENTED

**Complexity:** MEDIUM-HIGH  
**Files:** 13 implemented (5 core API + 8 built-in module implementations)  
**Goal:** Extensible module system for render pipeline stages  
**Depends on:** Phase 7 (performance), Phase 9 (compute), Phase 10 (RT)  
**Duration:** ~2 weeks

### WorldModule Interface (MCVR-Inspired)

```java
public interface WorldModule {
    String getName();
    ModulePhase getPhase();                    // BEFORE_TERRAIN, AFTER_TERRAIN, POST_PROCESS
    int getPriority();                          // Execution order within phase
    boolean isAvailable();                      // Runtime capability check
    
    void init(VulkaniumDevice device, VulkaniumMemory memory);
    void resize(int width, int height);
    void execute(WorldModuleContext ctx);
    void destroy();
}

public interface WorldModuleContext {
    VulkaniumCommand getCommandBuffer();
    RenderTargetSet getRenderTargets();
    ComputeScheduler getComputeScheduler();
    FrameUniforms getUniforms();
    AccelerationStructure getTLAS();            // null if no RT
    RenderTarget getSwapchainTarget();
}
```

### Built-in Modules

| Module | Phase | Purpose |
|--------|-------|---------|
| `RasterTerrainModule` | TERRAIN | Standard chunk rasterization |
| `RTTerrainModule` | TERRAIN | RT path-traced terrain (replaces raster) |
| `ShadowMapModule` | BEFORE_TERRAIN | Shadow pass rasterization |
| `RTShadowModule` | BEFORE_TERRAIN | RT shadow rays (replaces shadow maps) |
| `SVGFDenoiserModule` | POST_PROCESS | Temporal denoiser for RT output |
| `FSR3Module` | POST_PROCESS | AMD FidelityFX upscaling |
| `DLSSModule` | POST_PROCESS | NVIDIA DLSS upscaling |
| `ToneMappingModule` | POST_PROCESS | HDR→SDR tone mapping |
| `ComputeLightModule` | BEFORE_TERRAIN | GPU-accelerated light propagation |
| `CompositePassModule` | POST_PROCESS | Shader pack composite passes |

### Module Discovery & Registration

```java
public class ModuleRegistry {
    // Modules register via Fabric entrypoint or API
    void register(WorldModule module);
    
    // Pipeline builder assembles modules into execution order
    List<WorldModule> buildPipeline() {
        // 1. Filter by isAvailable()
        // 2. Sort by phase, then priority
        // 3. Resolve conflicts (e.g., RTTerrain disables RasterTerrain)
        // 4. Return ordered list
    }
}
```

---

## Revised Phase Dependencies

```
Phase 0 (Core) ──┬──► Phase 1 (Chunks) ──┬──► Phase 2 (GLSL Compat) ──► Phase 3 (MRT)
                  │                        │                                    │
                  │                        │                              Phase 4 (Composite)
                  │                        │                                    │
                  │                        └──────────────────────► Phase 5 (Shadows)
                  │                                                       │
                  │                                                 Phase 6 (Entity/Sky)
                  │                                                       │
                  └──────────────────────────────────────────► Phase 7 (Vulkan Perf)
                                                                    │         │
                                                              Phase 8 (UX)    │
                                                                    │         │
                                                              Phase 9 (Compute Platform)
                                                                    │
                                                              Phase 10 (Ray Tracing)
                                                                    │
                                                              Phase 11 (Module System)
```

---

## Revised Module Structure (Full Project Tree)

```
src/vulkanium/
├── java/net/irisshaders/vulkanium/
│   ├── Vulkanium.java                          # Mod entry, subsystem init
│   ├── VulkaniumPreLaunch.java                 # Pre-launch Vulkan detection
│   ├── VulkaniumConfig.java                    # Configuration management
│   │
│   ├── api/                                    # Public API for mods
│   │   ├── VulkaniumAPI.java                  # Static entry point
│   │   ├── compute/
│   │   │   ├── ComputeTask.java              # Task interface
│   │   │   ├── ComputeFuture.java            # Async result handle
│   │   │   ├── ComputeCapabilities.java      # GPU compute feature query
│   │   │   └── BufferBinding.java            # SSBO binding for compute
│   │   ├── module/
│   │   │   ├── WorldModule.java              # Render module interface
│   │   │   ├── WorldModuleContext.java        # Module execution context
│   │   │   ├── ModulePhase.java              # BEFORE_TERRAIN, TERRAIN, POST_PROCESS
│   │   │   └── ModuleRegistry.java           # Module discovery
│   │   └── rt/
│   │       ├── RTCapabilities.java           # RT feature tier query
│   │       ├── RTScene.java                  # Scene access for RT mods
│   │       └── RTMaterialProvider.java       # Custom material mapping
│   │
│   ├── core/                                   # Vulkan abstraction layer
│   │   ├── VulkaniumInstance.java              # VkInstance + debug
│   │   ├── VulkaniumDevice.java               # Physical/logical device
│   │   ├── VulkaniumQueues.java               # Multi-queue (graphics/present/transfer/compute)
│   │   ├── VulkaniumSwapchain.java            # Swapchain lifecycle
│   │   ├── VulkaniumMemory.java               # VMA-based allocator
│   │   ├── VulkaniumCommand.java              # Command buffer management
│   │   ├── VulkaniumSync.java                 # Fences, semaphores, barriers
│   │   └── VulkaniumCapabilities.java         # Extension/feature detection
│   │
│   ├── resource/                               # GPU resource management
│   │   ├── pipeline/
│   │   │   ├── VulkaniumGraphicsPipeline.java
│   │   │   ├── VulkaniumComputePipeline.java
│   │   │   ├── VulkaniumRayTracingPipeline.java  # RT pipeline (optional)
│   │   │   ├── VulkaniumPipelineCache.java
│   │   │   └── PipelineStateKey.java
│   │   ├── descriptor/
│   │   │   ├── DescriptorSetManager.java
│   │   │   ├── DescriptorPoolManager.java
│   │   │   └── DescriptorBinding.java
│   │   ├── buffer/
│   │   │   ├── VulkaniumBuffer.java
│   │   │   ├── StagingRing.java
│   │   │   ├── UniformRing.java
│   │   │   ├── VertexRing.java
│   │   │   ├── IndexRing.java
│   │   │   └── IndirectDrawBuffer.java
│   │   ├── texture/
│   │   │   ├── VulkaniumImage.java
│   │   │   ├── VulkaniumSampler.java
│   │   │   └── TextureUploader.java
│   │   ├── target/
│   │   │   ├── RenderTarget.java
│   │   │   ├── RenderTargetSet.java
│   │   │   ├── MRTFramebuffer.java
│   │   │   ├── MRTRenderPass.java
│   │   │   └── BufferFlipper.java
│   │   └── accel/                              # Acceleration structures (RT)
│   │       ├── AccelerationStructure.java     # Base BLAS/TLAS wrapper
│   │       ├── BLASManager.java               # Per-chunk BLAS lifecycle
│   │       ├── BLASBatchBuilder.java          # Batched BLAS builds
│   │       ├── TLASBuilder.java               # Per-frame TLAS assembly
│   │       └── ShaderBindingTable.java        # SBT management
│   │
│   ├── render/                                 # Rendering engine
│   │   ├── FrameOrchestrator.java             # Frame lifecycle
│   │   ├── PassRecorder.java                  # Render pass recording
│   │   ├── DrawDispatcher.java                # Draw call batching
│   │   ├── RenderGraph.java                   # Pass dependency graph
│   │   │
│   │   ├── terrain/                            # Chunk rendering (Sodium-inspired)
│   │   │   ├── ChunkRenderer.java             # Main terrain orchestrator
│   │   │   ├── ChunkVertexFormat.java         # 32-byte terrain vertex
│   │   │   ├── region/
│   │   │   │   ├── RenderRegion.java          # 8×4×8 section group
│   │   │   │   ├── RenderRegionManager.java   # Region lifecycle
│   │   │   │   └── RegionDrawBatch.java       # MDI command builder per region
│   │   │   ├── section/
│   │   │   │   ├── RenderSection.java         # Single 16³ chunk section
│   │   │   │   ├── SectionData.java           # Vertex/index data
│   │   │   │   └── SectionVisibility.java     # Visibility state
│   │   │   ├── build/
│   │   │   │   ├── ChunkBuildTask.java        # Async mesh build job
│   │   │   │   ├── ChunkBuildScheduler.java   # Priority-scored scheduling
│   │   │   │   ├── ChunkBuildWorkerPool.java  # Thread pool (work-stealing)
│   │   │   │   └── ChunkMeshBuilder.java      # BlockState → vertex data
│   │   │   ├── cull/
│   │   │   │   ├── ChunkFrustumCuller.java    # CPU frustum pre-cull
│   │   │   │   ├── ChunkOcclusionCuller.java  # Graph-based occlusion
│   │   │   │   └── ComputeFrustumCull.java    # GPU compute cull dispatch
│   │   │   ├── upload/
│   │   │   │   ├── ChunkUploadManager.java    # Async transfer queue upload
│   │   │   │   └── ChunkUploadBatch.java      # Batched upload commands
│   │   │   └── pass/
│   │   │       ├── TerrainRenderPass.java     # Per-layer render pass
│   │   │       └── TerrainPassType.java       # SOLID, CUTOUT, TRANSLUCENT, TRIPWIRE
│   │   │
│   │   ├── entity/
│   │   │   ├── EntityRenderer.java
│   │   │   ├── EntityVertexBuffer.java
│   │   │   └── EntityShaderBinder.java
│   │   │
│   │   ├── shadow/
│   │   │   ├── ShadowRenderer.java
│   │   │   ├── ShadowFramebuffer.java
│   │   │   ├── ShadowFrustum.java
│   │   │   └── ShadowMatrices.java
│   │   │
│   │   ├── composite/
│   │   │   ├── CompositeRenderer.java
│   │   │   ├── CompositePass.java
│   │   │   └── DeferredPass.java
│   │   │
│   │   ├── sky/
│   │   │   ├── SkyRenderer.java
│   │   │   └── CloudRenderer.java
│   │   │
│   │   ├── misc/
│   │   │   ├── ParticleRenderer.java
│   │   │   ├── WeatherRenderer.java
│   │   │   └── HandRenderer.java
│   │   │
│   │   └── module/                             # WorldModule implementations
│   │       ├── ModulePipelineBuilder.java     # Assembles modules into passes
│   │       ├── raster/
│   │       │   └── RasterTerrainModule.java
│   │       ├── rt/
│   │       │   ├── RTTerrainModule.java
│   │       │   └── RTShadowModule.java
│   │       ├── denoise/
│   │       │   └── SVGFDenoiserModule.java
│   │       ├── upscale/
│   │       │   ├── FSR3Module.java
│   │       │   ├── DLSSModule.java
│   │       │   └── XeSSModule.java
│   │       └── tonemap/
│   │           └── ToneMappingModule.java
│   │
│   ├── compute/                                # GP-Computing platform
│   │   ├── VulkaniumCompute.java              # Public compute entry point
│   │   ├── ComputeScheduler.java              # Task scheduling + dependency graph
│   │   ├── ComputeAllocator.java              # SSBO ring + readback staging
│   │   ├── ComputePipelinePool.java           # Cached compute pipelines
│   │   └── builtin/
│   │       ├── ChunkLightingCompute.java      # Parallel light flood fill
│   │       ├── PathfindingCompute.java        # GPU A* pathfinding
│   │       ├── PhysicsBroadphaseCompute.java  # GPU broadphase collision
│   │       ├── WorldGenNoiseCompute.java      # Noise on GPU
│   │       └── TranslucentSortCompute.java    # Translucent triangle sort
│   │
│   ├── shader/                                 # Shader compilation
│   │   ├── ShaderCompiler.java                # GLSL → SPIR-V
│   │   ├── ShaderCache.java                   # Disk-persistent SPIR-V cache
│   │   ├── ShaderModuleManager.java           # VkShaderModule lifecycle
│   │   ├── OptiFineGlslBridge.java            # OptiFine compatibility transforms
│   │   ├── VulkanTransformer.java             # AST-level GLSL → 450 transform
│   │   ├── UniformBridge.java                 # 2048-byte UBO mapping
│   │   ├── UniformDataWriter.java             # Per-frame uniform writes
│   │   └── PackTextureManager.java            # Shader pack texture binding
│   │
│   ├── compat/                                 # Mod compatibility
│   │   ├── iris/
│   │   │   ├── IrisIntegration.java
│   │   │   └── IrisPipelineAdapter.java
│   │   └── sodium/
│   │       └── SodiumPatterns.java
│   │
│   └── mixin/                                  # Fabric mixins
│       ├── VulkaniumMixinPlugin.java
│       ├── core/
│       │   ├── MixinWindow.java
│       │   ├── MixinGameRenderer.java
│       │   └── MixinLevelRenderer.java
│       ├── render/
│       │   ├── MixinBufferUploader.java
│       │   ├── MixinRenderSystem.java
│       │   └── MixinVertexBuffer.java
│       └── compat/
│           └── MixinVulkanModDetect.java
│
├── resources/
│   ├── fabric.mod.json
│   ├── vulkanium.mixins.json
│   ├── vulkanium.accesswidener
│   └── assets/vulkanium/
│       ├── shaders/
│       │   ├── include/
│       │   │   ├── vulkanium_common.glsl
│       │   │   ├── vertex_decode.glsl
│       │   │   └── fog.glsl
│       │   ├── terrain/
│       │   │   ├── terrain.vert
│       │   │   └── terrain.frag
│       │   ├── entity/
│       │   ├── sky/
│       │   ├── fullscreen/
│       │   │   ├── blit.vert
│       │   │   └── blit.frag
│       │   ├── compute/
│       │   │   ├── frustum_cull.comp
│       │   │   ├── translucent_sort.comp
│       │   │   ├── chunk_light.comp
│       │   │   └── physics_broadphase.comp
│       │   └── rt/
│       │       ├── world.rgen
│       │       ├── sky.rmiss
│       │       ├── shadow.rmiss
│       │       ├── world_solid.rchit
│       │       ├── world_transparent.rchit
│       │       └── shadow.rahit
│       └── lang/
│           └── en_us.json
```

---

## Revised File Count

| Phase | New Files | Description |
|-------|-----------|-------------|
| 0 | 12 | Core Vulkan abstraction |
| 1 | 18 | Chunk rendering (region, section, build, cull, upload, pass) |
| 2 | 8 | GLSL compatibility layer |
| 3 | 5 | MRT & G-buffer |
| 4 | 4 | Composite/deferred passes |
| 5 | 4 | Shadow mapping |
| 6 | 8 | Entity/sky/particle |
| 7 | 6 | Vulkan-native perf |
| 8 | 5 | Polish & UX |
| 9 | 14 | Compute platform (API + scheduler + 5 built-in modules) |
| 10 | 18 | Ray-tracing (AS, SBT, pipeline, denoiser, upscaler, shaders) |
| 11 | 10 | Module/plugin architecture |
| **Total** | **~112** | |

Plus ~12 mixin files, ~16 shader files, ~5 resource files.

**Grand total: ~145 files** for a complete Vulkan rendering engine + compute platform + RT pipeline + module system.

---

## Revised Timeline

| Phase | Duration | Cumulative | Milestone |
|-------|----------|------------|-----------|
| 0 — Core Vulkan | 2 weeks | Week 2 | ✅ COMPLETE |
| 1 — Chunk Rendering | 3 weeks | Week 5 | Terrain at Sodium quality on Vulkan |
| 2 — GLSL Compat | 3 weeks | Week 8 | Shader packs show effects |
| 3 — MRT/G-Buffer | 2 weeks | Week 10 | Deferred G-buffer data |
| 4 — Composites | 2 weeks | Week 12 | Post-processing works |
| 5 — Shadows | 2 weeks | Week 14 | Shadow maps functional |
| 6 — Entity/Sky | 2 weeks | Week 16 | All phases have pack shaders |
| 7 — Vulkan Perf | 3 weeks | Week 19 | Multi-thread + GPU compute |
| 8 — Polish | 1 week | Week 20 | Cache, progress, diagnostics |
| 9 — Compute Platform | 4 weeks | Week 24 | GP-computing API + built-in modules |
| 10 — Ray Tracing | 6 weeks | Week 30 | Hardware RT with denoiser + upscaler |
| 11 — Module System | 2 weeks | Week 32 | Pluggable pipeline architecture |

**Total estimated: ~32 weeks (8 months)**

---

## Implementation Summary

All 12 phases (0–11) now have substantial code artifacts in-tree, but multiple runtime-critical paths are still in progress. The inventory below reflects structure and coverage, not production completeness.

### File Counts by Phase

| Phase | Category | Files | Key Classes |
|-------|----------|-------|-------------|
| 0 — Core Abstraction | Build + Entry + Core + Resource + Mixin + Shaders | 28 | VulkaniumInstance, VulkaniumDevice, FrameOrchestrator, SPIRVCompiler |
| 1 — Chunk Rendering | Terrain passes, sections, regions, culling, building, upload | 18 | ChunkRenderer, RenderSection, RenderRegionManager, ChunkBuildWorkerPool |
| 2 — GLSL Compat | Preprocessor, transformer, uniforms, compiler, shader modules | 5 | VulkaniumGlslTransformer, UniformBridge, ShaderCompiler |
| 3 — MRT/G-Buffer | Render targets, G-buffer config, MRT pass/pipeline, samplers | 5 | GBufferTargets, MRTRenderPass, MRTGraphicsPipeline |
| 4 — Composite | Composite pass, pass manager, final pass | 3 | CompositePass, CompositePassManager, FinalPass |
| 5 — Shadows | Directives, map, matrices, renderer | 4 | ShadowMap, ShadowMatrices, ShadowRenderer |
| 6 — Entity/Sky | Program IDs, shader keys, program manager, world phases, entity/sky | 5 | ProgramId, ShaderKey, ShaderProgramManager, WorldRenderingPhase |
| 7 — Performance | Parallel recording, async transfer, GPU culling/sorting, occlusion, pipeline cache | 6 | ParallelCommandRecorder, AsyncTransferQueue, GPUFrustumCuller |
| 8 — Polish/UX | SPIR-V cache, compilation progress, debug report, config UI, overlay | 5 | SPIRVCache, CompilationProgress, PerformanceOverlay, ConfigUI |
| 9 — Compute | Core (allocator, capabilities, task graph) + modules (lighting, pathfinding, physics, worldgen) + shaders (3) | 12 | ComputeTaskGraph, ComputeAllocator, ChunkLightingCompute |
| 10 — Ray Tracing | Core (AS, BLAS, TLAS, SBT, pipeline) + extensions (capabilities, RT manager, SVGF, upscaler, FSR3, materials) + shaders (4) | 15 | RTModuleManager, SVGFDenoiser, FSR3Upscaler, MaterialTable |
| 11 — Module System | API interfaces (WorldModule, ModulePhase, ModuleRegistry) + pipeline builder + 9 built-in modules | 13 | ModulePipelineBuilder, WorldModule, ModulePhase |
| **Total** | | **~119** | |

### Built-in Module Inventory

| Module | Conflict Group | Priority | Requires | Phases |
|--------|---------------|----------|----------|--------|
| RasterTerrainModule | terrain | 0 | — | TERRAIN_SOLID, TERRAIN_CUTOUT, TERRAIN_TRANSLUCENT |
| RTTerrainModule | terrain | 100 | RT Tier 2 | PRE_TERRAIN, RT_DISPATCH |
| ShadowMapModule | shadow | 0 | — | SHADOW_TERRAIN, SHADOW_ENTITIES |
| RTShadowModule | shadow | 100 | RT Tier 1 | PRE_SHADOW, RT_DISPATCH |
| SVGFDenoiserModule | denoiser | 50 | RT Tier 1 | POST_RT |
| FSR3Module | upscaler | 50 | — | FRAME_SETUP, POST_PROCESS_FINAL |
| ToneMappingModule | — | 10 | Compute (optional) | COMPUTE_ASYNC, POST_PROCESS_FINAL |
| CompositePassModule | — | 0 | — | DEFERRED_COMPOSITE, POST_PROCESS_COMPOSITE, POST_PROCESS_FINAL |
| ComputeLightModule | — | 50 | Compute | FRAME_SETUP, COMPUTE_ASYNC |

### Shader Inventory

| File | Type | Location |
|------|------|----------|
| terrain.vert / terrain.frag | Rasterization | shaders/ |
| blit.vert / blit.frag | Fullscreen blit | shaders/ |
| frustum_cull.comp | Compute | shaders/compute/ |
| chunk_light_propagate.comp | Compute | shaders/compute/ |
| worldgen_noise.comp | Compute | shaders/compute/ |
| physics_broadphase.comp | Compute | shaders/compute/ |
| world.rgen | Ray generation | shaders/rt/ |
| world_solid.rchit | Closest hit | shaders/rt/ |
| sky.rmiss | Miss (sky) | shaders/rt/ |
| shadow.rmiss | Miss (shadow) | shaders/rt/ |

---

## Gap-Filling Infrastructure (Post-Phase Completion)

After completing phases 0–11, a comprehensive gap analysis was performed by referencing Sodium (~365 files) and VulkanMod (~228 files) project structures. Eight critical missing subsystems were identified and built:

### Config/GUI Options Framework (18 files)

| File | Package | Description |
|------|---------|-------------|
| VulkaniumGameOptions.java | gui/ | 8 setting groups (Video, Quality, Performance, Vulkan, Shader, RayTracing, Debug, Notifications), GSON serialization |
| TextProvider.java | gui/options/ | Functional interface for option display text |
| OptionImpact.java | gui/options/ | 5 performance impact levels (NONE → EXTREME) |
| OptionFlag.java | gui/options/ | 6 reload flags (RENDERER_RELOAD, SHADER_RELOAD, SWAPCHAIN_RECREATE, etc.) |
| Option.java | gui/options/ | Core option interface with generics |
| OptionImpl.java | gui/options/ | Builder-pattern implementation (modeled on Sodium's OptionImpl) |
| OptionGroup.java | gui/options/ | Grouping container with optional header |
| OptionPage.java | gui/options/ | Named page of option groups |
| OptionBinding.java | gui/options/binding/ | Get/set binding interface |
| GenericBinding.java | gui/options/binding/ | Lambda-based binding implementation |
| OptionStorage.java | gui/options/storage/ | Storage lifecycle (save/load) interface |
| VulkaniumOptionsStorage.java | gui/options/storage/ | Concrete storage backed by VulkaniumGameOptions |
| Control.java | gui/options/control/ | Abstract widget control type |
| ControlValueFormatter.java | gui/options/control/ | 11 formatter factories (percentage, fps, distance, etc.) |
| SliderControl.java | gui/options/control/ | Slider with min/max/step |
| TickBoxControl.java | gui/options/control/ | Boolean toggle |
| CyclingControl.java | gui/options/control/ | Enum/value cycling control |
| VulkaniumOptionPages.java | gui/ | 7 pages with ~40 options (video, quality, performance, vulkan, shader, RT, debug) |
| VulkaniumOptionsScreen.java | gui/ | Screen controller with page nav, change tracking, reload flag dispatch |

### GL State Interception Layer (3 files)

| File | Package | Description |
|------|---------|-------------|
| VRenderSystem.java | compat/ | Captures all GL state (blend, depth, cull, polygon offset, color mask, scissor, viewport, clear, texture binding × 32 slots, shader) |
| VulkanPipelineState.java | compat/ | Immutable state snapshot record; GL→VK converters (compare op, blend factors, color write mask, cull mode); packHash() for pipeline cache key |
| GlStateInterceptor.java | compat/ | Master bridge with init/enable/disable lifecycle; intercept methods connecting mixins to VRenderSystem |

### Vulkan Texture System (4 files)

| File | Package | Description |
|------|---------|-------------|
| VulkanImage.java | vulkan/texture/ | VkImage+VkImageView wrapper; Builder with usage flags; layout transitions, mipmap generation, 13 format + 9 layout constants |
| SamplerManager.java | vulkan/texture/ | Singleton sampler cache (Long2LongOpenHashMap); GL→VK filter/wrap conversion; convenience samplers (default, framebuffer, shadow, PCF) |
| TextureManager.java | vulkan/texture/ | GL texture ID → VulkanImage mapping; pending deletion queue; sprite animation tracking; format conversion |
| ImageUtil.java | vulkan/texture/ | Format utilities, pixel conversion (RGBA↔BGRA, RGB→RGBA, flip), mip calculations, alignment helpers |

### Memory/Buffer System (7 files)

| File | Package | Description |
|------|---------|-------------|
| VulkanBuffer.java | vulkan/memory/ | Base buffer with VMA allocation; MemoryType enum (GPU_ONLY, CPU_VISIBLE, CPU_TO_GPU, GPU_TO_CPU); map/unmap/flush/copy/resize |
| VertexBuffer.java | vulkan/memory/ | Typed buffer with stride and vertex count; bind(commandBuffer) |
| IndexBuffer.java | vulkan/memory/ | IndexType (UINT16/UINT32); static createSharedQuadIndices() |
| UniformBuffer.java | vulkan/memory/ | Per-frame dynamic UBO with alignment; writeFloat/Matrix4f/Vec4/Int |
| IndirectBuffer.java | vulkan/memory/ | VkDrawIndexedIndirectCommand storage; dispatchIndirect/dispatchIndirectCount (Vulkan 1.2+) |
| StagingBuffer.java | vulkan/memory/ | Ring-style staging with per-frame sections; alloc with alignment; usage stats; auto-resize |
| MemoryManager.java | vulkan/memory/ | VMA wrapper singleton; allocation tracking; leak detection; budget queries; defragmentation |

### World Rendering Infrastructure (7 files)

| File | Package | Description |
|------|---------|-------------|
| ClonedChunkSection.java | world/ | Immutable section snapshot; packed position (22+20+22 bits); blockStates/blockLight/skyLight/biomes arrays |
| ChunkRenderContext.java | world/ | 27-neighbor context for chunk building; cross-boundary block/light access |
| ClonedChunkSectionCache.java | world/ | Ring buffer cache (1024 entries) with invalidateColumn |
| BiomeColorSource.java | world/ | Enum (GRASS, FOLIAGE, WATER) |
| BiomeColorCache.java | world/ | Lazy per-Y-layer blended biome colors; configurable blend radius |
| VulkaniumWorldRenderer.java | world/ | Central singleton orchestrator; camera tracking; terrain render phases; block change propagation; Iris hooks |
| SectionGraph.java | world/ | BFS visibility traversal; face connectivity bitmask; frustum culling; front-to-back sorting |

### Vertex & Model System (6 files)

| File | Package | Description |
|------|---------|-------------|
| VulkanVertexBuilder.java | render/vertex/ | Native memory vertex builder; 32-byte stride; half-float UV; 10_10_10_2 normal packing; quad() convenience |
| QuadView.java | render/vertex/ | Read-only view into MC baked quad data (8 ints/vertex); face direction detection |
| BakedModelEncoder.java | render/model/ | MC BakedQuad → VulkanVertexBuilder bridge; color tinting; AO encoding |
| LightPipeline.java | render/model/ | Flat and smooth (AO) lighting; 3-corner neighbor sampling; AO_VALUES[0.2, 0.4, 0.6, 1.0] |
| BlockRenderer.java | render/model/ | Per-block face renderer; face culling; lighting; biome tinting; render layer routing |
| LiquidRenderer.java | render/model/ | Fluid rendering; corner height averaging; level-based height interpolation; biome water color |

### Core Mixin Expansion (13 files → 16 total)

| File | Package | Hooks |
|------|---------|-------|
| MixinRenderSystem.java | mixin/render/ | Blend, depth, cull, colorMask, polygonOffset → GlStateInterceptor |
| MixinGlStateManager.java | mixin/render/ | _enableBlend, _depthFunc, _activeTexture, _bindTexture, _viewport, _scissorBox → GlStateInterceptor |
| MixinWorldRenderer.java | mixin/render/ | Camera setup, scheduleChunkRender, setWorld → VulkaniumWorldRenderer |
| MixinTextureManager.java | mixin/render/ | close() → TextureManager.shutdown() |
| MixinShaderProgram.java | mixin/render/ | bind()/unbind() → shader tracking |
| MixinVertexBuffer.java | mixin/render/ | draw()/bind()/unbind() → Vulkan draw command recording |
| MixinBufferBuilder.java | mixin/render/ | begin()/end() → vertex data capture |
| MixinFramebuffer.java | mixin/render/ | beginWrite/endWrite/clear/resize/delete → render target management |
| MixinEntityRenderDispatcher.java | mixin/render/ | render() → entity ID tracking and culling |
| MixinBackgroundRenderer.java | mixin/render/ | applyFog()/render() → fog uniform capture |
| MixinClientWorld.java | mixin/world/ | setBlockState → VulkaniumWorldRenderer.onBlockChanged() |
| MixinClientPlayNetworkHandler.java | mixin/world/ | onChunkData/onUnloadChunk → chunk load/unload events |
| MixinChunkBuilder.java | mixin/world/ | upload/reset → chunk build lifecycle |

### Platform & Compatibility (3 files)

| File | Package | Description |
|------|---------|-------------|
| PlatformInfo.java | platform/ | OS/GPU/driver detection; vendor ID decoding (NVIDIA/AMD/Intel/Apple/Qualcomm/ARM); Vulkan version/feature flags |
| DriverWorkarounds.java | platform/ | Per-vendor workaround detection (NVIDIA pipeline cache, AMD RADV descriptors, Intel 16-bit indices, MoltenVK); low-VRAM handling |
| SystemInfo.java | platform/ | CPU/RAM/JVM/display detection; auto-config recommendations (threads, frames-in-flight, staging buffer, render distance) |

### Updated File Counts

| Category | Files | Source |
|----------|-------|--------|
| Phase 0–11 (original plan) | ~119 | Core Vulkan, chunk, GLSL, MRT, composite, shadow, entity, perf, compute, RT, modules |
| Config/GUI options | 18 | Sodium-style option framework |
| GL state interception | 3 | GL→Vulkan state bridge |
| Vulkan texture system | 4 | Image, sampler, texture manager |
| Memory/buffer system | 7 | Typed buffers + VMA memory manager |
| World rendering | 7 | Chunk cloning, biome color, section graph, world renderer |
| Vertex/model | 6 | Vertex builder, quad view, block/liquid renderer |
| Mixin expansion | 13 | Render + world interception |
| Platform/compat | 3 | Hardware detection, driver workarounds |
| Shaders | ~16 | GLSL/SPIR-V sources |
| Resources | ~5 | Mixin configs, fabric.mod.json, etc. |
| **Grand Total** | **~201** | |

---

### Next Steps

With all phases and gap-filling infrastructure structurally complete, the following work remains before Vulkanium is runtime-functional:

#### High Priority
1. **Vulkan command implementation** — Fill stub methods with actual `vkCmd*` calls, barrier placement, and layout transitions
2. **Mixin configuration** — Update `vulkanium.mixins.json` with all 16 mixin class names and verify targets
3. **Iris integration** — Wire Iris shader pack loading to Vulkanium's GLSL transformer + SPIR-V compiler pipeline
4. **Descriptor set management** — Implement descriptor pool/set allocation, binding table updates, push descriptors

#### Medium Priority
5. **Additional mixins** — ~34 more needed for full VulkanMod parity (particle, block entity, screen rendering, debug, voxel shape, window)
6. **Immediate-mode rendering** — CloudRenderer, entity model encoding, GUI vertex capture, text rendering interception
7. **Pipeline state objects** — Implement full PSO creation with VulkanPipelineState hash → cached VkPipeline lookup
8. **Render pass management** — Dynamic render pass creation for MRT/shadow/composite configurations

#### Lower Priority
9. **Testing** — Build against Minecraft 1.20.1 + Fabric, verify basic rendering, iterate on shader compatibility
10. **Performance tuning** — Profile with RenderDoc/NSight, optimize barrier placement, descriptor management, memory allocation patterns
11. **Shader pack compatibility** — Test against top-20 shader packs (BSL, Complementary, SEUS, Sildur's, etc.)
12. **Documentation** — API documentation for module system, shader pack developer guide

---

*This document is a living plan. Each phase will be detailed further as implementation progresses.*

