// src/main/java/cc/scaenacraft/rebootvote/RebootStatsStore.java
package cc.scaenacraft.rebootvote;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Persists reboot timing stats across restarts.
 *
 * Timing model (current project scope):
 * - The reboot "commit" happens when the countdown reaches 0 OR all players vote OK.
 * - The reboot-duration stopwatch STARTS at shutdown start (plugin onDisable),
 *   and ends at next boot when the plugin enables again.
 *
 * This approximates the real downtime a player experiences.
 */
public final class RebootStatsStore {

    private static final String FILE_NAME = "reboot-stats.yml";

    // If a pending timestamp is older than this, assume it is stale/corrupt and ignore it.
    private static final long MAX_REASONABLE_REBOOT_MS = 10L * 60L * 1000L; // 10 minutes

    private final JavaPlugin plugin;
    private final File file;

    private long lastDurationMs = -1L;
    private double avgDurationMs = -1.0;
    private long samples = 0L;

    public RebootStatsStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    /**
     * Call during onEnable to load and, if present, finalize a pending reboot measurement.
     */
    public void loadAndFinalizePendingIfPresent() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);

        this.lastDurationMs = yml.getLong("last_reboot_duration_ms", -1L);
        this.avgDurationMs = yml.getDouble("avg_reboot_duration_ms", -1.0);
        this.samples = yml.getLong("samples", 0L);

        long pendingStarted = yml.getLong("pending_reboot_started_ms", -1L);
        if (pendingStarted > 0) {
            long now = System.currentTimeMillis();
            long elapsed = now - pendingStarted;

            // Sanity check. If it looks unreasonable, treat it as stale.
            if (elapsed > 0 && elapsed <= MAX_REASONABLE_REBOOT_MS) {
                this.lastDurationMs = elapsed;

                double total = (this.avgDurationMs < 0 ? 0.0 : this.avgDurationMs * this.samples);
                this.samples = Math.max(0L, this.samples) + 1L;
                this.avgDurationMs = (total + elapsed) / this.samples;
            }

            // Always clear pending marker so it can't poison future boots.
            yml.set("pending_reboot_started_ms", null);
            yml.set("last_reboot_duration_ms", this.lastDurationMs);
            yml.set("avg_reboot_duration_ms", this.avgDurationMs);
            yml.set("samples", this.samples);
            save(yml);
        }
    }

    /**
     * Start (persist) the reboot-duration stopwatch at a specific timestamp.
     * Intended call site: plugin onDisable(), right as shutdown begins.
     */
    public void markRebootInitiatedAt(long startedAtMs) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        yml.set("pending_reboot_started_ms", startedAtMs);

        // Preserve existing stats.
        if (lastDurationMs >= 0) yml.set("last_reboot_duration_ms", lastDurationMs);
        if (avgDurationMs >= 0) yml.set("avg_reboot_duration_ms", avgDurationMs);
        yml.set("samples", samples);

        save(yml);
    }

    /**
     * Clears reboot timing history and any pending measurement.
     */
    public void resetTimingStats() {
        this.lastDurationMs = -1L;
        this.avgDurationMs = -1.0;
        this.samples = 0L;

        YamlConfiguration yml = new YamlConfiguration();
        yml.set("pending_reboot_started_ms", null);
        yml.set("last_reboot_duration_ms", null);
        yml.set("avg_reboot_duration_ms", null);
        yml.set("samples", 0L);
        save(yml);
    }

    public String lastSecondsDisplay() {
        if (lastDurationMs <= 0) return "unknown";
        return formatSeconds(lastDurationMs);
    }

    public String avgSecondsDisplay() {
        if (avgDurationMs <= 0) return "unknown";
        return String.format(Locale.ROOT, "%.1f", avgDurationMs / 1000.0);
    }

    private String formatSeconds(long ms) {
        return String.format(Locale.ROOT, "%.1f", ms / 1000.0);
    }

    private void save(YamlConfiguration yml) {
        try {
            if (!plugin.getDataFolder().exists()) {
                //noinspection ResultOfMethodCallIgnored
                plugin.getDataFolder().mkdirs();
            }
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save reboot stats: " + ex.getMessage());
        }
    }
}
