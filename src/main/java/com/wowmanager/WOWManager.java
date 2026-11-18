
package com.wowmanager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;

public final class WOWManager extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private final Map<UUID, Integer> teleportTasks = new HashMap<>();
    private final Set<UUID> scoreboardHidden = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String cmd : List.of("announcement", "setspawn", "spawn", "rank", "scoreboard")) {
            PluginCommand c = getCommand(cmd);
            if (c != null) {
                c.setExecutor(this);
                c.setTabCompleter(this);
            }
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getOnlinePlayers().forEach(this::applyRank);
        getLogger().info("WOWManager enabled.");
    }

    @Override
    public void onDisable() {
        teleportTasks.values().forEach(Bukkit.getScheduler()::cancelTask);
        attachments.values().forEach(PermissionAttachment::remove);
        attachments.clear();
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private void msg(CommandSender s, String text) {
        s.sendMessage(color(getConfig().getString("messages.prefix", "&8[&bWOW&8] &r") + text));
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("wowmanager.admin")) return true;
        msg(s, "&cYou don't have permission.");
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "announcement" -> {
                if (!admin(sender)) return true;
                if (args.length == 0) {
                    msg(sender, "&cUsage: /announcement <message>");
                    return true;
                }
                String announcement = String.join(" ", args);
                Bukkit.broadcastMessage(color(getConfig().getString(
                    "announcement-format", "&8[&6ANNOUNCEMENT&8] &f{message}"
                ).replace("{message}", announcement)));
                return true;
            }
            case "setspawn" -> {
                if (!admin(sender)) return true;
                if (!(sender instanceof Player p)) {
                    msg(sender, "&cOnly players can set spawn.");
                    return true;
                }
                Location l = p.getLocation();
                getConfig().set("spawn.world", l.getWorld().getName());
                getConfig().set("spawn.x", l.getX());
                getConfig().set("spawn.y", l.getY());
                getConfig().set("spawn.z", l.getZ());
                getConfig().set("spawn.yaw", l.getYaw());
                getConfig().set("spawn.pitch", l.getPitch());
                saveConfig();
                msg(sender, "&aSpawn location saved.");
                return true;
            }
            case "spawn" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "&cOnly players can use this command.");
                    return true;
                }
                if (!getConfig().contains("spawn.world")) {
                    msg(sender, "&cSpawn has not been set yet.");
                    return true;
                }
                Location target = spawnLocation();
                if (target == null) {
                    msg(sender, "&cSpawn world is unavailable.");
                    return true;
                }
                int seconds = Math.max(0, getConfig().getInt("spawn-countdown-seconds", 3));
                if (seconds == 0 || p.hasPermission("wowmanager.spawn.bypass")) {
                    p.teleport(target);
                    msg(p, "&aTeleported to spawn.");
                    return true;
                }
                cancelTeleport(p);
                msg(p, "&eTeleporting in " + seconds + " seconds. Don't move.");
                int task = Bukkit.getScheduler().scheduleSyncDelayedTask(this, () -> {
                    teleportTasks.remove(p.getUniqueId());
                    if (p.isOnline()) {
                        p.teleport(target);
                        msg(p, "&aTeleported to spawn.");
                    }
                }, seconds * 20L);
                teleportTasks.put(p.getUniqueId(), task);
                return true;
            }
            case "rank" -> {
                return rankCommand(sender, args);
            }
            case "scoreboard" -> {
                if (args.length == 1 && args[0].equalsIgnoreCase("reload") && admin(sender)) {
                    reloadConfig();
                    Bukkit.getOnlinePlayers().forEach(this::updateBoard);
                    msg(sender, "&aScoreboard config reloaded.");
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    msg(sender, "&cOnly players can toggle their scoreboard.");
                    return true;
                }
                if (!scoreboardHidden.add(p.getUniqueId())) {
                    updateBoard(p);
                    msg(p, "&aScoreboard enabled.");
                } else {
                    p.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
                    msg(p, "&eScoreboard hidden.");
                }
                return true;
            }
        }
        return false;
    }

    private boolean rankCommand(CommandSender sender, String[] a) {
        if (!admin(sender)) return true;
        if (a.length < 1) {
            msg(sender, "&c/rank create <name> | delete <name> | set <player> <rank> | remove <player> | list");
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            ConfigurationSection sec = getConfig().getConfigurationSection("ranks");
            msg(sender, "&eRanks: &f" + (sec == null ? "None" : String.join(", ", sec.getKeys(false))));
            return true;
        }
        if (sub.equals("create") && a.length >= 2) {
            String name = a[1].toUpperCase(Locale.ROOT);
            String path = "ranks." + name;
            if (getConfig().contains(path)) {
                msg(sender, "&cThat rank already exists.");
                return true;
            }
            getConfig().set(path + ".prefix", "&7[" + name + "]");
            getConfig().set(path + ".suffix", "");
            getConfig().set(path + ".weight", 0);
            getConfig().set(path + ".permissions", new ArrayList<>());
            saveConfig();
            msg(sender, "&aCreated rank " + name + ".");
            return true;
        }
        if (sub.equals("delete") && a.length >= 2) {
            String name = a[1].toUpperCase(Locale.ROOT);
            if (!getConfig().contains("ranks." + name)) {
                msg(sender, "&cRank not found.");
                return true;
            }
            getConfig().set("ranks." + name, null);
            ConfigurationSection players = getConfig().getConfigurationSection("players");
            if (players != null) {
                for (String key : players.getKeys(false)) {
                    if (name.equalsIgnoreCase(getConfig().getString("players." + key + ".rank", ""))) {
                        getConfig().set("players." + key + ".rank",
                            getConfig().getString("default-rank", "PLAYER"));
                    }
                }
            }
            saveConfig();
            Bukkit.getOnlinePlayers().forEach(this::applyRank);
            msg(sender, "&aDeleted rank " + name + ".");
            return true;
        }
        if (sub.equals("set") && a.length >= 3) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(a[1]);
            String rank = a[2].toUpperCase(Locale.ROOT);
            if (!getConfig().contains("ranks." + rank)) {
                msg(sender, "&cRank not found. Create it first.");
                return true;
            }
            getConfig().set("players." + target.getUniqueId() + ".rank", rank);
            saveConfig();
            if (target.isOnline()) {
                applyRank(target.getPlayer());
                updateBoard(target.getPlayer());
            }
            msg(sender, "&aSet " + target.getName() + " to rank " + rank + ".");
            return true;
        }
        if (sub.equals("remove") && a.length >= 2) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(a[1]);
            getConfig().set("players." + target.getUniqueId() + ".rank",
                getConfig().getString("default-rank", "PLAYER"));
            saveConfig();
            if (target.isOnline()) {
                applyRank(target.getPlayer());
                updateBoard(target.getPlayer());
            }
            msg(sender, "&aReset " + target.getName() + " to the default rank.");
            return true;
        }
        msg(sender, "&cUsage: /rank create <name> | delete <name> | set <player> <rank> | remove <player> | list");
        return true;
    }

    private String rankOf(Player p) {
        return getConfig().getString("players." + p.getUniqueId() + ".rank",
            getConfig().getString("default-rank", "PLAYER"));
    }

    private void applyRank(Player p) {
        if (p == null) return;
        PermissionAttachment old = attachments.remove(p.getUniqueId());
        if (old != null) old.remove();
        String rank = rankOf(p);
        List<String> perms = getConfig().getStringList("ranks." + rank + ".permissions");
        PermissionAttachment attachment = p.addAttachment(this);
        for (String permission : perms) attachment.setPermission(permission, true);
        attachments.put(p.getUniqueId(), attachment);
        updateBoard(p);
    }

    private Location spawnLocation() {
        String world = getConfig().getString("spawn.world");
        if (world == null || Bukkit.getWorld(world) == null) return null;
        return new Location(Bukkit.getWorld(world),
            getConfig().getDouble("spawn.x"),
            getConfig().getDouble("spawn.y"),
            getConfig().getDouble("spawn.z"),
            (float) getConfig().getDouble("spawn.yaw"),
            (float) getConfig().getDouble("spawn.pitch"));
    }

    private void cancelTeleport(Player p) {
        Integer task = teleportTasks.remove(p.getUniqueId());
        if (task != null) Bukkit.getScheduler().cancelTask(task);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (teleportTasks.containsKey(e.getPlayer().getUniqueId())
            && (e.getFrom().getBlockX() != e.getTo().getBlockX()
            || e.getFrom().getBlockY() != e.getTo().getBlockY()
            || e.getFrom().getBlockZ() != e.getTo().getBlockZ())) {
            cancelTeleport(e.getPlayer());
            msg(e.getPlayer(), "&cTeleport cancelled because you moved.");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyRank(e.getPlayer());
    }

    private void updateBoard(Player p) {
        if (p == null || scoreboardHidden.contains(p.getUniqueId())) return;
        if (!getConfig().getBoolean("scoreboard.enabled", true)) return;
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective("wow", "dummy",
            color(getConfig().getString("scoreboard.title", "&b&lWOW SMP")));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        List<String> lines = getConfig().getStringList("scoreboard.lines");
        if (lines.isEmpty()) lines = List.of("&7Player: &f%player%", "&7Rank: &f%rank%", "&7Online: &f%online%");
        int score = lines.size();
        Set<String> used = new HashSet<>();
        for (String raw : lines) {
            String line = color(raw.replace("%player%", p.getName())
                .replace("%rank%", getConfig().getString("ranks." + rankOf(p) + ".prefix", rankOf(p)))
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size())));
            if (line.length() > 40) line = line.substring(0, 40);
            String unique = line;
            while (!used.add(unique)) unique += ChatColor.values()[used.size() % ChatColor.values().length];
            obj.getScore(unique).setScore(score--);
        }
        p.setScoreboard(board);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (c.getName().equalsIgnoreCase("rank")) {
            if (a.length == 1) return filter(List.of("create", "delete", "set", "remove", "list"), a[0]);
            if (a.length == 2 && a[0].equalsIgnoreCase("delete")) {
                ConfigurationSection sec = getConfig().getConfigurationSection("ranks");
                return filter(sec == null ? List.of() : new ArrayList<>(sec.getKeys(false)), a[1]);
            }
            if (a.length == 3 && a[0].equalsIgnoreCase("set")) {
                ConfigurationSection sec = getConfig().getConfigurationSection("ranks");
                return filter(sec == null ? List.of() : new ArrayList<>(sec.getKeys(false)), a[2]);
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        return values.stream()
            .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)))
            .toList();
    }
}
