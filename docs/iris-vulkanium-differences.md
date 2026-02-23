# Iris vs Vulkanium — Functional Comparison

> Document created to summarise the differences in functionality between the Iris reference implementation and the Vulkanium renderer.  
> Focus is on features, architecture, and capabilities rather than specific implementation details.

## 1. Rendering Backend

- **Iris** is built on top of Minecraft's existing **OpenGL** renderer. It provides shader pack compatibility by intercepting and patching GL calls, while still relying on the legacy GL pipeline.  
- **Vulkanium** replaces the entire rendering stack with a modern **Vulkan** backend.  
  - No OpenGL calls once initialized; GL is emulated or intercepted for compatibility only.  
  - Explicit control over memory, multi‑queue command submission, and multi‑threaded recording.
  - Supports multiple swapchain formats including HDR (VK_FORMAT_B10G11R11_UFLOAT_PACK32) and Vulkan‑specific extensions.

## 2. Shader Pack Compatibility

- Both projects reuse Iris's AST‑based GLSL transformer (`TransformPatcher`/ANTLR4) to normalise legacy OptiFine syntax.  
- In **Iris**, transformed GLSL is compiled by the OpenGL driver at runtime.  
- In **Vulkanium**, the same compatibility layer is used but the output is further translated into **SPIR‑V** via `ShaderCompiler` and `VulkaniumGlslTransformer`.  
  - UBO layouts are expanded (from ~720 B to ~2048 B) to accommodate extra engine uniforms.  
  - Additional preprocessors and feature flags handle Vulkan‑specific constructs (storage images, compute, `layout(binding)` annotations).  
  - Shader module caching is cross‑session; failed compilations fall back to a safe default program to avoid game crashes.

## 3. Render Pass & Pipeline Architecture

- **Iris/OpenGL** uses a fixed sequence of draw calls issued directly on the graphics context.  
- **Vulkanium** introduces a layered pipeline:
  1. **FrameOrchestrator** – manages per‑frame resources, semaphores, fences, and command buffers.  
  2. **PassRecorder** – records individual render passes (terrain, entity, composite, shadows) into command buffers.  
  3. **DrawDispatcher** – submits indirect draw buffers prepared by the culling/sorting compute shaders.

- Render passes are explicit Vulkan `VkRenderPass` objects with support for:  
  - **Multi‑Render Target (MRT)**: G-buffer (normals, material properties) in the same pass.  
  - **Deferred/composite passes**: allows a full‑screen composite shader stage separated from geometry.
  - **Shadow mapping**: separate depth‑only passes with mipmap generation and layer arrays.

- Iris has no concept of render pass objects; framebuffers are simple GL FBOs supporting only a single colour attachment.

## 4. Performance & Optimization Features

| Feature | Iris (OpenGL) | Vulkanium (Vulkan) |
|---------|---------------|---------------------|
| Multi‑draw indirect | ✗ | ✅ (per‑region indirect buffers) |
| GPU compute culling/sorting | ✗ | ✅ (translucent sorting, frustum/occlusion via compute) |
| Async texture uploads | ✗ | ✅ (transfer queue, staging ring buffer) |
| Multi‑threaded command recording | ✗ | ✅ (secondary command buffers for chunks) |
| Descriptor set pressure | moderate | reduced via multi‑set layout (UBO, textures, storage) |
| Vertex format | Sodium‑like 20‑byte | Extended 24‑byte with normals/tangents + custom attributes |
| Region‑based chunk management | adopted from Sodium | adapted with Vulkan memory sub‑allocation and compute-driven updates |

Iris already includes many optimization patterns from Sodium (batched rendering, compact vertex formats); Vulkanium inherits these and augments them with Vulkan‑native improvements.

## 5. Additional Systems Present in Vulkanium

- **Compute Shader Platform**: general compute scheduler/allocator for use in terrain culling, mesh baking, and later ray tracing.  
- **Ray Tracing Framework**: preliminary BLAS/TLAS/SBT scaffolding (planned Phase 10).  
- **Vulkan‑Native Features**:
  - Explicit **memory barriers**, pipeline barriers, and layout transitions.
  - Support for storage images and SSBOs in shader packs.
  - HDR tone‑mapping, color‑space conversion, and optional VK_EXT_hdr_metadata.
  - Advanced **descriptor set caching** with runtime invalidation.
- **UX Improvements**: progress overlay, debug HUD, shader compile progress bar, shader cache management GUI.

Iris focuses purely on shader compatibility and the minimal required infrastructure; it does not offer compute or ray tracing platforms.

## 6. Feature Parity / Missing Capabilities

| Iris Feature | Vulkanium Status |
|--------------|------------------|
| All OptiFine GLSL packs (1.16–1.20) | Partial – most packs compile, but some legacy hacks still require Oxide‑style uniform names. Ongoing testing matrix. |
| Shader pack menu integration | ✅ (shares same UI code) |
| Custom post‑processing passes | ✅ via composite pipeline |
| Lightmap/SSAO support | ✅ (depth pre‑pass + compute based AO in development) |
| Batched entity rendering | ✗ yet – currently uses separate entity pass inherited from Iris but will move to GPU batching in later phases. |

## 7. Architecture & Codebase Differences

- Package naming reflects the separation: `net.irisshaders.*` vs `net.vulkanium.*`.  
- Vulkanium replicates many Iris modules under its own namespace with Vulkan‑specific subclasses (`VulkanTerrainPipeline`, `VulkanCompositeRenderer`, etc.).  
- Mixins: both projects inject into Minecraft, but Vulaniumn has a much larger mixin surface to intercept block models, texture uploads, and OpenGL states for compatibility.  
- Build system: Vulkanium’s `build.gradle.kts` includes additional dependencies (LWJGL Vulkan bindings, SPIR‑V tools) and an `accesswidener` for low‑level engine access.

## 8. Documentation & User Experience

- **Iris docs** are centred on shader pack authors: compatibility notes, preprocessor directives, bug lists.  
- **Vulkanium docs** (see `docs/vulkanium-plan.md` and this comparison) emphasise phases of engine re‑architecture, performance targets, and a developer‑oriented roadmap.  
- Vulkanium does not duplicate Iris’s shaderpack bug list; packs are expected to behave identically but additional rules are documented as compatibility notes.

## 9. Summary

Vulkanium extends Iris’s shader compatibility foundation by replacing the entire rendering backend with a Vulkan engine.  Functional differences are substantial: from low‑level memory/control improvements, new passes (MRT, compute, shadows), to support for modern GPU features such as ray tracing.  While Iris remains the go‑to reference for GLSL transformation logic and shader pack authoring, Vulkanium inherits that work while adding a parallel Vulkan‑centric subsystem that ultimately aims to surpass Sodium and VulkanMod in performance and capability.

---

*Document generated 2026‑02‑23.*