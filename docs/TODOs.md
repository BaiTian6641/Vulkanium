# TODOs in Vulkanium Codebase (Refreshed)

Current scan date: 2026-02-23  
Scan command: `grep -R --line-number --exclude-dir=Reference "TODO" src/main/java | sort`

## Snapshot

- Previous scan: **42 TODOs**
- Current scan: **29 TODOs**
- Delta: **13 TODOs resolved in this pass**
- Remaining TODOs are now concentrated in **Phase 10 ray tracing**.

## Recently Implemented (this pass)

- GL readback interception stubs in mixins replaced with explicit runtime behavior + warning logs.
- `sunPathRotation` is now sourced from active shaderpack pipeline in `ShaderpackUniformsImpl`.
- Shadow sampler mode auto-detection added in `VulkanShaderpackPipeline` (regular vs hardware compare sampler).
- AS→RT memory barrier implemented in `RTModuleManager`.
- `VulkaniumRayTracingPipeline` now has:
  - RT pipeline cache creation/destruction,
  - pipeline bind path,
  - `vkCmdTraceRaysKHR` dispatch wiring,
  - push-constants hook and debug logging,
  - pipeline/resource cleanup path.

## Remaining TODOs (29)

### A) BLAS Manager

- **src/main/java/net/vulkanium/rt/BLASManager.java:159** – `// TODO Phase 10: Store geometry references for vkCmdBuildAccelerationStructuresKHR`
- **src/main/java/net/vulkanium/rt/BLASManager.java:180** – `// TODO Phase 10: Implement actual BLAS building`
- **src/main/java/net/vulkanium/rt/BLASManager.java:211** – `// TODO Phase 10: vkDestroyAccelerationStructureKHR, free buffer`
- **src/main/java/net/vulkanium/rt/BLASManager.java:266** – `// TODO Phase 10: vkDestroyAccelerationStructureKHR per BLAS`

### B) FSR3 Upscaler

- **src/main/java/net/vulkanium/rt/FSR3Upscaler.java:86** – `// TODO: Create actual VkImage with mip chain`
- **src/main/java/net/vulkanium/rt/FSR3Upscaler.java:89** – `// TODO: Create R8_UINT image`
- **src/main/java/net/vulkanium/rt/FSR3Upscaler.java:92** – `// TODO: Create RGBA16F image`
- **src/main/java/net/vulkanium/rt/FSR3Upscaler.java:95** – `// TODO: Load precompiled SPIR-V from resources`
- **src/main/java/net/vulkanium/rt/FSR3Upscaler.java:116** – `// TODO: Bind pipeline + descriptors + dispatch`
- **src/main/java/net/vulkanium/rt/FSR3Upscaler.java:120** – `// TODO: Sequential mip dispatch with barriers`
- **src/main/java/net/vulkanium/rt/FSR3Upscaler.java:124** – `// TODO: Bind pipeline + descriptors + dispatch`
- **src/main/java/net/vulkanium/rt/FSR3Upscaler.java:128** – `// TODO: Bind pipeline + descriptors + dispatch`
- **src/main/java/net/vulkanium/rt/FSR3Upscaler.java:132** – `// TODO: Bind pipeline + descriptors + dispatch`
- **src/main/java/net/vulkanium/rt/FSR3Upscaler.java:137** – `// TODO: Bind pipeline + descriptors + dispatch`
- **src/main/java/net/vulkanium/rt/FSR3Upscaler.java:163** – `// TODO: Destroy all pipelines, images, descriptors`

### C) SVGF Denoiser

- **src/main/java/net/vulkanium/rt/SVGFDenoiser.java:90** – `// TODO: Create actual pipeline objects via VulkaniumComputePipeline`
- **src/main/java/net/vulkanium/rt/SVGFDenoiser.java:183** – `// TODO: vkCmdBindPipeline, vkCmdBindDescriptorSets, vkCmdDispatch`
- **src/main/java/net/vulkanium/rt/SVGFDenoiser.java:189** – `// TODO: vkCmdPipelineBarrier2`
- **src/main/java/net/vulkanium/rt/SVGFDenoiser.java:213** – `// TODO: Destroy pipelines, layouts, descriptor sets`

### D) Shader Binding Table

- **src/main/java/net/vulkanium/rt/ShaderBindingTable.java:149** – `// TODO Phase 10: Get device address`
- **src/main/java/net/vulkanium/rt/ShaderBindingTable.java:153** – `// TODO Phase 10: Copy shader group handles into SBT`
- **src/main/java/net/vulkanium/rt/ShaderBindingTable.java:200** – `// TODO Phase 10: vkCmdTraceRaysKHR with region addresses from this SBT`

### E) TLAS Builder

- **src/main/java/net/vulkanium/rt/TLASBuilder.java:119** – `// TODO Phase 10: Map the instance buffer`
- **src/main/java/net/vulkanium/rt/TLASBuilder.java:152** – `// TODO Phase 10: Write each BLAS instance into the instance buffer`
- **src/main/java/net/vulkanium/rt/TLASBuilder.java:195** – `// TODO Phase 10: Record TLAS build`
- **src/main/java/net/vulkanium/rt/TLASBuilder.java:221** – `// TODO Phase 10: VkMemoryBarrier2 with:`
- **src/main/java/net/vulkanium/rt/TLASBuilder.java:246** – `// TODO Phase 10: vkDestroyAccelerationStructureKHR`
- **src/main/java/net/vulkanium/rt/TLASBuilder.java:252** – `// TODO Phase 10: vmaUnmapMemory if mapped`

### F) RT Pipeline Creation

- **src/main/java/net/vulkanium/rt/VulkaniumRayTracingPipeline.java:248** – `// TODO Phase 10: Create RT pipeline`

---

This file is now aligned with current source state after the latest implementation pass.
