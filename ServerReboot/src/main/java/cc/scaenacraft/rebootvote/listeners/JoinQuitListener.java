// src/main/java/cc/scaenacraft/rebootvote/listeners/JoinQuitListener.java
package cc.scaenacraft.rebootvote.listeners;

import cc.scaenacraft.rebootvote.RebootVotePlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class JoinQuitListener implements Listener {

    private final RebootVotePlugin plugin;

    public JoinQuitListener(RebootVotePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> plugin.handleJoin(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> plugin.handleQuit(event.getPlayer()));
    }
}
