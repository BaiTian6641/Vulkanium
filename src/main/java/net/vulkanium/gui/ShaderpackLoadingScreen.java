package net.vulkanium.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vulkanium.Vulkanium;
import net.vulkanium.render.RenderMode;
import net.vulkanium.shaderpack.ShaderpackManager;
import net.vulkanium.shaderpack.VulkanShaderpackPipeline.LoadProgress;
import net.vulkanium.shaderpack.VulkanShaderpackPipeline.ProgramResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Progress screen displayed during shaderpack loading/compilation.
 *
 * <p>Shows real-time information about the loading process:</p>
 * <ul>
 *   <li>Current phase (parsing, discovering, preprocessing, compiling)</li>
 *   <li>Overall progress bar (N/total programs)</li>
 *   <li>Current shader file being processed</li>
 *   <li>Scrollable log of processed files with success/failure status</li>
 *   <li>Compilation timing per shader</li>
 *   <li>Error details for failed programs</li>
 * </ul>
 */
public class ShaderpackLoadingScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/LoadingScreen");

    // ── Layout Constants ──
    private static final int HEADER_HEIGHT = 44;
    private static final int STAGE_BAR_HEIGHT = 32;
    private static final int PROGRESS_BAR_HEIGHT = 10;
    private static final int LOG_TOP_MARGIN = 112;
    private static final int LOG_BOTTOM_MARGIN = 52;
    private static final int LOG_SIDE_MARGIN = 24;
    private static final int LOG_ENTRY_HEIGHT = 14;

    // ── Colors ──
    private static final int COLOR_BG = 0xF0101018;
    private static final int COLOR_HEADER_BG = 0xD0141428;
    private static final int COLOR_STAGE_BG = 0xB0181830;
    private static final int COLOR_PROGRESS_BG = 0xFF2A2A44;
    private static final int COLOR_PROGRESS_FILL = 0xFF5588FF;
    private static final int COLOR_PROGRESS_FILL_DONE = 0xFF44DD66;
    private static final int COLOR_PROGRESS_FILL_ERROR = 0xFFDD4444;
    private static final int COLOR_TEXT_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT_SUBTITLE = 0xFFBBBBDD;
    private static final int COLOR_TEXT_PHASE = 0xFFAAAADD;
    private static final int COLOR_TEXT_DETAIL = 0xFFCCCCCC;
    private static final int COLOR_TEXT_SUCCESS = 0xFF55FF77;
    private static final int COLOR_TEXT_FALLBACK = 0xFFFFAA44;
    private static final int COLOR_TEXT_FAILURE = 0xFFFF5555;
    private static final int COLOR_TEXT_TIMING = 0xFF777799;
    private static final int COLOR_TEXT_DIM = 0xFF555577;
    private static final int COLOR_LOG_BG = 0xA00E0E1A;
    private static final int COLOR_LOG_ENTRY_ALT = 0x18FFFFFF;
    private static final int COLOR_STAGE_DONE = 0xFF44DD66;
    private static final int COLOR_STAGE_ACTIVE = 0xFF5588FF;
    private static final int COLOR_STAGE_PENDING = 0xFF444466;
    private static final int COLOR_STAGE_LINE = 0xFF333355;

    /** User-friendly stage names displayed in the visual pipeline. */
    private static final String[] STAGE_LABELS = {
        "Read Config", "Scan Files", "Prepare", "Convert", "Compile", "Build", "Done"
    };

    // ── State ──
    private final Screen parentScreen;
    private final String packName;
    private final ShaderpackManager manager;

    /** Current progress snapshot (updated from loading thread) */
    private final AtomicReference<LoadProgress> currentProgress = new AtomicReference<>();

    /** Whether the load has finished */
    private volatile boolean loadComplete = false;
    private volatile boolean loadSuccess = false;
    private volatile String loadError = null;
    private volatile long loadTimeMs = 0;

    /** Scrollable log */
    private final List<ProgramResult> displayedResults = new ArrayList<>();
    private int logScrollOffset = 0;

    /** Guard against multiple concurrent loads (init() can be called multiple times) */
    private volatile boolean loadStarted = false;

    /** Animation */
    private float spinnerAngle = 0;
    private long startTime;

    public ShaderpackLoadingScreen(Screen parent, String packName) {
        super(Component.literal("Loading Shaderpack"));
        this.parentScreen = parent;
        this.packName = packName;
        this.manager = Vulkanium.getShaderpackManager();
    }

    @Override
    protected void init() {
        super.init();

        // Guard: init() may be called multiple times (resize, re-display).
        // Only start the load once.
        if (loadStarted) {
            return;
        }
        loadStarted = true;

        startTime = System.currentTimeMillis();

        // Start loading on a background thread
        CompletableFuture.runAsync(() -> {
            try {
                // Auto-unload: switch to vanilla mode while loading new pack.
                // This prevents the old pack's pipeline from being used during
                // the transition and avoids visual artifacts or crashes.
                if (manager != null && manager.isPackLoaded()) {
                    LOGGER.info("Auto-unloading current shaderpack before loading '{}'", packName);
                    Minecraft.getInstance().execute(() -> Vulkanium.setRenderMode(RenderMode.VANILLA));
                }

                boolean success = manager.loadPackWithProgress(packName, progress -> {
                    currentProgress.set(progress);
                    // Copy results for display on render thread
                    synchronized (displayedResults) {
                        displayedResults.clear();
                        displayedResults.addAll(progress.results);
                    }
                });

                loadTimeMs = System.currentTimeMillis() - startTime;
                loadSuccess = success;
                loadComplete = true;

                if (success) {
                    Vulkanium.setRenderMode(RenderMode.SHADERPACK);
                    ShaderpackSelectionScreen.persistShaderpackSelection(packName, true);
                    Minecraft.getInstance().execute(() -> {
                        if (Minecraft.getInstance().levelRenderer != null) {
                            Minecraft.getInstance().levelRenderer.allChanged();
                        }
                        // Iris-style behavior: force a full resource reload so MC
                        // rebuilds render state and displays the reload progress UI.
                        Minecraft.getInstance().reloadResourcePacks();
                    });
                    LOGGER.info("Shaderpack '{}' loaded via progress screen in {}ms", packName, loadTimeMs);
                } else {
                    LOGGER.warn("Shaderpack '{}' failed to load ({}ms)", packName, loadTimeMs);
                }
            } catch (Exception e) {
                loadTimeMs = System.currentTimeMillis() - startTime;
                loadError = e.getClass().getSimpleName() + ": " + e.getMessage();
                loadComplete = true;
                LOGGER.error("Exception during shaderpack load of '{}'", packName, e);
            }
        });
    }

    @Override
    public void tick() {
        super.tick();

        // Auto-return to parent after completion (with a short delay so user can see results)
        if (loadComplete && (System.currentTimeMillis() - startTime - loadTimeMs) > 2000) {
            // Only auto-close on success; keep error screen open
            if (loadSuccess) {
                this.minecraft.setScreen(parentScreen);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        synchronized (displayedResults) {
            int maxScroll = Math.max(0, displayedResults.size() * LOG_ENTRY_HEIGHT
                    - (this.height - LOG_TOP_MARGIN - LOG_BOTTOM_MARGIN));
            logScrollOffset = (int) Math.max(0, Math.min(logScrollOffset - delta * LOG_ENTRY_HEIGHT * 2, maxScroll));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC goes back after loading is complete
        if (keyCode == 256 && loadComplete) { // GLFW_KEY_ESCAPE
            this.minecraft.setScreen(parentScreen);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Click anywhere after completion to close
        if (loadComplete) {
            this.minecraft.setScreen(parentScreen);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Background
        graphics.fill(0, 0, this.width, this.height, COLOR_BG);

        LoadProgress progress = currentProgress.get();

        // ── Header (title + pack name) ──
        renderHeader(graphics, progress);

        // ── Visual Stage Pipeline ──
        renderStagePipeline(graphics, progress);

        // ── Progress Bar ──
        renderProgressBar(graphics, progress);

        // ── Current Task + Stats ──
        renderCurrentTask(graphics, progress);

        // ── Program Log ──
        renderProgramLog(graphics);

        // ── Footer ──
        renderFooter(graphics);

        // Spinner animation
        spinnerAngle += partialTick * 8;

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Render Sections
    // ═══════════════════════════════════════════════════════════════

    private void renderHeader(GuiGraphics graphics, LoadProgress progress) {
        graphics.fill(0, 0, this.width, HEADER_HEIGHT, COLOR_HEADER_BG);

        // Title
        String title = loadComplete
                ? (loadSuccess ? "\u2713 Shaderpack Ready" : "\u2717 Loading Failed")
                : "Loading Shaderpack";
        int titleColor = loadComplete ? (loadSuccess ? COLOR_TEXT_SUCCESS : COLOR_TEXT_FAILURE) : COLOR_TEXT_TITLE;
        graphics.drawCenteredString(this.font, title, this.width / 2, 8, titleColor);

        // Pack name (subtitle)
        graphics.drawCenteredString(this.font, packName, this.width / 2, 24, COLOR_TEXT_SUBTITLE);
    }

    /**
     * Renders a horizontal stage pipeline with dots and connecting lines.
     * Each stage shows as: done (green dot), active (blue pulsing), or pending (grey dot).
     */
    private void renderStagePipeline(GuiGraphics graphics, LoadProgress progress) {
        int barY = HEADER_HEIGHT;
        graphics.fill(0, barY, this.width, barY + STAGE_BAR_HEIGHT, COLOR_STAGE_BG);

        int stageCount = STAGE_LABELS.length;
        int currentStageIdx = progress != null ? phaseToStageIndex(progress.phase) : 0;
        if (loadComplete) currentStageIdx = stageCount - 1;

        int totalWidth = this.width - LOG_SIDE_MARGIN * 2;
        int dotRadius = 4;
        int startX = LOG_SIDE_MARGIN + 20;
        int endX = this.width - LOG_SIDE_MARGIN - 20;
        int segmentWidth = stageCount > 1 ? (endX - startX) / (stageCount - 1) : 0;
        int dotY = barY + STAGE_BAR_HEIGHT / 2 - 2;

        // Draw connecting lines first
        for (int i = 0; i < stageCount - 1; i++) {
            int x1 = startX + i * segmentWidth + dotRadius;
            int x2 = startX + (i + 1) * segmentWidth - dotRadius;
            int lineColor = i < currentStageIdx ? COLOR_STAGE_DONE : COLOR_STAGE_LINE;
            graphics.fill(x1, dotY + dotRadius - 1, x2, dotY + dotRadius + 1, lineColor);
        }

        // Draw stage dots and labels
        for (int i = 0; i < stageCount; i++) {
            int cx = startX + i * segmentWidth;
            int dotColor;
            if (i < currentStageIdx) {
                dotColor = COLOR_STAGE_DONE;
            } else if (i == currentStageIdx && !loadComplete) {
                // Pulsing animation for active stage
                float pulse = (float) (0.7 + 0.3 * Math.sin(spinnerAngle * 0.5));
                int alpha = (int) (pulse * 255);
                dotColor = (alpha << 24) | (COLOR_STAGE_ACTIVE & 0x00FFFFFF);
            } else if (i == currentStageIdx && loadComplete) {
                dotColor = loadSuccess ? COLOR_STAGE_DONE : COLOR_TEXT_FAILURE;
            } else {
                dotColor = COLOR_STAGE_PENDING;
            }

            // Draw dot (filled circle approximation as a small square with rounded look)
            graphics.fill(cx - dotRadius, dotY, cx + dotRadius, dotY + dotRadius * 2, dotColor);

            // Label below dot
            String label = STAGE_LABELS[i];
            int labelWidth = this.font.width(label);
            int labelColor = i <= currentStageIdx ? COLOR_TEXT_DETAIL : COLOR_TEXT_DIM;
            // Only draw label if it fits without overlapping neighbors
            if (labelWidth < segmentWidth - 4 || i == 0 || i == stageCount - 1) {
                graphics.drawString(this.font, label,
                        cx - labelWidth / 2, dotY + dotRadius * 2 + 3, labelColor, false);
            }
        }
    }

    private void renderProgressBar(GuiGraphics graphics, LoadProgress progress) {
        int barX = LOG_SIDE_MARGIN;
        int barY = HEADER_HEIGHT + STAGE_BAR_HEIGHT + 4;
        int barWidth = this.width - LOG_SIDE_MARGIN * 2;

        // Background
        graphics.fill(barX, barY, barX + barWidth, barY + PROGRESS_BAR_HEIGHT, COLOR_PROGRESS_BG);

        if (progress != null && progress.total > 0) {
            float fraction = (float) progress.completed / progress.total;
            int fillWidth = (int) (barWidth * fraction);

            int fillColor = loadComplete
                    ? (loadSuccess ? COLOR_PROGRESS_FILL_DONE : COLOR_PROGRESS_FILL_ERROR)
                    : COLOR_PROGRESS_FILL;

            graphics.fill(barX, barY, barX + fillWidth, barY + PROGRESS_BAR_HEIGHT, fillColor);

            // Context-rich progress text
            String pctText = getProgressDescription(progress, fraction);
            graphics.drawCenteredString(this.font, pctText,
                    this.width / 2, barY - 1, COLOR_TEXT_TITLE);
        } else if (loadComplete) {
            int fillColor = loadSuccess ? COLOR_PROGRESS_FILL_DONE : COLOR_PROGRESS_FILL_ERROR;
            graphics.fill(barX, barY, barX + barWidth, barY + PROGRESS_BAR_HEIGHT, fillColor);
        } else {
            // Indeterminate: animated sweep
            int animOffset = (int) (spinnerAngle * 3) % (barWidth + barWidth / 3) - barWidth / 3;
            int segWidth = barWidth / 3;
            int segStart = Math.max(barX, barX + animOffset);
            int segEnd = Math.min(barX + barWidth, barX + animOffset + segWidth);
            if (segEnd > segStart) {
                graphics.fill(segStart, barY, segEnd, barY + PROGRESS_BAR_HEIGHT, COLOR_PROGRESS_FILL);
            }
        }
    }

    private void renderCurrentTask(GuiGraphics graphics, LoadProgress progress) {
        int y = HEADER_HEIGHT + STAGE_BAR_HEIGHT + PROGRESS_BAR_HEIGHT + 10;

        // Elapsed time (right side)
        long elapsed = System.currentTimeMillis() - startTime;
        String timeStr = String.format("%.1fs", elapsed / 1000.0);
        int timeWidth = this.font.width(timeStr);
        int timeX = this.width - LOG_SIDE_MARGIN - timeWidth;
        graphics.drawString(this.font, timeStr, timeX, y, COLOR_TEXT_TIMING, false);

        // Real-time summary stats (left side)
        int successCount = 0, failCount = 0, fallbackCount = 0;
        synchronized (displayedResults) {
            for (ProgramResult r : displayedResults) {
                if (r.stageEvent) continue;
                if (r.success) successCount++;
                else if (r.isFallback) fallbackCount++;
                else failCount++;
            }
        }
        if (successCount > 0 || failCount > 0 || fallbackCount > 0) {
            String stats = "\u2713 " + successCount;
            if (fallbackCount > 0) stats += "  \u26A0 " + fallbackCount;
            if (failCount > 0) stats += "  \u2717 " + failCount;
            graphics.drawString(this.font, stats, LOG_SIDE_MARGIN, y, COLOR_TEXT_DETAIL, false);
        }

        // Current task description (center)
        if (progress != null && progress.detail != null && !loadComplete) {
            String spinner = getSpinnerChar() + " ";
            String detail = getPhaseDisplayName(progress.phase);
            if (progress.currentProgram != null && !progress.currentProgram.isEmpty()) {
                detail = detail + " \u2014 " + progress.currentProgram;
            }
            int maxWidth = Math.max(40, timeX - LOG_SIDE_MARGIN - 80);
            String detailText = truncateToWidth(spinner + detail, maxWidth);
            int detailX = LOG_SIDE_MARGIN + 70;
            graphics.drawString(this.font, detailText, detailX, y, COLOR_TEXT_PHASE, false);
        }
    }

    private void renderProgramLog(GuiGraphics graphics) {
        int logX = LOG_SIDE_MARGIN;
        int logY = LOG_TOP_MARGIN;
        int logWidth = this.width - LOG_SIDE_MARGIN * 2;
        int logHeight = this.height - LOG_TOP_MARGIN - LOG_BOTTOM_MARGIN;

        // Log background with subtle border
        graphics.fill(logX - 1, logY - 1, logX + logWidth + 1, logY + logHeight + 1, COLOR_STAGE_LINE);
        graphics.fill(logX, logY, logX + logWidth, logY + logHeight, COLOR_LOG_BG);

        // Column headers (above the log area)
        int timeColumnWidth = 50;
        int statusColumnWidth = Math.min(100, Math.max(70, logWidth / 5));
        int statusX = logX + logWidth - timeColumnWidth - statusColumnWidth;
        int timeHeaderX = logX + logWidth - timeColumnWidth;

        graphics.drawString(this.font, "Shader Program", logX + 4, logY - 11, COLOR_TEXT_DIM, false);
        graphics.drawString(this.font, "Status", statusX, logY - 11, COLOR_TEXT_DIM, false);
        graphics.drawString(this.font, "Time", timeHeaderX, logY - 11, COLOR_TEXT_DIM, false);

        // Enable scissor for scrolling
        graphics.enableScissor(logX, logY, logX + logWidth, logY + logHeight);

        synchronized (displayedResults) {
            for (int i = 0; i < displayedResults.size(); i++) {
                int entryY = logY + i * LOG_ENTRY_HEIGHT - logScrollOffset;
                if (entryY + LOG_ENTRY_HEIGHT < logY || entryY > logY + logHeight) continue;

                ProgramResult result = displayedResults.get(i);

                // Alternating row background
                if (i % 2 == 1) {
                    graphics.fill(logX, entryY, logX + logWidth, entryY + LOG_ENTRY_HEIGHT, COLOR_LOG_ENTRY_ALT);
                }

                // Icon + program name
                String icon;
                int nameColor;
                if (result.stageEvent) {
                    icon = "\u25B6"; // right-pointing triangle for stage header
                    nameColor = COLOR_TEXT_PHASE;
                } else if (result.success) {
                    icon = "\u2713"; // checkmark
                    nameColor = COLOR_TEXT_SUCCESS;
                } else if (result.isFallback) {
                    icon = "\u26A0"; // warning triangle
                    nameColor = COLOR_TEXT_FALLBACK;
                } else {
                    icon = "\u2717"; // cross
                    nameColor = COLOR_TEXT_FAILURE;
                }

                int programMaxWidth = Math.max(40, statusX - (logX + 8));
                String programText = truncateToWidth(icon + " " + result.programName, programMaxWidth);
                graphics.drawString(this.font, programText, logX + 4, entryY + 3, nameColor, false);

                // Status text
                if (!result.stageEvent) {
                    String statusText;
                    int statusColor;
                    if (result.success) {
                        statusText = result.fromCache ? "cached" : "ok";
                        statusColor = COLOR_TEXT_SUCCESS;
                    } else if (result.isFallback) {
                        statusText = "fallback";
                        statusColor = COLOR_TEXT_FALLBACK;
                    } else {
                        statusText = result.errorMessage != null
                                ? truncateToWidth(result.errorMessage, statusColumnWidth - 4)
                                : "failed";
                        statusColor = COLOR_TEXT_FAILURE;
                    }
                    graphics.drawString(this.font, statusText, statusX, entryY + 3, statusColor, false);

                    // Compile time
                    String timeStr = result.compileTimeMs + "ms";
                    int tw = this.font.width(timeStr);
                    graphics.drawString(this.font, timeStr,
                        logX + logWidth - tw - 4, entryY + 3, COLOR_TEXT_TIMING, false);
                }
            }
        }

        graphics.disableScissor();

        // Auto-scroll to bottom during loading
        if (!loadComplete) {
            synchronized (displayedResults) {
                int contentHeight = displayedResults.size() * LOG_ENTRY_HEIGHT;
                if (contentHeight > logHeight) {
                    logScrollOffset = contentHeight - logHeight;
                }
            }
        }
    }

    private void renderFooter(GuiGraphics graphics) {
        int footerY = this.height - LOG_BOTTOM_MARGIN + 8;

        if (loadComplete) {
            // Summary
            int successCount = 0, failCount = 0, fallbackCount = 0;
            synchronized (displayedResults) {
                for (ProgramResult r : displayedResults) {
                    if (r.stageEvent) continue;
                    if (r.success) successCount++;
                    else if (r.isFallback) fallbackCount++;
                    else failCount++;
                }
            }

            String summary = String.format("\u2713 %d compiled    \u26A0 %d fallback    \u2717 %d failed    |    %.1fs total",
                    successCount, fallbackCount, failCount, loadTimeMs / 1000.0);
            graphics.drawCenteredString(this.font, summary, this.width / 2, footerY, COLOR_TEXT_DETAIL);

            // Hint
            String hint = loadSuccess
                    ? "Click or ESC to continue"
                    : "Click or ESC to go back";
            graphics.drawCenteredString(this.font, hint, this.width / 2, footerY + 14, COLOR_TEXT_DIM);

            if (loadError != null) {
                graphics.drawCenteredString(this.font, loadError,
                        this.width / 2, footerY + 28, COLOR_TEXT_FAILURE);
            }
        } else {
            graphics.drawCenteredString(this.font,
                    "Please wait while shaders are compiled for your GPU",
                    this.width / 2, footerY, COLOR_TEXT_DIM);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private String getPhaseDisplayName(LoadProgress.Phase phase) {
        return switch (phase) {
            case PARSING_PROPERTIES -> "Reading shader configuration";
            case DISCOVERING_PROGRAMS -> "Scanning shader files";
            case PREPROCESSING -> "Preparing shaders";
            case TRANSFORMING -> "Converting shader code";
            case COMPILING -> "Compiling for GPU";
            case CREATING_MODULES -> "Building GPU pipeline";
            case DONE -> loadSuccess ? "Ready!" : "Failed";
        };
    }

    /**
     * Maps a LoadProgress.Phase to the visual stage pipeline index.
     */
    private int phaseToStageIndex(LoadProgress.Phase phase) {
        return switch (phase) {
            case PARSING_PROPERTIES -> 0;
            case DISCOVERING_PROGRAMS -> 1;
            case PREPROCESSING -> 2;
            case TRANSFORMING -> 3;
            case COMPILING -> 4;
            case CREATING_MODULES -> 5;
            case DONE -> 6;
        };
    }

    /**
     * Returns a context-rich progress description instead of just "N/M (XX%)".
     */
    private String getProgressDescription(LoadProgress progress, float fraction) {
        return switch (progress.phase) {
            case COMPILING -> String.format("Compiling %d of %d shaders (%.0f%%)",
                    progress.completed, progress.total, fraction * 100);
            case CREATING_MODULES -> String.format("Building module %d of %d (%.0f%%)",
                    progress.completed, progress.total, fraction * 100);
            default -> String.format("%d / %d (%.0f%%)",
                    progress.completed, progress.total, fraction * 100);
        };
    }

    private String getSpinnerChar() {
        int idx = ((int) (spinnerAngle / 4)) % 4;
        return switch (idx) {
            case 0 -> "|";
            case 1 -> "/";
            case 2 -> "-";
            case 3 -> "\\";
            default -> "|";
        };
    }

    private String truncateToWidth(String text, int maxWidth) {
        if (text == null || text.isEmpty() || this.font.width(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        int targetWidth = Math.max(0, maxWidth - this.font.width(ellipsis));
        int end = text.length();
        while (end > 0 && this.font.width(text.substring(0, end)) > targetWidth) {
            end--;
        }
        if (end <= 0) {
            return ellipsis;
        }
        return text.substring(0, end) + ellipsis;
    }
}
