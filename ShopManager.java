package de.coreeco;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Shop + /sell. Kategorien, Preise und Verkaufswerte stehen in plugins/CoreEco/shop.yml (/shop reload). */
public class ShopManager {
    private static final int CONFIG_VERSION = 2;

    public static final class Item {
        public final Material mat;
        public final double price;
        Item(Material mat, double price) { this.mat = mat; this.price = price; }
    }

    public static final class Category {
        public final String id;
        public final String name;
        public final Material icon;
        public final List<Item> items = new ArrayList<>();
        Category(String id, String name, Material icon) { this.id = id; this.name = name; this.icon = icon; }
    }

    private final CoreEco pl;
    private final File file;
    private final Map<String, Category> cats = new LinkedHashMap<>();
    private final Map<Material, Double> sellMap = new HashMap<>();

    public ShopManager(CoreEco pl) {
        this.pl = pl;
        this.file = new File(pl.getDataFolder(), "shop.yml");
        if (file.exists() && YamlConfiguration.loadConfiguration(file).getInt("config-version", 1) < CONFIG_VERSION) {
            File old = new File(pl.getDataFolder(), "shop.yml.old");
            if (old.exists()) old.delete();
            file.renameTo(old);
            pl.getLogger().info("Alte shop.yml wurde als shop.yml.old gesichert, der neue Standard-Shop wird erstellt.");
        }
        if (!file.exists()) writeDefaults();
        load();
    }

    public Collection<Category> categories() { return cats.values(); }
    public Category cat(String id) { return cats.get(id); }
    public Double sellPrice(Material m) { return sellMap.get(m); }

    public Category find(String in) {
        String s = in.toLowerCase(Locale.ROOT);
        for (Category c : cats.values()) {
            if (c.id.equalsIgnoreCase(s)) return c;
            if (org.bukkit.ChatColor.stripColor(U.c(c.name)).toLowerCase(Locale.ROOT).contains(s)) return c;
        }
        return null;
    }

    public void reload() { load(); }

    private void load() {
        cats.clear();
        sellMap.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        double ratio = Math.max(0, y.getDouble("sell-ratio", 0.4));

        ConfigurationSection root = y.getConfigurationSection("categories");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection cs = root.getConfigurationSection(id);
                if (cs == null) continue;
                Material icon = Material.matchMaterial(cs.getString("icon", "CHEST"));
                if (icon == null) icon = Material.CHEST;
                Category c = new Category(id, cs.getString("name", id), icon);
                ConfigurationSection is = cs.getConfigurationSection("items");
                if (is != null) {
                    for (String key : is.getKeys(false)) {
                        Material m = Material.matchMaterial(key);
                        double price = is.getDouble(key);
                        if (m == null || !m.isItem() || m.isLegacy() || price <= 0) {
                            pl.getLogger().warning("shop.yml: Eintrag '" + key + "' in '" + id + "' ist ungültig und wird übersprungen.");
                            continue;
                        }
                        c.items.add(new Item(m, U.round(price)));
                    }
                }
                if (!c.items.isEmpty()) cats.put(id, c);
            }
        }

        // Verkaufswerte: erst die Extra-Liste "sell:", dann alle Shop-Items automatisch mit sell-ratio
        ConfigurationSection ss = y.getConfigurationSection("sell");
        if (ss != null) {
            for (String key : ss.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                double v = ss.getDouble(key);
                if (m == null || !m.isItem() || m.isLegacy() || v <= 0) {
                    pl.getLogger().warning("shop.yml: Verkaufs-Eintrag '" + key + "' ist ungültig und wird übersprungen.");
                    continue;
                }
                sellMap.put(m, U.round(v));
            }
        }
        for (Category c : cats.values())
            for (Item it : c.items) {
                double v = U.round(it.price * ratio);
                if (v > 0) sellMap.putIfAbsent(it.mat, v);
            }
    }

    // ---------- Kaufen ----------

    public void buy(Player p, Item it, int amount) {
        EconomyManager eco = pl.eco();
        double total = U.round(it.price * amount);
        if (!eco.withdraw(p.getUniqueId(), total)) {
            U.msg(p, "&cDafür brauchst du &e" + eco.format(total) + "&c. Du hast nur &e" + eco.format(eco.get(p.getUniqueId())) + "&c.");
            return;
        }
        U.give(p, new ItemStack(it.mat, amount));
        U.msg(p, "Gekauft: &e" + amount + "x " + U.pretty(it.mat) + " &7für &e" + eco.format(total));
    }

    // ---------- Verkaufen (/sell) ----------

    /** Nur "saubere" Items ohne Verzauberung, Namen, Haltbarkeitsschaden usw. werden angenommen. */
    private boolean sellable(ItemStack it) {
        return it != null && it.getType() != Material.AIR && !it.hasItemMeta() && sellMap.containsKey(it.getType());
    }

    public void sellHand(Player p, Integer limit) {
        EconomyManager eco = pl.eco();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) { U.msg(p, "&cDu hast nichts in der Hand."); return; }
        if (!sellable(hand)) {
            U.msg(p, hand.hasItemMeta()
                    ? "&cDieses Item hat Besonderheiten (Name, Verzauberung, Schaden ...) und kann nicht verkauft werden."
                    : "&cDieses Item kann nicht verkauft werden.");
            return;
        }
        double each = sellMap.get(hand.getType());
        int amount = limit == null ? hand.getAmount() : Math.min(limit, hand.getAmount());
        double total = U.round(each * amount);
        Material type = hand.getType();
        if (amount >= hand.getAmount()) p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        else hand.setAmount(hand.getAmount() - amount);
        eco.deposit(p.getUniqueId(), total);
        U.msg(p, "Verkauft: &e" + amount + "x " + U.pretty(type) + " &7für &a+" + eco.format(total));
    }

    public void sellAll(Player p) {
        EconomyManager eco = pl.eco();
        PlayerInventory inv = p.getInventory();
        Map<Material, Integer> sold = new LinkedHashMap<>();
        double total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (!sellable(it)) continue;
            int n = it.getAmount();
            total += sellMap.get(it.getType()) * n;
            sold.merge(it.getType(), n, Integer::sum);
            inv.setItem(i, null);
        }
        if (sold.isEmpty()) { U.msg(p, "&cIn deinem Inventar ist nichts, was sich verkaufen lässt."); return; }
        total = U.round(total);
        eco.deposit(p.getUniqueId(), total);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<Material, Integer> e : sold.entrySet()) {
            if (shown == 5) { sb.append("&7und &f").append(sold.size() - 5).append(" weitere"); break; }
            sb.append("&f").append(e.getValue()).append("x ").append(U.pretty(e.getKey())).append("&7, ");
            shown++;
        }
        String list = sb.toString();
        if (list.endsWith("&7, ")) list = list.substring(0, list.length() - 4);
        U.msg(p, "Verkauft: " + list);
        U.msg(p, "Gesamt: &a+" + eco.format(total) + " &7| Neues Guthaben: &e" + eco.format(eco.get(p.getUniqueId())));
    }

    public void worth(Player p) {
        EconomyManager eco = pl.eco();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) { U.msg(p, "&cDu hast nichts in der Hand."); return; }
        Double each = sellMap.get(hand.getType());
        if (each == null) { U.msg(p, "&cDieses Item kann nicht verkauft werden."); return; }
        U.msg(p, "&e" + U.pretty(hand.getType()) + "&7: &e" + eco.format(each) + " &7pro Stück"
                + (hand.getAmount() > 1 ? " &8(&7dein Stack: &e" + eco.format(each * hand.getAmount()) + "&8)" : "")
                + (hand.hasItemMeta() ? " &c- mit Besonderheiten nicht verkaufbar" : ""));
    }

    // ---------- Standard-Katalog ----------
    // Skala: Startguthaben 1000. Eine Million ist auf dem Server richtig viel Geld.
    // Verkauf bringt automatisch 40 % des Shop-Preises (sell-ratio), damit Kaufen+Verkaufen keine Gelddruckmaschine ist.

    private void def(YamlConfiguration y, String id, String name, Material icon, Object... pairs) {
        String base = "categories." + id;
        y.set(base + ".name", name);
        y.set(base + ".icon", icon.name());
        for (int i = 0; i < pairs.length; i += 2)
            y.set(base + ".items." + pairs[i], ((Number) pairs[i + 1]).doubleValue());
    }

    private void writeDefaults() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "CoreEco Shop - Preise gelten pro Stück.",
                "Format: MATERIAL: Preis   (Namen: https://hub.spigotmc.org/javadocs/spigot/org/bukkit/Material.html)",
                "sell-ratio: Anteil des Shop-Preises, den /sell auszahlt (0.4 = 40 %).",
                "sell: Zusätzliche Items, die nur verkauft (nicht gekauft) werden können, mit festem Verkaufspreis.",
                "Nach Änderungen: /shop reload"));
        y.set("config-version", CONFIG_VERSION);
        y.set("sell-ratio", 0.4);

        def(y, "pvp", "&c&lPvP", Material.ENDER_PEARL,
                "ENDER_PEARL", 25, "TOTEM_OF_UNDYING", 800, "SHIELD", 60,
                "END_CRYSTAL", 150, "GLOWSTONE", 8, "OBSIDIAN", 12);

        def(y, "nether", "&4&lNether", Material.SOUL_SAND,
                "QUARTZ", 4, "QUARTZ_BLOCK", 14, "SOUL_SAND", 3, "SOUL_SOIL", 3,
                "NETHERRACK", 1, "NETHER_BRICKS", 3, "NETHER_WART", 6, "MAGMA_BLOCK", 5,
                "BLACKSTONE", 2, "BASALT", 2, "BLAZE_ROD", 20, "GHAST_TEAR", 60);

        def(y, "food", "&a&lEssen", Material.COOKED_BEEF,
                "COOKED_BEEF", 6, "COOKED_PORKCHOP", 6, "COOKED_CHICKEN", 5, "COOKED_MUTTON", 5,
                "COOKED_SALMON", 5, "COOKED_COD", 4, "BREAD", 3, "BAKED_POTATO", 3,
                "APPLE", 4, "GOLDEN_CARROT", 12, "PUMPKIN_PIE", 6, "COOKIE", 2, "GOLDEN_APPLE", 100);

        String sb = "sell.";
        Object[][] extra = {
                {"COAL", 2}, {"COPPER_INGOT", 2}, {"IRON_INGOT", 6}, {"GOLD_INGOT", 10}, {"LAPIS_LAZULI", 3},
                {"REDSTONE", 2}, {"EMERALD", 30}, {"DIAMOND", 55}, {"ANCIENT_DEBRIS", 120}, {"NETHERITE_SCRAP", 130},
                {"NETHERITE_INGOT", 550}, {"WHEAT", 1}, {"CARROT", 1}, {"POTATO", 1}, {"BEETROOT", 1},
                {"SUGAR_CANE", 1}, {"PUMPKIN", 2}, {"MELON_SLICE", 0.5}, {"BONE", 1.5}, {"STRING", 1.5},
                {"GUNPOWDER", 3}, {"SPIDER_EYE", 1.5}, {"SLIME_BALL", 4}, {"ROTTEN_FLESH", 0.5}
        };
        for (Object[] e : extra) y.set(sb + e[0], ((Number) e[1]).doubleValue());

        try { pl.getDataFolder().mkdirs(); y.save(file); }
        catch (IOException e) { pl.getLogger().warning("Konnte shop.yml nicht erstellen: " + e.getMessage()); }
    }
}
