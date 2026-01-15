// src/main/java/cc/scaenacraft/rebootvote/RebootSession.java
package cc.scaenacraft.rebootvote;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

public final class RebootSession {

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final TemplatePools pools;

    private final int totalSeconds;
    private int remainingSeconds;

    private final int onlineAtStart;

    private final Map<UUID, Vote> votes = new HashMap<>();
    private final Set<UUID> holders = new LinkedHashSet<>();

    private final long holdBroadcastCooldownMs;
    private final int statusUpdateIntervalSeconds;
    private final int holdReminderIntervalSeconds;

    private final Map<UUID, Long> lastHoldBroadcastAt = new HashMap<>();
    private UUID lastHolder = null;

    private BukkitTask countdownTask;
    private BukkitTask holdReminderTask;

    private boolean active = true;
    private boolean finalBroadcastSent = false;

    /**
     * One-way latch so the reboot action can never be “missed”,
     * even if state flags (like {@link #active}) change before a delayed task runs.
     */
    private boolean rebootTriggered = false;

    /** Prevents multiple delayed reboot tasks from being queued. */
    private boolean rebootScheduled = false;

    public RebootSession(
            JavaPlugin plugin,
            MessageService messages,
            TemplatePools pools,
            int seconds,
            long holdCooldownSeconds,
            int statusUpdateIntervalSeconds,
            int holdReminderIntervalSeconds
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.pools = pools;

        this.totalSeconds = Math.max(1, seconds);
        this.remainingSeconds = this.totalSeconds;

        this.onlineAtStart = Bukkit.getOnlinePlayers().size();

        this.holdBroadcastCooldownMs = Math.max(0, holdCooldownSeconds) * 1000L;

        this.statusUpdateIntervalSeconds = Math.max(1, statusUpdateIntervalSeconds);
        this.holdReminderIntervalSeconds = Math.max(5, holdReminderIntervalSeconds);

        for (Player p : Bukkit.getOnlinePlayers()) {
            votes.put(p.getUniqueId(), Vote.NONE);
        }
    }

    public boolean isActive() {
        return active;
    }

    public int remainingSeconds() {
        return remainingSeconds;
    }

    public int onlineNow() {
        return Bukkit.getOnlinePlayers().size();
    }

    public int onlineAtStart() {
        return onlineAtStart;
    }

    public String holdersDisplay() {
        if (holders.isEmpty()) return "none";
        return holders.stream()
                .map(id -> {
                    Player p = Bukkit.getPlayer(id);
                    return p != null ? p.getName() : "unknown";
                })
                .collect(Collectors.joining(", "));
    }

    private String lastHolderName() {
        if (lastHolder == null) return "none";
        Player p = Bukkit.getPlayer(lastHolder);
        return p != null ? p.getName() : "unknown";
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("RebootSession method must be called on the main thread.");
        }
    }

    public void start() {
        requireMainThread();

        broadcastStart();

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active) return;

            if (!holders.isEmpty()) return; // paused

            // Stage-manager style callouts (only when not paused).
            maybeBroadcastCallout();

            if (!finalBroadcastSent && remainingSeconds == 5) {
                broadcastFinal(5);
                finalBroadcastSent = true;
            }

            remainingSeconds--;
            if (remainingSeconds <= 0) {
                rebootNow();
                return;
            }
        }, 20L, 20L);
    }

    public void cancel(CommandSender by) {
        requireMainThread();
        if (!active) return;

        active = false;
        cancelTasks();
        broadcastCanceled();
    }

    public void endSilently() {
        requireMainThread();
        active = false;
        cancelTasks();
    }

    public void forceReboot(CommandSender by) {
        requireMainThread();
        if (!active) {
            // Allow “ephemeral session” usage.
            active = true;
        }

        broadcastFinal(0);
        rebootNow();
    }

    public void status(CommandSender sender) {
        requireMainThread();

        if (!active) {
            sender.sendMessage("RebootVote: no session running.");
            return;
        }

        var resolver = PlaceholderResolvers.resolver(
                lastHolderName(),
                holdersDisplay(),
                remainingSeconds,
                onlineNow(),
                onlineAtStart,
                lastRebootSeconds(),
                avgRebootSeconds()
        );

        if (pools.status != null && !pools.status.isEmpty()) {
            String chosen = pools.status.get(new Random().nextInt(pools.status.size()));
            messages.sendToSender(sender, chosen, resolver);
            return;
        }

        sender.sendMessage("RebootVote status:");
        sender.sendMessage(" - Remaining: " + remainingSeconds + "s (total " + totalSeconds + "s)");
        sender.sendMessage(" - Online now: " + onlineNow() + " | Online at start: " + onlineAtStart);
        sender.sendMessage(" - Holding: " + holdersDisplay());
    }

    public void onPlayerJoin(Player p) {
        requireMainThread();
        if (!active) return;

        votes.putIfAbsent(p.getUniqueId(), Vote.NONE);
        checkEarlyReboot();
    }

    public void onPlayerQuit(Player p) {
        requireMainThread();
        if (!active) return;

        UUID id = p.getUniqueId();
        votes.remove(id);

        boolean removed = holders.remove(id);
        if (removed) {
            if (Objects.equals(lastHolder, id)) {
                lastHolder = holders.isEmpty() ? null : holders.iterator().next();
            }
            if (holders.isEmpty()) stopHoldReminder();
        }

        lastHoldBroadcastAt.remove(id);
        checkEarlyReboot();
    }

    public void onVote(Player p, Vote newVote) {
        requireMainThread();
        if (!active) return;

        UUID id = p.getUniqueId();
        votes.put(id, newVote);

        if (newVote == Vote.WAIT) {
            holders.add(id);
            lastHolder = id;

            maybeBroadcastHold(p.getName());
            startHoldReminderIfNeeded();
            return;
        }

        if (newVote == Vote.OK) {
            boolean wasHolder = holders.remove(id);
            if (wasHolder) {
                if (Objects.equals(lastHolder, id)) {
                    lastHolder = holders.isEmpty() ? null : holders.iterator().next();
                }
                if (holders.isEmpty()) stopHoldReminder();
            }
        }

        checkEarlyReboot();
    }

    private void maybeBroadcastCallout() {
        // Called once per second while counting down and not paused.
        // Fire when remainingSeconds is a multiple of the configured interval,
        // but never at 0s or 5s (final countdown handles 5s).
        if (!active) return;
        if (remainingSeconds <= 0) return;
        if (remainingSeconds == 5) return;

        // Avoid doubling up with the "start" announcement.
        if (remainingSeconds == totalSeconds) return;

        if (statusUpdateIntervalSeconds <= 0) return;
        if (remainingSeconds % statusUpdateIntervalSeconds != 0) return;

        broadcastCallout();
    }

    private void broadcastCallout() {
        if (pools.callout == null || pools.callout.isEmpty()) return;

        var resolver = PlaceholderResolvers.resolver(
                lastHolderName(),
                holdersDisplay(),
                remainingSeconds,
                onlineNow(),
                onlineAtStart,
                lastRebootSeconds(),
                avgRebootSeconds()
        );

        messages.broadcastRandom(pools.callout, resolver);
    }

    private void maybeBroadcastHold(String mostRecentHolderName) {
        long now = System.currentTimeMillis();
        UUID id = lastHolder;
        if (id == null) return;

        Long last = lastHoldBroadcastAt.get(id);
        if (last != null && holdBroadcastCooldownMs > 0 && (now - last) < holdBroadcastCooldownMs) {
            return;
        }
        lastHoldBroadcastAt.put(id, now);
        broadcastHold(mostRecentHolderName);
    }

    private void checkEarlyReboot() {
        if (!active) return;
        if (!holders.isEmpty()) return;
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        boolean allOk = Bukkit.getOnlinePlayers().stream()
                .allMatch(pl -> votes.getOrDefault(pl.getUniqueId(), Vote.NONE) == Vote.OK);

        if (allOk) {
            // Freeze session state immediately so the countdown can't keep running
            // and we don't spam ALL CLEAR due to joins/quits/votes.
            active = false;
            cancelTasks();

            broadcastAllOk();
            scheduleReboot();
        }
    }

    private void broadcastStart() {
        var resolver = PlaceholderResolvers.resolver(
                lastHolderName(),
                holdersDisplay(),
                remainingSeconds,
                onlineNow(),
                onlineAtStart,
                lastRebootSeconds(),
                avgRebootSeconds()
        );
        messages.broadcastRandom(pools.start, resolver);
    }

    private void broadcastHold(String mostRecentHolderName) {
        var resolver = PlaceholderResolvers.resolver(
                mostRecentHolderName,
                holdersDisplay(),
                remainingSeconds,
                onlineNow(),
                onlineAtStart,
                lastRebootSeconds(),
                avgRebootSeconds()
        );
        messages.broadcastRandom(pools.hold, resolver);
    }

    private void broadcastAllOk() {
        var resolver = PlaceholderResolvers.resolver(
                lastHolderName(),
                holdersDisplay(),
                remainingSeconds,
                onlineNow(),
                onlineAtStart,
                lastRebootSeconds(),
                avgRebootSeconds()
        );
        messages.broadcastRandom(pools.allOk, resolver);
    }

    private void broadcastFinal(int seconds) {
        var resolver = PlaceholderResolvers.resolver(
                lastHolderName(),
                holdersDisplay(),
                seconds,
                onlineNow(),
                onlineAtStart,
                lastRebootSeconds(),
                avgRebootSeconds()
        );
        messages.broadcastRandom(pools.fin, resolver);
    }

    private void broadcastCanceled() {
        var resolver = PlaceholderResolvers.resolver(
                lastHolderName(),
                holdersDisplay(),
                remainingSeconds,
                onlineNow(),
                onlineAtStart,
                lastRebootSeconds(),
                avgRebootSeconds()
        );
        messages.broadcastRandom(pools.canceled, resolver);
    }

    private void startHoldReminderIfNeeded() {
        if (holdReminderTask != null) return;

        holdReminderTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active) return;
            if (holders.isEmpty()) {
                stopHoldReminder();
                return;
            }
            broadcastHold(lastHolderName());
        }, 20L * holdReminderIntervalSeconds, 20L * holdReminderIntervalSeconds);
    }

    private void stopHoldReminder() {
        if (holdReminderTask != null) {
            holdReminderTask.cancel();
            holdReminderTask = null;
        }
    }

    private void scheduleReboot() {
        // Preserve existing behavior: schedule on main thread shortly after broadcast.
        if (rebootScheduled) return;
        rebootScheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin, this::rebootNow, 20L);
    }

    private void rebootNow() {
        // "Missed reboot" fix: do not depend on `active` for the actual reboot action.
        // We use a one-way latch so delayed tasks (like early-reboot) can't be invalidated
        // by state changes.
        if (rebootTriggered) return;
        rebootTriggered = true;

        active = false;
        cancelTasks();

        // Preserve existing behavior (mode + command handled by MessageService/Plugin config elsewhere).
        if (plugin instanceof RebootVotePlugin p) {
            p.executeRebootAction();
            return;
        }

        Bukkit.shutdown();
    }

    private String lastRebootSeconds() {
        if (plugin instanceof RebootVotePlugin p) return p.getLastRebootSecondsDisplay();
        return "";
    }

    private String avgRebootSeconds() {
        if (plugin instanceof RebootVotePlugin p) return p.getAvgRebootSecondsDisplay();
        return "";
    }

    private void cancelTasks() {
        if (holdReminderTask != null) {
            holdReminderTask.cancel();
            holdReminderTask = null;
        }
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }
}
