package com.dsh.lonelymail;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * LonelyMail · 离线邮件系统
 * 玩家：只收邮件（/mail 打开邮箱领取）
 * 管理员：可视化写信（聊天栏输入正文 → 物品编辑界面 → 选择收件人）
 */
public class Main extends JavaPlugin implements Listener {

    private static final int SIZE = 54;
    private static final int SLOT_CLOSE = 45;
    private static final int SLOT_CLAIM_ALL = 49;
    private static final int SLOT_HELP = 53;
    private static final int SLOT_COMPOSE = 48;
    private static final int EDIT_ROWS = 45;          // 编辑区 0~44
    private static final int EDIT_CANCEL = 45;
    private static final int EDIT_NEXT = 49;
    private static final int EDIT_HELP = 53;
    private static final int PICK_SEARCH = 53;
    private static final int PICK_ALL = 49;
    private static final int PICK_CANCEL = 45;

    private final Map<UUID, List<Mail>> mails = new HashMap<UUID, List<Mail>>();
    private final Map<UUID, Integer> nextId = new HashMap<UUID, Integer>();
    private final Map<UUID, Compose> composing = new HashMap<UUID, Compose>();
    private File dataDir;
    private String title;
    private String editorTitle;
    private String pickerTitle;

    // ---------------- 生命周期 ----------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataDir = new File(getDataFolder(), "data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            getLogger().warning("无法创建 data 目录");
        }
        title = color(getConfig().getString("gui-title", "&8「 &b邮箱 &8」&7 你的信件"));
        editorTitle = color("&8「 &b写信 &8」&7 放入要寄的物品");
        pickerTitle = color("&8「 &b写信 &8」&7 选择收件人");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("LonelyMail 已启用：邮件保留 " + getConfig().getInt("expire-days", 30)
                + " 天，上限 " + getConfig().getInt("max-mails", 60) + " 封");
    }

    @Override
    public void onDisable() {
        for (UUID uuid : new ArrayList<UUID>(mails.keySet())) {
            savePlayer(uuid);
        }
        mails.clear();
        composing.clear();
    }

    // ---------------- 数据 ----------------

    private File fileOf(UUID uuid) {
        return new File(dataDir, uuid + ".yml");
    }

    private List<Mail> loadPlayer(UUID uuid) {
        List<Mail> list = mails.get(uuid);
        if (list != null) {
            return list;
        }
        list = new ArrayList<Mail>();
        File file = fileOf(uuid);
        if (file.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            nextId.put(uuid, Integer.valueOf(yaml.getInt("next-id", 1)));
            ConfigurationSection root = yaml.getConfigurationSection("mails");
            if (root != null) {
                for (String key : root.getKeys(false)) {
                    ConfigurationSection s = root.getConfigurationSection(key);
                    if (s == null) {
                        continue;
                    }
                    Mail mail = new Mail();
                    mail.id = parseInt(key, 1);
                    mail.sender = color(s.getString("sender", "系统"));
                    mail.note = color(s.getString("note", ""));
                    mail.time = s.getLong("time", System.currentTimeMillis());
                    mail.read = s.getBoolean("read", false);
                    ConfigurationSection items = s.getConfigurationSection("items");
                    if (items != null) {
                        for (String ik : items.getKeys(false)) {
                            try {
                                ItemStack stack = items.getItemStack(ik);
                                if (stack != null && stack.getType() != Material.AIR) {
                                    mail.items.add(stack);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (!mail.items.isEmpty()) {
                        list.add(mail);
                    }
                }
            }
        }
        purgeExpired(list);
        mails.put(uuid, list);
        return list;
    }

    private void purgeExpired(List<Mail> list) {
        long days = Math.max(1, getConfig().getInt("expire-days", 30));
        long dead = System.currentTimeMillis() - days * 24L * 3600L * 1000L;
        Iterator<Mail> it = list.iterator();
        while (it.hasNext()) {
            if (it.next().time < dead) {
                it.remove();
            }
        }
    }

    private void savePlayer(UUID uuid) {
        List<Mail> list = mails.get(uuid);
        if (list == null) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-id", nextId.containsKey(uuid) ? nextId.get(uuid) : Integer.valueOf(1));
        for (Mail mail : list) {
            String base = "mails." + mail.id + ".";
            yaml.set(base + "sender", mail.sender);
            yaml.set(base + "note", mail.note);
            yaml.set(base + "time", Long.valueOf(mail.time));
            yaml.set(base + "read", Boolean.valueOf(mail.read));
            int j = 0;
            for (ItemStack stack : mail.items) {
                yaml.set(base + "items." + j, stack);
                j++;
            }
        }
        try {
            yaml.save(fileOf(uuid));
        } catch (IOException e) {
            getLogger().warning("保存邮件失败 " + uuid + ": " + e.getMessage());
        }
    }

    // ---------------- 对外 API ----------------

    public void send(UUID uuid, List<ItemStack> items, String sender, String note) {
        if (uuid == null || items == null || items.isEmpty()) {
            return;
        }
        List<Mail> list = loadPlayer(uuid);
        int max = Math.max(1, getConfig().getInt("max-mails", 60));
        while (list.size() >= max) {
            list.remove(0);
        }
        Mail mail = new Mail();
        mail.id = nextId.containsKey(uuid) ? nextId.get(uuid).intValue() : 1;
        nextId.put(uuid, Integer.valueOf(mail.id + 1));
        mail.sender = color(sender == null ? "系统" : sender);
        mail.note = color(note == null ? "" : note);
        mail.time = System.currentTimeMillis();
        for (ItemStack stack : items) {
            if (stack != null && stack.getType() != Material.AIR) {
                mail.items.add(stack.clone());
            }
        }
        if (mail.items.isEmpty()) {
            return;
        }
        list.add(mail);
        savePlayer(uuid);

        Player online = Bukkit.getPlayer(uuid);
        if (online != null && online.isOnline()) {
            online.sendMessage(color("&8[&b邮箱&8] &7你收到了一封新邮件，输入 &f/mail &7查看"));
            online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
        }
    }

    public void send(UUID uuid, ItemStack item, String sender, String note) {
        List<ItemStack> list = new ArrayList<ItemStack>();
        list.add(item);
        send(uuid, list, sender, note);
    }

    // ---------------- 命令 ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(color("&7控制台：/mail send <玩家> <物品键> <数量> [附言]"));
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("lonelymail.use")) {
                player.sendMessage(color("&c你没有邮箱权限"));
                return true;
            }
            openMailbox(player);
            return true;
        }
        String sub = args[0].toLowerCase();
        if ("compose".equals(sub) || "write".equals(sub)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(color("&c只有玩家能用可视化写信"));
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("lonelymail.admin")) {
                player.sendMessage(color("&c只有管理员能寄信"));
                return true;
            }
            startCompose(player);
            return true;
        }
        if ("send".equals(sub) || "senditem".equals(sub)) {
            return adminSend(sender, sub, args);
        }
        if ("list".equals(sub)) {
            return adminList(sender, args);
        }
        if ("clear".equals(sub)) {
            return adminClear(sender, args);
        }
        if ("help".equals(sub)) {
            sender.sendMessage(color("&8&m----------&r &bLonelyMail &8&m----------"));
            sender.sendMessage(color("&f/mail &7- 打开邮箱（玩家）"));
            if (sender.hasPermission("lonelymail.admin")) {
                sender.sendMessage(color("&f/mail compose &7- 可视化写信（管理员）"));
                sender.sendMessage(color("&f/mail send <玩家> <物品键> <数量> [附言] &7- 命令行寄信"));
                sender.sendMessage(color("&f/mail senditem <玩家> [附言] &7- 寄出手上的物品"));
                sender.sendMessage(color("&f/mail list|clear <玩家> &7- 查看/清空邮箱"));
            }
            return true;
        }
        sender.sendMessage(color("&c未知子命令，试试 /mail help"));
        return true;
    }

    private boolean adminSend(CommandSender sender, String sub, String[] args) {
        if (!sender.hasPermission("lonelymail.admin")) {
            sender.sendMessage(color("&c只有管理员能寄信"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(color("&c用法：/mail " + sub + " <玩家> " + ("send".equals(sub) ? "<物品键> <数量> " : "") + "[附言]"));
            return true;
        }
        UUID target = resolveUuid(args[1]);
        if (target == null) {
            sender.sendMessage(color("&c找不到玩家：" + args[1]));
            return true;
        }
        List<ItemStack> items = new ArrayList<ItemStack>();
        if ("send".equals(sub)) {
            if (args.length < 4) {
                sender.sendMessage(color("&c用法：/mail send <玩家> <物品键> <数量> [附言]"));
                return true;
            }
            int amount = parseInt(args[3], 1);
            ItemStack stack = resolveSxItem(args[2], amount);
            if (stack == null) {
                sender.sendMessage(color("&c找不到物品：" + args[2]));
                return true;
            }
            stack.setAmount(Math.max(1, Math.min(stack.getType().getMaxStackSize(), amount)));
            items.add(stack);
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(color("&c只有玩家能用 senditem"));
                return true;
            }
            ItemStack hand = ((Player) sender).getInventory().getItemInMainHand();
            if (hand == null || hand.getType() == Material.AIR) {
                sender.sendMessage(color("&c手上没有物品"));
                return true;
            }
            items.add(hand.clone());
        }
        StringBuilder note = new StringBuilder();
        int start = "send".equals(sub) ? 4 : 2;
        for (int i = start; i < args.length; i++) {
            if (note.length() > 0) {
                note.append(' ');
            }
            note.append(args[i]);
        }
        String senderName = sender instanceof Player ? ((Player) sender).getName() : "系统";
        send(target, items, senderName, note.length() == 0 ? "来自 " + senderName + " 的邮件" : note.toString());
        sender.sendMessage(color("&a已寄出：" + args[1] + " 收到 " + items.size() + " 件物品"));
        return true;
    }

    private boolean adminList(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lonelymail.admin")) {
            sender.sendMessage(color("&c没有权限"));
            return true;
        }
        if (args.length >= 2) {
            UUID uuid = resolveUuid(args[1]);
            if (uuid == null) {
                sender.sendMessage(color("&c找不到玩家"));
                return true;
            }
            List<Mail> list = loadPlayer(uuid);
            sender.sendMessage(color("&8[&b邮箱&8] &7" + args[1] + " 待领取 &f" + list.size() + " &7封"));
            for (Mail mail : list) {
                sender.sendMessage(color("  &7#" + mail.id + " 来自 &f" + mail.sender + " &7物品 &f" + mail.items.size()
                        + " &7件 &8(" + timeText(mail.time) + ")" + (mail.read ? "" : " &c[未读]")));
            }
            return true;
        }
        int total = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            int n = loadPlayer(p.getUniqueId()).size();
            if (n > 0) {
                total += n;
                sender.sendMessage(color("&7" + p.getName() + " &8-&f " + n + " 封"));
            }
        }
        sender.sendMessage(color("&8[&b邮箱&8] &7在线玩家共有 &f" + total + " &7封未领取邮件"));
        return true;
    }

    private boolean adminClear(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lonelymail.admin")) {
            sender.sendMessage(color("&c没有权限"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(color("&c用法：/mail clear <玩家>"));
            return true;
        }
        UUID uuid = resolveUuid(args[1]);
        if (uuid == null) {
            sender.sendMessage(color("&c找不到玩家"));
            return true;
        }
        int n = loadPlayer(uuid).size();
        loadPlayer(uuid).clear();
        savePlayer(uuid);
        sender.sendMessage(color("&a已清空 " + args[1] + " 的 " + n + " 封邮件"));
        return true;
    }

    // ---------------- 写信流程 ----------------

    private void startCompose(Player player) {
        if (composing.containsKey(player.getUniqueId()) && composing.get(player.getUniqueId()).stage != Stage.NONE) {
            player.sendMessage(color("&c你已经在写信了，先完成或取消当前这封"));
            return;
        }
        Compose compose = new Compose();
        compose.stage = Stage.NOTE;
        composing.put(player.getUniqueId(), compose);
        player.closeInventory();
        player.sendMessage(color("&8&m----------&r &b写信 &8&m----------"));
        player.sendMessage(color("&7第 1 步：&f在聊天栏输入邮件正文"));
        player.sendMessage(color("&7输入 &f取消 &7可放弃本次写信"));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        final Compose compose = composing.get(player.getUniqueId());
        if (compose == null) {
            return;
        }
        if (compose.stage == Stage.NOTE) {
            event.setCancelled(true);
            final String text = event.getMessage();
            runTask(new Runnable() {
                @Override
                public void run() {
                    if ("取消".equals(text.trim()) || "cancel".equalsIgnoreCase(text.trim())) {
                        composing.remove(player.getUniqueId());
                        player.sendMessage(color("&c已取消写信"));
                        return;
                    }
                    compose.note = text;
                    compose.stage = Stage.ITEMS;
                    openEditor(player, compose);
                }
            });
            return;
        }
        if (compose.stage == Stage.SEARCH) {
            event.setCancelled(true);
            final String text = event.getMessage().trim();
            runTask(new Runnable() {
                @Override
                public void run() {
                    UUID uuid = resolveUuid(text);
                    if (uuid == null) {
                        player.sendMessage(color("&c找不到玩家：" + text + "，再输一次或输入 取消"));
                        return;
                    }
                    finishCompose(player, compose, uuid, text);
                }
            });
        }
    }

    private void openEditor(Player player, Compose compose) {
        Holder holder = new Holder(ViewType.EDITOR);
        Inventory inv = Bukkit.createInventory(holder, SIZE, editorTitle);
        holder.inventory = inv;
        compose.editor = inv;
        for (int i = EDIT_ROWS; i < SIZE; i++) {
            inv.setItem(i, pane((short) 15));
        }
        inv.setItem(EDIT_CANCEL, icon(Material.BARRIER, "&c取消写信", "&8编辑框里的物品会退回背包"));
        inv.setItem(EDIT_NEXT, icon(Material.EMERALD_BLOCK, "&a下一步：选收件人",
                "&8把要寄的物品放进上面的格子",
                "&7然后点这里选择收件人"));
        inv.setItem(EDIT_HELP, icon(Material.PAPER, "&7怎么写",
                "&8· 从背包拖物品到上面 45 格",
                "&8· 可以放多格、多件",
                "&8· 点绿色块进入选人步骤",
                "&8· 取消会把物品退回背包"));
        player.openInventory(inv);
        player.sendMessage(color("&7第 2 步：&f把要寄的物品放进编辑框，然后点绿色块"));
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.1f);
    }

    private void openPicker(Player player, Compose compose) {
        Holder holder = new Holder(ViewType.PICKER);
        Inventory inv = Bukkit.createInventory(holder, SIZE, pickerTitle);
        holder.inventory = inv;
        compose.stage = Stage.PICKER;
        compose.editor = null;

        for (int i = 45; i < SIZE; i++) {
            inv.setItem(i, pane((short) 15));
        }
        inv.setItem(PICK_CANCEL, icon(Material.BARRIER, "&c放弃这封邮件", "&8物品会退回背包"));
        inv.setItem(PICK_SEARCH, icon(Material.COMPASS, "&e搜索玩家", "&8点击后在聊天栏输入玩家名"));
        int allCount = recipients(player).size();
        if (compose.confirmAll) {
            inv.setItem(PICK_ALL, icon(Material.TNT, "&c&l再点一次：群发全部",
                    "&8会发给 &f" + allCount + " &8名玩家（含离线）",
                    "&c确认请再点一次"));
        } else {
            inv.setItem(PICK_ALL, icon(Material.FIREWORK, "&6&l全部发送",
                    "&8发给所有玩家（含离线）",
                    "&7当前共 &f" + allCount + " &7人",
                    "&8点一次先确认，再点一次才发"));
        }

        List<String> candidates = new ArrayList<String>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(player.getUniqueId())) {
                candidates.add(p.getName());
            }
        }
        for (org.bukkit.OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && !candidates.contains(op.getName())
                    && !op.getUniqueId().equals(player.getUniqueId())) {
                candidates.add(op.getName());
            }
            if (candidates.size() >= 45) {
                break;
            }
        }
        int slot = 0;
        for (String name : candidates) {
            if (slot >= 45) {
                break;
            }
            if (slot == PICK_CANCEL || slot == PICK_SEARCH) {
                continue;
            }
            inv.setItem(slot, head(name));
            slot++;
        }
        player.openInventory(inv);
        player.sendMessage(color("&7第 3 步：&f点击玩家头像选择收件人，或点指南针搜索"));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.3f);
    }

    private void finishCompose(Player player, Compose compose, UUID target, String targetName) {
        if (compose.items.isEmpty()) {
            player.sendMessage(color("&c没有任何物品，写信已取消"));
            composing.remove(player.getUniqueId());
            return;
        }
        send(target, compose.items, player.getName(), compose.note);
        composing.remove(player.getUniqueId());
        player.closeInventory();
        player.sendMessage(color("&a邮件已寄给 &f" + targetName + " &a（" + compose.items.size() + " 件物品）"));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
        if (target.equals(player.getUniqueId())) {
            player.sendMessage(color("&7（你寄给了自己，打开邮箱就能看到）"));
        }
    }

    private List<UUID> recipients(Player exclude) {
        List<UUID> list = new ArrayList<UUID>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(exclude.getUniqueId())) {
                list.add(p.getUniqueId());
            }
        }
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() == null || op.getUniqueId().equals(exclude.getUniqueId())) {
                continue;
            }
            if (!list.contains(op.getUniqueId())) {
                list.add(op.getUniqueId());
            }
        }
        return list;
    }

    private void sendAll(Player player, Compose compose) {
        if (compose.items.isEmpty()) {
            player.sendMessage(color("&c没有物品可寄"));
            composing.remove(player.getUniqueId());
            return;
        }
        List<UUID> targets = recipients(player);
        for (UUID uuid : targets) {
            send(uuid, compose.items, player.getName(), compose.note);
        }
        composing.remove(player.getUniqueId());
        player.closeInventory();
        player.sendMessage(color("&a邮件已群发给 &f" + targets.size() + " &a名玩家（各 " + compose.items.size() + " 件物品）"));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.1f);
    }

    private void cancelCompose(Player player, Compose compose) {
        returnItems(player, compose.items);
        compose.items.clear();
        composing.remove(player.getUniqueId());
        player.closeInventory();
        player.sendMessage(color("&7已取消写信，物品已退回背包"));
    }

    private void returnItems(Player player, List<ItemStack> items) {
        for (ItemStack stack : items) {
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(stack.clone());
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    // ---------------- 邮箱界面 ----------------

    private void openMailbox(Player player) {
        List<Mail> list = loadPlayer(player.getUniqueId());
        Holder holder = new Holder(ViewType.MAILBOX);
        Inventory inv = Bukkit.createInventory(holder, SIZE, title);
        holder.inventory = inv;

        for (int i = 45; i < SIZE; i++) {
            inv.setItem(i, pane((short) 15));
        }
        inv.setItem(0, pane((short) 3));
        inv.setItem(8, pane((short) 3));
        inv.setItem(36, pane((short) 3));
        inv.setItem(44, pane((short) 3));

        int idx = 0;
        for (Mail mail : list) {
            if (idx >= EDIT_ROWS) {
                break;
            }
            inv.setItem(idx, mailIcon(mail));
            idx++;
        }

        inv.setItem(SLOT_CLOSE, icon(Material.BARRIER, "&c关闭", "&8点击关闭邮箱"));
        inv.setItem(SLOT_CLAIM_ALL, icon(Material.EMERALD_BLOCK, "&a一键领取",
                "&8领取所有邮件里的物品", "&7背包满时保留未领取部分", "",
                "&7当前可领取：&f" + countItems(list) + " 件"));
        if (player.hasPermission("lonelymail.admin")) {
            inv.setItem(SLOT_COMPOSE, icon(Material.BOOK_AND_QUILL, "&e&l写邮件",
                    "&8管理员功能", "&7先输入正文，再放物品、选收件人"));
        }
        inv.setItem(SLOT_HELP, icon(Material.PAPER, "&7邮箱规则",
                "&8· 左键邮件领取附件",
                "&8· 右键邮件查看附言",
                "&8· 邮件保留 " + getConfig().getInt("expire-days", 30) + " 天",
                "&8· 背包满时物品留在邮箱"));

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
    }

    private int countItems(List<Mail> list) {
        int n = 0;
        for (Mail mail : list) {
            n += mail.items.size();
        }
        return n;
    }

    private ItemStack mailIcon(Mail mail) {
        ItemStack stack = mail.items.get(0).clone();
        ItemMeta meta = stack.getItemMeta();
        List<String> lore = new ArrayList<String>();
        lore.add(color((mail.read ? "&8" : "&e✦ &8") + "邮件 #" + mail.id + " &8· " + timeText(mail.time)));
        lore.add(color("&7寄件人：&f" + mail.sender));
        if (mail.note != null && mail.note.length() > 0) {
            lore.add(color("&7附言：&f" + mail.note));
        }
        lore.add(color("&7附件：&f" + mail.items.size() + " 件"));
        lore.add("");
        lore.add(color("&a左键领取"));
        lore.add(color("&7右键查看附言"));
        meta.setLore(lore);
        meta.setDisplayName(color((mail.read ? "&f" : "&e") + "邮件 #" + mail.id));
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Inventory inv = event.getInventory();
        if (inv == null || !(inv.getHolder() instanceof Holder)) {
            return;
        }
        Holder holder = (Holder) inv.getHolder();
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        Compose compose = composing.get(player.getUniqueId());

        if (holder.type == ViewType.EDITOR) {
            if (compose == null || compose.stage != Stage.ITEMS) {
                event.setCancelled(true);
                return;
            }
            if (slot >= EDIT_ROWS && slot < SIZE) {
                event.setCancelled(true);
                if (slot == EDIT_CANCEL) {
                    player.closeInventory();
                } else if (slot == EDIT_NEXT) {
                    List<ItemStack> items = new ArrayList<ItemStack>();
                    for (int i = 0; i < EDIT_ROWS; i++) {
                        ItemStack stack = inv.getItem(i);
                        if (stack != null && stack.getType() != Material.AIR) {
                            items.add(stack.clone());
                            inv.setItem(i, null);
                        }
                    }
                    if (items.isEmpty()) {
                        player.sendMessage(color("&c编辑框里没有物品，先放东西再点下一步"));
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BASS, 0.7f, 0.7f);
                        return;
                    }
                    compose.items = items;
                    openPicker(player, compose);
                }
            }
            return;
        }

        if (holder.type == ViewType.PICKER) {
            event.setCancelled(true);
            if (compose == null || compose.stage != Stage.PICKER) {
                return;
            }
            if (slot == PICK_CANCEL) {
                cancelCompose(player, compose);
                return;
            }
            if (slot == PICK_ALL) {
                if (compose.items.isEmpty()) {
                    player.sendMessage(color("&c没有物品可寄"));
                    return;
                }
                if (!compose.confirmAll) {
                    compose.confirmAll = true;
                    openPicker(player, compose);
                    player.sendMessage(color("&e再点一次「全部发送」确认群发"));
                    return;
                }
                sendAll(player, compose);
                return;
            }
            if (slot == PICK_SEARCH) {
                compose.stage = Stage.SEARCH;
                player.closeInventory();
                player.sendMessage(color("&7在聊天栏输入玩家名（输入 取消 放弃）"));
                return;
            }
            ItemStack clicked = inv.getItem(slot);
            if (clicked == null || clicked.getType() == Material.AIR) {
                return;
            }
            String name = skullOwner(clicked);
            if (name == null) {
                return;
            }
            UUID target = resolveUuid(name);
            if (target == null) {
                player.sendMessage(color("&c找不到玩家：" + name));
                return;
            }
            finishCompose(player, compose, target, name);
            return;
        }

        // 邮箱界面
        event.setCancelled(true);
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        List<Mail> list = loadPlayer(player.getUniqueId());

        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.5f, 1.2f);
            return;
        }
        if (slot == SLOT_COMPOSE) {
            if (player.hasPermission("lonelymail.admin")) {
                startCompose(player);
            }
            return;
        }
        if (slot == SLOT_CLAIM_ALL) {
            int claimed = 0;
            Iterator<Mail> it = list.iterator();
            while (it.hasNext()) {
                Mail mail = it.next();
                claimed += claim(player, mail);
                if (mail.items.isEmpty()) {
                    it.remove();
                }
            }
            savePlayer(player.getUniqueId());
            player.sendMessage(color("&8[&b邮箱&8] &a已领取 &f" + claimed + " &a件物品"));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f);
            openMailbox(player);
            return;
        }
        if (slot >= list.size()) {
            return;
        }
        Mail mail = list.get(slot);
        if (event.isRightClick()) {
            player.sendMessage(color("&8&m--------&r &b邮件 #" + mail.id + " &8&m--------"));
            player.sendMessage(color("&7寄件人：&f" + mail.sender));
            player.sendMessage(color("&7时间：&f" + timeText(mail.time)));
            player.sendMessage(color("&7附言：&f" + (mail.note == null || mail.note.isEmpty() ? "（无）" : mail.note)));
            player.sendMessage(color("&7附件：&f" + mail.items.size() + " 件"));
            mail.read = true;
            savePlayer(player.getUniqueId());
            openMailbox(player);
            return;
        }
        int claimed = claim(player, mail);
        if (mail.items.isEmpty()) {
            list.remove(mail);
        }
        mail.read = true;
        savePlayer(player.getUniqueId());
        player.sendMessage(color("&8[&b邮箱&8] &a领取 &f" + claimed + " &a件物品"
                + (mail.items.isEmpty() ? "" : "&7，背包满了，剩余 &f" + mail.items.size() + " &7件留在邮箱")));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
        openMailbox(player);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Inventory inv = event.getInventory();
        if (inv == null || !(inv.getHolder() instanceof Holder)) {
            return;
        }
        Holder holder = (Holder) inv.getHolder();
        if (holder.type != ViewType.EDITOR) {
            event.setCancelled(true);
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot >= EDIT_ROWS && slot < SIZE) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Inventory inv = event.getInventory();
        if (inv == null || !(inv.getHolder() instanceof Holder)) {
            return;
        }
        Holder holder = (Holder) inv.getHolder();
        if (holder.type != ViewType.EDITOR) {
            return;
        }
        Player player = (Player) event.getPlayer();
        Compose compose = composing.get(player.getUniqueId());
        if (compose == null || compose.stage != Stage.ITEMS) {
            return;
        }
        // 关闭编辑框：退回物品并取消
        List<ItemStack> back = new ArrayList<ItemStack>();
        for (int i = 0; i < EDIT_ROWS; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && stack.getType() != Material.AIR) {
                back.add(stack.clone());
                inv.setItem(i, null);
            }
        }
        if (!back.isEmpty()) {
            returnItems(player, back);
        }
        composing.remove(player.getUniqueId());
        player.sendMessage(color("&7已关闭编辑框，物品已退回背包（写信已取消）"));
    }

    private int claim(Player player, Mail mail) {
        int claimed = 0;
        Iterator<ItemStack> it = mail.items.iterator();
        while (it.hasNext()) {
            ItemStack stack = it.next();
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(stack.clone());
            if (leftover.isEmpty()) {
                it.remove();
                claimed += stack.getAmount();
            } else {
                ItemStack back = leftover.values().iterator().next();
                stack.setAmount(back.getAmount());
            }
        }
        return claimed;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        runTaskLater(new Runnable() {
            @Override
            public void run() {
                List<Mail> list = loadPlayer(player.getUniqueId());
                if (list.isEmpty()) {
                    return;
                }
                int unread = 0;
                for (Mail mail : list) {
                    if (!mail.read) {
                        unread++;
                    }
                }
                player.sendMessage(color("&8[&b邮箱&8] &7你有 &f" + list.size() + " &7封邮件"
                        + (unread > 0 ? "（&c" + unread + " 封未读&7）" : "") + "，输入 &f/mail &7查看"));
            }
        }, 30L);
    }

    // ---------------- 工具 ----------------

    private enum ViewType { MAILBOX, EDITOR, PICKER }

    private enum Stage { NONE, NOTE, ITEMS, PICKER, SEARCH }

    private static class Holder implements InventoryHolder {
        private final ViewType type;
        private Inventory inventory;

        Holder(ViewType type) {
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static class Compose {
        String note = "";
        List<ItemStack> items = new ArrayList<ItemStack>();
        Stage stage = Stage.NONE;
        boolean confirmAll;
        Inventory editor;
    }

    private static class Mail {
        int id;
        String sender = "系统";
        String note = "";
        long time;
        boolean read;
        List<ItemStack> items = new ArrayList<ItemStack>();
    }

    private void runTask(Runnable task) {
        Bukkit.getScheduler().runTask(this, task);
    }

    private void runTaskLater(Runnable task, long delay) {
        Bukkit.getScheduler().runTaskLater(this, task, delay);
    }

    private ItemStack pane(short data) {
        ItemStack stack = new ItemStack(Material.STAINED_GLASS_PANE, 1, data);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName("§r");
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack icon(Material material, String name, String... loreLines) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(color(name));
        List<String> lore = new ArrayList<String>();
        Collections.addAll(lore, loreLines);
        for (int i = 0; i < lore.size(); i++) {
            lore.set(i, color(lore.get(i)));
        }
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack head(String name) {
        ItemStack stack = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        meta.setOwner(name);
        meta.setDisplayName(color("&f" + name));
        List<String> lore = new ArrayList<String>();
        lore.add(color("&7点击把邮件寄给 &f" + name));
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private String skullOwner(ItemStack stack) {
        if (stack.getType() != Material.SKULL_ITEM) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof SkullMeta)) {
            return null;
        }
        return ((SkullMeta) meta).getOwner();
    }

    private ItemStack resolveSxItem(String key, int amount) {
        try {
            org.bukkit.plugin.Plugin sx = Bukkit.getPluginManager().getPlugin("SX-Attribute");
            if (sx != null) {
                Object manager = sx.getClass().getMethod("getItemDataManager").invoke(sx);
                Method get = manager.getClass().getMethod("getItem", String.class, Player.class);
                ItemStack stack = (ItemStack) get.invoke(manager, key, null);
                if (stack != null) {
                    return stack;
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    ItemStack s2 = (ItemStack) get.invoke(manager, key, p);
                    if (s2 != null) {
                        return s2;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        Material material = Material.matchMaterial(key);
        if (material != null && material != Material.AIR) {
            return new ItemStack(material, Math.max(1, amount));
        }
        return null;
    }

    private UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        for (org.bukkit.OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(name)) {
                return op.getUniqueId();
            }
        }
        return null;
    }

    private static String color(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }

    private static String timeText(long millis) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm");
        return fmt.format(new java.util.Date(millis));
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
