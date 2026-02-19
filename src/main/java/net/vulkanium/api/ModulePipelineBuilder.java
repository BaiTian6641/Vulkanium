package net.vulkanium.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Builds an ordered execution pipeline from registered {@link WorldModule}s.
 *
 * <p>The pipeline builder takes all registered modules and produces a flat
 * execution list ordered by phase, then by priority within each phase.
 * It also resolves conflicts between mutually exclusive modules (e.g.,
 * raster terrain vs. RT terrain).</p>
 *
 * <h3>Conflict Resolution</h3>
 * <p>Some modules are mutually exclusive — they both try to produce the same
 * output (e.g., terrain color targets). When conflicts are detected:</p>
 * <ol>
 *   <li>Module with higher priority wins</li>
 *   <li>If priorities are equal, the first registered module wins</li>
 *   <li>Disabled module's {@code onPhase} is never called</li>
 * </ol>
 *
 * <h3>Conflict Groups</h3>
 * <ul>
 *   <li>{@code terrain} — RasterTerrainModule vs RTTerrainModule</li>
 *   <li>{@code shadow} — ShadowMapModule vs RTShadowModule</li>
 *   <li>{@code upscaler} — FSR3Module vs DLSSModule vs XeSSModule</li>
 *   <li>{@code denoiser} — Only one denoiser active at a time</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   ModulePipelineBuilder builder = new ModulePipelineBuilder(registry);
 *   builder.resolveConflicts();
 *   List<PipelineEntry> pipeline = builder.build();
 *   // Execute pipeline each frame
 *   for (PipelineEntry entry : pipeline) {
 *       entry.module().onPhase(entry.phase(), context);
 *   }
 * }</pre>
 */
public class ModulePipelineBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/PipelineBuilder");

    /**
     * A single entry in the execution pipeline.
     */
    public record PipelineEntry(ModulePhase phase, WorldModule module) {}

    /**
     * Conflict group — modules that produce the same output.
     */
    public record ConflictGroup(String name, Set<String> moduleIds) {
        public ConflictGroup(String name, String... ids) {
            this(name, new LinkedHashSet<>(Arrays.asList(ids)));
        }
    }

    private final ModuleRegistry registry;
    private final Set<String> disabledModules = new HashSet<>();
    private final List<ConflictGroup> conflictGroups = new ArrayList<>();

    public ModulePipelineBuilder(ModuleRegistry registry) {
        this.registry = registry;
        registerDefaultConflictGroups();
    }

    private void registerDefaultConflictGroups() {
        conflictGroups.add(new ConflictGroup("terrain",
                "vulkanium:raster_terrain", "vulkanium:rt_terrain"));
        conflictGroups.add(new ConflictGroup("shadow",
                "vulkanium:shadow_map", "vulkanium:rt_shadow"));
        conflictGroups.add(new ConflictGroup("upscaler",
                "vulkanium:fsr3", "vulkanium:dlss", "vulkanium:xess"));
        conflictGroups.add(new ConflictGroup("denoiser",
                "vulkanium:svgf_denoiser"));
    }

    /**
     * Adds a custom conflict group.
     */
    public void addConflictGroup(ConflictGroup group) {
        conflictGroups.add(group);
    }

    /**
     * Resolves conflicts between mutually exclusive modules.
     * Disables lower-priority modules in each conflict group.
     */
    public void resolveConflicts() {
        for (ConflictGroup group : conflictGroups) {
            List<WorldModule> active = new ArrayList<>();

            for (String moduleId : group.moduleIds()) {
                registry.getModule(moduleId).ifPresent(active::add);
            }

            if (active.size() <= 1) continue; // No conflict

            // Sort by priority descending (highest wins)
            active.sort(Comparator.comparingInt(WorldModule::getPriority).reversed());

            // Keep first, disable the rest
            WorldModule winner = active.get(0);
            for (int i = 1; i < active.size(); i++) {
                String loserId = active.get(i).getModuleId();
                disabledModules.add(loserId);
                LOGGER.info("Conflict group '{}': '{}' wins over '{}' (priority {} vs {})",
                        group.name(), winner.getModuleId(), loserId,
                        winner.getPriority(), active.get(i).getPriority());
            }
        }
    }

    /**
     * Builds the ordered execution pipeline.
     *
     * @return Flat list of (phase, module) entries in execution order
     */
    public List<PipelineEntry> build() {
        List<PipelineEntry> pipeline = new ArrayList<>();

        // Iterate phases in order (ModulePhase ordinal = execution order)
        for (ModulePhase phase : ModulePhase.values()) {
            List<WorldModule> modules = registry.getModulesForPhase(phase);

            for (WorldModule module : modules) {
                if (disabledModules.contains(module.getModuleId())) {
                    continue; // Skip disabled modules
                }
                pipeline.add(new PipelineEntry(phase, module));
            }
        }

        LOGGER.info("Pipeline built: {} entries across {} phases, {} modules disabled",
                pipeline.size(), countActivePhases(pipeline), disabledModules.size());
        return Collections.unmodifiableList(pipeline);
    }

    /**
     * Returns the set of disabled module IDs.
     */
    public Set<String> getDisabledModules() {
        return Collections.unmodifiableSet(disabledModules);
    }

    /**
     * Manually disables a module by ID.
     */
    public void disableModule(String moduleId) {
        disabledModules.add(moduleId);
    }

    /**
     * Manually enables a previously disabled module.
     */
    public void enableModule(String moduleId) {
        disabledModules.remove(moduleId);
    }

    private int countActivePhases(List<PipelineEntry> pipeline) {
        return (int) pipeline.stream()
                .map(PipelineEntry::phase)
                .distinct()
                .count();
    }
}
