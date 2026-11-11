package dev.dani.rewards;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Rewards extends JavaPlugin implements Listener, CommandExecutor {

    private static final LegacyComponentSerializer LEG =
        LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final int[] SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    private File dataFile;
    private YamlConfiguration data;
    private final Map<UUID, Consumer<String>> prompts = new ConcurrentHashMap<>();

    // One holder class for every menu, so clicks can be told apart.
    static class Menu implements InventoryHolder {
        final String type;
        final String cat;
        final String rid;
        final Map<Integer, String> slots = new HashMap<>();
        Inventory inv;

        Menu(String type, String cat, String rid) {
            this.type = type;
            this.cat = cat;
            this.rid = rid;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("rewards").setExecutor(this);
    }

    // ---------- helpers ----------

    private static Component c(String s) {
        return LEG.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private String prefix() {
        return getConfig().getString("prefix", "&8[&bRewards&8] ");
    }

    private void msg(Player p, String s) {
        p.sendMessage(c(prefix() + s));
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(this, r);
    }

    private static String rp(String cat, String rid) {
        return "categories." + cat + ".rewards." + rid;
    }

    private static String styled(String s, String defaultColor) {
        return s.contains("&") ? s : defaultColor + s;
    }

    private ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(c(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(c(s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private static Material material(String s, Material fallback) {
        Material m = Material.matchMaterial(s == null ? "" : s);
        return (m == null || m.isAir() || !m.isItem()) ? fallback : m;
    }

    private static String fmt(long s) {
        if (s <= 0) return "0s";
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        StringBuilder b = new StringBuilder();
        if (d > 0) b.append(d).append("d ");
        if (h > 0) b.append(h).append("h ");
        if (m > 0) b.append(m).append("m ");
        if (sec > 0 && d == 0 && h == 0) b.append(sec).append("s");
        return b.toString().trim();
    }

    // Accepts 30m, 1h, 2h30m, 1d, or a plain number of seconds.
    private static long parseTime(String in) {
        String s = in.toLowerCase().replace(" ", "");
        try {
            if (s.matches("[0-9]+")) return Long.parseLong(s);
            if (!s.matches("([0-9]+[dhms])+")) return -1;
            Matcher m = Pattern.compile("([0-9]+)([dhms])").matcher(s);
            long total = 0;
            while (m.find()) {
                long n = Long.parseLong(m.group(1));
                total += switch (m.group(2).charAt(0)) {
                    case 'd' -> n * 86400;
                    case 'h' -> n * 3600;
                    case 'm' -> n * 60;
                    default -> n;
                };
            }
            return total;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private String uniqueId(ConfigurationSection sec, String name) {
        String base = name.replaceAll("(?i)&[0-9a-fk-or]", "").toLowerCase()
            .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "item";
        String id = base;
        int n = 2;
        while (sec != null && sec.contains(id)) {
            id = base + "-" + n;
            n++;
        }
        return id;
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    private void build(Menu m, Component title, int count, boolean filler) {
        int rows = Math.max(1, (count + 6) / 7);
        int size = Math.min(54, (rows + 2) * 9);
        m.inv = Bukkit.createInventory(m, size, title);
        if (filler) fill(m);
    }

    private void fill(Menu m) {
        ItemStack f = icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < m.inv.getSize(); i++) m.inv.setItem(i, f);
    }

    private void put(Menu m, int slot, String id, ItemStack it) {
        m.inv.setItem(slot, it);
        m.slots.put(slot, id);
    }

    // ---------- player menus ----------

    private void openMain(Player p) {
        ConfigurationSection cats = getConfig().getConfigurationSection("categories");
        List<String> ids = new ArrayList<>();
        if (cats != null) ids.addAll(cats.getKeys(false));
        Menu m = new Menu("main", null, null);
        build(m, c(getConfig().getString("menu-title", "&b&lRewards")), ids.size(), true);
        for (int i = 0; i < ids.size() && i < SLOTS.length; i++) {
            String id = ids.get(i);
            String path = "categories." + id;
            ConfigurationSection rs = getConfig().getConfigurationSection(path + ".rewards");
            int count = rs == null ? 0 : rs.getKeys(false).size();
            put(m, SLOTS[i], id, icon(
                material(getConfig().getString(path + ".icon"), Material.CHEST),
                getConfig().getString(path + ".name", id),
                List.of("&7" + count + " rewards", "", "&eClick to open")));
        }
        p.openInventory(m.inv);
    }

    private void openCategory(Player p, String cat) {
        String base = "categories." + cat;
        if (!getConfig().isConfigurationSection(base)) {
            openMain(p);
            return;
        }
        ConfigurationSection rs = getConfig().getConfigurationSection(base + ".rewards");
        List<String> ids = new ArrayList<>();
        if (rs != null) ids.addAll(rs.getKeys(false));
        Menu m = new Menu("cat", cat, null);
        build(m, c(getConfig().getString(base + ".name", cat)), ids.size(), true);
        long now = System.currentTimeMillis();
        for (int i = 0; i < ids.size() && i < SLOTS.length; i++) {
            String rid = ids.get(i);
            String path = rp(cat, rid);
            long cd = getConfig().getLong(path + ".cooldown", 3600);
            long last = data.getLong(p.getUniqueId() + "." + cat + "." + rid, 0L);
            long remaining = last + cd * 1000L - now;
            String perm = getConfig().getString(path + ".permission", "");
            List<String> lore = new ArrayList<>();
            lore.add("&7Cooldown: &f" + fmt(cd));
            lore.add("");
            if (!perm.isEmpty() && !p.hasPermission(perm)) lore.add("&cLocked - you need a higher rank");
            else if (remaining > 0) lore.add("&cAvailable in &f" + fmt((remaining + 999) / 1000));
            else lore.add("&aClick to claim!");
            put(m, SLOTS[i], rid, icon(
                material(getConfig().getString(path + ".icon"), Material.PAPER),
                getConfig().getString(path + ".name", rid), lore));
        }
        put(m, m.inv.getSize() - 5, "__back", icon(Material.ARROW, "&7Back", List.of()));
        p.openInventory(m.inv);
    }

    private void claim(Player p, String cat, String rid) {
        String path = rp(cat, rid);
        if (!getConfig().isConfigurationSection(path)) return;
        String perm = getConfig().getString(path + ".permission", "");
        if (!perm.isEmpty() && !p.hasPermission(perm)) {
            msg(p, "&cYou don't have access to this reward.");
            return;
        }
        String key = p.getUniqueId() + "." + cat + "." + rid;
        long cd = getConfig().getLong(path + ".cooldown", 3600) * 1000L;
        long remaining = data.getLong(key, 0L) + cd - System.currentTimeMillis();
        if (remaining > 0) {
            msg(p, "&cYou can claim this again in &f" + fmt((remaining + 999) / 1000));
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        List<?> raw = getConfig().getList(path + ".items");
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof ItemStack is) items.add(is);
            }
        }
        List<String> cmds = getConfig().getStringList(path + ".commands");
        if (items.isEmpty() && cmds.isEmpty()) {
            msg(p, "&cThis reward isn't set up yet.");
            return;
        }
        for (ItemStack is : items) {
            for (ItemStack left : p.getInventory().addItem(is.clone()).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        for (String cmd : cmds) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
        data.set(key, System.currentTimeMillis());
        saveData();
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        msg(p, "&aReward claimed!");
    }

    // ---------- admin menus ----------

    private void openAdminMain(Player p) {
        ConfigurationSection cats = getConfig().getConfigurationSection("categories");
        List<String> ids = new ArrayList<>();
        if (cats != null) ids.addAll(cats.getKeys(false));
        Menu m = new Menu("admin-main", null, null);
        build(m, c("&8Rewards settings"), ids.size() + 1, true);
        int i = 0;
        for (; i < ids.size() && i < SLOTS.length - 1; i++) {
            String id = ids.get(i);
            String path = "categories." + id;
            put(m, SLOTS[i], id, icon(
                material(getConfig().getString(path + ".icon"), Material.CHEST),
                getConfig().getString(path + ".name", id),
                List.of("&7Click to edit this category")));
        }
        put(m, SLOTS[i], "__add", icon(Material.EMERALD, "&aAdd category",
            List.of("&7Create a new group of rewards")));
        p.openInventory(m.inv);
    }

    private void openAdminCat(Player p, String cat) {
        String base = "categories." + cat;
        if (!getConfig().isConfigurationSection(base)) {
            openAdminMain(p);
            return;
        }
        ConfigurationSection rs = getConfig().getConfigurationSection(base + ".rewards");
        List<String> ids = new ArrayList<>();
        if (rs != null) ids.addAll(rs.getKeys(false));
        Menu m = new Menu("admin-cat", cat, null);
        build(m, c("&8Edit category: " + cat), ids.size() + 1, true);
        int i = 0;
        for (; i < ids.size() && i < SLOTS.length - 1; i++) {
            String rid = ids.get(i);
            String path = rp(cat, rid);
            put(m, SLOTS[i], rid, icon(
                material(getConfig().getString(path + ".icon"), Material.PAPER),
                getConfig().getString(path + ".name", rid),
                List.of("&7Cooldown: &f" + fmt(getConfig().getLong(path + ".cooldown", 3600)),
                    "", "&eClick to edit")));
        }
        put(m, SLOTS[i], "__add", icon(Material.EMERALD, "&aAdd reward",
            List.of("&7Create a new reward in this category")));
        int size = m.inv.getSize();
        put(m, size - 9, "__back", icon(Material.ARROW, "&7Back", List.of()));
        put(m, size - 8, "__rename", icon(Material.NAME_TAG, "&eRename category",
            List.of("&7Type a new name in chat")));
        put(m, size - 7, "__icon", icon(Material.ITEM_FRAME, "&eSet icon",
            List.of("&7Hold an item, then click here")));
        put(m, size - 1, "__delete", icon(Material.BARRIER, "&cDelete category",
            List.of("&7Shift-click to delete it and all its rewards")));
        p.openInventory(m.inv);
    }

    private void openAdminReward(Player p, String cat, String rid) {
        String path = rp(cat, rid);
        if (!getConfig().isConfigurationSection(path)) {
            openAdminCat(p, cat);
            return;
        }
        Menu m = new Menu("admin-reward", cat, rid);
        m.inv = Bukkit.createInventory(m, 27, c("&8Edit reward: " + rid));
        fill(m);
        String perm = getConfig().getString(path + ".permission", "");
        List<?> raw = getConfig().getList(path + ".items");
        int itemCount = raw == null ? 0 : raw.size();
        int cmdCount = getConfig().getStringList(path + ".commands").size();
        put(m, 4, "info", icon(
            material(getConfig().getString(path + ".icon"), Material.PAPER),
            getConfig().getString(path + ".name", rid),
            List.of("&7Cooldown: &f" + fmt(getConfig().getLong(path + ".cooldown", 3600)),
                "&7Permission: &f" + (perm.isEmpty() ? "none" : perm),
                "&7Kit item stacks: &f" + itemCount,
                "&7Commands: &f" + cmdCount)));
        put(m, 10, "rename", icon(Material.NAME_TAG, "&eRename", List.of("&7Type a new name in chat")));
        put(m, 11, "icon", icon(Material.ITEM_FRAME, "&eSet icon", List.of("&7Hold an item, then click here")));
        put(m, 12, "cooldown", icon(Material.CLOCK, "&eSet cooldown", List.of("&7Like 30m, 1h, 2h30m or 1d")));
        put(m, 13, "kit", icon(Material.CHEST, "&eEdit kit items", List.of("&7Put the items to give, then close")));
        put(m, 14, "addcmd", icon(Material.COMMAND_BLOCK, "&eAdd command", List.of("&7Console command, use %player%")));
        put(m, 15, "clearcmd", icon(Material.LAVA_BUCKET, "&eClear commands", List.of("&7Remove all commands")));
        put(m, 16, "perm", icon(Material.SHIELD, "&eSet permission", List.of("&7Who can claim it, or none")));
        put(m, 18, "back", icon(Material.ARROW, "&7Back", List.of()));
        put(m, 26, "delete", icon(Material.BARRIER, "&cDelete reward", List.of("&7Shift-click to delete")));
        p.openInventory(m.inv);
    }

    private void openKit(Player p, String cat, String rid) {
        Menu m = new Menu("kit", cat, rid);
        m.inv = Bukkit.createInventory(m, 54, c("&8Kit items - put items, then close"));
        List<?> raw = getConfig().getList(rp(cat, rid) + ".items");
        if (raw != null) {
            int i = 0;
            for (Object o : raw) {
                if (o instanceof ItemStack is && i < 54) {
                    m.inv.setItem(i, is);
                    i++;
                }
            }
        }
        p.openInventory(m.inv);
    }

    // ---------- chat prompts ----------

    private void ask(Player p, String question, Consumer<String> handler) {
        p.closeInventory();
        msg(p, "&e" + question + " &7(type &fcancel &7to stop)");
        prompts.put(p.getUniqueId(), handler);
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Consumer<String> h = prompts.remove(e.getPlayer().getUniqueId());
        if (h == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        later(() -> {
            if (text.equalsIgnoreCase("cancel")) {
                msg(p, "&7Cancelled.");
                return;
            }
            h.accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prompts.remove(e.getPlayer().getUniqueId());
    }

    // ---------- clicks ----------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        if (m.type.equals("kit")) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= e.getInventory().getSize()) return;
        String id = m.slots.get(raw);
        if (id == null) return;
        boolean shift = e.isShiftClick();
        later(() -> handle(p, m, id, shift));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu m && !m.type.equals("kit")) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m) || !m.type.equals("kit")) return;
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack is : e.getInventory().getContents()) {
            if (is != null && !is.getType().isAir()) items.add(is);
        }
        getConfig().set(rp(m.cat, m.rid) + ".items", items);
        saveConfig();
        if (e.getPlayer() instanceof Player p) {
            msg(p, "&aKit items saved (" + items.size() + " stacks).");
            later(() -> {
                if (p.isOnline()) openAdminReward(p, m.cat, m.rid);
            });
        }
    }

    private void handle(Player p, Menu m, String id, boolean shift) {
        if (m.type.startsWith("admin") && !p.hasPermission("rewards.admin")) return;
        switch (m.type) {
            case "main" -> openCategory(p, id);
            case "cat" -> {
                if (id.equals("__back")) {
                    openMain(p);
                } else {
                    claim(p, m.cat, id);
                    openCategory(p, m.cat);
                }
            }
            case "admin-main" -> {
                if (id.equals("__add")) {
                    ask(p, "Type the name of the new category.", s -> {
                        String cid = uniqueId(getConfig().getConfigurationSection("categories"), s);
                        getConfig().set("categories." + cid + ".name", styled(s, "&b&l"));
                        getConfig().set("categories." + cid + ".icon", "CHEST");
                        saveConfig();
                        openAdminCat(p, cid);
                    });
                } else {
                    openAdminCat(p, id);
                }
            }
            case "admin-cat" -> adminCat(p, m, id, shift);
            case "admin-reward" -> adminReward(p, m, id, shift);
            default -> { }
        }
    }

    private void adminCat(Player p, Menu m, String id, boolean shift) {
        String base = "categories." + m.cat;
        switch (id) {
            case "__back" -> openAdminMain(p);
            case "__add" -> ask(p, "Type the name of the new reward.", s -> {
                String rid = uniqueId(getConfig().getConfigurationSection(base + ".rewards"), s);
                String path = rp(m.cat, rid);
                getConfig().set(path + ".name", styled(s, "&a"));
                getConfig().set(path + ".icon", "PAPER");
                getConfig().set(path + ".cooldown", 3600);
                getConfig().set(path + ".permission", "");
                getConfig().set(path + ".commands", new ArrayList<String>());
                getConfig().set(path + ".items", new ArrayList<ItemStack>());
                saveConfig();
                openAdminReward(p, m.cat, rid);
            });
            case "__rename" -> ask(p, "Type the new name for this category.", s -> {
                getConfig().set(base + ".name", styled(s, "&b&l"));
                saveConfig();
                openAdminCat(p, m.cat);
            });
            case "__icon" -> {
                Material hand = p.getInventory().getItemInMainHand().getType();
                if (hand.isAir()) {
                    msg(p, "&cHold an item in your hand first.");
                } else {
                    getConfig().set(base + ".icon", hand.name());
                    saveConfig();
                }
                openAdminCat(p, m.cat);
            }
            case "__delete" -> {
                if (!shift) {
                    msg(p, "&cShift-click to delete this category.");
                } else {
                    getConfig().set(base, null);
                    saveConfig();
                    openAdminMain(p);
                }
            }
            default -> openAdminReward(p, m.cat, id);
        }
    }

    private void adminReward(Player p, Menu m, String id, boolean shift) {
        String path = rp(m.cat, m.rid);
        switch (id) {
            case "back" -> openAdminCat(p, m.cat);
            case "rename" -> ask(p, "Type the new name for this reward.", s -> {
                getConfig().set(path + ".name", styled(s, "&a"));
                saveConfig();
                openAdminReward(p, m.cat, m.rid);
            });
            case "icon" -> {
                Material hand = p.getInventory().getItemInMainHand().getType();
                if (hand.isAir()) {
                    msg(p, "&cHold an item in your hand first.");
                } else {
                    getConfig().set(path + ".icon", hand.name());
                    saveConfig();
                }
                openAdminReward(p, m.cat, m.rid);
            }
            case "cooldown" -> ask(p, "Type the cooldown, like 30m, 1h, 2h30m or 1d.", s -> {
                long secs = parseTime(s);
                if (secs <= 0) {
                    msg(p, "&cThat isn't a valid time.");
                } else {
                    getConfig().set(path + ".cooldown", secs);
                    saveConfig();
                    msg(p, "&aCooldown set to &f" + fmt(secs) + "&a.");
                }
                openAdminReward(p, m.cat, m.rid);
            });
            case "kit" -> openKit(p, m.cat, m.rid);
            case "addcmd" -> ask(p, "Type a console command without the slash. Use %player% for the player name.", s -> {
                List<String> cmds = new ArrayList<>(getConfig().getStringList(path + ".commands"));
                cmds.add(s.startsWith("/") ? s.substring(1) : s);
                getConfig().set(path + ".commands", cmds);
                saveConfig();
                openAdminReward(p, m.cat, m.rid);
            });
            case "clearcmd" -> {
                getConfig().set(path + ".commands", new ArrayList<String>());
                saveConfig();
                openAdminReward(p, m.cat, m.rid);
            }
            case "perm" -> ask(p, "Type a permission node, or none for no permission.", s -> {
                getConfig().set(path + ".permission", s.equalsIgnoreCase("none") ? "" : s);
                saveConfig();
                openAdminReward(p, m.cat, m.rid);
            });
            case "delete" -> {
                if (!shift) {
                    msg(p, "&cShift-click to delete this reward.");
                } else {
                    getConfig().set(path, null);
                    saveConfig();
                    openAdminCat(p, m.cat);
                }
            }
            default -> { }
        }
    }

    // ---------- command ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Players only.");
            return true;
        }
        if (a.length > 0 && (a[0].equalsIgnoreCase("settings") || a[0].equalsIgnoreCase("reload"))) {
            if (!p.hasPermission("rewards.admin")) {
                msg(p, "&cYou don't have permission.");
                return true;
            }
            if (a[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                data = YamlConfiguration.loadConfiguration(dataFile);
                msg(p, "&aReloaded.");
            } else {
                openAdminMain(p);
            }
            return true;
        }
        if (!p.hasPermission("rewards.use")) {
            msg(p, "&cYou don't have permission.");
            return true;
        }
        openMain(p);
        return true;
    }
}
