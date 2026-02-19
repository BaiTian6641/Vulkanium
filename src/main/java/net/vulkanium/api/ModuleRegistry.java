package net.vulkanium.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Registry for {@link WorldModule} instances.
 *
 * <p>Manages module registration, lifecycle, and phase-based dispatch.
 * Modules must be registered during mod initialization (before the first
 * world load). The registry validates module requirements (RT tier, compute
 * support) at registration time and filters out incompatible modules.</p>
 *
 * <h3>Registration</h3>
 * <pre>{@code
 *   // In your mod's entrypoint
 *   VulkaniumAPI.getInstance().getModuleRegistry().register(new MyModule());
 * }</pre>
 *
 * <h3>Dispatch Order</h3>
 * <p>Within each phase, modules execute in priority order (highest first).
 * Modules with the same priority execute in registration order.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Registration is thread-safe. Phase dispatch happens on the render thread.
 * Module callbacks are always invoked from the render thread unless the phase
 * is {@link ModulePhase#COMPUTE_ASYNC}, which may run on a compute thread.</p>
 *
 * @see WorldModule
 * @see ModulePhase
 */
public interface ModuleRegistry {

    /**
     * Registers a world module.
     *
     * @param module The module to register
     * @throws IllegalArgumentException if a module with the same ID is already registered
     * @throws IllegalStateException    if registration is called after world load
     */
    void register(WorldModule module);

    /**
     * Unregisters a world module by ID.
     *
     * @param moduleId The module's unique identifier
     * @return true if the module was found and removed
     */
    boolean unregister(String moduleId);

    /**
     * Gets a registered module by ID.
     *
     * @param moduleId The module's unique identifier
     * @return The module, or empty if not registered
     */
    Optional<WorldModule> getModule(String moduleId);

    /**
     * Returns all registered modules in registration order.
     */
    List<WorldModule> getModules();

    /**
     * Returns modules registered for a specific phase, sorted by priority.
     *
     * @param phase The render pipeline phase
     * @return Modules for that phase, sorted highest-priority-first
     */
    List<WorldModule> getModulesForPhase(ModulePhase phase);

    // ── Default Implementation ──

    /**
     * Creates the default module registry implementation.
     *
     * @param rtTier        Current device RT tier (0, 1, or 2)
     * @param hasCompute    Whether compute is available
     * @return A new ModuleRegistry instance
     */
    static ModuleRegistry create(int rtTier, boolean hasCompute) {
        return new DefaultModuleRegistry(rtTier, hasCompute);
    }

    /**
     * Default implementation of the module registry.
     */
    class DefaultModuleRegistry implements ModuleRegistry {
        private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ModuleRegistry");

        private final int rtTier;
        private final boolean hasCompute;
        private final Map<String, WorldModule> modules = new ConcurrentHashMap<>();
        private final List<WorldModule> orderedModules = new CopyOnWriteArrayList<>();

        // Phase → sorted module list cache (invalidated on registration)
        private volatile Map<ModulePhase, List<WorldModule>> phaseCache = null;

        public DefaultModuleRegistry(int rtTier, boolean hasCompute) {
            this.rtTier = rtTier;
            this.hasCompute = hasCompute;
        }

        @Override
        public void register(WorldModule module) {
            String id = module.getModuleId();

            // Validate no duplicate
            if (modules.containsKey(id)) {
                throw new IllegalArgumentException("Module already registered: " + id);
            }

            // Validate requirements
            if (module.requiresRayTracing() && rtTier < module.minimumRTTier()) {
                LOGGER.warn("Module '{}' requires RT tier {} but device has tier {} — skipping",
                        id, module.minimumRTTier(), rtTier);
                return;
            }

            if (module.requiresCompute() && !hasCompute) {
                LOGGER.warn("Module '{}' requires compute but it's not available — skipping", id);
                return;
            }

            // Validate phases
            ModulePhase[] phases = module.getPhases();
            if (phases == null || phases.length == 0) {
                LOGGER.warn("Module '{}' has no phases registered — skipping", id);
                return;
            }

            for (ModulePhase phase : phases) {
                if (phase.requiresRayTracing() && rtTier == 0) {
                    LOGGER.warn("Module '{}' uses RT phase {} but RT is not available — skipping",
                            id, phase);
                    return;
                }
            }

            modules.put(id, module);
            orderedModules.add(module);
            phaseCache = null; // Invalidate

            LOGGER.info("Registered module '{}' ({}) for phases: {}",
                    id, module.getDisplayName(),
                    Arrays.stream(phases).map(Enum::name).collect(Collectors.joining(", ")));
        }

        @Override
        public boolean unregister(String moduleId) {
            WorldModule removed = modules.remove(moduleId);
            if (removed != null) {
                orderedModules.remove(removed);
                phaseCache = null;
                LOGGER.info("Unregistered module '{}'", moduleId);
                return true;
            }
            return false;
        }

        @Override
        public Optional<WorldModule> getModule(String moduleId) {
            return Optional.ofNullable(modules.get(moduleId));
        }

        @Override
        public List<WorldModule> getModules() {
            return Collections.unmodifiableList(orderedModules);
        }

        @Override
        public List<WorldModule> getModulesForPhase(ModulePhase phase) {
            Map<ModulePhase, List<WorldModule>> cache = phaseCache;
            if (cache == null) {
                cache = buildPhaseCache();
                phaseCache = cache;
            }
            return cache.getOrDefault(phase, List.of());
        }

        private Map<ModulePhase, List<WorldModule>> buildPhaseCache() {
            Map<ModulePhase, List<WorldModule>> cache = new EnumMap<>(ModulePhase.class);

            for (WorldModule module : orderedModules) {
                for (ModulePhase phase : module.getPhases()) {
                    cache.computeIfAbsent(phase, k -> new ArrayList<>()).add(module);
                }
            }

            // Sort each phase's modules by priority (highest first)
            for (List<WorldModule> moduleList : cache.values()) {
                moduleList.sort(Comparator.comparingInt(WorldModule::getPriority).reversed());
            }

            // Make immutable
            Map<ModulePhase, List<WorldModule>> immutable = new EnumMap<>(ModulePhase.class);
            cache.forEach((phase, list) -> immutable.put(phase, Collections.unmodifiableList(list)));

            return Collections.unmodifiableMap(immutable);
        }
    }
}
