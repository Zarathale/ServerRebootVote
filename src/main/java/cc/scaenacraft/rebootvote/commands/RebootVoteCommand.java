// src/main/java/cc/scaenacraft/rebootvote/commands/RebootVoteCommand.java
package cc.scaenacraft.rebootvote.commands;

import cc.scaenacraft.rebootvote.RebootVotePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Locale;

public final class RebootVoteCommand implements CommandExecutor {

    private final RebootVotePlugin plugin;

    public RebootVoteCommand(RebootVotePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /rebootvote <start|cancel|status|force|reload|stats reset> [seconds]");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "start" -> {
                int seconds = plugin.getConfig().getInt("default-reboot-time", 45);
                if (args.length >= 2) {
                    try {
                        seconds = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ignored) {
                        sender.sendMessage("Invalid seconds value. Using default " + seconds + ".");
                    }
                }
                plugin.commandStart(sender, seconds);
                return true;
            }
            case "cancel" -> {
                plugin.commandCancel(sender);
                return true;
            }
            case "status" -> {
                plugin.commandStatus(sender);
                return true;
            }
            case "force" -> {
                plugin.commandForce(sender);
                return true;
            }
            case "reload" -> {
                plugin.commandReload(sender);
                return true;
            }
            case "stats" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
                    plugin.commandStatsReset(sender);
                    return true;
                }
                sender.sendMessage("Usage: /rebootvote stats reset");
                return true;
            }
            default -> {
                sender.sendMessage("Unknown subcommand. Use: start, cancel, status, force, reload, stats reset");
                return true;
            }
        }
    }
}
