// src/main/java/cc/scaenacraft/rebootvote/RebootStatsStore.java
package cc.scaenacraft.rebootvote;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

/**
 * Persists simple reboot timing stats across restarts.
 *
 * How it works:
 * - Right before we trigger a reboot, we write a {@code pending_reboot_started_ms} timestamp.
 * - On next plugin enable, if that timestamp exists, we compute the elapsed time to "now"
 *   and treat that as the last reboot duration.
 *
 * This is an approximation of "time until server is back" (process stop + process start + plugin enable).
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
                // Running average
                double total = (this.avgDurationMs < 0 ? 0.0 : this.avgDurationMs * this.samples);
                this.samples = Math.max(0L, this.samples) + 1L;
                this.avgDurationMs = (total + elapsed) / this.samples;
            }

            // Always clear the pending marker so it doesn't poison future boots.
            yml.set("pending_reboot_started_ms", null);
            yml.set("last_reboot_duration_ms", this.lastDurationMs);
            yml.set("avg_reboot_duration_ms", this.avgDurationMs);
            yml.set("samples", this.samples);
            save(yml);
        }
    }

    /**
     * Call immediately before triggering a reboot/restart.
     */
    public void markRebootInitiated() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        yml.set("pending_reboot_started_ms", System.currentTimeMillis());
        // Keep previous stats; just write the marker.
        if (lastDurationMs >= 0) yml.set("last_reboot_duration_ms", lastDurationMs);
        if (avgDurationMs >= 0) yml.set("avg_reboot_duration_ms", avgDurationMs);
        yml.set("samples", samples);
        save(yml);
    }

    public String lastSecondsDisplay() {
        if (lastDurationMs <= 0) return "unknown";
        return formatSeconds(lastDurationMs);
    }

    public String avgSecondsDisplay() {
        if (avgDurationMs <= 0) return "unknown";
        return String.format(java.util.Locale.ROOT, "%.1f", avgDurationMs / 1000.0);
    }

    private String formatSeconds(long ms) {
        return String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0);
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
