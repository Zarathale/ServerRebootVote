// src/main/java/cc/scaenacraft/rebootvote/listeners/ChatListener.java
package cc.scaenacraft.rebootvote.listeners;

import cc.scaenacraft.rebootvote.RebootVotePlugin;
import cc.scaenacraft.rebootvote.Vote;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Locale;

public final class ChatListener implements Listener {

    private final RebootVotePlugin plugin;
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    public ChatListener(RebootVotePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        String msg = plain.serialize(event.message());
        if (msg == null) return;

        String trimmed = msg.trim();
        if (trimmed.isEmpty()) return;

        // Defensive: ignore anything that looks like a command.
        if (trimmed.startsWith("/")) return;

        String lowered = trimmed.toLowerCase(Locale.ROOT);

        Vote vote = plugin.getVoteKeywords().classify(lowered);
        if (vote == null) return;

        Player player = event.getPlayer();

        Bukkit.getScheduler().runTask(plugin, () -> plugin.handleVote(player, vote));
    }
}
