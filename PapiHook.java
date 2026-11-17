package de.coreeco;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Placeholders fuer SimpleScore & Co: %coreeco_balance%, %coreeco_balance_formatted%, %coreeco_rank%,
 *  %coreeco_bounty%, %coreeco_top_1_name%, %coreeco_top_1_balance% */
public class PapiHook extends PlaceholderExpansion {
    private final CoreEco pl;

    public PapiHook(CoreEco pl) { this.pl = pl; }

    @Override public String getIdentifier() { return "coreeco"; }
    @Override public String getAuthor() { return "CoreEco"; }
    @Override public String getVersion() { return pl.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer p, String params) {
        EconomyManager eco = pl.eco();
        String s = params.toLowerCase();
        if (s.startsWith("top_")) {
            String[] sp = s.split("_");
            if (sp.length != 3) return "";
            try {
                int n = Integer.parseInt(sp[1]);
                List<Map.Entry<UUID, Double>> top = eco.top();
                if (n < 1 || n > top.size()) return "-";
                return sp[2].equals("name") ? eco.name(top.get(n - 1).getKey())
                        : sp[2].equals("balance") ? eco.format(top.get(n - 1).getValue()) : "";
            } catch (NumberFormatException e) { return ""; }
        }
        if (p == null) return "";
        UUID id = p.getUniqueId();
        return switch (s) {
            case "balance" -> String.valueOf(eco.get(id));
            case "balance_formatted" -> eco.format(eco.get(id));
            case "rank" -> String.valueOf(eco.rank(id));
            case "bounty" -> eco.format(pl.bounties().get(id));
            default -> null;
        };
    }
}
