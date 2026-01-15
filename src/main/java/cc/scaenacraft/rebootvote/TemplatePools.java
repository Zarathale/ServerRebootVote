// src/main/java/cc/scaenacraft/rebootvote/TemplatePools.java
package cc.scaenacraft.rebootvote;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public final class TemplatePools {

    public final List<String> start;
    public final List<String> hold;
    public final List<String> allOk;
    public final List<String> fin;
    public final List<String> canceled;
    public final List<String> status;

    /**
     * Random callouts broadcast during an active countdown (when not paused).
     * Falls back to {@link #status} if the callout pool is missing/empty.
     */
    public final List<String> callout;

    public TemplatePools(FileConfiguration cfg) {
        this.start = cfg.getStringList("messages.start_templates");
        this.hold = cfg.getStringList("messages.hold_templates");
        this.allOk = cfg.getStringList("messages.all_ok_templates");
        this.fin = cfg.getStringList("messages.final_templates");
        this.canceled = cfg.getStringList("messages.canceled_templates");
        this.status = cfg.getStringList("messages.status_templates");

        List<String> callouts = cfg.getStringList("messages.callout_templates");
        this.callout = (callouts == null || callouts.isEmpty()) ? this.status : callouts;
    }
}
