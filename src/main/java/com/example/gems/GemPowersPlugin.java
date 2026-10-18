package com.example.gems;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class GemPowersPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<UUID, String> gems = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> tiers = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> energy = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> trusted = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private NamespacedKey gemKey, tierKey;
    private File dataFile;

    @Override public void onEnable() {
        saveDefaultConfig();
        gemKey = new NamespacedKey(this, "gem_type");
        tierKey = new NamespacedKey(this, "gem_tier");
        dataFile = new File(getDataFolder(), "players.yml");
        loadData();
        Objects.requireNonNull(getCommand("gems")).setExecutor(this);
        Objects.requireNonNull(getCommand("gems")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        long refresh = Math.max(20L, getConfig().getLong("settings.passive-refresh-ticks", 40));
        getServer().getScheduler().runTaskTimer(this, this::refreshPassives, 20L, refresh);
        getLogger().info("GemPowers enabled.");
    }

    @Override public void onDisable() { saveData(); }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!gems.containsKey(p.getUniqueId()) && getConfig().getBoolean("settings.random-gem-on-first-join", true)) {
            List<String> available = new ArrayList<>();
            for (String name : getConfig().getConfigurationSection("settings.enabled-gems").getKeys(false)) {
                if (getConfig().getBoolean("settings.enabled-gems." + name) && !name.equals("AURATUS") && !name.equals("HERETIC") && !name.equals("GOLD")) available.add(name);
            }
            if (!available.isEmpty()) {
                gems.put(p.getUniqueId(), available.get(new Random().nextInt(available.size())));
                tiers.put(p.getUniqueId(), Math.max(1, Math.min(4, getConfig().getInt("settings.default-tier", 1))));
                energy.put(p.getUniqueId(), Math.max(0, getConfig().getInt("settings.starting-energy", 0)));
                saveData();
                p.sendMessage(color("&6You received the &e" + gems.get(p.getUniqueId()) + " Gem&6!"));
            }
        }
    }

    @EventHandler(ignoreCancelled = true) public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().getPersistentDataContainer().has(gemKey, PersistentDataType.STRING)) return;
        e.setCancelled(true);
        activate(e.getPlayer());
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        if (gems.containsKey(victim.getUniqueId())) setEnergy(victim, energy(victim) - getConfig().getInt("settings.energy-loss-on-death", 10));
        Player killer = victim.getKiller();
        if (killer != null && gems.containsKey(killer.getUniqueId())) {
            setEnergy(killer, energy(killer) + getConfig().getInt("settings.energy-per-kill", 10));
            killer.sendMessage(color("&a+" + getConfig().getInt("settings.energy-per-kill", 10) + " energy"));
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            String g = gem(p);
            if (g == null) { p.sendMessage(color("&cYou don't have a gem assigned.")); return true; }
            p.sendMessage(color("&6Gem: &f" + g + " &8| &6Tier: &f" + tier(p) + " &8| &6Energy: &f" + energy(p) + "/" + maxEnergy()));
            return true;
        }
        if (args[0].equalsIgnoreCase("ability")) {
            if (sender instanceof Player p) activate(p); else sender.sendMessage("Players only.");
            return true;
        }
        if (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust")) {
            if (!(sender instanceof Player p) || args.length < 2) { sender.sendMessage("Usage: /gems trust|untrust <online-player>"); return true; }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { p.sendMessage(color("&cThat player must be online.")); return true; }
            Set<UUID> set = trusted.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
            if (args[0].equalsIgnoreCase("trust")) { set.add(target.getUniqueId()); p.sendMessage(color("&aTrusted " + target.getName() + ".")); }
            else { set.remove(target.getUniqueId()); p.sendMessage(color("&eUntrusted " + target.getName() + ".")); }
            saveData(); return true;
        }
        if (args[0].equalsIgnoreCase("admin")) {
            if (!sender.hasPermission("gempowers.admin")) { sender.sendMessage(color("&cNo permission.")); return true; }
            if (args.length < 2) { sender.sendMessage("/gems admin <set|give|energy|reload>"); return true; }
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "reload" -> { reloadConfig(); sender.sendMessage(color("&aGemPowers config reloaded.")); }
                case "set", "give" -> {
                    if (args.length < 4) { sender.sendMessage("/gems admin " + args[1] + " <player> <gem> [tier]"); return true; }
                    Player target = Bukkit.getPlayerExact(args[2]);
                    String g = args[3].toUpperCase(Locale.ROOT);
                    if (target == null) { sender.sendMessage(color("&cPlayer must be online.")); return true; }
                    if (!isValidGem(g)) { sender.sendMessage(color("&cUnknown gem. Try ASTRA, FIRE, FLUX, LIFE, PUFF, SPEED, STRENGTH, WEALTH.")); return true; }
                    int t = args.length > 4 ? parseInt(args[4], 1) : 1;
                    gems.put(target.getUniqueId(), g); tiers.put(target.getUniqueId(), Math.max(1, Math.min(4, t)));
                    energy.putIfAbsent(target.getUniqueId(), getConfig().getInt("settings.starting-energy", 0));
                    if (args[1].equalsIgnoreCase("give")) target.getInventory().addItem(gemItem(g, t));
                    saveData(); sender.sendMessage(color("&aAssigned " + g + " to " + target.getName() + "."));
                }
                case "energy" -> {
                    if (args.length < 4) { sender.sendMessage("/gems admin energy <player> <amount>"); return true; }
                    Player target = Bukkit.getPlayerExact(args[2]);
                    if (target == null) { sender.sendMessage(color("&cPlayer must be online.")); return true; }
                    setEnergy(target, parseInt(args[3], 0)); sender.sendMessage(color("&aEnergy set to " + energy(target) + "."));
                }
                default -> sender.sendMessage("/gems admin <set|give|energy|reload>");
            }
            return true;
        }
        sender.sendMessage(color("&6/gems &7status | ability | trust <player> | untrust <player> | admin"));
        return true;
    }

    private void activate(Player p) {
        String g = gem(p);
        if (g == null) { p.sendMessage(color("&cYou don't have a gem assigned.")); return; }
        if (!p.hasPermission("gempowers.use") || !getConfig().getBoolean("settings.enabled-gems." + g, true)) { p.sendMessage(color("&cYour ability is disabled.")); return; }
        long now = System.currentTimeMillis();
        String key = p.getUniqueId() + ":" + g;
        long ready = cooldowns.getOrDefault(key, 0L);
        if (ready > now) { p.sendMessage(color("&cAbility ready in " + ((ready - now + 999) / 1000) + "s.")); return; }
        int t = tier(p); double radius = getConfig().getDouble("settings.ability-radius", 5.0) + t - 1;
        Location loc = p.getLocation(); World w = p.getWorld();
        switch (g) {
            case "ASTRA" -> {
                Vector dir = loc.getDirection().normalize(); Location dest = loc.clone();
                for (int i = 0; i < 10; i++) { Location next = dest.clone().add(dir.clone().multiply(0.5)); if (!next.getBlock().isPassable() || !next.clone().add(0, 1, 0).getBlock().isPassable()) break; dest = next; }
                p.teleport(dest); w.spawnParticle(Particle.PORTAL, loc.add(0, 1, 0), 20); w.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            }
            case "FIRE" -> {
                for (Entity e : p.getNearbyEntities(radius, radius, radius)) if (e instanceof LivingEntity le && e != p && !isTrusted(p, e)) { le.damage(2 + t, p); le.setFireTicks(40 + t * 20); }
                w.spawnParticle(Particle.FLAME, p.getEyeLocation(), 30, .5, .5, .5, .03);
            }
            case "FLUX" -> {
                for (Entity e : p.getNearbyEntities(radius, radius, radius)) if (e instanceof LivingEntity le && e != p && !isTrusted(p, e)) { Vector pull = p.getLocation().toVector().subtract(e.getLocation().toVector()).normalize().multiply(.5); pull.setY(Math.max(.15, pull.getY())); le.setVelocity(pull); }
                w.spawnParticle(Particle.REVERSE_PORTAL, loc.add(0, 1, 0), 30);
            }
            case "LIFE" -> {
                p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + 4 + t * 2));
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60 + t * 20, 0, true, false, true));
                w.spawnParticle(Particle.HAPPY_VILLAGER, loc.add(0, 1, 0), 25);
            }
            case "PUFF" -> { Vector v = p.getVelocity(); v.setY(0.9 + t * .15); p.setVelocity(v); p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 80, 0, true, false, true)); }
            case "SPEED" -> p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100 + t * 40, t - 1, true, false, true));
            case "STRENGTH" -> { p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 100 + t * 40, t - 1, true, false, true)); p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60 + t * 20, 0, true, false, true)); }
            case "WEALTH" -> p.giveExp(3 + t * 3);
        }
        cooldowns.put(key, now + getConfig().getLong("settings.cooldown-seconds." + g, 20) * 1000L);
        p.sendMessage(color("&aGem ability activated!"));
    }

    private boolean isTrusted(Player owner, Entity target) { return target instanceof Player other && trusted.getOrDefault(owner.getUniqueId(), Set.of()).contains(other.getUniqueId()); }
    private void refreshPassives() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            String g = gem(p); if (g == null) continue;
            if (g.equals("SPEED") && getConfig().getBoolean("settings.enabled-gems.SPEED", false)) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, Math.max(0, tier(p)-1), true, false, true));
            if (getConfig().getBoolean("settings.show-actionbar", true)) p.sendActionBar(color("&6" + g + " &8| &fEnergy: " + energy(p) + "/" + maxEnergy()));
        }
    }

    private ItemStack gemItem(String g, int t) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color("&d&l" + g + " Gem"));
        meta.setLore(List.of(color("&7Tier: &f" + Math.max(1, Math.min(4, t))), color("&8Right-click to activate your ability")));
        meta.getPersistentDataContainer().set(gemKey, PersistentDataType.STRING, g);
        meta.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER, t);
        meta.setCustomModelData(21000 + Math.max(1, List.of("ASTRA","FIRE","FLUX","LIFE","PUFF","SPEED","STRENGTH","WEALTH").indexOf(g) + 1));
        item.setItemMeta(meta); return item;
    }

    private String gem(Player p) { return gems.get(p.getUniqueId()); }
    private int tier(Player p) { return tiers.getOrDefault(p.getUniqueId(), 1); }
    private int energy(Player p) { return energy.getOrDefault(p.getUniqueId(), 0); }
    private int maxEnergy() { return Math.max(1, getConfig().getInt("settings.max-energy", 100)); }
    private void setEnergy(Player p, int amount) { energy.put(p.getUniqueId(), Math.max(0, Math.min(maxEnergy(), amount))); saveData(); }
    private boolean isValidGem(String g) { return List.of("ASTRA","FIRE","FLUX","LIFE","PUFF","SPEED","STRENGTH","WEALTH").contains(g); }
    private int parseInt(String s, int fallback) { try { return Integer.parseInt(s); } catch (NumberFormatException ex) { return fallback; } }
    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration c = YamlConfiguration.loadConfiguration(dataFile);
        for (String id : c.getKeys(false)) try {
            UUID uuid = UUID.fromString(id);
            String g = c.getString(id + ".gem"); if (g != null) gems.put(uuid, g);
            tiers.put(uuid, Math.max(1, Math.min(4, c.getInt(id + ".tier", 1))));
            energy.put(uuid, Math.max(0, c.getInt(id + ".energy", 0)));
            Set<UUID> set = ConcurrentHashMap.newKeySet();
            for (String s : c.getStringList(id + ".trusted")) try { set.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
            trusted.put(uuid, set);
        } catch (IllegalArgumentException ignored) {}
    }
    private void saveData() {
        YamlConfiguration c = new YamlConfiguration();
        Set<UUID> ids = new HashSet<>(); ids.addAll(gems.keySet()); ids.addAll(tiers.keySet()); ids.addAll(energy.keySet()); ids.addAll(trusted.keySet());
        for (UUID id : ids) {
            String path = id.toString();
            if (gems.containsKey(id)) c.set(path + ".gem", gems.get(id));
            c.set(path + ".tier", tiers.getOrDefault(id, 1));
            c.set(path + ".energy", energy.getOrDefault(id, 0));
            c.set(path + ".trusted", trusted.getOrDefault(id, Set.of()).stream().map(UUID::toString).toList());
        }
        try { if (!getDataFolder().exists()) getDataFolder().mkdirs(); c.save(dataFile); }
        catch (IOException e) { getLogger().severe("Could not save player data: " + e.getMessage()); }
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return starts(List.of("status","ability","trust","untrust","admin"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) return starts(List.of("set","give","energy","reload"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")) return starts(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("give"))) return starts(List.of("ASTRA","FIRE","FLUX","LIFE","PUFF","SPEED","STRENGTH","WEALTH"), args[3]);
        return Collections.emptyList();
    }
    private List<String> starts(List<String> list, String prefix) { return list.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList(); }
}