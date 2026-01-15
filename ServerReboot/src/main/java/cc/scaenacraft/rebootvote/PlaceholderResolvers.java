// src/main/java/cc/scaenacraft/rebootvote/PlaceholderResolvers.java
package cc.scaenacraft.rebootvote;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public final class PlaceholderResolvers {

    private PlaceholderResolvers() {}

    public static TagResolver resolver(
            String player,
            String holders,
            int seconds,
            int onlineNow,
            int onlineAtStart
    ) {
        return TagResolver.resolver(
                TagResolver.resolver("player", Tag.inserting(Component.text(safe(player)))),
                TagResolver.resolver("holders", Tag.inserting(Component.text(safe(holders)))),
                TagResolver.resolver("seconds", Tag.inserting(Component.text(String.valueOf(seconds)))),
                TagResolver.resolver("online", Tag.inserting(Component.text(String.valueOf(onlineNow)))),
                TagResolver.resolver("online_start", Tag.inserting(Component.text(String.valueOf(onlineAtStart))))
        );
    }

    public static TagResolver dummy() {
        return resolver("Player", "Alice, Bob", 60, 2, 2);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
