// src/main/java/cc/scaenacraft/rebootvote/MessageService.java
package cc.scaenacraft.rebootvote;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageService {

    private static final Pattern PALETTE_OPEN = Pattern.compile("<c\\.([a-zA-Z0-9_-]+)>");
    private static final Pattern PALETTE_CLOSE = Pattern.compile("</c\\.([a-zA-Z0-9_-]+)>");

    private final JavaPlugin plugin;
    private final MiniMessage mini;
    private Map<String, String> paletteTags; // name -> "<#RRGGBB>"

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.mini = MiniMessage.miniMessage();
        reloadPalette();
    }

    public void reloadPalette() {
        FileConfiguration cfg = plugin.getConfig();
        Map<String, String> map = new HashMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection("palette");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                String hex = sec.getString(key, "").trim();
                if (hex.isEmpty()) continue;
                map.put(key.toLowerCase(Locale.ROOT), "<" + hex + ">");
            }
        }
        this.paletteTags = Collections.unmodifiableMap(map);
    }

    public void validateTemplates(TemplatePools pools) {
        // Validate by attempting to deserialize each template with a dummy resolver.
        // Palette tokens are preprocessed; placeholders use TagResolver.
        TagResolver dummy = PlaceholderResolvers.dummy();

        validatePool("messages.start_templates", pools.start, dummy);
        validatePool("messages.hold_templates", pools.hold, dummy);
        validatePool("messages.all_ok_templates", pools.allOk, dummy);
        validatePool("messages.final_templates", pools.fin, dummy);
        validatePool("messages.canceled_templates", pools.canceled, dummy);
        validatePool("messages.status_templates", pools.status, dummy);
    }

    private void validatePool(String key, List<String> pool, TagResolver dummy) {
        if (pool == null || pool.isEmpty()) return;

        for (int i = 0; i < pool.size(); i++) {
            String raw = pool.get(i);
            if (raw == null || raw.isBlank()) continue;

            String pre = preprocessPaletteTokens(raw);
            try {
                // Parse line-by-line and join, matching the actual broadcast behavior.
                Component comp = parseBlock(pre, dummy);
                if (comp == null) throw new IllegalStateException("Parsed component is null");
            } catch (Exception ex) {
                plugin.getLogger().warning("Template parse failed: " + key + "[" + i + "]. "
                        + "Fix the MiniMessage tags. Error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
    }

    public void broadcastRandom(List<String> pool, TagResolver resolver) {
        if (pool == null || pool.isEmpty()) return;
        String chosen = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        broadcastTemplate(chosen, resolver);
    }

    public void broadcastTemplate(String template, TagResolver resolver) {
        if (template == null || template.isBlank()) return;

        String pre = preprocessPaletteTokens(template);
        Component block = parseBlock(pre, resolver);
        if (block == null) return;

        Bukkit.broadcast(block);
    }

    public void sendToSender(CommandSender sender, String template, TagResolver resolver) {
        if (template == null || template.isBlank()) return;

        String pre = preprocessPaletteTokens(template);
        Component block = parseBlock(pre, resolver);
        if (block == null) return;

        sender.sendMessage(block);
    }

    private Component parseBlock(String preprocessedTemplate, TagResolver resolver) {
        // Keep “block” atomic: parse each non-empty line and join with newline into one Component.
        String[] lines = preprocessedTemplate.split("\\r?\\n");

        List<Component> comps = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            try {
                comps.add(mini.deserialize(trimmed, resolver));
            } catch (Exception ex) {
                // Defensive fallback: emit plain text rather than dropping the whole block.
                comps.add(Component.text(trimmed));
            }
        }

        if (comps.isEmpty()) return null;
        return Component.join(JoinConfiguration.newlines(), comps);
    }

    private String preprocessPaletteTokens(String input) {
        // Replace <c.name> tokens with <#RRGGBB>, fallback <gray>.
        String out = input;

        // Tolerate closing tags by converting them to <reset> to prevent parser errors.
        // Best practice is not to use closers; templates should explicitly set the next color.
        Matcher close = PALETTE_CLOSE.matcher(out);
        if (close.find()) {
            out = close.replaceAll("<reset>");
        }

        Matcher open = PALETTE_OPEN.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (open.find()) {
            String name = open.group(1).toLowerCase(Locale.ROOT);
            String repl = paletteTags.getOrDefault(name, "<gray>");
            open.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        open.appendTail(sb);

        return sb.toString();
    }
}
