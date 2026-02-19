package net.vulkanium.mixin.core;

import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.vulkanium.Vulkanium;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diagnostic mixin to track resource reload progress and detect hangs.
 */
@Mixin(LoadingOverlay.class)
public abstract class MixinLoadingOverlay {

    @Shadow @Final private ReloadInstance reload;

    @Unique private long lastLogTime = 0;
    @Unique private int renderCount = 0;
    @Unique private float lastProgress = -1;
    @Unique private boolean dumpedDetails = false;

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(CallbackInfo ci) {
        renderCount++;
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 3000) { // Log every 3 seconds
            lastLogTime = now;
            boolean done = this.reload.isDone();
            float progress = this.reload.getActualProgress();
            boolean progressChanged = Math.abs(progress - lastProgress) > 0.0001f;
            lastProgress = progress;

            Vulkanium.LOGGER.debug("[LoadingOverlay] renderCount={} isDone={} progress={} changed={}",
                    renderCount, done, progress, progressChanged);

            // Dump internal state if stuck for more than 10 seconds
            if (!done && !progressChanged && !dumpedDetails) {
                dumpedDetails = true;
                dumpReloadState();
            }
        }
    }

    @Unique
    private void dumpReloadState() {
        try {
            Object instance = this.reload;
            Class<?> clz = instance.getClass();
            Vulkanium.LOGGER.debug("[LoadingOverlay] ReloadInstance class: {}", clz.getName());

            // Get all relevant fields via reflection
            Field listenerCountF = findField(clz, "listenerCount");
            Field startedReloadsF = findField(clz, "startedReloads");
            Field finishedReloadsF = findField(clz, "finishedReloads");
            Field startedTaskF = findField(clz, "startedTaskCounter");
            Field doneTaskF = findField(clz, "doneTaskCounter");
            Field preparingF = findField(clz, "preparingListeners");
            Field allPrepF = findField(clz, "allPreparations");
            Field allDoneF = findField(clz, "allDone");

            if (listenerCountF != null) {
                listenerCountF.setAccessible(true);
                Vulkanium.LOGGER.debug("[LoadingOverlay] listenerCount={}", listenerCountF.getInt(instance));
            }
            if (startedReloadsF != null) {
                startedReloadsF.setAccessible(true);
                Vulkanium.LOGGER.debug("[LoadingOverlay] startedReloads={}", startedReloadsF.getInt(instance));
            }
            if (finishedReloadsF != null) {
                finishedReloadsF.setAccessible(true);
                Vulkanium.LOGGER.debug("[LoadingOverlay] finishedReloads={}", finishedReloadsF.getInt(instance));
            }
            if (startedTaskF != null) {
                startedTaskF.setAccessible(true);
                AtomicInteger ai = (AtomicInteger) startedTaskF.get(instance);
                Vulkanium.LOGGER.debug("[LoadingOverlay] startedTaskCounter={}", ai.get());
            }
            if (doneTaskF != null) {
                doneTaskF.setAccessible(true);
                AtomicInteger ai = (AtomicInteger) doneTaskF.get(instance);
                Vulkanium.LOGGER.debug("[LoadingOverlay] doneTaskCounter={}", ai.get());
            }
            if (preparingF != null) {
                preparingF.setAccessible(true);
                Set<?> preparing = (Set<?>) preparingF.get(instance);
                Vulkanium.LOGGER.debug("[LoadingOverlay] preparingListeners.size={}", preparing.size());
                for (Object listener : preparing) {
                    Vulkanium.LOGGER.debug("[LoadingOverlay]   STILL PREPARING: {}", listener.getClass().getName());
                }
            }
            if (allPrepF != null) {
                allPrepF.setAccessible(true);
                CompletableFuture<?> cf = (CompletableFuture<?>) allPrepF.get(instance);
                Vulkanium.LOGGER.debug("[LoadingOverlay] allPreparations: isDone={} isExceptional={}", cf.isDone(), cf.isCompletedExceptionally());
            }
            if (allDoneF != null) {
                allDoneF.setAccessible(true);
                CompletableFuture<?> cf = (CompletableFuture<?>) allDoneF.get(instance);
                Vulkanium.LOGGER.debug("[LoadingOverlay] allDone: isDone={} isExceptional={}", cf.isDone(), cf.isCompletedExceptionally());
                if (cf.isCompletedExceptionally()) {
                    cf.exceptionally(ex -> {
                        Vulkanium.LOGGER.error("[LoadingOverlay] allDone EXCEPTION:", ex);
                        return null;
                    });
                }
            }
        } catch (Exception e) {
            Vulkanium.LOGGER.warn("[LoadingOverlay] Failed to dump reload state: {}", e.getMessage());
        }
    }

    @Unique
    private static Field findField(Class<?> clz, String name) {
        while (clz != null) {
            try {
                return clz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clz = clz.getSuperclass();
            }
        }
        return null;
    }
}
