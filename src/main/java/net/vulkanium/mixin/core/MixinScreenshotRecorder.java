package net.vulkanium.mixin.core;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.vulkanium.Vulkanium;
import net.vulkanium.core.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Intercepts Minecraft's screenshot capture to read from the Vulkan swapchain
 * image instead of calling glReadPixels.
 *
 * <p>When a screenshot is requested (F2), this mixin:</p>
 * <ol>
 *   <li>Waits for all GPU work to complete</li>
 *   <li>Transitions the last-presented swapchain image to TRANSFER_SRC</li>
 *   <li>Copies image data into a host-visible staging buffer</li>
 *   <li>Transitions the image back to PRESENT_SRC</li>
 *   <li>Maps the staging buffer and creates a NativeImage</li>
 *   <li>Saves the screenshot as PNG and sends the chat message</li>
 * </ol>
 */
@Mixin(Screenshot.class)
public abstract class MixinScreenshotRecorder {

    @Inject(method = "grab", at = @At("HEAD"), cancellable = true)
    private static void onTakeScreenshot(File gameDir, RenderTarget framebuffer,
                                         Consumer<Component> messageConsumer,
                                         CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        try {
            vulkanium$captureAndSave(gameDir, messageConsumer);
        } catch (Exception e) {
            Vulkanium.LOGGER.error("Failed to capture Vulkan screenshot", e);
            messageConsumer.accept(Component.translatable("screenshot.failure", e.getMessage()));
        }

        ci.cancel();
    }

    /**
     * Performs the actual Vulkan framebuffer readback and saves the image.
     */
    @Unique
    private static void vulkanium$captureAndSave(File gameDir, Consumer<Component> messageConsumer) {
        VulkaniumSwapchain swapchain = Vulkanium.getVulkanSwapchain();
        VulkaniumMemory memory = Vulkanium.getVulkanMemory();
        VulkaniumCommand command = Vulkanium.getVulkanCommand();
        FrameOrchestrator orchestrator = Vulkanium.getFrameOrchestrator();

        int width = swapchain.getWidth();
        int height = swapchain.getHeight();

        // Determine which swapchain image was last presented
        int imageIndex = orchestrator.getLastPresentedImageIndex();
        if (imageIndex < 0) {
            Vulkanium.LOGGER.warn("No frame has been presented yet — cannot take screenshot");
            messageConsumer.accept(Component.literal("Screenshot failed: no frame rendered yet"));
            return;
        }

        long swapImage = swapchain.getImages()[imageIndex];

        // Swapchain format is B8G8R8A8_UNORM → 4 bytes per pixel
        long imageSizeBytes = (long) width * height * 4;

        // Create a host-visible staging buffer for the readback
        VulkaniumMemory.BufferAllocation staging = memory.createBuffer(
                imageSizeBytes,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

        try {
            // Wait for all GPU work to finish — the swapchain image is now idle
            orchestrator.waitIdle();

            // Record a one-shot command buffer for the image-to-buffer copy
            VkCommandBuffer cmd = command.beginSingleTimeCommand();

            // Transition swapchain image: PRESENT_SRC → TRANSFER_SRC
            VulkaniumCommand.transitionImageLayout(cmd, swapImage,
                    VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    0, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT);

            // Copy image to staging buffer
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                        .bufferOffset(0)
                        .bufferRowLength(0)   // tightly packed
                        .bufferImageHeight(0) // tightly packed
                        .imageOffset(it -> it.set(0, 0, 0))
                        .imageExtent(it -> it.set(width, height, 1));

                region.imageSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1);

                vkCmdCopyImageToBuffer(cmd, swapImage,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        staging.buffer(), region);
            }

            // Transition swapchain image back: TRANSFER_SRC → PRESENT_SRC
            VulkaniumCommand.transitionImageLayout(cmd, swapImage,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    VK_ACCESS_TRANSFER_READ_BIT, 0,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT);

            // Submit and wait for completion
            command.endSingleTimeCommand(cmd);

            // Map staging buffer and create a NativeImage
            long mappedPtr = memory.map(staging.allocation());

            try {
                // Create NativeImage (RGBA format — MC's screenshot format)
                NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
                long dstPixels = vulkanium$getNativeImagePixels(image);

                // Convert BGRA → RGBA and flip vertically (Vulkan Y is top-down,
                // but NativeImage also stores top-down, so we only need channel swizzle)
                int rowBytes = width * 4;
                for (int y = 0; y < height; y++) {
                    long srcRow = mappedPtr + (long) y * rowBytes;
                    long dstRow = dstPixels + (long) y * rowBytes;

                    for (int x = 0; x < width; x++) {
                        long srcPixel = srcRow + (long) x * 4;
                        long dstPixel = dstRow + (long) x * 4;

                        // BGRA → RGBA: swap B and R channels
                        byte b = MemoryUtil.memGetByte(srcPixel);
                        byte g = MemoryUtil.memGetByte(srcPixel + 1);
                        byte r = MemoryUtil.memGetByte(srcPixel + 2);
                        byte a = MemoryUtil.memGetByte(srcPixel + 3);

                        MemoryUtil.memPutByte(dstPixel, r);
                        MemoryUtil.memPutByte(dstPixel + 1, g);
                        MemoryUtil.memPutByte(dstPixel + 2, b);
                        MemoryUtil.memPutByte(dstPixel + 3, a);
                    }
                }

                // Save the screenshot
                File screenshotDir = new File(gameDir, "screenshots");
                screenshotDir.mkdirs();

                String filename = vulkanium$generateScreenshotName(screenshotDir);
                File outputFile = new File(screenshotDir, filename);
                try {
                    image.writeToFile(outputFile);
                } catch (IOException e) {
                    image.close();
                    throw new RuntimeException("Failed to write screenshot", e);
                }
                image.close();

                // Send success message with clickable file link
                Component fileComponent = Component.literal(filename)
                        .withStyle(style -> style.withUnderlined(true)
                                .withColor(net.minecraft.ChatFormatting.GREEN)
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                        net.minecraft.network.chat.ClickEvent.Action.OPEN_FILE,
                                        outputFile.getAbsolutePath())));
                messageConsumer.accept(Component.translatable("screenshot.success", fileComponent));

                Vulkanium.LOGGER.info("Screenshot saved: {} ({}x{})", outputFile.getAbsolutePath(), width, height);

            } finally {
                memory.unmap(staging.allocation());
            }

        } finally {
            // Free the staging buffer
            memory.freeBufferImmediate(staging);
        }
    }

    /**
     * Access the raw pixel pointer of a NativeImage via reflection/Unsafe.
     * NativeImage stores pixels as a long field "pixels".
     */
    @Unique
    private static long vulkanium$getNativeImagePixels(NativeImage image) {
        try {
            var field = NativeImage.class.getDeclaredField("pixels");
            field.setAccessible(true);
            return field.getLong(image);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot access NativeImage.pixels", e);
        }
    }

    /**
     * Generates a unique screenshot filename matching MC's format.
     */
    @Unique
    private static String vulkanium$generateScreenshotName(File screenshotDir) {
        java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");
        String baseName = dateFormat.format(new java.util.Date());
        String filename = baseName + ".png";

        // Handle duplicates within the same second
        if (new File(screenshotDir, filename).exists()) {
            int counter = 1;
            do {
                filename = baseName + "_" + counter + ".png";
                counter++;
            } while (new File(screenshotDir, filename).exists());
        }

        return filename;
    }
}
