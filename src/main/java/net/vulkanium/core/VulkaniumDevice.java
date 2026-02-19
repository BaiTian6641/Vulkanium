package net.vulkanium.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.*;

import static java.util.stream.Collectors.toSet;
import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.*;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * Manages physical device selection, logical device creation, and device features.
 *
 * <p>Improvements over VulkanMod's {@code DeviceManager}:</p>
 * <ul>
 *   <li>Explicit feature detection with capability flags</li>
 *   <li>Scoring system for device selection (discrete > integrated > CPU)</li>
 *   <li>Requests multi-draw-indirect, wide lines, shader draw parameters</li>
 *   <li>Conditionally enables RT extensions when hardware supports them</li>
 *   <li>Stores device info as immutable record</li>
 * </ul>
 */
public class VulkaniumDevice {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Device");

    private static final Set<String> REQUIRED_EXTENSIONS = Set.of(VK_KHR_SWAPCHAIN_EXTENSION_NAME);

    /** RT extensions to enable when available (order matters for dependency chain) */
    private static final List<String> RT_EXTENSIONS = List.of(
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,   // required by AS
            VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME,      // required by AS
            "VK_KHR_spirv_1_4",                                // required by RT pipeline
            VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,      // BLAS/TLAS
            VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME         // RT shader stages
    );

    /** Optional RT extensions (nice to have, not required) */
    private static final String VK_KHR_RAY_QUERY = "VK_KHR_ray_query";

    private static VkDevice globalDevice;

    private VkPhysicalDevice physicalDevice;
    private VkDevice logicalDevice;
    private DeviceInfo deviceInfo;

    // Device capabilities
    private boolean supportsMultiDrawIndirect;
    private boolean supportsWideLines;
    private boolean supportsSamplerAnisotropy;
    private boolean supportsGeometryShader;
    private boolean supportsTessellation;
    private boolean supportsShaderDrawParameters;

    // RT capabilities (probed at device selection, enabled at device creation)
    private boolean rtExtensionsEnabled = false;
    private boolean hasAccelerationStructure = false;
    private boolean hasRayTracingPipeline = false;
    private boolean hasRayQuery = false;
    private boolean hasSPIRV14 = false;
    private boolean hasDeferredHostOps = false;
    private boolean hasBufferDeviceAddress = false;

    // RT pipeline properties (queried from physical device)
    private int rtShaderGroupHandleSize = 0;
    private int rtShaderGroupHandleAlignment = 0;
    private int rtShaderGroupBaseAlignment = 0;
    private int rtMaxRayRecursionDepth = 0;
    private int rtMaxRayDispatchInvocationCount = 0;

    // AS properties
    private long rtMaxInstanceCount = 0;
    private long rtMaxPrimitiveCount = 0;
    private long rtMaxGeometryCount = 0;

    private VkPhysicalDeviceProperties deviceProperties;
    private VkPhysicalDeviceMemoryProperties memoryProperties;

    // Queue family indices
    private QueueFamilyIndices queueFamilyIndices;

    /**
     * Selects the best physical device and creates a logical device with all needed queues.
     */
    public void initialize(VulkaniumInstance vulkaniumInstance) {
        LOGGER.info("Initializing Vulkan device...");

        List<DeviceCandidate> candidates = enumerateDevices(vulkaniumInstance);
        if (candidates.isEmpty()) {
            throw new RuntimeException("No suitable Vulkan GPU found!");
        }

        // Sort by score (highest first)
        candidates.sort(Comparator.comparingInt(DeviceCandidate::score).reversed());

        DeviceCandidate chosen = candidates.get(0);
        this.physicalDevice = chosen.physicalDevice;

        // Query properties
        this.deviceProperties = VkPhysicalDeviceProperties.malloc();
        vkGetPhysicalDeviceProperties(physicalDevice, deviceProperties);

        this.memoryProperties = VkPhysicalDeviceMemoryProperties.malloc();
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);

        // Query features
        queryDeviceFeatures();

        // Probe RT extension availability (before device creation)
        probeRTExtensions();

        // Find queue families
        this.queueFamilyIndices = findQueueFamilies(physicalDevice, vulkaniumInstance.getSurface());
        if (!queueFamilyIndices.isComplete()) {
            throw new RuntimeException("Selected GPU doesn't have required queue families");
        }

        // Build device info record
        this.deviceInfo = buildDeviceInfo();

        LOGGER.info("Selected GPU: {} ({})", deviceInfo.name(), deviceInfo.deviceTypeName());
        LOGGER.info("  Vulkan API: {}, Driver: {}", deviceInfo.apiVersionString(), deviceInfo.driverVersionString());
        LOGGER.info("  VRAM: {} MB", deviceInfo.vramMB());
        LOGGER.info("  Multi-draw-indirect: {}, Geometry: {}, Tessellation: {}",
                supportsMultiDrawIndirect, supportsGeometryShader, supportsTessellation);
        LOGGER.info("  RT extensions: AS={}, RTPipeline={}, RayQuery={}, BDA={}, SPIRV1.4={}, DeferredOps={}",
                hasAccelerationStructure, hasRayTracingPipeline, hasRayQuery,
                hasBufferDeviceAddress, hasSPIRV14, hasDeferredHostOps);

        // Create logical device (with RT extensions if available)
        createLogicalDevice();

        LOGGER.info("Logical device created with {} unique queue families",
                queueFamilyIndices.uniqueFamilies().length);
    }

    private List<DeviceCandidate> enumerateDevices(VulkaniumInstance vulkaniumInstance) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer deviceCount = stack.ints(0);
            vkEnumeratePhysicalDevices(vulkaniumInstance.getInstance(), deviceCount, null);

            if (deviceCount.get(0) == 0) {
                return List.of();
            }

            PointerBuffer ppDevices = stack.mallocPointer(deviceCount.get(0));
            vkEnumeratePhysicalDevices(vulkaniumInstance.getInstance(), deviceCount, ppDevices);

            List<DeviceCandidate> candidates = new ArrayList<>();
            for (int i = 0; i < ppDevices.capacity(); i++) {
                VkPhysicalDevice device = new VkPhysicalDevice(ppDevices.get(i), vulkaniumInstance.getInstance());

                if (isDeviceSuitable(device, vulkaniumInstance.getSurface())) {
                    VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                    vkGetPhysicalDeviceProperties(device, props);

                    int score = scoreDevice(device, props);
                    String name = props.deviceNameString();

                    LOGGER.info("  Found GPU: {} (score: {})", name, score);
                    candidates.add(new DeviceCandidate(device, score, name));
                }
            }
            return candidates;
        }
    }

    private int scoreDevice(VkPhysicalDevice device, VkPhysicalDeviceProperties props) {
        int score = 0;

        // Strongly prefer discrete GPUs
        switch (props.deviceType()) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> score += 10000;
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> score += 1000;
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> score += 500;
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> score += 100;
        }

        // Bonus for higher API version
        score += VK_API_VERSION_MINOR(props.apiVersion()) * 100;

        // Bonus for max image dimension (proxy for GPU capability)
        score += props.limits().maxImageDimension2D() / 1024;

        return score;
    }

    private boolean isDeviceSuitable(VkPhysicalDevice device, long surface) {
        try (MemoryStack stack = stackPush()) {
            // Check queue families
            QueueFamilyIndices indices = findQueueFamilies(device, surface);
            if (!indices.isComplete()) return false;

            // Check required extensions
            IntBuffer extensionCount = stack.ints(0);
            vkEnumerateDeviceExtensionProperties(device, (String) null, extensionCount, null);
            VkExtensionProperties.Buffer availableExtensions = VkExtensionProperties.malloc(extensionCount.get(0), stack);
            vkEnumerateDeviceExtensionProperties(device, (String) null, extensionCount, availableExtensions);

            Set<String> available = availableExtensions.stream()
                    .map(VkExtensionProperties::extensionNameString)
                    .collect(toSet());

            if (!available.containsAll(REQUIRED_EXTENSIONS)) return false;

            // Check swapchain support
            IntBuffer formatCount = stack.ints(0);
            KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, formatCount, null);
            IntBuffer presentModeCount = stack.ints(0);
            KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, presentModeCount, null);

            return formatCount.get(0) > 0 && presentModeCount.get(0) > 0;
        }
    }

    private void queryDeviceFeatures() {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.malloc(stack);
            vkGetPhysicalDeviceFeatures(physicalDevice, features);

            supportsMultiDrawIndirect = features.multiDrawIndirect();
            supportsWideLines = features.wideLines();
            supportsSamplerAnisotropy = features.samplerAnisotropy();
            supportsGeometryShader = features.geometryShader();
            supportsTessellation = features.tessellationShader();
        }

        // Check Vulkan 1.1 features
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceVulkan11Features vk11Features = VkPhysicalDeviceVulkan11Features.calloc(stack);
            vk11Features.sType$Default();

            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack);
            features2.sType$Default();
            features2.pNext(vk11Features);

            vkGetPhysicalDeviceFeatures2(physicalDevice, features2);
            supportsShaderDrawParameters = vk11Features.shaderDrawParameters();
        }
    }

    /**
     * Probes the physical device for RT extension availability and queries RT properties.
     * Must be called before createLogicalDevice().
     */
    private void probeRTExtensions() {
        try (MemoryStack stack = stackPush()) {
            // Enumerate all available device extensions
            IntBuffer extensionCount = stack.ints(0);
            vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, extensionCount, null);
            VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(extensionCount.get(0), stack);
            vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, extensionCount, extensions);

            Set<String> available = new HashSet<>();
            for (int i = 0; i < extensions.capacity(); i++) {
                available.add(extensions.get(i).extensionNameString());
            }

            // Check each RT extension
            hasAccelerationStructure = available.contains(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
            hasRayTracingPipeline = available.contains(VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME);
            hasRayQuery = available.contains(VK_KHR_RAY_QUERY);
            hasSPIRV14 = available.contains("VK_KHR_spirv_1_4");
            hasDeferredHostOps = available.contains(VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
            hasBufferDeviceAddress = available.contains(VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME);

            // All core RT extensions must be present to enable hardware RT
            boolean canEnableRT = hasAccelerationStructure && hasRayTracingPipeline
                    && hasDeferredHostOps && hasBufferDeviceAddress && hasSPIRV14;

            if (canEnableRT) {
                // Query Vulkan 1.2 features to verify bufferDeviceAddress is supported
                VkPhysicalDeviceVulkan12Features vk12Features = VkPhysicalDeviceVulkan12Features.calloc(stack);
                vk12Features.sType$Default();

                VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack);
                features2.sType$Default();
                features2.pNext(vk12Features);

                vkGetPhysicalDeviceFeatures2(physicalDevice, features2);

                if (!vk12Features.bufferDeviceAddress()) {
                    LOGGER.warn("GPU reports VK_KHR_buffer_device_address extension but bufferDeviceAddress feature is not supported");
                    hasBufferDeviceAddress = false;
                    canEnableRT = false;
                }
            }

            if (canEnableRT) {
                // Query RT pipeline properties
                VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps =
                        VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack);
                rtProps.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR);

                VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps =
                        VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack);
                asProps.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_PROPERTIES_KHR);
                asProps.pNext(rtProps.address());

                VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack);
                props2.sType$Default();
                props2.pNext(asProps.address());

                vkGetPhysicalDeviceProperties2(physicalDevice, props2);

                rtShaderGroupHandleSize = rtProps.shaderGroupHandleSize();
                rtShaderGroupHandleAlignment = rtProps.shaderGroupHandleAlignment();
                rtShaderGroupBaseAlignment = rtProps.shaderGroupBaseAlignment();
                rtMaxRayRecursionDepth = rtProps.maxRayRecursionDepth();
                rtMaxRayDispatchInvocationCount = rtProps.maxRayDispatchInvocationCount();

                rtMaxInstanceCount = asProps.maxInstanceCount();
                rtMaxPrimitiveCount = asProps.maxPrimitiveCount();
                rtMaxGeometryCount = asProps.maxGeometryCount();

                rtExtensionsEnabled = true;

                LOGGER.info("  RT properties: handleSize={}, handleAlign={}, baseAlign={}, maxRecursion={}, maxDispatch={}",
                        rtShaderGroupHandleSize, rtShaderGroupHandleAlignment, rtShaderGroupBaseAlignment,
                        rtMaxRayRecursionDepth, rtMaxRayDispatchInvocationCount);
                LOGGER.info("  RT AS properties: maxInstances={}, maxPrimitives={}, maxGeometries={}",
                        rtMaxInstanceCount, rtMaxPrimitiveCount, rtMaxGeometryCount);
            } else {
                LOGGER.info("  Hardware RT not available (missing extensions or features)");
            }
        }
    }

    private void createLogicalDevice() {
        try (MemoryStack stack = stackPush()) {
            int[] uniqueFamilies = queueFamilyIndices.uniqueFamilies();

            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueFamilies.length, stack);
            for (int i = 0; i < uniqueFamilies.length; i++) {
                queueCreateInfos.get(i)
                        .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                        .queueFamilyIndex(uniqueFamilies[i])
                        .pQueuePriorities(stack.floats(1.0f));
            }

            // Build the pNext feature chain (tail → head, each struct's pNext points to previous)
            // The chain order is: VkDeviceCreateInfo → vk11Features → [vk12Features → asFeatures → rtPipelineFeatures]
            long pNextChain = 0;

            // RT feature structs (only if RT extensions will be enabled)
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR rtPipelineFeatures = null;
            VkPhysicalDeviceAccelerationStructureFeaturesKHR asFeatures = null;
            VkPhysicalDeviceVulkan12Features vk12Features = null;

            if (rtExtensionsEnabled) {
                // RT pipeline features (tail of chain)
                rtPipelineFeatures = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack);
                rtPipelineFeatures.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR);
                rtPipelineFeatures.rayTracingPipeline(true);
                rtPipelineFeatures.pNext(pNextChain);
                pNextChain = rtPipelineFeatures.address();

                // AS features
                asFeatures = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack);
                asFeatures.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR);
                asFeatures.accelerationStructure(true);
                asFeatures.pNext(pNextChain);
                pNextChain = asFeatures.address();

                // Vulkan 1.2 features (bufferDeviceAddress)
                vk12Features = VkPhysicalDeviceVulkan12Features.calloc(stack);
                vk12Features.sType$Default();
                vk12Features.bufferDeviceAddress(true);
                vk12Features.pNext(pNextChain);
                pNextChain = vk12Features.address();
            }

            // Vulkan 1.1 features (always enabled)
            VkPhysicalDeviceVulkan11Features vk11Features = VkPhysicalDeviceVulkan11Features.calloc(stack);
            vk11Features.sType$Default();
            vk11Features.shaderDrawParameters(supportsShaderDrawParameters);
            vk11Features.pNext(pNextChain);

            // Base features
            VkPhysicalDeviceFeatures deviceFeatures = VkPhysicalDeviceFeatures.calloc(stack);
            deviceFeatures.samplerAnisotropy(supportsSamplerAnisotropy);
            deviceFeatures.multiDrawIndirect(supportsMultiDrawIndirect);
            deviceFeatures.wideLines(supportsWideLines);
            deviceFeatures.logicOp(true);
            if (supportsGeometryShader) deviceFeatures.geometryShader(true);
            if (supportsTessellation) deviceFeatures.tessellationShader(true);

            // Build extension list: required + optional RT extensions
            Set<String> enabledExtensions = new LinkedHashSet<>(REQUIRED_EXTENSIONS);
            if (rtExtensionsEnabled) {
                enabledExtensions.addAll(RT_EXTENSIONS);
                if (hasRayQuery) {
                    enabledExtensions.add(VK_KHR_RAY_QUERY);
                }
                LOGGER.info("Enabling {} device extensions (including {} RT extensions)",
                        enabledExtensions.size(), enabledExtensions.size() - REQUIRED_EXTENSIONS.size());
            }

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueCreateInfos)
                    .pEnabledFeatures(deviceFeatures)
                    .ppEnabledExtensionNames(VulkaniumInstance.asPointerBuffer(enabledExtensions, stack))
                    .pNext(vk11Features);

            PointerBuffer pDevice = stack.pointers(VK_NULL_HANDLE);
            int result = vkCreateDevice(physicalDevice, createInfo, null, pDevice);
            checkResult(result, "Failed to create logical device");

            logicalDevice = new VkDevice(pDevice.get(0), physicalDevice, createInfo, VK_API_VERSION_1_2);
            globalDevice = logicalDevice;

            if (rtExtensionsEnabled) {
                LOGGER.info("Logical device created with hardware RT extensions enabled");
            }
        }
    }

    private DeviceInfo buildDeviceInfo() {
        int apiVersion = deviceProperties.apiVersion();
        int driverVersion = deviceProperties.driverVersion();

        // Calculate VRAM
        long vram = 0;
        for (int i = 0; i < memoryProperties.memoryHeapCount(); i++) {
            VkMemoryHeap heap = memoryProperties.memoryHeaps(i);
            if ((heap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                vram = Math.max(vram, heap.size());
            }
        }

        return new DeviceInfo(
                deviceProperties.deviceNameString(),
                deviceProperties.vendorID(),
                deviceProperties.deviceID(),
                deviceProperties.deviceType(),
                apiVersion,
                driverVersion,
                vram / (1024 * 1024)
        );
    }

    // === Queue Family Discovery ===

    public static QueueFamilyIndices findQueueFamilies(VkPhysicalDevice device, long surface) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer queueFamilyCount = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null);

            VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.malloc(queueFamilyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies);

            int graphicsFamily = -1;
            int presentFamily = -1;
            int transferFamily = -1;
            int computeFamily = -1;

            for (int i = 0; i < queueFamilies.capacity(); i++) {
                VkQueueFamilyProperties props = queueFamilies.get(i);
                int flags = props.queueFlags();

                // Graphics queue
                if ((flags & VK_QUEUE_GRAPHICS_BIT) != 0 && graphicsFamily == -1) {
                    graphicsFamily = i;
                }

                // Present queue
                IntBuffer presentSupport = stack.ints(VK_FALSE);
                KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, presentSupport);
                if (presentSupport.get(0) == VK_TRUE && presentFamily == -1) {
                    presentFamily = i;
                }

                // Prefer dedicated transfer queue (not graphics)
                if ((flags & VK_QUEUE_TRANSFER_BIT) != 0 && (flags & VK_QUEUE_GRAPHICS_BIT) == 0) {
                    transferFamily = i;
                }

                // Prefer dedicated compute queue (not graphics)
                if ((flags & VK_QUEUE_COMPUTE_BIT) != 0 && (flags & VK_QUEUE_GRAPHICS_BIT) == 0) {
                    computeFamily = i;
                }
            }

            // Fallback: use graphics queue for transfer/compute if no dedicated one
            if (transferFamily == -1) transferFamily = graphicsFamily;
            if (computeFamily == -1) computeFamily = graphicsFamily;

            return new QueueFamilyIndices(graphicsFamily, presentFamily, transferFamily, computeFamily);
        }
    }

    // === Cleanup ===

    public void destroy() {
        if (logicalDevice != null) {
            vkDeviceWaitIdle(logicalDevice);
            vkDestroyDevice(logicalDevice, null);
            logicalDevice = null;
        }
        if (deviceProperties != null) {
            deviceProperties.free();
            deviceProperties = null;
        }
        if (memoryProperties != null) {
            memoryProperties.free();
            memoryProperties = null;
        }
    }

    // === Getters ===

    public VkPhysicalDevice getPhysicalDevice() { return physicalDevice; }
    public VkDevice getLogicalDevice() { return logicalDevice; }
    public static VkDevice getGlobalDevice() { return globalDevice; }
    public DeviceInfo getDeviceInfo() { return deviceInfo; }
    public QueueFamilyIndices getQueueFamilyIndices() { return queueFamilyIndices; }
    public VkPhysicalDeviceProperties getDeviceProperties() { return deviceProperties; }
    public VkPhysicalDeviceMemoryProperties getMemoryProperties() { return memoryProperties; }

    public boolean supportsMultiDrawIndirect() { return supportsMultiDrawIndirect; }
    public boolean supportsWideLines() { return supportsWideLines; }
    public boolean supportsSamplerAnisotropy() { return supportsSamplerAnisotropy; }
    public boolean supportsGeometryShader() { return supportsGeometryShader; }
    public boolean supportsShaderDrawParameters() { return supportsShaderDrawParameters; }

    // RT extension state (probed before device creation, enabled in VkDeviceCreateInfo)
    public boolean isRTExtensionsEnabled() { return rtExtensionsEnabled; }
    public boolean hasAccelerationStructure() { return hasAccelerationStructure; }
    public boolean hasRayTracingPipeline() { return hasRayTracingPipeline; }
    public boolean hasRayQuery() { return hasRayQuery; }
    public boolean hasSPIRV14() { return hasSPIRV14; }
    public boolean hasDeferredHostOps() { return hasDeferredHostOps; }
    public boolean hasBufferDeviceAddress() { return hasBufferDeviceAddress; }

    // RT pipeline properties (queried from physical device during probing)
    public int getRTShaderGroupHandleSize() { return rtShaderGroupHandleSize; }
    public int getRTShaderGroupHandleAlignment() { return rtShaderGroupHandleAlignment; }
    public int getRTShaderGroupBaseAlignment() { return rtShaderGroupBaseAlignment; }
    public int getRTMaxRayRecursionDepth() { return rtMaxRayRecursionDepth; }
    public int getRTMaxRayDispatchInvocationCount() { return rtMaxRayDispatchInvocationCount; }
    public long getRTMaxInstanceCount() { return rtMaxInstanceCount; }
    public long getRTMaxPrimitiveCount() { return rtMaxPrimitiveCount; }
    public long getRTMaxGeometryCount() { return rtMaxGeometryCount; }

    // === Inner Types ===

    /**
     * Immutable GPU device information.
     */
    public record DeviceInfo(
            String name,
            int vendorId,
            int deviceId,
            int deviceType,
            int apiVersion,
            int driverVersion,
            long vramMB
    ) {
        public String apiVersionString() {
            return "%d.%d.%d".formatted(
                    VK_API_VERSION_MAJOR(apiVersion),
                    VK_API_VERSION_MINOR(apiVersion),
                    VK_API_VERSION_PATCH(apiVersion));
        }

        public String driverVersionString() {
            return "%d.%d.%d".formatted(
                    VK_VERSION_MAJOR(driverVersion),
                    VK_VERSION_MINOR(driverVersion),
                    VK_VERSION_PATCH(driverVersion));
        }

        public String deviceTypeName() {
            return switch (deviceType) {
                case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "Discrete GPU";
                case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "Integrated GPU";
                case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "Virtual GPU";
                case VK_PHYSICAL_DEVICE_TYPE_CPU -> "CPU";
                default -> "Other";
            };
        }
    }

    /**
     * Queue family indices for graphics, present, transfer, and compute.
     */
    public record QueueFamilyIndices(
            int graphicsFamily,
            int presentFamily,
            int transferFamily,
            int computeFamily
    ) {
        public boolean isComplete() {
            return graphicsFamily >= 0 && presentFamily >= 0;
        }

        public boolean hasDedicatedTransfer() {
            return transferFamily >= 0 && transferFamily != graphicsFamily;
        }

        public boolean hasDedicatedCompute() {
            return computeFamily >= 0 && computeFamily != graphicsFamily;
        }

        /**
         * Returns unique queue family indices for device creation.
         */
        public int[] uniqueFamilies() {
            Set<Integer> unique = new LinkedHashSet<>();
            if (graphicsFamily >= 0) unique.add(graphicsFamily);
            if (presentFamily >= 0) unique.add(presentFamily);
            if (transferFamily >= 0) unique.add(transferFamily);
            if (computeFamily >= 0) unique.add(computeFamily);
            return unique.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    private record DeviceCandidate(VkPhysicalDevice physicalDevice, int score, String name) {}
}
