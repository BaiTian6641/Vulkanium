package net.vulkanium.compute;

import net.vulkanium.api.ComputeTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dependency graph for compute tasks, enabling topological dispatch ordering.
 *
 * <p>Compute tasks can declare dependencies on other tasks via
 * {@link ComputeTask#getDependencies()}. This class builds a DAG from submitted
 * tasks, validates it (no cycles), and produces a topologically sorted execution
 * order that respects both dependencies and priorities.</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Build adjacency list from task dependencies</li>
 *   <li>Detect cycles (DFS coloring — error if found)</li>
 *   <li>Kahn's algorithm for topological sort</li>
 *   <li>Within each topological level, sort by priority descending</li>
 *   <li>Adjacent tasks with the same pipeline are batched together</li>
 * </ol>
 *
 * <h3>Barrier Insertion</h3>
 * <p>Between tasks that share a dependency edge, a compute barrier
 * ({@code VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT}) is inserted so the
 * producer's SSBO writes are visible to the consumer's reads.</p>
 */
public class ComputeTaskGraph {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/TaskGraph");

    /**
     * A node in the task graph.
     */
    public static class TaskNode {
        final ComputeTask task;
        final int id;
        final List<TaskNode> dependsOn = new ArrayList<>();
        final List<TaskNode> dependedBy = new ArrayList<>();
        final AtomicInteger inDegree = new AtomicInteger(0);

        // Scheduling metadata
        int topologicalLevel = -1;
        boolean needsBarrierBefore = false;

        TaskNode(ComputeTask task, int id) {
            this.task = task;
            this.id = id;
        }
    }

    /**
     * Execution batch — a group of tasks at the same topological level that
     * can potentially be dispatched together (same pipeline, no inter-dependencies).
     */
    public record ExecutionBatch(List<TaskNode> tasks, boolean needsBarrierBefore) {}

    private final List<TaskNode> allNodes = new ArrayList<>();
    private final Map<ComputeTask, TaskNode> taskToNode = new IdentityHashMap<>();
    private int nextId = 0;

    /**
     * Adds a task to the graph.
     */
    public TaskNode addTask(ComputeTask task) {
        TaskNode node = new TaskNode(task, nextId++);
        allNodes.add(node);
        taskToNode.put(task, node);
        return node;
    }

    /**
     * Resolves all dependency edges after all tasks have been added.
     *
     * @throws IllegalStateException if a dependency references a task not in the graph
     */
    public void resolveDependencies() {
        for (TaskNode node : allNodes) {
            List<ComputeTask> deps = node.task.getDependencies();
            if (deps == null) continue;

            for (ComputeTask dep : deps) {
                TaskNode depNode = taskToNode.get(dep);
                if (depNode == null) {
                    throw new IllegalStateException(
                            "Task depends on unregistered task: " + dep);
                }
                node.dependsOn.add(depNode);
                depNode.dependedBy.add(node);
                node.inDegree.incrementAndGet();
            }
        }
    }

    /**
     * Produces a topologically sorted execution order.
     *
     * @return Batches of tasks, each batch preceded by a barrier if needed
     * @throws IllegalStateException if the graph contains a cycle
     */
    public List<ExecutionBatch> sort() {
        if (allNodes.isEmpty()) return Collections.emptyList();

        // Cycle detection via DFS
        if (hasCycle()) {
            throw new IllegalStateException("Compute task graph contains a cycle");
        }

        // Kahn's algorithm — BFS topological sort by levels
        int[] inDegrees = new int[allNodes.size()];
        for (TaskNode node : allNodes) {
            inDegrees[node.id] = node.inDegree.get();
        }

        Queue<TaskNode> ready = new ArrayDeque<>();
        for (TaskNode node : allNodes) {
            if (inDegrees[node.id] == 0) {
                node.topologicalLevel = 0;
                ready.add(node);
            }
        }

        List<List<TaskNode>> levels = new ArrayList<>();
        int processed = 0;

        while (!ready.isEmpty()) {
            List<TaskNode> currentLevel = new ArrayList<>(ready);
            ready.clear();

            // Sort by priority within the level (highest priority first)
            currentLevel.sort(Comparator.comparingInt(
                    n -> -n.task.getPriority().ordinal()));

            int level = currentLevel.isEmpty() ? 0 : currentLevel.get(0).topologicalLevel;
            while (levels.size() <= level) levels.add(new ArrayList<>());
            levels.get(level).addAll(currentLevel);
            processed += currentLevel.size();

            for (TaskNode node : currentLevel) {
                for (TaskNode downstream : node.dependedBy) {
                    inDegrees[downstream.id]--;
                    if (inDegrees[downstream.id] == 0) {
                        downstream.topologicalLevel = node.topologicalLevel + 1;
                        downstream.needsBarrierBefore = true;
                        ready.add(downstream);
                    }
                }
            }
        }

        if (processed != allNodes.size()) {
            throw new IllegalStateException("Cycle detected: processed " + processed
                    + " of " + allNodes.size() + " tasks");
        }

        // Convert levels to execution batches
        List<ExecutionBatch> batches = new ArrayList<>();
        for (int i = 0; i < levels.size(); i++) {
            batches.add(new ExecutionBatch(levels.get(i), i > 0));
        }

        LOGGER.debug("Task graph sorted: {} tasks → {} batches", allNodes.size(), batches.size());
        return batches;
    }

    /**
     * DFS-based cycle detection.
     */
    private boolean hasCycle() {
        int[] color = new int[allNodes.size()]; // 0=white, 1=gray, 2=black
        for (TaskNode node : allNodes) {
            if (color[node.id] == 0 && dfsCycle(node, color)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfsCycle(TaskNode node, int[] color) {
        color[node.id] = 1; // gray — visiting
        for (TaskNode dep : node.dependedBy) {
            if (color[dep.id] == 1) return true; // back edge → cycle
            if (color[dep.id] == 0 && dfsCycle(dep, color)) return true;
        }
        color[node.id] = 2; // black — done
        return false;
    }

    /**
     * Clears the graph for reuse next frame.
     */
    public void clear() {
        allNodes.clear();
        taskToNode.clear();
        nextId = 0;
    }

    public int getTaskCount() { return allNodes.size(); }
}
