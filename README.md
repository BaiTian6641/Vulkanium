# Vulkanium

**Next‑generation Vulkan rendering engine for Minecraft (Fabric)**

Status: **IN PROGRESS — targets Minecraft 1.20.1 (Fabric)**

Vulkanium replaces Minecraft's OpenGL renderer with a modern Vulkan backend while keeping full compatibility with OptiFine/Iris GLSL shader packs. The project focuses on high-performance rendering (multi‑draw indirect, MRT/G‑buffer, compute-driven culling/sorting) and a clean shader-compatibility layer that translates legacy GLSL → SPIR-V.

---

## Highlights

- Full Vulkan rendering path (device/queues/memory/pipeline abstractions)
- OptiFine / Iris GLSL → SPIR‑V compatibility layer and runtime shaderpack support
- Sodium‑style region-based multi-draw-indirect terrain rendering
- Multi-Render Target (G‑buffer) + deferred/composite passes
- GPU frustum culling & translucent sorting (compute shaders)
- Async staging/transfer queue and per-thread command recording
- Roadmap: compute platform, hardware ray-tracing, multi‑version support

---

## Current status (short)

- Project builds successfully (Java 17 + Fabric Loom).  ✅
- Core Vulkan abstraction, render pipeline scaffolding, shaderpack loader — implemented (partial).  ⚠️
- Chunk renderer, MRT/G‑buffer, shadow pipeline — implemented but still being completed across all descriptor/command paths.  ⚠️
- Ray tracing & some Vulkan end-to-end command paths — scaffolded / work in progress.  🟧

See the `docs/vulkanium-plan.md` for a full phase-by-phase audit and checklist.

---

## Supported runtime (today)

- Minecraft: **1.20.1** (Fabric)
- Fabric Loader: **>= 0.17.2** (tested with **0.17.2**)
- Java: **17**
- Host GPU: Vulkan-compatible drivers required (NVIDIA/AMD/Intel with recent drivers)

Planned: support for additional Minecraft and Fabric versions in future releases.

---

## Quickstart — developer

1. Install Java 17 and a working Vulkan driver for your GPU.
2. Clone the repo and open the project in your IDE (Gradle wrapper included).

   git clone <repo-url>
   cd Vulkanium

3. Run the Minecraft client in the development environment:

   ./gradlew runClient

   - The `runClient` task launches a development instance (use `run/shaderpacks` or your normal `shaderpacks` folder for testing packs).
   - Use `LD_PRELOAD=/path/to/librenderdoc.so ./gradlew runClient` on Linux if you want RenderDoc capture (optional).

4. Build a distributable JAR:

   ./gradlew build
   cp build/libs/vulkanium-*.jar ~/.minecraft/mods/

---

## How to test shader packs

- Drop shader pack `.zip` or folder into `run/shaderpacks/` (for dev) or Minecraft's `shaderpacks/` (for normal runs).
- Shaderpack discovery supports both zip and directory formats; select the pack via in-game Shaderpack options.

Notes: the compatibility layer rewrites legacy OptiFine GLSL to Vulkan‑compatible GLSL before compiling to SPIR‑V.

---

## Development notes

- Language: Java 17, Gradle (Gradle wrapper provided), Loom for Fabric.
- Build: `./gradlew build` — produces `build/libs/vulkanium-<version>.jar`.
- Dev run: `./gradlew runClient`.
- Important: your system must support Vulkan. The mod will detect compatibility at runtime and gracefully fall back if Vulkan is unavailable.

---

## Roadmap (short)

1. Stabilize command-recording & descriptor set lifecycle (in progress)
2. Finish shaderpack runtime compatibility matrix
3. Compute platform (GPU-accelerated lighting / world workloads)
4. Hardware ray-tracing (BLAS/TLAS/SBT + denoiser)
5. Multi-version support and release channels (expand beyond 1.20.1)

Refer to `docs/vulkanium-plan.md` for the detailed phased plan.

---

## Contributing

- Open an issue or PR against this repository.
- Branch from `main`, keep changes focused and document breaking changes.
- Run `./gradlew build` and validate the mod with `./gradlew runClient` before submitting a PR.
- Follow Java 17 conventions; keep changes small and test shaderpack compatibility when relevant.

---

## License & attribution

- License: **LGPL‑3.0** (declared in `fabric.mod.json`).

### Credits

This project was inspired by and builds on ideas, patterns and research from the following projects:

- **Sodium** — region-based chunk batching and performance-driven rendering patterns
- **Iris** — shader‑pack compatibility and GLSL transformation pipeline
- **VulkanMod** — Vulkan integration and low-level Vulkan reference implementation
- **C2ME** — concurrency and threading improvements for worldgen/server-side systems

(Thanks to the communities and authors of these projects for the technical guidance and inspiration.)

---

## Contact / Issues

- Please open issues or PRs in this repository for bugs, feature requests, and shaderpack compatibility reports.

---

Thank you — contributions, shaderpack test cases, and compatibility reports are welcome.
