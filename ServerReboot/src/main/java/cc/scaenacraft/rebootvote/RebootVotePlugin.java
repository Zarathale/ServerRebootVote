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

    private RebootSession session;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        reloadAllConfigState();

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
        // Silent cleanup: no broadcasts during shutdown.
        if (session != null && session.isActive()) {
            try {
                session.endSilently();
            } catch (Exception ignored) {
                // If anything goes weird during shutdown, don’t block disable.
            }
        }
        getLogger().info("RebootVote disabled.");
    }

    public boolean isSenderAllowed(CommandSender sender) {
        if (!(sender instanceof Player)) return true; // console always allowed
        return sender.hasPermission("rebootvote.admin");
    }

    public VoteKeywords getVoteKeywords() {
        return voteKeywords;
    }

    public void commandStart(CommandSender sender, int seconds) {
        if (session != null && session.isActive()) {
            sender.sendMessage("RebootVote: a session is already running. Use /rebootvote status or /rebootvote cancel.");
            return;
        }

        // Ensure latest config before session start.
        reloadAllConfigState();

        long cooldown = getConfig().getLong("anti_spam.hold_broadcast_cooldown_seconds", 3L);
        int statusUpdateInterval = getConfig().getInt("status-update-interval", 15);
        int holdReminderInterval = getConfig().getInt("hold-reminder-interval", 60);

        session = new RebootSession(this, messages, pools, seconds, cooldown, statusUpdateInterval, holdReminderInterval);
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
        // Force works regardless of session state.
        reloadAllConfigState();

        long cooldown = getConfig().getLong("anti_spam.hold_broadcast_cooldown_seconds", 3L);
        int statusUpdateInterval = getConfig().getInt("status-update-interval", 15);
        int holdReminderInterval = getConfig().getInt("hold-reminder-interval", 60);

        if (session != null && session.isActive()) {
            session.forceReboot(sender);
            sender.sendMessage("RebootVote: force reboot initiated.");
            return;
        }

        // No active session: create a tiny “ephemeral” session to reuse messaging + reboot behavior.
        RebootSession ephemeral = new RebootSession(this, messages, pools, 1, cooldown, statusUpdateInterval, holdReminderInterval);
        ephemeral.forceReboot(sender);
        sender.sendMessage("RebootVote: force reboot initiated (no session).");
    }

    public void commandReload(CommandSender sender) {
        // Reload config + palette + keywords + pools. Does not alter a running session.
        reloadAllConfigState();
        sender.sendMessage("RebootVote: reloaded config.");
    }

    // Listener entry points (already scheduled onto main thread by listeners)
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

    private void reloadAllConfigState() {
        reloadConfig();

        if (messages == null) messages = new MessageService(this);
        messages.reloadPalette();

        pools = new TemplatePools(getConfig());
        voteKeywords = new VoteKeywords(getConfig());

        // Validate templates (logs warnings, never hard-fails).
        messages.validateTemplates(pools);
    }

    /**
     * Called by RebootSession when the countdown completes.
     * Preserves existing reboot behavior:
     * - reboot.mode = SHUTDOWN -> Bukkit.shutdown()
     * - reboot.mode = COMMAND  -> dispatch reboot.command as console
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
}
