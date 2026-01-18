// src/main/java/cc/scaenacraft/rebootvote/commands/RebootVoteTabCompleter.java
package cc.scaenacraft.rebootvote.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public final class RebootVoteTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String s : List.of("start", "cancel", "status", "force", "reload", "stats")) {
                if (s.startsWith(prefix)) out.add(s);
            }
            return out;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            for (String s : List.of("30", "45", "60", "90", "120")) out.add(s);
            return out;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
            String prefix = args[1].toLowerCase();
            for (String s : List.of("reset")) {
                if (s.startsWith(prefix)) out.add(s);
            }
            return out;
        }

        return out;
    }
}
