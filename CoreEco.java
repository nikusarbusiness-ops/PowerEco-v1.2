package de.coreeco;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class CoreEco extends JavaPlugin implements Listener {
    private EconomyManager eco;
    private AuctionManager ah;
    private OrderManager orders;
    private BountyManager bounties;
    private ShopManager shop;
    private GuiManager gui;

    public EconomyManager eco() { return eco; }
    public AuctionManager ah() { return ah; }
    public OrderManager orders() { return orders; }
    public BountyManager bounties() { return bounties; }
    public ShopManager shop() { return shop; }
    public GuiManager gui() { return gui; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        eco = new EconomyManager(this);
        ah = new AuctionManager(this);
        orders = new OrderManager(this);
        bounties = new BountyManager(this);
        shop = new ShopManager(this);
        gui = new GuiManager(this);

        CoreCommands cc = new CoreCommands(this);
        for (String c : new String[]{"bal", "pay", "eco", "baltop", "ah", "orders", "bounty", "shop", "sell"}) {
            PluginCommand pc = getCommand(c);
            if (pc != null) { pc.setExecutor(cc); pc.setTabCompleter(cc); }
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(gui, this);
        Bukkit.getPluginManager().registerEvents(bounties, this);

        // Optionale Integrationen (Vault -> viele Plugins; PlaceholderAPI -> SimpleScore, Scoreboards, Chat usw.)
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            try { new VaultHook(this).register(); getLogger().info("Vault-Economy registriert."); }
            catch (Throwable t) { getLogger().warning("Vault-Hook fehlgeschlagen: " + t.getMessage()); }
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try { new PapiHook(this).register(); getLogger().info("PlaceholderAPI-Expansion registriert."); }
            catch (Throwable t) { getLogger().warning("PlaceholderAPI-Hook fehlgeschlagen: " + t.getMessage()); }
        }
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null)
            getLogger().info("LuckPerms erkannt: Rechte und coreeco.ah.limit.<Zahl> werden unterstützt.");

        Bukkit.getScheduler().runTaskTimer(this, () -> ah.tickExpire(), 20L * 60, 20L * 60);
        Bukkit.getScheduler().runTaskTimer(this, this::saveAll, 20L * 300, 20L * 300);
    }

    @Override
    public void onDisable() { saveAll(); }

    private void saveAll() {
        if (eco != null) eco.save();
        if (ah != null) ah.save();
        if (orders != null) orders.save();
        if (bounties != null) bounties.save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        eco.ensure(p.getUniqueId(), p.getName());
        Bukkit.getScheduler().runTaskLater(this, () -> ah.deliver(p), 40L);
    }
}
