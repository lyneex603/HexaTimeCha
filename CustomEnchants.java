package me.customenchants;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Effect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Levelled;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class CustomEnchants extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // ---- настройки (меняются здесь, потом пересобрать) ----
    private static final int MAX_LOGS = 64;               // Дровосек: сколько брёвен максимум
    private static final double VAMPIRE_CHANCE = 0.15;    // Вампиризм: шанс за каждый уровень
    private static final double VAMPIRE_MAX_HEAL = 6.0;   // Вампиризм: макс. лечение за удар (3 сердца)
    private static final double VAMPIRE_PART = 0.5;       // Вампиризм: доля нанесённого урона
    private static final int LAVA_RESTORE_TICKS = 80;     // Лаваходы: через сколько тиков лава возвращается
    private static final int XP_BONUS_PER_LEVEL = 1;      // Опытный: +100% опыта за уровень

    private static final String PERM = "customenchants.admin";
    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    /** Какие дропы превращает Автоплавка. */
    private static final Map<Material, Material> SMELT_MAP = Map.of(
            Material.RAW_IRON, Material.IRON_INGOT,
            Material.RAW_GOLD, Material.GOLD_INGOT,
            Material.RAW_COPPER, Material.COPPER_INGOT,
            Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP
    );

    /** Блоки, которые Бур, Бульдозер и Дровосек никогда не ломают. */
    private static final Set<Material> NO_BREAK = EnumSet.of(
            Material.SPAWNER, Material.REINFORCED_DEEPSLATE, Material.END_PORTAL_FRAME
    );

    /** Культуры, которые пересаживает Садовод. */
    private static final Set<Material> CROPS = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.NETHER_WART
    );

    /** Наковальня: до какого уровня можно слить две одинаковые максимальные ванильные чары. */
    private static final Map<Enchantment, Integer> ANVIL_CAPS = Map.of(
            Enchantment.PROTECTION, 5,   // Защита: 4 + 4 -> 5
            Enchantment.SHARPNESS, 7     // Острота: 5 + 5 -> 6, 6 + 6 -> 7
    );
    private static final int ANVIL_COST = 15;  // Стоимость такого слияния в уровнях опыта

    private static CustomEnchants instance;

    /** Игроки, у которых сейчас идёт массовая добыча (чтобы не было рекурсии). */
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    /** Блоки лавы, временно превращённые в обсидиан. */
    private final Set<Block> pendingLava = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------ список чар

    enum CE {
        DRILL("drill", "Бур", 2, "_PICKAXE"),
        BULLDOZER("bulldozer", "Бульдозер", 1, "_PICKAXE", "_SHOVEL"),
        SMELT("smelt", "Автоплавка", 1, "_PICKAXE"),
        GODTOUCH("godtouch", "Божье касание", 1, "_PICKAXE"),
        TIMBER("timber", "Дровосек", 1, "_AXE"),
        XP("xp", "Опытный", 3, "_PICKAXE", "_SHOVEL", "_AXE", "_HOE"),
        MAGNET("magnet", "Магнит", 1, "_PICKAXE", "_SHOVEL", "_AXE", "_HOE", "_SWORD"),
        REPLANT("replant", "Садовод", 1, "_HOE"),
        VAMPIRE("vampire", "Вампиризм", 3, "_SWORD", "_AXE"),
        POISON("poison", "Яд", 3, "_SWORD", "_AXE"),
        LAVA("lavawalker", "Лаваходы", 1, "_BOOTS"),
        SPEED("speed", "Ускорение", 3, "_BOOTS"),
        AQUA("aqua", "Подводник", 1, "_HELMET"),
        NIGHT("night", "Ночное зрение", 1, "_HELMET"),
        UNBREAKABLE("unbreakable", "Нерушимость", 1, "_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS");

        final String id;
        final String title;
        final int max;
        final String[] suffixes;

        CE(String id, String title, int max, String... suffixes) {
            this.id = id;
            this.title = title;
            this.max = max;
            this.suffixes = suffixes;
        }

        boolean applies(Material m) {
            // Без суффиксов: подходит любому предмету с прочностью (броня, инструменты, оружие)
            if (suffixes.length == 0) return m.getMaxDurability() > 0;
            for (String s : suffixes) {
                if (m.name().endsWith(s)) return true;
            }
            return false;
        }

        static CE byId(String id) {
            for (CE c : values()) {
                if (c.id.equalsIgnoreCase(id)) return c;
            }
            return null;
        }
    }

    private static NamespacedKey key(CE ce) {
        return new NamespacedKey(instance, ce.id);
    }

    private static NamespacedKey loreKey() {
        return new NamespacedKey(instance, "lore_n");
    }

    private static String roman(int n) {
        return n >= 1 && n < ROMAN.length ? ROMAN[n] : String.valueOf(n);
    }

    private static PotionEffectType eff(String key) {
        return PotionEffectType.getByKey(NamespacedKey.minecraft(key));
    }

    // ------------------------------------------------------------------ старт / стоп

    @Override
    public void onEnable() {
        instance = this;
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("cench").setExecutor(this);
        getCommand("cench").setTabCompleter(this);

        // Пассивные эффекты: ботинки и шлем (раз в 2 секунды)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            PotionEffectType speed = eff("speed");
            PotionEffectType night = eff("night_vision");
            PotionEffectType haste = eff("haste");
            for (Player p : Bukkit.getOnlinePlayers()) {
                int s = level(p.getInventory().getBoots(), CE.SPEED);
                if (s > 0 && speed != null) {
                    p.addPotionEffect(new PotionEffect(speed, 60, s - 1, true, false, false));
                }
                ItemStack helmet = p.getInventory().getHelmet();
                if (level(helmet, CE.NIGHT) > 0 && night != null) {
                    p.addPotionEffect(new PotionEffect(night, 220, 0, true, false, false));
                }
                if (level(helmet, CE.AQUA) > 0 && haste != null && p.isInWater()) {
                    p.addPotionEffect(new PotionEffect(haste, 60, 1, true, false, false));
                }
            }
        }, 40L, 40L);

        // Лаваходы: чаще, чтобы лава под ногами успевала застыть
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                ItemStack boots = p.getInventory().getBoots();
                if (boots == null || !boots.hasItemMeta()) continue;
                if (level(boots, CE.LAVA) > 0) lavaWalk(p);
            }
        }, 4L, 4L);
    }

    @Override
    public void onDisable() {
        // Вернуть лаву на место, если сервер выключают раньше времени
        for (Block b : pendingLava) {
            if (b.getType() == Material.OBSIDIAN) b.setType(Material.LAVA);
        }
        pendingLava.clear();
    }

    // ------------------------------------------------------------------ чтение / запись чар на предмете

    private int level(ItemStack it, CE ce) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return 0;
        return it.getItemMeta().getPersistentDataContainer()
                .getOrDefault(key(ce), PersistentDataType.INTEGER, 0);
    }

    /** Ставит уровень (0 = снять) и пересобирает строки зачарований в описании предмета. */
    @SuppressWarnings("deprecation")
    private void setLevel(ItemStack it, CE ce, int lvl) {
        ItemMeta m = it.getItemMeta();
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        if (lvl <= 0) {
            pdc.remove(key(ce));
        } else {
            pdc.set(key(ce), PersistentDataType.INTEGER, lvl);
        }

        List<String> lore = m.hasLore() ? new ArrayList<>(m.getLore()) : new ArrayList<>();
        int old = pdc.getOrDefault(loreKey(), PersistentDataType.INTEGER, 0);
        for (int i = 0; i < old && !lore.isEmpty(); i++) lore.remove(0);

        List<String> mine = new ArrayList<>();
        for (CE c : CE.values()) {
            int l = pdc.getOrDefault(key(c), PersistentDataType.INTEGER, 0);
            if (l > 0) mine.add(ChatColor.GRAY + c.title + " " + roman(l));
        }
        lore.addAll(0, mine);
        m.setLore(lore.isEmpty() ? null : lore);

        if (mine.isEmpty()) {
            pdc.remove(loreKey());
        } else {
            pdc.set(loreKey(), PersistentDataType.INTEGER, mine.size());
        }
        it.setItemMeta(m);
    }

    private void give(Player p, Location loc, ItemStack s) {
        for (ItemStack left : p.getInventory().addItem(s).values()) {
            loc.getWorld().dropItemNaturally(loc, left);
        }
    }

    // ------------------------------------------------------------------ добыча: Бур, Бульдозер, Дровосек

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (busy.contains(p.getUniqueId())) return;
        if (p.getGameMode() != GameMode.SURVIVAL) return;

        ItemStack tool = p.getInventory().getItemInMainHand();
        int drill = level(tool, CE.DRILL);
        int bull = level(tool, CE.BULLDOZER);
        int timber = level(tool, CE.TIMBER);
        if (drill == 0 && bull == 0 && timber == 0) return;

        Block origin = e.getBlock();
        Set<Block> extra = new LinkedHashSet<>();

        if (drill > 0) {
            RayTraceResult r = p.rayTraceBlocks(8);
            BlockFace face = (r != null && r.getHitBlockFace() != null) ? r.getHitBlockFace() : BlockFace.UP;
            extra.addAll(drillBlocks(origin, face, drill, tool));
        }
        if (bull > 0) {
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        Block t = origin.getRelative(x, y, z);
                        if (breakable(t, tool)) extra.add(t);
                    }
                }
            }
        }
        if (timber > 0 && Tag.LOGS.isTagged(origin.getType())) {
            extra.addAll(logs(origin));
        }
        if (extra.isEmpty()) return;

        boolean smelt = level(tool, CE.SMELT) > 0;
        boolean magnet = level(tool, CE.MAGNET) > 0;
        int broken = 0;
        busy.add(p.getUniqueId());
        try {
            for (Block b : extra) {
                // Вызываем обычное событие, чтобы приваты и другие плагины могли запретить
                BlockBreakEvent ev = new BlockBreakEvent(b, p);
                Bukkit.getPluginManager().callEvent(ev);
                if (ev.isCancelled()) continue;
                breakBlock(b, p, tool, smelt, magnet, ev.isDropItems());
                broken++;
            }
        } finally {
            busy.remove(p.getUniqueId());
        }
        wear(tool, broken);
    }

    private List<Block> drillBlocks(Block center, BlockFace face, int radius, ItemStack tool) {
        List<Block> out = new ArrayList<>();
        for (int a = -radius; a <= radius; a++) {
            for (int b = -radius; b <= radius; b++) {
                if (a == 0 && b == 0) continue;
                int dx = 0, dy = 0, dz = 0;
                switch (face) {
                    case UP, DOWN -> {
                        dx = a;
                        dz = b;
                    }
                    case NORTH, SOUTH -> {
                        dx = a;
                        dy = b;
                    }
                    default -> {
                        dz = a;
                        dy = b;
                    }
                }
                Block t = center.getRelative(dx, dy, dz);
                if (breakable(t, tool)) out.add(t);
            }
        }
        return out;
    }

    private boolean breakable(Block b, ItemStack tool) {
        Material m = b.getType();
        if (m.isAir() || b.isLiquid()) return false;
        if (m.getHardness() < 0 || NO_BREAK.contains(m)) return false;
        return b.isPreferredTool(tool);
    }

    private List<Block> logs(Block start) {
        List<Block> out = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty() && out.size() < MAX_LOGS) {
            Block cur = queue.poll();
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        Block n = cur.getRelative(x, y, z);
                        if (seen.add(n) && Tag.LOGS.isTagged(n.getType()) && out.size() < MAX_LOGS) {
                            out.add(n);
                            queue.add(n);
                        }
                    }
                }
            }
        }
        return out;
    }

    private void breakBlock(Block b, Player p, ItemStack tool, boolean smelt, boolean magnet, boolean drop) {
        Collection<ItemStack> drops = drop ? b.getDrops(tool, p) : List.of();
        Location loc = b.getLocation().add(0.5, 0.5, 0.5);
        b.getWorld().playEffect(loc, Effect.STEP_SOUND, b.getBlockData());
        b.setType(Material.AIR);
        for (ItemStack d : drops) {
            ItemStack out = d;
            if (smelt) {
                Material res = SMELT_MAP.get(d.getType());
                if (res != null) out = new ItemStack(res, d.getAmount());
            }
            if (magnet) {
                give(p, loc, out);
            } else {
                b.getWorld().dropItemNaturally(loc, out);
            }
        }
    }

    /** Лишние сломанные блоки тратят прочность, но сам инструмент от этого не ломается. */
    private void wear(ItemStack tool, int amount) {
        if (amount <= 0 || level(tool, CE.UNBREAKABLE) > 0) return;
        ItemMeta m = tool.getItemMeta();
        int max = tool.getType().getMaxDurability();
        if (max <= 0 || m.isUnbreakable()) return;
        if (m instanceof Damageable d) {
            d.setDamage(Math.min(max - 1, d.getDamage() + amount));
            tool.setItemMeta(d);
        }
    }

    /** Кирка с Божьим касанием ломает только спавнер: любой другой блок ломаться не будет. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGodTouchGuard(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (level(tool, CE.GODTOUCH) > 0 && e.getBlock().getType() != Material.SPAWNER) {
            e.setCancelled(true);
            msg(p, ChatColor.RED + "Эта кирка с Божьим касанием ломает только спавнер.");
        }
    }

    // ------------------------------------------------------------------ один блок: Божье касание, Опытный, Магнит (опыт), Садовод

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakSingle(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (busy.contains(p.getUniqueId())) return;
        if (p.getGameMode() != GameMode.SURVIVAL) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (tool.getType().isAir() || !tool.hasItemMeta()) return;

        Block block = e.getBlock();

        // Божье касание: одноразовое, ломает только спавнер и сгорает после него
        if (level(tool, CE.GODTOUCH) > 0 && block.getType() == Material.SPAWNER
                && block.getState() instanceof CreatureSpawner cs) {
            ItemStack spawner = new ItemStack(Material.SPAWNER);
            if (spawner.getItemMeta() instanceof BlockStateMeta bsm) {
                bsm.setBlockState(cs);
                spawner.setItemMeta(bsm);
            }
            e.setDropItems(false);
            e.setExpToDrop(0);
            give(p, block.getLocation().add(0.5, 0.5, 0.5), spawner);

            setLevel(tool, CE.GODTOUCH, 0);
            p.getInventory().setItemInMainHand(tool);
            msg(p, ChatColor.YELLOW + "Божье касание израсходовано.");
            return;
        }

        // Опытный: больше опыта с блока
        int xp = level(tool, CE.XP);
        if (xp > 0 && e.getExpToDrop() > 0) {
            e.setExpToDrop(e.getExpToDrop() * (1 + XP_BONUS_PER_LEVEL * xp));
        }
        // Магнит: опыт сразу игроку
        if (level(tool, CE.MAGNET) > 0 && e.getExpToDrop() > 0) {
            p.giveExp(e.getExpToDrop());
            e.setExpToDrop(0);
        }

        // Садовод: созревшая культура пересаживается сразу
        if (level(tool, CE.REPLANT) > 0 && CROPS.contains(block.getType())
                && block.getBlockData() instanceof Ageable age && age.getAge() >= age.getMaximumAge()) {
            Material crop = block.getType();
            Bukkit.getScheduler().runTask(this, () -> {
                if (block.getType().isAir()) {
                    block.setType(crop);
                    if (block.getBlockData() instanceof Ageable fresh) {
                        fresh.setAge(0);
                        block.setBlockData(fresh);
                    }
                }
            });
        }
    }

    /** Автоплавка и Магнит для обычного (одного) блока. */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        boolean smelt = level(tool, CE.SMELT) > 0;
        boolean magnet = level(tool, CE.MAGNET) > 0;
        if (!smelt && !magnet) return;

        Iterator<Item> iter = e.getItems().iterator();
        while (iter.hasNext()) {
            Item it = iter.next();
            ItemStack s = it.getItemStack();
            if (smelt) {
                Material res = SMELT_MAP.get(s.getType());
                if (res != null) {
                    s = new ItemStack(res, s.getAmount());
                    it.setItemStack(s);
                }
            }
            if (magnet) {
                Map<Integer, ItemStack> left = p.getInventory().addItem(s);
                if (left.isEmpty()) {
                    iter.remove();
                } else {
                    it.setItemStack(left.values().iterator().next());
                }
            }
        }
    }

    /** Магнит с оружия: добыча с мобов сразу в инвентарь. */
    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        Player p = e.getEntity().getKiller();
        if (p == null) return;
        if (level(p.getInventory().getItemInMainHand(), CE.MAGNET) == 0) return;

        List<ItemStack> keep = new ArrayList<>();
        for (ItemStack d : new ArrayList<>(e.getDrops())) {
            keep.addAll(p.getInventory().addItem(d).values());
        }
        e.getDrops().clear();
        e.getDrops().addAll(keep);
        if (e.getDroppedExp() > 0) {
            p.giveExp(e.getDroppedExp());
            e.setDroppedExp(0);
        }
    }

    // ------------------------------------------------------------------ бой: Вампиризм, Яд

    @SuppressWarnings("deprecation")
    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        ItemStack weapon = p.getInventory().getItemInMainHand();
        if (weapon.getType().isAir() || !weapon.hasItemMeta()) return;

        int po = level(weapon, CE.POISON);
        if (po > 0) {
            PotionEffectType wither = eff("wither");
            if (wither != null) {
                victim.addPotionEffect(new PotionEffect(wither, 40 + 40 * po, 0));
            }
        }

        int v = level(weapon, CE.VAMPIRE);
        if (v > 0 && p.getHealth() > 0 && ThreadLocalRandom.current().nextDouble() < VAMPIRE_CHANCE * v) {
            double heal = Math.min(VAMPIRE_MAX_HEAL, e.getFinalDamage() * VAMPIRE_PART);
            p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + heal));
        }
    }

    // ------------------------------------------------------------------ Книги кастомных чар: перетащить книгу на предмет

    private static NamespacedKey bookKey() {
        return new NamespacedKey(instance, "book_ce");
    }

    private static NamespacedKey bookLvlKey() {
        return new NamespacedKey(instance, "book_lvl");
    }

    private record BookData(CE ce, int lvl) {
    }

    /** Накладывает кастомную чару на предмет (с доп. эффектом для Подводника). */
    private void applyEnchant(ItemStack it, CE ce, int lvl) {
        setLevel(it, ce, lvl);
        if (ce == CE.AQUA) {
            // Подводник дополнительно даёт ванильное «Подводное сродство»
            Enchantment aq = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("aqua_affinity"));
            if (aq != null) it.addUnsafeEnchantment(aq, 1);
        }
    }

    /** Для каких предметов подходит чара (для описания книги). */
    private static String targetsOf(CE ce) {
        if (ce.suffixes.length == 0) return "любой предмет с прочностью";
        List<String> out = new ArrayList<>();
        for (String s : ce.suffixes) {
            out.add(switch (s) {
                case "_PICKAXE" -> "кирка";
                case "_SHOVEL" -> "лопата";
                case "_AXE" -> "топор";
                case "_HOE" -> "мотыга";
                case "_SWORD" -> "меч";
                case "_HELMET" -> "шлем";
                case "_CHESTPLATE" -> "нагрудник";
                case "_LEGGINGS" -> "поножи";
                case "_BOOTS" -> "ботинки";
                default -> s.toLowerCase();
            });
        }
        return String.join(", ", out);
    }

    @SuppressWarnings("deprecation")
    private ItemStack makeBook(CE ce, int lvl) {
        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta m = book.getItemMeta();
        m.setDisplayName(ChatColor.LIGHT_PURPLE + "Книга: " + ce.title + " " + roman(lvl));
        m.setLore(List.of(
                ChatColor.YELLOW + "Как наложить:",
                ChatColor.GRAY + "Перенесите книгу на предмет,",
                ChatColor.GRAY + "который хотите зачаровать.",
                ChatColor.DARK_GRAY + "Подходит для: " + targetsOf(ce)));
        m.setEnchantmentGlintOverride(true);
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(bookKey(), PersistentDataType.STRING, ce.id);
        pdc.set(bookLvlKey(), PersistentDataType.INTEGER, lvl);
        book.setItemMeta(m);
        return book;
    }

    private BookData readBook(ItemStack it) {
        if (it == null || it.getType() != Material.BOOK || !it.hasItemMeta()) return null;
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(bookKey(), PersistentDataType.STRING);
        if (id == null) return null;
        CE ce = CE.byId(id);
        int lvl = pdc.getOrDefault(bookLvlKey(), PersistentDataType.INTEGER, 0);
        if (ce == null || lvl < 1) return null;
        return new BookData(ce, lvl);
    }

    private void giveToHandOrInventory(Player p, ItemStack it) {
        if (p.getInventory().getItemInMainHand().getType().isAir()) {
            p.getInventory().setItemInMainHand(it);
        } else {
            give(p, p.getLocation(), it);
        }
    }

    /** Книга на курсоре + клик по предмету в инвентаре игрока = чара наложена, книга потрачена. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBookDrop(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (p.getGameMode() == GameMode.CREATIVE) return;
        if (!(e.getClickedInventory() instanceof PlayerInventory)) return;
        if (e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT) return;

        ItemStack cursor = e.getCursor();
        BookData bd = readBook(cursor);
        if (bd == null) {
            // Игрок берёт кастомную книгу в руку: показываем, как её использовать
            if ((cursor == null || cursor.getType().isAir()) && readBook(e.getCurrentItem()) != null) {
                p.sendActionBar(Component.text(
                        "Перенесите книгу на предмет, который хотите зачаровать", NamedTextColor.YELLOW));
            }
            return;
        }

        ItemStack target = e.getCurrentItem();
        if (target == null || target.getType().isAir()) return;   // пустая ячейка: книга кладётся как обычно
        if (readBook(target) != null) return;                     // книга на книгу: обычная замена

        e.setCancelled(true);
        if (!bd.ce().applies(target.getType())) {
            msg(p, ChatColor.RED + "Эта книга не подходит к предмету.");
            return;
        }
        if (level(target, bd.ce()) >= bd.lvl()) {
            msg(p, ChatColor.RED + "На предмете уже есть эта чара такого же или более высокого уровня.");
            return;
        }

        applyEnchant(target, bd.ce(), bd.lvl());
        e.setCurrentItem(target);
        if (cursor.getAmount() > 1) {
            cursor.setAmount(cursor.getAmount() - 1);
            e.setCursor(cursor);
        } else {
            e.setCursor(null);
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        msg(p, "Наложено: " + ChatColor.LIGHT_PURPLE + bd.ce().title + " " + roman(bd.lvl()));
    }

    // ------------------------------------------------------------------ Наковальня: Защита 5, Острота 7

    private static int enchLevel(ItemStack it, Enchantment en) {
        if (it.getItemMeta() instanceof EnchantmentStorageMeta esm) return esm.getStoredEnchantLevel(en);
        return it.getEnchantmentLevel(en);
    }

    private static void setEnch(ItemStack it, Enchantment en, int lvl) {
        if (it.getItemMeta() instanceof EnchantmentStorageMeta esm) {
            esm.addStoredEnchant(en, lvl, true);
            it.setItemMeta(esm);
        } else {
            it.addUnsafeEnchantment(en, lvl);
        }
    }

    /** Две одинаковые максимальные чары (Защита IV + IV и т.д.) в наковальне дают уровнем выше. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvil(PrepareAnvilEvent e) {
        ItemStack first = e.getInventory().getFirstItem();
        ItemStack second = e.getInventory().getSecondItem();
        if (first == null || second == null || first.getType().isAir() || second.getType().isAir()) return;
        if (second.getType() != Material.ENCHANTED_BOOK && second.getType() != first.getType()) return;

        ItemStack res = e.getResult();
        ItemStack base = (res != null && !res.getType().isAir()) ? res.clone() : first.clone();
        boolean changed = false;
        for (Map.Entry<Enchantment, Integer> cap : ANVIL_CAPS.entrySet()) {
            Enchantment en = cap.getKey();
            int l1 = enchLevel(first, en);
            int l2 = enchLevel(second, en);
            if (l1 <= 0 || l1 != l2) continue;
            if (l1 < en.getMaxLevel() || l1 >= cap.getValue()) continue;
            if (first.getType() != Material.ENCHANTED_BOOK && !en.canEnchantItem(first)) continue;
            setEnch(base, en, l1 + 1);
            changed = true;
        }
        if (!changed) return;

        e.setResult(base);
        if (e.getView().getRepairCost() < ANVIL_COST) e.getView().setRepairCost(ANVIL_COST);
    }

    // ------------------------------------------------------------------ Нерушимость

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent e) {
        if (level(e.getItem(), CE.UNBREAKABLE) > 0) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ Лаваходы

    private void lavaWalk(Player p) {
        Block base = p.getLocation().getBlock().getRelative(BlockFace.DOWN);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz > 5) continue;
                Block b = base.getRelative(dx, 0, dz);
                if (b.getType() == Material.LAVA
                        && b.getBlockData() instanceof Levelled lv && lv.getLevel() == 0
                        && b.getRelative(BlockFace.UP).getType().isAir()
                        && pendingLava.add(b)) {
                    b.setType(Material.OBSIDIAN);
                    Bukkit.getScheduler().runTaskLater(this, () -> restoreLava(b), LAVA_RESTORE_TICKS);
                }
            }
        }
    }

    private void restoreLava(Block b) {
        if (!pendingLava.contains(b)) return;
        // Пока рядом стоит игрок в лаваходах, обсидиан держим
        Location center = b.getLocation().add(0.5, 0.5, 0.5);
        for (Player p : b.getWorld().getPlayers()) {
            if (level(p.getInventory().getBoots(), CE.LAVA) > 0
                    && p.getLocation().distanceSquared(center) < 9) {
                Bukkit.getScheduler().runTaskLater(this, () -> restoreLava(b), 20L);
                return;
            }
        }
        pendingLava.remove(b);
        if (b.getType() == Material.OBSIDIAN) b.setType(Material.LAVA);
    }

    // ------------------------------------------------------------------ команда /cench

    private void msg(CommandSender s, String text) {
        s.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.LIGHT_PURPLE + "Чары" + ChatColor.DARK_GRAY + "] "
                + ChatColor.WHITE + text);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission(PERM)) {
            msg(sender, ChatColor.RED + "Нет прав.");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> {
                for (CE c : CE.values()) {
                    sender.sendMessage(ChatColor.YELLOW + c.id + ChatColor.GRAY + " - " + c.title
                            + " (макс. уровень " + c.max + ")");
                }
            }
            case "give", "remove" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "Только для игроков.");
                    return true;
                }
                if (args.length < 2) {
                    msg(sender, ChatColor.RED + "Укажи чару. Список: /cench list");
                    return true;
                }
                CE ce = CE.byId(args[1]);
                if (ce == null) {
                    msg(sender, ChatColor.RED + "Такой чары нет. Список: /cench list");
                    return true;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    msg(sender, ChatColor.RED + "Возьми предмет в руку.");
                    return true;
                }

                if (args[0].equalsIgnoreCase("remove")) {
                    setLevel(hand, ce, 0);
                    if (ce == CE.AQUA) {
                        Enchantment aq = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("aqua_affinity"));
                        if (aq != null) hand.removeEnchantment(aq);
                    }
                    p.getInventory().setItemInMainHand(hand);
                    msg(sender, "Чара " + ce.title + " снята.");
                    return true;
                }

                if (!ce.applies(hand.getType())) {
                    msg(sender, ChatColor.RED + "Эта чара не подходит к предмету " + hand.getType().name() + ".");
                    return true;
                }
                int lvl = 1;
                if (args.length > 2) {
                    try {
                        lvl = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ex) {
                        msg(sender, ChatColor.RED + "Уровень должен быть числом.");
                        return true;
                    }
                }
                if (lvl < 1 || lvl > ce.max) {
                    msg(sender, ChatColor.RED + "Уровень для этой чары: от 1 до " + ce.max + ".");
                    return true;
                }
                applyEnchant(hand, ce, lvl);
                p.getInventory().setItemInMainHand(hand);
                msg(sender, "Выдано: " + ChatColor.LIGHT_PURPLE + ce.title + " " + roman(lvl));
            }
            case "vanilla" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "Только для игроков.");
                    return true;
                }
                if (args.length < 3) {
                    msg(sender, ChatColor.RED + "Использование: /cench vanilla <чара> <уровень>, например: /cench vanilla protection 5");
                    return true;
                }
                Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(args[1].toLowerCase()));
                if (en == null) {
                    msg(sender, ChatColor.RED + "Такой ванильной чары нет.");
                    return true;
                }
                int lvl;
                try {
                    lvl = Integer.parseInt(args[2]);
                } catch (NumberFormatException ex) {
                    msg(sender, ChatColor.RED + "Уровень должен быть числом.");
                    return true;
                }
                if (lvl < 0 || lvl > 255) {
                    msg(sender, ChatColor.RED + "Уровень от 0 до 255 (0 = снять).");
                    return true;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    msg(sender, ChatColor.RED + "Возьми предмет в руку.");
                    return true;
                }
                if (lvl == 0) {
                    hand.removeEnchantment(en);
                } else {
                    hand.addUnsafeEnchantment(en, lvl);
                }
                p.getInventory().setItemInMainHand(hand);
                msg(sender, "Готово: " + en.getKey().getKey() + " " + lvl);
            }
            case "book" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "Только для игроков.");
                    return true;
                }
                if (args.length < 2) {
                    msg(sender, ChatColor.RED + "Использование: /cench book <чара> [уровень], например: /cench book protection");
                    return true;
                }
                CE custom = CE.byId(args[1]);
                if (custom != null) {
                    int cl = 1;
                    if (args.length > 2) {
                        try {
                            cl = Integer.parseInt(args[2]);
                        } catch (NumberFormatException ex) {
                            msg(sender, ChatColor.RED + "Уровень должен быть числом.");
                            return true;
                        }
                    }
                    if (cl < 1 || cl > custom.max) {
                        msg(sender, ChatColor.RED + "Уровень для этой чары: от 1 до " + custom.max + ".");
                        return true;
                    }
                    giveToHandOrInventory(p, makeBook(custom, cl));
                    msg(sender, "Выдана книга: " + ChatColor.LIGHT_PURPLE + custom.title + " " + roman(cl));
                    return true;
                }
                Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(args[1].toLowerCase()));
                if (en == null) {
                    msg(sender, ChatColor.RED + "Такой чары нет. Список кастомных: /cench list");
                    return true;
                }
                // По умолчанию: Защита V, Острота VII, остальные - максимальный ванильный уровень
                int lvl = en.equals(Enchantment.PROTECTION) ? 5
                        : en.equals(Enchantment.SHARPNESS) ? 7
                        : en.getMaxLevel();
                if (args.length > 2) {
                    try {
                        lvl = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ex) {
                        msg(sender, ChatColor.RED + "Уровень должен быть числом.");
                        return true;
                    }
                }
                if (lvl < 1 || lvl > 255) {
                    msg(sender, ChatColor.RED + "Уровень от 1 до 255.");
                    return true;
                }
                ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
                if (book.getItemMeta() instanceof EnchantmentStorageMeta esm) {
                    esm.addStoredEnchant(en, lvl, true);
                    book.setItemMeta(esm);
                }
                // В руку, если она пустая; иначе в инвентарь (или на землю, если места нет)
                if (p.getInventory().getItemInMainHand().getType().isAir()) {
                    p.getInventory().setItemInMainHand(book);
                } else {
                    give(p, p.getLocation(), book);
                }
                msg(sender, "Выдана книга: " + ChatColor.LIGHT_PURPLE + en.getKey().getKey() + " " + lvl);
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender s) {
        msg(s, "Команды (предмет должен быть в руке):");
        String[] lines = {
                "/cench list &7- список чар",
                "/cench give <чара> [уровень] &7- наложить чару",
                "/cench remove <чара> &7- снять чару",
                "/cench vanilla <чара> <уровень> &7- ванильная чара выше лимита (protection 5, sharpness 7)",
                "/cench book <чара> [уровень] &7- книга: кастомная (drill, timber...) или ванильная (protection - V, sharpness - VII по умолчанию)"
        };
        for (String l : lines) s.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e" + l));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(PERM)) return out;
        if (args.length == 1) {
            out.addAll(List.of("list", "give", "remove", "vanilla", "book"));
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("remove")) {
                for (CE c : CE.values()) out.add(c.id);
            } else if (args[0].equalsIgnoreCase("vanilla") || args[0].equalsIgnoreCase("book")) {
                if (args[0].equalsIgnoreCase("book")) {
                    for (CE c : CE.values()) out.add(c.id);
                }
                for (Enchantment en : Registry.ENCHANTMENT) out.add(en.getKey().getKey());
            }
        }
        String last = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(last));
        return out;
    }
}
