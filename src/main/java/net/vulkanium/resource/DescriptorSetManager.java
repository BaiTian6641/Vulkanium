package net.vulkanium.resource;

import net.vulkanium.core.VulkaniumInstance;
import net.vulkanium.core.VulkaniumMemory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages descriptor set layouts, descriptor pools, and descriptor set allocation.
 *
 * <p>Vulkanium's descriptor architecture uses 3 sets:</p>
 * <ul>
 *   <li><b>Set 0</b>: Per-frame UBOs (camera, fog, time, etc.)</li>
 *   <li><b>Set 1</b>: Samplers (atlas, lightmap, shadow maps)</li>
 *   <li><b>Set 2</b>: Storage images / storage buffers (for compute passes)</li>
 * </ul>
 *
 * <p>Unlike VulkanMod's single descriptor set approach, this enables efficient
 * binding where per-frame data changes every frame but samplers rarely change.</p>
 */
public class DescriptorSetManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Descriptors");

    private VkDevice device;

    // Descriptor set layouts (one per set number)
    private long uboLayout;       // set 0
    private long samplerLayout;   // set 1
    private long storageLayout;   // set 2

    // Descriptor pool
    private long descriptorPool;
    private int maxSets;

    /**
     * Creates descriptor set layouts and a pool.
     *
     * @param maxFrames Max frames in flight (determines UBO descriptor count)
     * @param maxSamplers Max combined image samplers
     */
    public void initialize(VkDevice device, int maxFrames, int maxSamplers) {
        this.device = device;
        this.maxSets = maxFrames * 3 + 64; // some headroom

        createLayouts(maxSamplers);
        createPool(maxFrames, maxSamplers);

        LOGGER.info("Descriptor set manager initialized (ubo/sampler/storage layouts, pool with {} max sets)", maxSets);
    }

    private void createLayouts(int maxSamplers) {
        // Set 0: UBOs
        uboLayout = createLayout(new LayoutBinding[]{
                new LayoutBinding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT), // camera
                new LayoutBinding(1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT), // fog
                new LayoutBinding(2, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT), // time/misc
        });

        // Set 1: Samplers
        samplerLayout = createLayout(new LayoutBinding[]{
                new LayoutBinding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT), // texture atlas
                new LayoutBinding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT), // lightmap
                new LayoutBinding(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT), // shadow map
                new LayoutBinding(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT), // normals
                new LayoutBinding(4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT), // specular
                new LayoutBinding(5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 8, VK_SHADER_STAGE_FRAGMENT_BIT), // colortex0-7 (array)
                new LayoutBinding(6, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 8, VK_SHADER_STAGE_FRAGMENT_BIT), // shadowtex/noisetex
        });

        // Set 2: Storage (for compute)
        storageLayout = createLayout(new LayoutBinding[]{
                new LayoutBinding(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT),  // indirect draw buffer
                new LayoutBinding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT),  // chunk visibility
                new LayoutBinding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT),   // output image
        });
    }

    private long createLayout(LayoutBinding[] bindings) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer layoutBindings = VkDescriptorSetLayoutBinding.calloc(bindings.length, stack);

            for (int i = 0; i < bindings.length; i++) {
                LayoutBinding b = bindings[i];
                layoutBindings.get(i)
                        .binding(b.binding)
                        .descriptorType(b.type)
                        .descriptorCount(b.count)
                        .stageFlags(b.stageFlags)
                        .pImmutableSamplers(null);
            }

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(layoutBindings);

            LongBuffer pLayout = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout);
            checkResult(result, "Failed to create descriptor set layout");

            return pLayout.get(0);
        }
    }

    private void createPool(int maxFrames, int maxSamplers) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(4, stack);

            // UBOs: 3 bindings × maxFrames
            poolSizes.get(0)
                    .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(3 * maxFrames + 16);

            // Combined image samplers
            poolSizes.get(1)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(Math.max(maxSamplers, 64));

            // Storage buffers (set 2 bindings 0-1)
            poolSizes.get(2)
                    .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(16);

            // Storage images (set 2 binding 2 — output image for compute)
            poolSizes.get(3)
                    .type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(8);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(poolSizes)
                    .maxSets(maxSets)
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT);

            LongBuffer pPool = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateDescriptorPool(device, poolInfo, null, pPool);
            checkResult(result, "Failed to create descriptor pool");

            descriptorPool = pPool.get(0);
        }
    }

    /**
     * Allocates descriptor sets from the pool using the given layouts.
     */
    public long[] allocateDescriptorSets(long... layouts) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(layouts));

            LongBuffer pSets = stack.mallocLong(layouts.length);
            int result = vkAllocateDescriptorSets(device, allocInfo, pSets);
            checkResult(result, "Failed to allocate descriptor sets");

            long[] sets = new long[layouts.length];
            for (int i = 0; i < layouts.length; i++) {
                sets[i] = pSets.get(i);
            }
            return sets;
        }
    }

    /**
     * Updates a descriptor set binding to point to a uniform buffer.
     */
    public void updateUniformBuffer(long descriptorSet, int binding, long buffer, long offset, long range) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer)
                    .offset(offset)
                    .range(range);

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(binding)
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(bufferInfo);

            vkUpdateDescriptorSets(device, write, null);
        }
    }

    /**
     * Updates a descriptor set binding to point to a combined image sampler.
     */
    public void updateImageSampler(long descriptorSet, int binding, long imageView, long sampler, int imageLayout) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(imageView)
                    .sampler(sampler)
                    .imageLayout(imageLayout);

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(binding)
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(imageInfo);

            vkUpdateDescriptorSets(device, write, null);
        }
    }

    /**
     * Resets the entire descriptor pool, freeing all allocated sets.
     */
    public void resetPool() {
        vkResetDescriptorPool(device, descriptorPool, 0);
    }

    // === Cleanup ===

    public void destroy() {
        if (device == null) return;

        if (descriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device, descriptorPool, null);
        }
        if (uboLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, uboLayout, null);
        if (samplerLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, samplerLayout, null);
        if (storageLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, storageLayout, null);

        device = null;
    }

    // === Getters ===

    public long getUboLayout() { return uboLayout; }
    public long getSamplerLayout() { return samplerLayout; }
    public long getStorageLayout() { return storageLayout; }
    public long getDescriptorPool() { return descriptorPool; }

    /**
     * Returns all three layouts in order [set0, set1, set2] for pipeline layout creation.
     */
    public long[] getAllLayouts() {
        return new long[]{uboLayout, samplerLayout, storageLayout};
    }

    // === Inner Types ===

    private record LayoutBinding(int binding, int type, int count, int stageFlags) {}
}
