package dev.dragonbuff;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.Vex;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * DragonBuff - server-side only, so it works for Java players and for Bedrock
 * players connecting through Geyser/Floodgate (no resource pack or client mod needed).
 */
public class DragonBuff extends JavaPlugin implements Listener {

    private final Random rng = new Random();
    private final Map<UUID, Integer> cooldowns = new HashMap<>();
    private final Set<UUID> enraged = new HashSet<>();
    private NamespacedKey buffedKey;
    private NamespacedKey minionKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        buffedKey = new NamespacedKey(this, "buffed");
        minionKey = new NamespacedKey(this, "minion");
        Bukkit.getPluginManager().registerEvents(this, this);

        // Logic tick: every 0.5s
        Bukkit.getScheduler().runTaskTimer(this, this::logicTick, 20L, 10L);
        // Visual tick: every 4 ticks
        Bukkit.getScheduler().runTaskTimer(this, this::fxTick, 20L, 4L);

        getLogger().info("DragonBuff enabled.");
    }

    // ------------------------------------------------------------------
    // Command
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("dragonbuff.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("§aDragonBuff config reloaded. (Health/scale apply to newly spawned dragons.)");
            return true;
        }
        sender.sendMessage("§e/dragonbuff reload");
        return true;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private List<EnderDragon> dragons() {
        List<EnderDragon> out = new ArrayList<>();
        List<String> allowed = getConfig().getStringList("worlds");
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.THE_END) continue;
            if (!allowed.isEmpty() && !allowed.contains(w.getName())) continue;
            out.addAll(w.getEntitiesByClass(EnderDragon.class));
        }
        return out;
    }

    private List<Player> targets(EnderDragon d) {
        double range = getConfig().getDouble("range", 100.0);
        List<Player> out = new ArrayList<>();
        for (Player p : d.getWorld().getPlayers()) {
            if (p.isDead()) continue;
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (p.getLocation().distanceSquared(d.getLocation()) > range * range) continue;
            out.add(p);
        }
        return out;
    }

    private double maxHealth(EnderDragon d) {
        AttributeInstance a = d.getAttribute(Attribute.MAX_HEALTH);
        return a != null ? a.getValue() : 200.0;
    }

    private boolean isEnraged(EnderDragon d) {
        return d.getHealth() / maxHealth(d) <= getConfig().getDouble("enrage.threshold", 0.5);
    }

    private double scale() {
        return getConfig().getDouble("scale", 1.5);
    }

    // ------------------------------------------------------------------
    // Applying the permanent buffs to a dragon (once per dragon)
    // ------------------------------------------------------------------
    private void applyBuffs(EnderDragon d) {
        if (d.getPersistentDataContainer().has(buffedKey, PersistentDataType.BYTE)) return;

        double hp = getConfig().getDouble("health", 600.0);
        AttributeInstance maxHp = d.getAttribute(Attribute.MAX_HEALTH);
        if (maxHp != null) {
            maxHp.setBaseValue(hp);
            d.setHealth(hp);
        }

        AttributeInstance sc = d.getAttribute(Attribute.SCALE);
        if (sc != null) sc.setBaseValue(scale());

        d.getPersistentDataContainer().set(buffedKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void updateBossBar(EnderDragon d, boolean enr) {
        DragonBattle battle = d.getDragonBattle();
        if (battle == null) return;
        BossBar bar = battle.getBossBar();
        bar.setColor(enr ? BarColor.RED : BarColor.PURPLE);
        bar.setStyle(BarStyle.SEGMENTED_12);
        bar.setTitle(enr ? "§4§lENRAGED ENDER DRAGON" : "§5§lEmpowered Ender Dragon");
    }

    // ------------------------------------------------------------------
    // Main logic loop
    // ------------------------------------------------------------------
    private void logicTick() {
        List<EnderDragon> all = dragons();

        Set<UUID> alive = new HashSet<>();
        for (EnderDragon d : all) alive.add(d.getUniqueId());
        cooldowns.keySet().retainAll(alive);
        enraged.retainAll(alive);

        for (EnderDragon d : all) {
            applyBuffs(d);
            boolean enr = isEnraged(d);
            updateBossBar(d, enr);

            if (enr && enraged.add(d.getUniqueId())) onEnrage(d);

            List<Player> targets = targets(d);
            if (targets.isEmpty()) continue;

            int cd = cooldowns.getOrDefault(d.getUniqueId(), 60) - 10;
            if (cd > 0) {
                cooldowns.put(d.getUniqueId(), cd);
                continue;
            }

            double interval = getConfig().getInt("attack-interval-ticks", 100);
            if (enr) interval *= getConfig().getDouble("enrage.cooldown-multiplier", 0.5);
            cooldowns.put(d.getUniqueId(), (int) interval + rng.nextInt(20));

            int count = (enr && getConfig().getBoolean("enrage.double-attacks", true)) ? 2 : 1;
            for (int i = 0; i < count; i++) randomAttack(d, targets, enr);
        }
    }

    private void onEnrage(EnderDragon d) {
        World w = d.getWorld();
        for (Player p : w.getPlayers()) {
            p.sendMessage("§4§lThe Ender Dragon is ENRAGED!");
            p.sendTitle("§4§lENRAGED", "§cThe dragon grows desperate...", 5, 40, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, 0.5f);
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.5f, 0.6f);
        }
    }

    private void randomAttack(EnderDragon d, List<Player> t, boolean enr) {
        List<String> pool = new ArrayList<>();
        if (getConfig().getBoolean("attacks.lightning", true)) pool.add("lightning");
        if (getConfig().getBoolean("attacks.fireballs", true)) pool.add("fireballs");
        if (getConfig().getBoolean("attacks.wither-skulls", true)) pool.add("skulls");
        if (getConfig().getBoolean("attacks.shulker-bullets", true)) pool.add("bullets");
        if (getConfig().getBoolean("attacks.vex-minions", true)) pool.add("vex");
        if (getConfig().getBoolean("attacks.void-pull", true)) pool.add("pull");
        if (pool.isEmpty()) return;

        switch (pool.get(rng.nextInt(pool.size()))) {
            case "lightning" -> lightning(d, t, enr);
            case "fireballs" -> barrage(d, t, enr, false);
            case "skulls" -> barrage(d, t, enr, true);
            case "bullets" -> shulkerBullets(d, t, enr);
            case "vex" -> summonVexes(d, t, enr);
            case "pull" -> voidPull(d, t, enr);
        }
    }

    // ------------------------------------------------------------------
    // ATTACKS
    // ------------------------------------------------------------------

    /** Telegraphed lightning: purple burst now, strike 1.25s later. */
    private void lightning(EnderDragon d, List<Player> targets, boolean enr) {
        int strikes = enr ? 6 : 3;
        World w = d.getWorld();
        for (int i = 0; i < strikes; i++) {
            Player p = targets.get(rng.nextInt(targets.size()));
            Location l = p.getLocation().add(rng.nextInt(9) - 4, 0, rng.nextInt(9) - 4);
            w.spawnParticle(Particle.DUST, l.clone().add(0, 1, 0), 50, 0.8, 1.5, 0.8,
                    new Particle.DustOptions(Color.fromRGB(190, 0, 255), 2.5f));
            w.playSound(l, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 0.5f);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (d.isValid()) w.strikeLightning(l);
            }, 25L);
        }
    }

    /** Burst of dragon fireballs or wither skulls aimed at players. */
    private void barrage(EnderDragon d, List<Player> targets, boolean enr, boolean skulls) {
        final int shots = enr ? 8 : 5;
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (!d.isValid() || i++ >= shots) {
                    cancel();
                    return;
                }
                Player p = targets.get(rng.nextInt(targets.size()));
                if (!p.isOnline() || p.isDead() || p.getWorld() != d.getWorld()) return;

                Vector dir = p.getEyeLocation().toVector().subtract(d.getLocation().toVector()).normalize();
                Location from = d.getLocation().add(dir.clone().multiply(8 * scale()));
                World w = d.getWorld();
                w.playSound(from, Sound.ENTITY_ENDER_DRAGON_SHOOT, 3f, 0.8f);

                if (skulls) {
                    w.spawn(from, WitherSkull.class, s -> {
                        s.setShooter(d);
                        s.setCharged(rng.nextInt(3) == 0);
                        s.setDirection(dir);
                        s.setVelocity(dir.clone().multiply(1.1));
                    });
                } else {
                    w.spawn(from, DragonFireball.class, f -> {
                        f.setShooter(d);
                        f.setDirection(dir);
                        f.setVelocity(dir.clone().multiply(1.1));
                    });
                }
            }
        }.runTaskTimer(this, 0L, 6L);
    }

    /** Homing shulker bullets (levitation) - forces players off their pearl/bridge spots. */
    private void shulkerBullets(EnderDragon d, List<Player> targets, boolean enr) {
        int n = enr ? 5 : 3;
        World w = d.getWorld();
        for (int i = 0; i < n; i++) {
            Player p = targets.get(rng.nextInt(targets.size()));
            Location from = d.getLocation().add(rng.nextInt(7) - 3, 3, rng.nextInt(7) - 3);
            w.spawn(from, ShulkerBullet.class, b -> {
                b.setShooter(d);
                b.setTarget(p);
            });
        }
        w.playSound(d.getLocation(), Sound.ENTITY_SHULKER_SHOOT, 4f, 0.6f);
    }

    /** Summon temporary vexes that hunt players. */
    private void summonVexes(EnderDragon d, List<Player> targets, boolean enr) {
        World w = d.getWorld();
        int max = getConfig().getInt("minions.max", 10);
        long life = getConfig().getLong("minions.lifetime-seconds", 30) * 20L;

        long existing = w.getEntitiesByClass(Vex.class).stream()
                .filter(v -> v.getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE))
                .count();
        int toSpawn = (int) Math.min(enr ? 5 : 3, Math.max(0, max - existing));

        for (int i = 0; i < toSpawn; i++) {
            Player p = targets.get(rng.nextInt(targets.size()));
            Location l = d.getLocation().add(rng.nextInt(9) - 4, rng.nextInt(3), rng.nextInt(9) - 4);
            w.spawn(l, Vex.class, v -> {
                v.getPersistentDataContainer().set(minionKey, PersistentDataType.BYTE, (byte) 1);
                v.setTarget(p);
                if (v.getEquipment() != null) {
                    v.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
                    v.getEquipment().setItemInMainHandDropChance(0f);
                }
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (v.isValid()) v.remove();
                }, life);
            });
        }
        w.playSound(d.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 4f, 0.6f);
    }

    /** Yanks nearby players toward the dragon, then slams a shockwave. */
    private void voidPull(EnderDragon d, List<Player> targets, boolean enr) {
        World w = d.getWorld();
        Location c = d.getLocation();
        w.playSound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, 4f, 0.7f);

        for (Player p : targets) {
            if (p.getLocation().distance(c) > 45) continue;
            Vector v = c.toVector().subtract(p.getLocation().toVector()).normalize().multiply(1.0);
            v.setY(0.4);
            p.setVelocity(v);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_SCREAM, 1f, 0.5f);
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!d.isValid()) return;
            Location now = d.getLocation();
            double dmg = enr ? 12 : 8;
            w.spawnParticle(Particle.EXPLOSION_EMITTER, now, 3, 3, 1, 3, 0);
            w.spawnParticle(Particle.REVERSE_PORTAL, now, 200, 8, 3, 8, 0.5);
            w.playSound(now, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.6f);
            double radius = 14 * scale();
            for (Player p : w.getPlayers()) {
                if (p.isDead()) continue;
                if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
                if (p.getLocation().distance(now) > radius) continue;
                p.damage(dmg, d); // goes through the damage-multiplier listener too
                Vector away = p.getLocation().toVector().subtract(now.toVector()).normalize().multiply(1.6);
                away.setY(0.8);
                p.setVelocity(away);
            }
        }, 25L);
    }

    // ------------------------------------------------------------------
    // Visuals: make the dragon look dangerous (particles only = Bedrock safe)
    // ------------------------------------------------------------------
    private void fxTick() {
        for (EnderDragon d : dragons()) {
            boolean enr = enraged.contains(d.getUniqueId());
            World w = d.getWorld();
            Location l = d.getLocation();

            w.spawnParticle(Particle.PORTAL, l, 30, 4, 2, 4, 0.4);
            w.spawnParticle(Particle.DUST, l, 14, 5, 2, 5,
                    new Particle.DustOptions(enr ? Color.RED : Color.fromRGB(150, 0, 200), 2.5f));
            if (enr) {
                w.spawnParticle(Particle.FLAME, l, 12, 4, 1.5, 4, 0.03);
                w.spawnParticle(Particle.LARGE_SMOKE, l, 8, 4, 1.5, 4, 0.02);
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 6, 3, 1, 3, 0.02);
            }
        }
    }

    // ------------------------------------------------------------------
    // Damage: dragon hits harder, takes less
    // ------------------------------------------------------------------
    private boolean fromDragon(Entity en) {
        if (en instanceof EnderDragon) return true;
        if (en instanceof Projectile p) return p.getShooter() instanceof EnderDragon;
        if (en instanceof AreaEffectCloud c) return c.getSource() instanceof EnderDragon;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        // Damage dealt TO players by the dragon
        if (e.getEntity() instanceof Player player) {
            boolean dragon = false;
            if (e instanceof EntityDamageByEntityEvent ee) dragon = fromDragon(ee.getDamager());
            else if (e.getCause() == EntityDamageEvent.DamageCause.DRAGON_BREATH) dragon = true;
            if (!dragon) return;

            double mult = getConfig().getDouble("damage-multiplier", 2.0);
            for (EnderDragon d : player.getWorld().getEntitiesByClass(EnderDragon.class)) {
                if (enraged.contains(d.getUniqueId())) {
                    mult *= getConfig().getDouble("enrage.extra-damage-multiplier", 1.25);
                    break;
                }
            }
            e.setDamage(e.getDamage() * mult);
            return;
        }

        // Damage taken BY the dragon
        if (e.getEntity() instanceof EnderDragon) {
            e.setDamage(e.getDamage() * getConfig().getDouble("damage-taken-multiplier", 0.75));
        }
    }
}
