// src/main/java/cc/scaenacraft/rebootvote/VoteKeywords.java
package cc.scaenacraft.rebootvote;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class VoteKeywords {

    private final Set<String> ok;
    private final Set<String> wait;

    public VoteKeywords(FileConfiguration cfg) {
        this.ok = toSet(cfg.getStringList("vote_keywords.ok"));
        this.wait = toSet(cfg.getStringList("vote_keywords.wait"));
    }

    private static Set<String> toSet(List<String> list) {
        Set<String> set = new HashSet<>();
        if (list == null) return set;
        for (String s : list) {
            if (s == null) continue;
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }

    public Vote classify(String messageLowerTrimmed) {
        if (ok.contains(messageLowerTrimmed)) return Vote.OK;
        if (wait.contains(messageLowerTrimmed)) return Vote.WAIT;
        return null;
    }
}
