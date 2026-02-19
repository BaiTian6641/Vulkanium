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
    private static final int HEADER_HEIGHT = 50;
    private static final int PROGRESS_BAR_HEIGHT = 12;
    private static final int LOG_TOP_MARGIN = 90;
    private static final int LOG_BOTTOM_MARGIN = 60;
    private static final int LOG_SIDE_MARGIN = 30;
    private static final int LOG_ENTRY_HEIGHT = 16;

    // ── Colors ──
    private static final int COLOR_BG = 0xE0101020;
    private static final int COLOR_HEADER_BG = 0xC0181830;
    private static final int COLOR_PROGRESS_BG = 0xFF333355;
    private static final int COLOR_PROGRESS_FILL = 0xFF4488FF;
    private static final int COLOR_PROGRESS_FILL_DONE = 0xFF44CC66;
    private static final int COLOR_PROGRESS_FILL_ERROR = 0xFFCC4444;
    private static final int COLOR_TEXT_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT_PHASE = 0xFFAAAADD;
    private static final int COLOR_TEXT_DETAIL = 0xFFCCCCCC;
    private static final int COLOR_TEXT_SUCCESS = 0xFF55FF77;
    private static final int COLOR_TEXT_FALLBACK = 0xFFFFAA44;
    private static final int COLOR_TEXT_FAILURE = 0xFFFF5555;
    private static final int COLOR_TEXT_TIMING = 0xFF888899;
    private static final int COLOR_LOG_BG = 0xA0101020;
    private static final int COLOR_LOG_ENTRY_ALT = 0x20FFFFFF;

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

        // ── Header ──
        renderHeader(graphics, progress);

        // ── Progress Bar ──
        renderProgressBar(graphics, progress);

        // ── Current Task ──
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
        // Header background
        graphics.fill(0, 0, this.width, HEADER_HEIGHT, COLOR_HEADER_BG);

        // Title
        String title = loadComplete
                ? (loadSuccess ? "Shaderpack Loaded Successfully" : "Shaderpack Load Failed")
                : "Loading Shaderpack...";
        graphics.drawCenteredString(this.font, title, this.width / 2, 8, COLOR_TEXT_TITLE);

        // Pack name
        graphics.drawCenteredString(this.font, packName, this.width / 2, 22, COLOR_TEXT_PHASE);

        // Phase
        if (progress != null) {
            String phaseText = getPhaseDisplayName(progress.phase);
            graphics.drawCenteredString(this.font, phaseText, this.width / 2, 36, COLOR_TEXT_DETAIL);
        } else if (!loadComplete) {
            graphics.drawCenteredString(this.font, "Initializing...", this.width / 2, 36, COLOR_TEXT_DETAIL);
        }
    }

    private void renderProgressBar(GuiGraphics graphics, LoadProgress progress) {
        int barX = LOG_SIDE_MARGIN;
        int barY = HEADER_HEIGHT + 8;
        int barWidth = this.width - LOG_SIDE_MARGIN * 2;

        // Background
        graphics.fill(barX, barY, barX + barWidth, barY + PROGRESS_BAR_HEIGHT, COLOR_PROGRESS_BG);

        if (progress != null && progress.total > 0) {
            float fraction = (float) progress.completed / progress.total;
            int fillWidth = (int) (barWidth * fraction);

            int fillColor = loadComplete
                    ? (loadSuccess ? COLOR_PROGRESS_FILL_DONE : COLOR_PROGRESS_FILL_ERROR)
                    : COLOR_PROGRESS_FILL;

            // Fill
            graphics.fill(barX, barY, barX + fillWidth, barY + PROGRESS_BAR_HEIGHT, fillColor);

            // Percentage text
            String pctText = String.format("%d/%d (%.0f%%)", progress.completed, progress.total, fraction * 100);
            graphics.drawCenteredString(this.font, pctText,
                    this.width / 2, barY + 2, COLOR_TEXT_TITLE);
        } else if (loadComplete) {
            // Full bar in success/error color
            int fillColor = loadSuccess ? COLOR_PROGRESS_FILL_DONE : COLOR_PROGRESS_FILL_ERROR;
            graphics.fill(barX, barY, barX + barWidth, barY + PROGRESS_BAR_HEIGHT, fillColor);
            graphics.drawCenteredString(this.font,
                    loadSuccess ? "Complete!" : "Failed",
                    this.width / 2, barY + 2, COLOR_TEXT_TITLE);
        } else {
            // Indeterminate: animated bar
            int animOffset = (int) (spinnerAngle * 2) % barWidth;
            int segWidth = barWidth / 4;
            graphics.fill(barX + animOffset, barY,
                    Math.min(barX + animOffset + segWidth, barX + barWidth),
                    barY + PROGRESS_BAR_HEIGHT, COLOR_PROGRESS_FILL);
        }
    }

    private void renderCurrentTask(GuiGraphics graphics, LoadProgress progress) {
        int y = HEADER_HEIGHT + PROGRESS_BAR_HEIGHT + 16;

        // Elapsed time
        long elapsed = System.currentTimeMillis() - startTime;
        String timeStr = String.format("Elapsed: %.1fs", elapsed / 1000.0);
        int timeWidth = this.font.width(timeStr);
        int timeX = this.width - LOG_SIDE_MARGIN - timeWidth;
        graphics.drawString(this.font, timeStr, timeX, y, COLOR_TEXT_TIMING);

        if (progress != null && progress.detail != null) {
            // Animated spinner + current task
            String spinner = loadComplete ? "" : getSpinnerChar() + " ";
            int maxDetailWidth = Math.max(40, timeX - LOG_SIDE_MARGIN - 8);
            String detailText = truncateToWidth(spinner + progress.detail, maxDetailWidth);
            graphics.drawString(this.font, detailText, LOG_SIDE_MARGIN, y, COLOR_TEXT_DETAIL);
        }
    }

    private void renderProgramLog(GuiGraphics graphics) {
        int logX = LOG_SIDE_MARGIN;
        int logY = LOG_TOP_MARGIN;
        int logWidth = this.width - LOG_SIDE_MARGIN * 2;
        int logHeight = this.height - LOG_TOP_MARGIN - LOG_BOTTOM_MARGIN;

        // Log background
        graphics.fill(logX - 2, logY - 2, logX + logWidth + 2, logY + logHeight + 2, COLOR_LOG_BG);

        // Column header
        graphics.drawString(this.font, "Program", logX + 4, logY - 12, COLOR_TEXT_TIMING);
        int timeColumnWidth = 56;
        int statusColumnWidth = Math.min(130, Math.max(90, logWidth / 4));
        int statusX = logX + logWidth - timeColumnWidth - statusColumnWidth;

        String statusHeader = "Status";
        graphics.drawString(this.font, statusHeader, statusX, logY - 12, COLOR_TEXT_TIMING);
        String timeHeader = "Time";
        graphics.drawString(this.font, timeHeader,
            logX + logWidth - this.font.width(timeHeader) - 4, logY - 12, COLOR_TEXT_TIMING);

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

                // Status icon + program name
                String icon;
                int nameColor;
                if (result.stageEvent) {
                    icon = "•";
                    nameColor = COLOR_TEXT_PHASE;
                } else if (result.success) {
                    icon = "\u2713"; // checkmark
                    nameColor = COLOR_TEXT_SUCCESS;
                } else if (result.isFallback) {
                    icon = "\u26A0"; // warning
                    nameColor = COLOR_TEXT_FALLBACK;
                } else {
                    icon = "\u2717"; // cross
                    nameColor = COLOR_TEXT_FAILURE;
                }

                int programMaxWidth = Math.max(40, statusX - (logX + 8));
                String programText = truncateToWidth(icon + " " + result.programName, programMaxWidth);
                graphics.drawString(this.font, programText, logX + 4, entryY + 4, nameColor);

                // Status text
                String statusText;
                int statusColor;
                if (result.stageEvent) {
                    statusText = result.errorMessage != null ? result.errorMessage : "Processing";
                    statusColor = COLOR_TEXT_PHASE;
                } else if (result.success) {
                    statusText = "OK";
                    statusColor = COLOR_TEXT_SUCCESS;
                } else if (result.isFallback) {
                    statusText = "Fallback";
                    statusColor = COLOR_TEXT_FALLBACK;
                } else {
                    // Truncate error
                    statusText = result.errorMessage != null
                            ? (result.errorMessage.length() > 30
                            ? result.errorMessage.substring(0, 30) + "..."
                            : result.errorMessage)
                            : "Failed";
                    statusColor = COLOR_TEXT_FAILURE;
                }
                String clippedStatus = truncateToWidth(statusText, statusColumnWidth - 6);
                graphics.drawString(this.font, clippedStatus, statusX, entryY + 4, statusColor);

                // Compile time
                String timeStr = result.stageEvent ? "..." : result.compileTimeMs + "ms";
                int tw = this.font.width(timeStr);
                graphics.drawString(this.font, timeStr,
                    logX + logWidth - tw - 4, entryY + 4, COLOR_TEXT_TIMING);
            }
        }

        graphics.disableScissor();
    }

    private void renderFooter(GuiGraphics graphics) {
        int footerY = this.height - LOG_BOTTOM_MARGIN + 10;

        if (loadComplete) {
            // Summary line
            int successCount = 0, failCount = 0, fallbackCount = 0;
            synchronized (displayedResults) {
                for (ProgramResult r : displayedResults) {
                    if (r.stageEvent) continue;
                    if (r.success) successCount++;
                    else if (r.isFallback) fallbackCount++;
                    else failCount++;
                }
            }

            String summary = String.format("Results: %d compiled, %d fallback, %d failed  |  Total: %.1fs",
                    successCount, fallbackCount, failCount, loadTimeMs / 1000.0);
            graphics.drawCenteredString(this.font, summary, this.width / 2, footerY, COLOR_TEXT_DETAIL);

            // Click to close hint
            String hint = loadSuccess
                    ? "Click or press ESC to continue"
                    : "Click or press ESC to go back";
            graphics.drawCenteredString(this.font, hint, this.width / 2, footerY + 16, COLOR_TEXT_TIMING);

            if (loadError != null) {
                graphics.drawCenteredString(this.font, "Error: " + loadError,
                        this.width / 2, footerY + 30, COLOR_TEXT_FAILURE);
            }
        } else {
            graphics.drawCenteredString(this.font,
                    "Compiling shaders... Please wait",
                    this.width / 2, footerY, COLOR_TEXT_TIMING);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private String getPhaseDisplayName(LoadProgress.Phase phase) {
        return switch (phase) {
            case PARSING_PROPERTIES -> "Parsing shaders.properties...";
            case DISCOVERING_PROGRAMS -> "Discovering shader programs...";
            case PREPROCESSING -> "Initializing compilation pipeline...";
            case TRANSFORMING -> "Preparing shader compilation...";
            case COMPILING -> "Compiling GLSL → SPIR-V...";
            case CREATING_MODULES -> "Finalizing shader modules...";
            case DONE -> loadSuccess ? "Done!" : "Load Failed";
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
