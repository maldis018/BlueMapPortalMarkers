package dev.aldis.bluemapportalmarkers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /bmportals} admin command: live control over the portal store and
 * marker layer (reload, sweep, stats, purge) without restarting or hand-editing
 * {@code portals.json}.
 *
 * <p>Gated by the {@code bmportals.admin} permission declared in
 * {@code plugin.yml}, so Bukkit only dispatches here for permitted senders. All
 * work runs on the main thread (safe for Bukkit + {@link PoiSweeper}); the store
 * mutations the subcommands trigger persist asynchronously via the plugin's
 * coalesced {@code requestSave()}.</p>
 */
public final class PortalsCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "[BMPortals] ";
    private static final List<String> SUBCOMMANDS = List.of("reload", "sweep", "stats", "purge");

    private final NetherPortalMarkersPlugin plugin;

    public PortalsCommand(NetherPortalMarkersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                return handleReload(sender);
            case "sweep":
                return handleSweep(sender, args);
            case "stats":
                return handleStats(sender);
            case "purge":
                return handlePurge(sender, args);
            default:
                sendUsage(sender);
                return true;
        }
    }

    private boolean handleReload(CommandSender sender) {
        List<String> report = plugin.reloadConfigAndApply();
        sender.sendMessage(PREFIX + "Configuration reloaded:");
        for (String line : report) {
            sender.sendMessage("  - " + line);
        }
        return true;
    }

    private boolean handleStats(CommandSender sender) {
        PortalStore store = plugin.store();
        sender.sendMessage(PREFIX + "Tracked portals: " + store.size());
        for (World world : Bukkit.getWorlds()) {
            int count = store.inWorld(world.getUID()).size();
            if (count > 0) {
                sender.sendMessage("  - " + world.getName() + ": " + count);
            }
        }
        return true;
    }

    private boolean handlePurge(CommandSender sender, String[] args) {
        PortalStore store = plugin.store();
        BlueMapBridge bridge = plugin.bridge();

        List<Portal> removed;
        String scope;
        if (args.length >= 2) {
            World world = Bukkit.getWorld(args[1]);
            if (world == null) {
                sender.sendMessage(PREFIX + "Unknown world: " + args[1]);
                return true;
            }
            removed = store.removeWorld(world.getUID());
            scope = "world " + world.getName();
        } else {
            removed = store.clear();
            scope = "all worlds";
        }

        for (Portal portal : removed) {
            bridge.removePortal(portal);
        }
        if (!removed.isEmpty()) {
            plugin.requestSave();
        }
        sender.sendMessage(PREFIX + "Purged " + removed.size() + " portal(s) from " + scope + ".");
        return true;
    }

    /**
     * {@code sweep} forms (numeric token count disambiguates the all-numeric cases):
     * <pre>
     *   sweep                  default sweep (spawn + online players), config radius
     *   sweep &lt;radius&gt;          default sweep at the given radius
     *   sweep me [radius]      around the sending player
     *   sweep &lt;player&gt; [radius] around that player
     *   sweep &lt;x&gt; &lt;z&gt;            full-height column at coords in the sender's world
     *   sweep &lt;x&gt; &lt;y&gt; &lt;z&gt;        full-height column at coords (y ignored)
     * </pre>
     */
    private boolean handleSweep(CommandSender sender, String[] args) {
        int radius = plugin.sweepRadius();

        // Tokens after "sweep".
        if (args.length == 1) {
            int added = plugin.defaultSweep(radius).size();
            sender.sendMessage(PREFIX + "Default sweep complete: " + added
                    + " new portal(s) (radius " + radius + ").");
            return true;
        }

        String first = args[1];

        if (first.equalsIgnoreCase("me")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(PREFIX + "'me' requires a player. Use a player name or coordinates from console.");
                return true;
            }
            Integer parsed = optionalRadius(sender, args, 2);
            if (parsed == null && args.length > 2) {
                return true; // bad radius already reported
            }
            if (parsed != null) {
                radius = parsed;
            }
            int added = plugin.sweepAt(player.getWorld(), player.getLocation(), radius).size();
            sender.sendMessage(PREFIX + "Sweep around you complete: " + added
                    + " new portal(s) (radius " + radius + ").");
            return true;
        }

        // A non-numeric first token is a player name.
        if (!isNumber(first)) {
            Player target = Bukkit.getPlayerExact(first);
            if (target == null) {
                sender.sendMessage(PREFIX + "Player not found: " + first);
                return true;
            }
            Integer parsed = optionalRadius(sender, args, 2);
            if (parsed == null && args.length > 2) {
                return true;
            }
            if (parsed != null) {
                radius = parsed;
            }
            int added = plugin.sweepAt(target.getWorld(), target.getLocation(), radius).size();
            sender.sendMessage(PREFIX + "Sweep around " + target.getName() + " complete: " + added
                    + " new portal(s) (radius " + radius + ").");
            return true;
        }

        // All-numeric forms. Validate every remaining token is numeric.
        for (int i = 1; i < args.length; i++) {
            if (!isNumber(args[i])) {
                sendUsage(sender);
                return true;
            }
        }
        int numeric = args.length - 1;
        if (numeric == 1) {
            // sweep <radius>
            Integer r = parseInt(args[1]);
            if (r == null || r <= 0) {
                sender.sendMessage(PREFIX + "Radius must be a positive integer.");
                return true;
            }
            int added = plugin.defaultSweep(r).size();
            sender.sendMessage(PREFIX + "Default sweep complete: " + added
                    + " new portal(s) (radius " + r + ").");
            return true;
        }

        // Coordinate forms require a player (coords are in the sender's world).
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "Coordinate sweeps require a player (the world is the sender's). "
                    + "From console, use a player name or omit the center.");
            return true;
        }
        double x;
        double z;
        if (numeric == 2) {
            x = parseDouble(args[1]);
            z = parseDouble(args[2]);
        } else if (numeric == 3) {
            x = parseDouble(args[1]);
            // args[2] is Y — intentionally ignored, the column sweep is full-height.
            z = parseDouble(args[3]);
        } else {
            sendUsage(sender);
            return true;
        }
        int added = plugin.sweepColumn(player.getWorld(), x, z, radius).size();
        sender.sendMessage(PREFIX + "Full-height sweep at " + Math.round(x) + ", " + Math.round(z)
                + " complete: " + added + " new portal(s) (radius " + radius + ").");
        return true;
    }

    /** Parse an optional radius at {@code index}; report and return null if present but invalid. */
    private Integer optionalRadius(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            return null;
        }
        Integer r = parseInt(args[index]);
        if (r == null || r <= 0) {
            sender.sendMessage(PREFIX + "Radius must be a positive integer.");
            return null;
        }
        return r;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(PREFIX + "Usage:");
        sender.sendMessage("  /bmportals reload");
        sender.sendMessage("  /bmportals sweep [radius] | me [radius] | <player> [radius] | <x> [y] <z>");
        sender.sendMessage("  /bmportals stats");
        sender.sendMessage("  /bmportals purge [world]");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    out.add(sub);
                }
            }
            return out;
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("purge")) {
                for (World world : Bukkit.getWorlds()) {
                    if (world.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                        out.add(world.getName());
                    }
                }
            } else if (sub.equals("sweep")) {
                if ("me".startsWith(args[1].toLowerCase())) {
                    out.add("me");
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                        out.add(player.getName());
                    }
                }
            }
        }
        return out;
    }

    private static boolean isNumber(String s) {
        return parseDoubleBoxed(s) != null;
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static double parseDouble(String s) {
        return Double.parseDouble(s);
    }

    private static Double parseDoubleBoxed(String s) {
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
