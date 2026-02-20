package net.vulkanium.core;

import net.vulkanium.Vulkanium;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * Manages the VkInstance, debug messenger, and window surface.
 *
 * <p>This is the first Vulkan object created and the last destroyed.
 * All other Vulkan objects depend on the instance.</p>
 *
 * <p>Improvements over VulkanMod's {@code Vulkan.java}:</p>
 * <ul>
 *   <li>Separated from device/memory/renderer (single responsibility)</li>
 *   <li>Configurable validation layers via config file</li>
 *   <li>Severity-graded debug logging via SLF4J</li>
 * </ul>
 */
public class VulkaniumInstance {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Instance");

    private static final Set<String> VALIDATION_LAYERS = Set.of("VK_LAYER_KHRONOS_validation");

    private VkInstance instance;
    private long debugMessenger = VK_NULL_HANDLE;
    private long surface = VK_NULL_HANDLE;
    private long windowHandle;
    private boolean validationEnabled;

    public VulkaniumInstance(boolean enableValidation) {
        this.validationEnabled = enableValidation;
    }

    /**
     * Creates the VkInstance, sets up debug messenger, and creates the window surface.
     *
     * @param windowHandle The GLFW window handle
     */
    public void initialize(long windowHandle) {
        this.windowHandle = windowHandle;

        LOGGER.info("Creating Vulkan instance (validation: {})", validationEnabled);
        createInstance();

        if (validationEnabled) {
            setupDebugMessenger();
        }

        createSurface(windowHandle);
        LOGGER.info("Vulkan instance created successfully");
    }

    private void createInstance() {
        if (validationEnabled && !checkValidationLayerSupport()) {
            LOGGER.warn("Validation layers requested but not available — disabling");
            validationEnabled = false;
        }

        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8Safe("Vulkanium"))
                    .applicationVersion(VK_MAKE_VERSION(0, 1, 0))
                    .pEngineName(stack.UTF8Safe("Vulkanium Engine"))
                    .engineVersion(VK_MAKE_VERSION(0, 1, 0))
                    .apiVersion(VK_API_VERSION_1_2);

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(getRequiredInstanceExtensions(stack));

            if (validationEnabled) {
                createInfo.ppEnabledLayerNames(asPointerBuffer(VALIDATION_LAYERS, stack));

                VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
                populateDebugMessengerCreateInfo(debugCreateInfo);
                createInfo.pNext(debugCreateInfo.address());
            }

            PointerBuffer pInstance = stack.mallocPointer(1);
            int result = vkCreateInstance(createInfo, null, pInstance);
            checkResult(result, "Failed to create Vulkan instance");

            instance = new VkInstance(pInstance.get(0), createInfo);
        }
    }

    private void setupDebugMessenger() {
        try (MemoryStack stack = stackPush()) {
            VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
            populateDebugMessengerCreateInfo(createInfo);

            LongBuffer pDebugMessenger = stack.longs(VK_NULL_HANDLE);

            if (vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT") != NULL) {
                int result = vkCreateDebugUtilsMessengerEXT(instance, createInfo, null, pDebugMessenger);
                checkResult(result, "Failed to set up debug messenger");
                debugMessenger = pDebugMessenger.get(0);
                LOGGER.info("Vulkan debug messenger installed");
            } else {
                LOGGER.warn("vkCreateDebugUtilsMessengerEXT not available");
            }
        }
    }

    private void createSurface(long window) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSurface = stack.longs(VK_NULL_HANDLE);
            int result = glfwCreateWindowSurface(instance, window, null, pSurface);
            checkResult(result, "Failed to create window surface");
            surface = pSurface.get(0);
        }
    }

    private void populateDebugMessengerCreateInfo(VkDebugUtilsMessengerCreateInfoEXT createInfo) {
        createInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                .messageSeverity(
                        VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(
                        VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(VulkaniumInstance::debugCallback);
    }

    private static int debugCallback(int messageSeverity, int messageType, long pCallbackData, long pUserData) {
        VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
        String message = callbackData.pMessageString();

        if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
            LOGGER.error("[Vulkan] {}", message);
        } else if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
            LOGGER.warn("[Vulkan] {}", message);
        } else if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) != 0) {
            LOGGER.info("[Vulkan] {}", message);
        } else {
            LOGGER.debug("[Vulkan] {}", message);
        }

        return VK_FALSE;
    }

    private boolean checkValidationLayerSupport() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer layerCount = stack.ints(0);
            vkEnumerateInstanceLayerProperties(layerCount, null);

            VkLayerProperties.Buffer availableLayers = VkLayerProperties.malloc(layerCount.get(0), stack);
            vkEnumerateInstanceLayerProperties(layerCount, availableLayers);

            Set<String> available = availableLayers.stream()
                    .map(VkLayerProperties::layerNameString)
                    .collect(toSet());

            return available.containsAll(VALIDATION_LAYERS);
        }
    }

    private PointerBuffer getRequiredInstanceExtensions(MemoryStack stack) {
        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
        if (glfwExtensions == null) {
            throw new RuntimeException("GLFW: Vulkan not supported (glfwGetRequiredInstanceExtensions returned null)");
        }

        // Always request: VK_EXT_swapchain_colorspace (for HDR color spaces)
        // + optionally VK_EXT_debug_utils (validation)
        int extraCount = 1; // swapchain_colorspace
        if (validationEnabled) extraCount++;

        PointerBuffer extensions = stack.mallocPointer(glfwExtensions.capacity() + extraCount);
        extensions.put(glfwExtensions);
        extensions.put(stack.UTF8("VK_EXT_swapchain_colorspace"));

        if (validationEnabled) {
            extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
        }

        return extensions.rewind();
    }

    // === Cleanup ===

    public void destroy() {
        LOGGER.info("Destroying Vulkan instance");

        if (surface != VK_NULL_HANDLE) {
            KHRSurface.vkDestroySurfaceKHR(instance, surface, null);
            surface = VK_NULL_HANDLE;
        }

        if (debugMessenger != VK_NULL_HANDLE) {
            if (vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT") != NULL) {
                vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
            }
            debugMessenger = VK_NULL_HANDLE;
        }

        vkDestroyInstance(instance, null);
        instance = null;
    }

    // === Getters ===

    public VkInstance getInstance() { return instance; }
    public long getSurface() { return surface; }
    public long getWindowHandle() { return windowHandle; }
    public boolean isValidationEnabled() { return validationEnabled; }

    // === Utilities ===

    public static void checkResult(int result, String errorMessage) {
        if (result != VK_SUCCESS) {
            throw new RuntimeException(String.format("%s: VkResult=%d", errorMessage, result));
        }
    }

    public static void checkResult(int result) {
        checkResult(result, "Vulkan operation failed");
    }

    static PointerBuffer asPointerBuffer(Collection<String> strings, MemoryStack stack) {
        PointerBuffer buffer = stack.mallocPointer(strings.size());
        for (String s : strings) {
            buffer.put(stack.UTF8(s));
        }
        return buffer.rewind();
    }
}
