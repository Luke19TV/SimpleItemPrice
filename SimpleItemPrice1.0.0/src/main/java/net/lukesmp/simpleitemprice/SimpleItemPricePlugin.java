package net.lukesmp.simpleitemprice;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.PacketType;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.block.BlockState;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import org.bukkit.GameMode;
import org.bukkit.scheduler.BukkitTask;

public final class SimpleItemPricePlugin extends JavaPlugin implements CommandExecutor, Listener {

    private ProtocolManager protocolManager;

    // Bedrock (Geyser/Floodgate) inventory interactions differ from Java.
    // ProtocolLib packet rewriting that is safe for Java clients can desync/kick Bedrock players.
    // Default: disable all ProtocolLib price injection for Floodgate players.
    private boolean disableProtocolForBedrock = true;

    private final Map<UUID, BukkitTask> pendingResync = new HashMap<>();
    private EconomyShopGUIReflectHook esguiHook;

    private boolean enabled;
    private boolean playerInventoryOnly;

    private String colorCode;
    private String prefix;
    private String suffix;
    private boolean showZero;
    private LinePosition linePosition;

    private boolean debugEnabled;

    private enum LinePosition { APPEND, REPLACE_LAST, REPLACE_FIRST }

    private boolean isEconomyShopGuiTop(Player player) {
        if (player == null) return false;
        try {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top == null) return false;
            InventoryHolder holder = top.getHolder();
            if (holder == null) return false;
            String hn = holder.getClass().getName().toLowerCase(Locale.ROOT);
            return hn.contains("economyshop");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isGlassLike(Material m) {
        if (m == null) return false;
        String n = m.name();
        return m == Material.GLASS
                || m == Material.GLASS_PANE
                || n.endsWith("_STAINED_GLASS")
                || n.endsWith("_STAINED_GLASS_PANE")
                || n.endsWith("_TINTED_GLASS");
    }

    private static String cleanName(ItemStack item) {
        if (item == null) return "";
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasDisplayName()) return "";
            String dn = meta.getDisplayName();
            if (dn == null) return "";
            String stripped = ChatColor.stripColor(dn);
            return stripped == null ? "" : stripped.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * EconomyShopGUI uses decorative items (glass panes/buttons/page paper) inside its /shop GUI.
     * We want prices on real sale items in that GUI, but NOT on the decorative/control elements.
     */
    private boolean isEconomyShopGuiControlItem(Player player, ItemStack item) {
        if (!isEconomyShopGuiTop(player)) return false;
        if (item == null || item.getType() == Material.AIR) return false;

        Material m = item.getType();
        String name = cleanName(item).toLowerCase(Locale.ROOT);

        // Page indicator paper at the bottom ("Page 1/5" etc)
        if (m == Material.PAPER) {
            if (name.contains("page")) return true;
            if (name.matches(".*\\b\\d+\\s*/\\s*\\d+\\b.*")) return true;
        }

        if (isGlassLike(m)) {
            // Many decorative panes are named as blank/space.
            if (name.isBlank()) return true;

            // Common navigation/control keywords.
            if (name.contains("next") || name.contains("prev") || name.contains("previous")
                    || name.contains("back") || name.contains("forward") || name.contains("page")
                    || name.contains(">>") || name.contains("<<")) {
                return true;
            }
        }

        return false;
    }

    /**
     * We only want to show prices in:
     * - player inventory (including while other inventories are open)
     * - real containers (chests/barrels/shulkers/etc)
     * - EconomyShopGUI menus
     * And NOT in other plugin GUI menus.
     */
    private boolean allowTopDecoration(Player player) {
        if (player == null) return false;
        Inventory top;
        try {
            top = player.getOpenInventory().getTopInventory();
        } catch (Throwable t) {
            return false;
        }
        if (top == null) return false;

        InventoryType type = top.getType();
        if (type == InventoryType.PLAYER || type == InventoryType.CRAFTING) return true;

        InventoryHolder holder = top.getHolder();
        if (holder instanceof BlockState) return true;
        if (top instanceof DoubleChestInventory) return true;
        try {
            if (top.getLocation() != null) return true;
        } catch (Throwable ignored) {
            // ignore
        }

        if (holder != null) {
            String hn = holder.getClass().getName().toLowerCase(Locale.ROOT);
            if (hn.contains("economyshop")) return true;
        }

        return false;
    }

    private int safeTopSize(Player player) {
        try {
            Inventory top = player.getOpenInventory().getTopInventory();
            return (top == null) ? 0 : top.getSize();
        } catch (Throwable t) {
            return 0;
        }
    }

    private void scheduleResync(Player player, int delayTicks) {
        if (player == null || !player.isOnline()) return;
        UUID id = player.getUniqueId();

        BukkitTask existing = pendingResync.remove(id);
        if (existing != null) existing.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            pendingResync.remove(id);
            if (!player.isOnline()) return;
            player.updateInventory();
        }, Math.max(0, delayTicks));
        pendingResync.put(id, task);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLocalConfig();

        protocolManager = ProtocolLibrary.getProtocolManager();

        setupEconomyShopGUIHook();

        getCommand("siptest").setExecutor(this);
        getCommand("sipscan").setExecutor(this);

        registerPacketListeners();

        org.bukkit.Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("SimpleItemPrice enabled (ProtocolLib mode). ESGUI hook ready=" + (esguiHook != null && esguiHook.isReady()));
    }

    @Override
    public void onDisable() {
        if (protocolManager != null) {
            protocolManager.removePacketListeners(this);
        }
    }

    private void loadLocalConfig() {
        reloadConfig();
        enabled = getConfig().getBoolean("enabled", true);

        // Bedrock (Geyser/Floodgate) clients: disable all ProtocolLib price injection by default
        // to avoid inventory desync / "Network Protocol Error" kicks.
        disableProtocolForBedrock = getConfig().getBoolean("bedrock.disableProtocolLib", true);

        // Backwards compatible key + safer default.
        // If true -> only tag the player's inventory (bottom) and cursor.
        // If false -> also tag container slots (chests, etc.). Default: false.
        playerInventoryOnly = getConfig().getBoolean(
                "options.player_inventory_only",
                getConfig().getBoolean("economyshopgui.player_inventory_only", false)
        );

        colorCode = translate(getConfig().getString("display.color", "&a"));
        prefix = getConfig().getString("display.prefix", "");
        suffix = getConfig().getString("display.suffix", "");
        showZero = getConfig().getBoolean("display.show_zero", false);

        String pos = getConfig().getString("display.line_position", "append").toLowerCase(Locale.ROOT);
        linePosition = switch (pos) {
            case "replace_last" -> LinePosition.REPLACE_LAST;
            case "replace_first" -> LinePosition.REPLACE_FIRST;
            default -> LinePosition.APPEND;
        };

        debugEnabled = getConfig().getBoolean("debug.enabled", false);
    }

    private void setupEconomyShopGUIHook() {
        Plugin p = Bukkit.getPluginManager().getPlugin("EconomyShopGUI-Premium");
        if (p == null) p = Bukkit.getPluginManager().getPlugin("EconomyShopGUI");
        if (p == null) {
            esguiHook = null;
            getLogger().warning("EconomyShopGUI not found. Prices will not display.");
            return;
        }
        esguiHook = new EconomyShopGUIReflectHook(p);
        if (!esguiHook.isReady()) {
            getLogger().warning("EconomyShopGUI hook not ready: " + esguiHook.getNotReadyReason());
        }
    }

    private boolean isBedrockPlayer(Player player) {
        if (!disableProtocolForBedrock || player == null) return false;
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            Object res = api.getMethod("isFloodgatePlayer", UUID.class).invoke(inst, player.getUniqueId());
            return (res instanceof Boolean) && (Boolean) res;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void registerPacketListeners() {
        protocolManager.addPacketListener(new PacketAdapter(this,
                PacketType.Play.Server.SET_SLOT,
                PacketType.Play.Server.WINDOW_ITEMS) {

            @Override
            public void onPacketSending(PacketEvent event) {
                if (!enabled) return;
                if (esguiHook == null || !esguiHook.isReady()) return;

                Player player = event.getPlayer();
                if (player == null) return;
                if (isBedrockPlayer(player)) return;

                try {
                    if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                        handleSetSlot(event, player);
                    } else if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                        handleWindowItems(event, player);
                    }
                } catch (Throwable t) {
                    if (debugEnabled) {
                        getLogger().warning("Packet handling error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }
            }
        });
    }

    private void handleSetSlot(PacketEvent event, Player player) {
        var packet = event.getPacket();
        int windowId = packet.getIntegers().read(0);
        // 1.20+ has (windowId, stateId, slot). Older has (windowId, slot)
        int ints = packet.getIntegers().size();
        int slot = (ints >= 3) ? packet.getIntegers().read(2) : packet.getIntegers().read(1);

        // Cursor updates are encoded as SET_SLOT with windowId=-1 and slot=-1.
        // We always allow these so split stacks keep the price line on the cursor.
        boolean isCursor = (windowId == -1) || (slot == -1);

        boolean allowTop = allowTopDecoration(player);
        int topSize = safeTopSize(player);

        // If the player is inside some other plugin GUI, do NOT tag the GUI's top inventory slots.
        // Still tag the player's own inventory (bottom) so prices continue to show normally.
        if (!isCursor && windowId != 0 && !allowTop && slot >= 0 && slot < topSize) {
            return;
        }

        // Be conservative with cursor tagging while inside other plugin GUIs.
        if (isCursor && !allowTop) {
            return;
        }

        if (!isCursor && playerInventoryOnly && !isPlayerInventorySlot(player, windowId, slot)) return;

        ItemStack item = packet.getItemModifier().read(0);
        ItemStack modified = withPriceLore(player, item);
        if (modified != null) {
            packet.getItemModifier().write(0, modified);
        }
    }

    private void handleWindowItems(PacketEvent event, Player player) {
        var packet = event.getPacket();
        int windowId = packet.getIntegers().read(0);

        List<ItemStack> items = packet.getItemListModifier().read(0);
        if (items == null || items.isEmpty()) return;

        // 1.20.2+ WINDOW_ITEMS also carries the cursor (carried) item.
        // If we don't patch that, dragging/splitting can produce stacks with no price lore
        // (because the client places the carried stack into a slot without receiving a new slot update).
        try {
            ItemStack carried = packet.getItemModifier().readSafely(0);
            if (carried != null && carried.getType() != Material.AIR) {
                packet.getItemModifier().write(0, withPriceLore(player, carried));
            }
        } catch (Throwable ignored) {
            // Older protocol mappings may not expose carried item on this packet.
        }

        boolean allowTop = allowTopDecoration(player);
        int topSize = getTopSizeIfOpen(player, windowId);

        List<ItemStack> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            // If playerInventoryOnly is enabled, always skip top inventory for open windows.
            if (playerInventoryOnly && windowId != 0 && i < topSize) {
                out.add(items.get(i));
                continue;
            }

            // If the open window is NOT a real container and NOT EconomyShopGUI, skip top inventory.
            if (!allowTop && windowId != 0 && i < topSize) {
                out.add(items.get(i));
                continue;
            }

            out.add(withPriceLore(player, items.get(i)));
        }

        packet.getItemListModifier().write(0, out);
    }

    private boolean isPlayerInventorySlot(Player player, int windowId, int slot) {
        if (windowId == 0) return true; // player inventory window
        int top = getTopSizeIfOpen(player, windowId);
        return slot >= top;
    }

    private int getTopSizeIfOpen(Player player, int windowId) {
        try {
            if (windowId == 0) return 0;
            if (player.getOpenInventory() == null) return 0;
            if (player.getOpenInventory().getTopInventory() == null) return 0;
			return player.getOpenInventory().getTopInventory().getSize();
        } catch (Throwable ignored) {}
        return 0;
    }

    private ItemStack withPriceLore(Player player, ItemStack original) {
        if (original == null || original.getType() == Material.AIR) return original;
        if (isBedrockPlayer(player)) return original;

        // EconomyShopGUI uses decorative/control items in its /shop GUI (glass panes/buttons/page paper).
        // Don't add prices to those, but still add prices to real shop items.
        if (isEconomyShopGuiControlItem(player, original)) return original;

        // Don't touch items that already have our exact same line? We'll rebuild anyway.
        Double total = esguiHook.getTotalSellPrice(player, original);
        if (total == null) return original;

        if (!showZero && total.doubleValue() <= 0.0) return original;

        String priceText = "$" + formatMinimalDecimals(total);
        String line = colorCode + prefix + priceText + suffix;

        ItemStack clone = original.clone();
        ItemMeta meta = clone.getItemMeta();
        if (meta == null) return original;

        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();

        // Remove any old line that looks like our price line (starts with color + prefix + "$")
        lore.removeIf(s -> s != null && ChatColor.stripColor(translate(s)).startsWith(ChatColor.stripColor(translate(prefix)) + "$"));

        switch (linePosition) {
            case REPLACE_FIRST -> {
                if (lore.isEmpty()) lore.add(line);
                else lore.set(0, line);
            }
            case REPLACE_LAST -> {
                if (lore.isEmpty()) lore.add(line);
                else lore.set(lore.size() - 1, line);
            }
            default -> lore.add(line);
        }

        meta.setLore(lore);
        clone.setItemMeta(meta);
        return clone;
    }

    private static String formatMinimalDecimals(Double value) {
        // shows as few decimals as needed (0..2), no trailing zeros
        BigDecimal bd = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        // Avoid scientific notation
        String s = bd.toPlainString();
        // If ends with .0 after strip (can happen if value is integer)
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }

    private static String translate(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Player only.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("siptest")) {
            player.sendMessage(ChatColor.GREEN + "SimpleItemPrice OK. ProtocolLib=" +
                    (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) +
                    " ESGUI=" + (Bukkit.getPluginManager().getPlugin("EconomyShopGUI-Premium") != null || Bukkit.getPluginManager().getPlugin("EconomyShopGUI") != null) +
                    " HookReady=" + (esguiHook != null && esguiHook.isReady()));

            if (esguiHook != null && !esguiHook.isReady()) {
                player.sendMessage(ChatColor.RED + "Hook not ready: " + esguiHook.getNotReadyReason());
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("sipscan")) {
            int shown = 0;
            for (ItemStack it : player.getInventory().getContents()) {
                if (it == null || it.getType().isAir()) continue;
                Double total = (esguiHook != null) ? esguiHook.getTotalSellPrice(player, it) : null;
                if (total == null) continue;
                player.sendMessage(ChatColor.GRAY + it.getType().name() + " x" + it.getAmount() + " -> " + ChatColor.GREEN + "$" + formatMinimalDecimals(total));
                shown++;
                if (shown >= 10) break;
            }
            if (shown == 0) player.sendMessage(ChatColor.RED + "No sellable items found (first 10 slots scanned).");
            return true;
        }

        return false;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (isBedrockPlayer(player)) return;
        if (event.getView() == null || event.getView().getTopInventory() == null) return;

        // Only resync after closing non-player containers.
        // Never resync in CREATIVE; that is a common source of client disconnects (network protocol error).
        if (player.getGameMode() == GameMode.CREATIVE) return;

        InventoryType top = event.getView().getTopInventory().getType();
        if (top != InventoryType.PLAYER && top != InventoryType.CRAFTING) {
            // Delay a few ticks so we don't race the close packet.
            scheduleResync(player, 3);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isBedrockPlayer(player)) return;
        if (event.getView() == null || event.getView().getTopInventory() == null) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;
        // Only the player's own inventory uses window id 0.
        if (event.getView().getTopInventory().getType() != InventoryType.CRAFTING) return;

        final int raw = event.getRawSlot();

        // Run next tick so Bukkit has applied the click results.
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                if (raw >= 0 && raw <= 45) {
                    ItemStack now = event.getView().getItem(raw);
                    sendSetSlotWindow0(player, raw, withPriceLore(player, now));
                }

                int hb = event.getHotbarButton();
                if (hb >= 0 && hb <= 8) {
                    int hotbarRaw = 36 + hb;
                    ItemStack now = event.getView().getItem(hotbarRaw);
                    sendSetSlotWindow0(player, hotbarRaw, withPriceLore(player, now));
                }

                // IMPORTANT: do NOT call updateInventory() from click handlers.
                // It causes cursor flicker (select/deselect/select) and can trigger protocol disconnects.
            } catch (Throwable ignored) {
            }
        });
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isBedrockPlayer(player)) return;
        if (event.getView() == null || event.getView().getTopInventory() == null) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (event.getView().getTopInventory().getType() != InventoryType.CRAFTING) return;

        Bukkit.getScheduler().runTask(this, () -> {
            try {
                for (Integer rawSlot : event.getRawSlots()) {
                    if (rawSlot == null) continue;
                    int raw = rawSlot;
                    if (raw < 0 || raw > 45) continue;
                    ItemStack now = event.getView().getItem(raw);
                    sendSetSlotWindow0(player, raw, withPriceLore(player, now));
                }

                // Same rule as clicks: never force updateInventory() mid-drag.
            } catch (Throwable ignored) {
            }
        });
    }


    private void sendSetSlotWindow0(Player player, int slot, ItemStack item) {
        if (protocolManager == null || player == null) return;
        try {
            PacketContainer pkt = protocolManager.createPacket(PacketType.Play.Server.SET_SLOT);

            // Newer protocol: (windowId, stateId, slot)
            if (pkt.getIntegers().size() >= 3) {
                pkt.getIntegers().write(0, 0);       // player inventory window
                pkt.getIntegers().write(1, 0);       // stateId (0 avoids mismatch kicks)
                pkt.getIntegers().write(2, slot);    // slot index
            } else {
                // Older protocol: (windowId, slot)
                pkt.getIntegers().write(0, 0);
                pkt.getIntegers().write(1, slot);
            }

            pkt.getItemModifier().write(0, item);
            protocolManager.sendServerPacket(player, pkt);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Update the client-side cursor (carried item) without changing server state.
     */
    private void sendCursor(Player player, ItemStack cursorItem) {
        if (protocolManager == null || player == null) return;
        try {
            PacketContainer pkt = protocolManager.createPacket(PacketType.Play.Server.SET_SLOT);

            if (pkt.getIntegers().size() >= 3) {
                pkt.getIntegers().write(0, -1);      // "cursor" window
                pkt.getIntegers().write(1, 0);       // stateId
                pkt.getIntegers().write(2, -1);      // cursor slot
            } else {
                pkt.getIntegers().write(0, -1);
                pkt.getIntegers().write(1, -1);
            }

            pkt.getItemModifier().write(0, cursorItem);
            protocolManager.sendServerPacket(player, pkt);
        } catch (Throwable ignored) {
        }
    }
}
