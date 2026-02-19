package net.vulkanium.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A future representing the result of a GPU compute task.
 *
 * <p>Wraps a {@link CompletableFuture} with GPU-specific semantics:</p>
 * <ul>
 *   <li>Completion happens after GPU execution + readback + result processing</li>
 *   <li>Exceptions indicate GPU errors, out-of-memory, or shader compilation failures</li>
 *   <li>Cancellation attempts to skip the task if it hasn't been dispatched yet</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   ComputeFuture<float[]> future = api.getComputeService().submit(noiseTask);
 *
 *   // Non-blocking callback
 *   future.onComplete(data -> applyNoiseToChunk(data));
 *
 *   // Or blocking wait (careful on render thread!)
 *   float[] result = future.get(100, TimeUnit.MILLISECONDS);
 * }</pre>
 *
 * @param <T> The result type
 */
public class ComputeFuture<T> {

    private final CompletableFuture<T> inner;
    private volatile boolean cancelled = false;

    public ComputeFuture(CompletableFuture<T> inner) {
        this.inner = inner;
    }

    /**
     * Registers a callback to be invoked when the compute task completes.
     * The callback runs on a worker thread, not the render thread.
     *
     * @param callback Consumer receiving the result
     * @return This future for chaining
     */
    public ComputeFuture<T> onComplete(Consumer<T> callback) {
        inner.thenAccept(callback);
        return this;
    }

    /**
     * Registers an error handler.
     *
     * @param handler Consumer receiving the exception
     * @return This future for chaining
     */
    public ComputeFuture<T> onError(Consumer<Throwable> handler) {
        inner.exceptionally(ex -> {
            handler.accept(ex);
            return null;
        });
        return this;
    }

    /**
     * Blocks until the result is available.
     *
     * <p><b>Warning:</b> Do not call from the render thread as it will stall
     * the frame. Use {@link #onComplete(Consumer)} for non-blocking access.</p>
     *
     * @return The result
     * @throws RuntimeException if the task failed
     */
    public T get() {
        try {
            return inner.get();
        } catch (Exception e) {
            throw new RuntimeException("Compute task failed", e);
        }
    }

    /**
     * Blocks until the result is available or timeout expires.
     *
     * @param timeout Maximum wait time
     * @param unit    Time unit
     * @return The result, or null if timed out
     */
    public T get(long timeout, TimeUnit unit) {
        try {
            return inner.get(timeout, unit);
        } catch (java.util.concurrent.TimeoutException e) {
            return null;
        } catch (Exception e) {
            throw new RuntimeException("Compute task failed", e);
        }
    }

    /**
     * Whether the compute task has completed (successfully or with error).
     */
    public boolean isDone() {
        return inner.isDone();
    }

    /**
     * Whether the compute task completed exceptionally.
     */
    public boolean isFailed() {
        return inner.isCompletedExceptionally();
    }

    /**
     * Attempts to cancel the task. If the task hasn't been dispatched
     * to the GPU yet, it will be skipped. If already dispatched, the
     * GPU work will complete but the result will be discarded.
     *
     * @return true if cancellation was requested
     */
    public boolean cancel() {
        cancelled = true;
        return inner.cancel(false);
    }

    /** Whether cancellation was requested. */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Returns the underlying CompletableFuture for advanced composition.
     */
    public CompletableFuture<T> toCompletableFuture() {
        return inner;
    }

    // ── Factory Methods ──

    /**
     * Creates an already-completed future with null result.
     * Used by no-op implementations when Vulkanium is not available.
     */
    @SuppressWarnings("unchecked")
    public static <T> ComputeFuture<T> completedEmpty() {
        return new ComputeFuture<>(CompletableFuture.completedFuture(null));
    }

    /**
     * Creates an already-completed future with the given result.
     */
    public static <T> ComputeFuture<T> completed(T result) {
        return new ComputeFuture<>(CompletableFuture.completedFuture(result));
    }

    /**
     * Creates an already-failed future.
     */
    public static <T> ComputeFuture<T> failed(Throwable cause) {
        return new ComputeFuture<>(CompletableFuture.failedFuture(cause));
    }
}
