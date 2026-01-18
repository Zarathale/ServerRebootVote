// src/main/java/cc/scaenacraft/rebootvote/RebootVotePlugin.java
package cc.scaenacraft.rebootvote;

import cc.scaenacraft.rebootvote.commands.RebootVoteCommand;
import cc.scaenacraft.rebootvote.commands.RebootVoteTabCompleter;
import cc.scaenacraft.rebootvote.listeners.ChatListener;
import cc.scaenacraft.rebootvote.listeners.JoinQuitListener;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class RebootVotePlugin extends JavaPlugin {

    private MessageService messages;
    private TemplatePools pools;
    private VoteKeywords voteKeywords;

    private RebootStatsStore rebootStats;
    private RebootSession session;

    /**
     * Set to true when a reboot is committed (countdown reached 0 or all players voted OK).
     * The reboot-duration stopwatch is started in onDisable(), aligning timing to
     * actual downtime (shutdown start -> plugin enable).
     */
    private volatile boolean rebootCommittedThisCycle = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // New boot cycle
        rebootCommittedThisCycle = false;

        reloadAllConfigState();

        // Load persisted reboot timing stats (and finalize any pending measurement).
        rebootStats = new RebootStatsStore(this);
        rebootStats.loadAndFinalizePendingIfPresent();

        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new JoinQuitListener(this), this);

        var cmd = getCommand("rebootvote");
        if (cmd != null) {
            cmd.setExecutor(new RebootVoteCommand(this));
            cmd.setTabCompleter(new RebootVoteTabCompleter());
        }

        getLogger().info("RebootVote enabled.");
    }

    @Override
    public void onDisable() {
        // If this shutdown is due to a committed RebootVote reboot,
        // start the reboot-duration stopwatch now.
        if (rebootCommittedThisCycle) {
            try {
                if (rebootStats == null) rebootStats = new RebootStatsStore(this);
                rebootStats.markRebootInitiatedAt(System.currentTimeMillis());
            } catch (Exception ignored) {
                // Never block shutdown
            }
        }

        // Silent cleanup: no broadcasts during shutdown.
        if (session != null && session.isActive()) {
            try {
                session.endSilently();
            } catch (Exception ignored) {
                // Never block shutdown
            }
        }

        getLogger().info("RebootVote disabled.");
    }

    /* -------------------------------------------------------------------------
     * Permissions & access
     * ---------------------------------------------------------------------- */

    public boolean isSenderAllowed(CommandSender sender) {
        if (!(sender instanceof Player)) return true; // console always allowed
        return sender.hasPermission("rebootvote.admin");
    }

    public VoteKeywords getVoteKeywords() {
        return voteKeywords;
    }

    /* -------------------------------------------------------------------------
     * Session lifecycle
     * ---------------------------------------------------------------------- */

    /**
     * Called by RebootSession at the moment the reboot is committed
     * (countdown reached 0 OR all players voted OK).
     */
    public void noteRebootCommitted() {
        rebootCommittedThisCycle = true;
    }

    public void commandStart(CommandSender sender, int seconds) {
        if (session != null && session.isActive()) {
            sender.sendMessage("RebootVote: a session is already running. Use /rebootvote status or /rebootvote cancel.");
            return;
        }

        reloadAllConfigState();
        rebootCommittedThisCycle = false;

        long cooldown = getConfig().getLong("anti_spam.hold_broadcast_cooldown_seconds", 3L);
        int statusUpdateInterval = getConfig().getInt("status-update-interval", 15);
        int holdReminderInterval = getConfig().getInt("hold-reminder-interval", 60);

        session = new RebootSession(
                this,
                messages,
                pools,
                seconds,
                cooldown,
                statusUpdateInterval,
                holdReminderInterval
        );
        session.start();

        sender.sendMessage("RebootVote: started (" + seconds + "s).");
    }

    public void commandCancel(CommandSender sender) {
        if (session == null || !session.isActive()) {
            sender.sendMessage("RebootVote: no active session to cancel.");
            return;
        }
        session.cancel(sender);
        sender.sendMessage("RebootVote: canceled.");
    }

    public void commandStatus(CommandSender sender) {
        if (session == null || !session.isActive()) {
            sender.sendMessage("RebootVote: no session running.");
            return;
        }
        session.status(sender);
    }

    public void commandForce(CommandSender sender) {
        reloadAllConfigState();
        rebootCommittedThisCycle = true;

        long cooldown = getConfig().getLong("anti_spam.hold_broadcast_cooldown_seconds", 3L);
        int statusUpdateInterval = getConfig().getInt("status-update-interval", 15);
        int holdReminderInterval = getConfig().getInt("hold-reminder-interval", 60);

        if (session != null && session.isActive()) {
            session.forceReboot(sender);
            sender.sendMessage("RebootVote: force reboot initiated.");
            return;
        }

        RebootSession ephemeral = new RebootSession(
                this,
                messages,
                pools,
                1,
                cooldown,
                statusUpdateInterval,
                holdReminderInterval
        );
        ephemeral.forceReboot(sender);
        sender.sendMessage("RebootVote: force reboot initiated (no session).");
    }

    public void commandReload(CommandSender sender) {
        reloadAllConfigState();
        sender.sendMessage("RebootVote: reloaded config.");
    }

    public void commandStatsReset(CommandSender sender) {
        if (!isSenderAllowed(sender)) {
            sender.sendMessage("RebootVote: you do not have permission.");
            return;
        }

        if (rebootStats == null) rebootStats = new RebootStatsStore(this);
        rebootStats.resetTimingStats();

        rebootCommittedThisCycle = false;
        sender.sendMessage("RebootVote: reboot timing stats reset.");
    }

    /* -------------------------------------------------------------------------
     * Listener entry points
     * ---------------------------------------------------------------------- */

    public void handleVote(Player player, Vote vote) {
        if (session == null || !session.isActive()) return;
        session.onVote(player, vote);
    }

    public void handleJoin(Player player) {
        if (session == null || !session.isActive()) return;
        session.onPlayerJoin(player);
    }

    public void handleQuit(Player player) {
        if (session == null || !session.isActive()) return;
        session.onPlayerQuit(player);
    }

    /* -------------------------------------------------------------------------
     * Config / reboot execution
     * ---------------------------------------------------------------------- */

    private void reloadAllConfigState() {
        reloadConfig();

        if (messages == null) messages = new MessageService(this);
        messages.reloadPalette();

        pools = new TemplatePools(getConfig());
        voteKeywords = new VoteKeywords(getConfig());

        messages.validateTemplates(pools);
    }

    /**
     * Executes the configured reboot action.
     */
    public void executeRebootAction() {
        String mode = getConfig().getString("reboot.mode", "SHUTDOWN");

        if ("COMMAND".equalsIgnoreCase(mode)) {
            String cmd = getConfig().getString("reboot.command", "restart");
            if (cmd != null && !cmd.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                return;
            }
        }

        Bukkit.shutdown();
    }

    /* -------------------------------------------------------------------------
     * Placeholder helpers
     * ---------------------------------------------------------------------- */

    public String getLastRebootSecondsDisplay() {
        return rebootStats == null ? "" : rebootStats.lastSecondsDisplay();
    }

    public String getAvgRebootSecondsDisplay() {
        return rebootStats == null ? "" : rebootStats.avgSecondsDisplay();
    }
}
