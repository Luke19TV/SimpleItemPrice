package net.lukesmp.simpleitemprice;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.util.Enumeration;
import java.util.Optional;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Reflection-based hook for EconomyShopGUI / EconomyShopGUI-Premium with package relocation support.
 *
 * Price strategy:
 *  1) Preferred: EconomyShopGUIHook.getSellPrice(owner, item) -> Optional<SellPrice>
 *     - This can return empty if ShopItem isn't found in shops, or player lacks perms/requirements/limits.
 *
 *  2) Fallback: EconomyShopGUIHook.getShopItem(item) (or getShopItem(player,item)) -> ShopItem
 *     then EconomyShopGUIHook.getItemSellPrice(shopItem, item, player?, amount, 0) -> double
 *     - This helps diagnose whether the item exists in the shop at all.
 */
public final class EconomyShopGUIReflectHook {

    private boolean ready;
    private String notReadyReason = "";

    private Class<?> hookClass;

    // Main API calls
    private Method getSellPriceMethod;    // getSellPrice(owner, ItemStack)
    private Class<?> sellPriceClass;

    // Debug / fallback
    private Method getShopItem1;          // getShopItem(ItemStack)
    private Method getShopItem2;          // getShopItem(Player/OfflinePlayer, ItemStack)
    private Method isSellAbleMethod;      // isSellAble(ShopItem)
    private Method getItemSellPriceMethod;// getItemSellPrice(ShopItem, ItemStack, Player?, int, int) variants
    private Class<?> shopItemClass;       // return type of getShopItem / first param of getItemSellPrice

    // Extract price from SellPrice
    private Method priceNoArgMethod;
    private Method priceOneArgMethod;
    private Class<?> economyTypeParamClass;
    private Object vaultEcoType;

    private String resolvedHookClassName = "";
    private String resolvedSellPriceClassName = "";
    private String resolvedShopItemClassName = "";

    // For /siptest details
    private String lastDebugReason = "";

    public EconomyShopGUIReflectHook(Plugin esguiPlugin) {
        try {
            ClassLoader cl = esguiPlugin.getClass().getClassLoader();

            hookClass = tryLoadKnownOrScan(esguiPlugin, cl,
                    "com.gypopo.economyshopgui.api.EconomyShopGUIHook",
                    "EconomyShopGUIHook.class");
            if (hookClass == null) {
                ready = false;
                notReadyReason = "ClassNotFoundException: EconomyShopGUIHook";
                return;
            }
            resolvedHookClassName = hookClass.getName();

            // Locate methods on hook class
            for (Method m : hookClass.getMethods()) {
                String n = m.getName();

                if (n.equals("getSellPrice") && m.getParameterCount() == 2 && ItemStack.class.isAssignableFrom(m.getParameterTypes()[1])) {
                    // accept OfflinePlayer / Player / UUID
                    Class<?> ownerT = m.getParameterTypes()[0];
                    if (OfflinePlayer.class.isAssignableFrom(ownerT) || Player.class.isAssignableFrom(ownerT) || UUID.class.isAssignableFrom(ownerT)) {
                        getSellPriceMethod = m;
                    }
                }

                if (n.equals("getShopItem")) {
                    if (m.getParameterCount() == 1 && ItemStack.class.isAssignableFrom(m.getParameterTypes()[0])) {
                        getShopItem1 = m;
                        shopItemClass = m.getReturnType();
                    } else if (m.getParameterCount() == 2 && ItemStack.class.isAssignableFrom(m.getParameterTypes()[1])) {
                        getShopItem2 = m;
                        shopItemClass = m.getReturnType();
                    }
                }
            }

            if (shopItemClass != null) resolvedShopItemClassName = shopItemClass.getName();

            // SellPrice class (relocated)
            sellPriceClass = tryLoadKnownOrScan(esguiPlugin, cl,
                    "com.gypopo.economyshopgui.objects.prices.SellPrice",
                    "SellPrice.class");
            if (sellPriceClass != null) resolvedSellPriceClassName = sellPriceClass.getName();

            // Price extract methods from SellPrice (best effort)
            if (sellPriceClass != null) {
                for (Method m : sellPriceClass.getMethods()) {
                    if (m.getName().equals("getPrice") && m.getParameterCount() == 0) {
                        priceNoArgMethod = m;
                        break;
                    }
                }
                if (priceNoArgMethod == null) {
                    for (Method m : sellPriceClass.getMethods()) {
                        if (m.getName().equals("getPrice") && m.getParameterCount() == 1) {
                            priceOneArgMethod = m;
                            economyTypeParamClass = m.getParameterTypes()[0];
                            vaultEcoType = buildVaultEcoType(economyTypeParamClass);
                            break;
                        }
                    }
                }
            }

            // isSellAble + getItemSellPrice fallback methods (only if we have ShopItem)
            if (shopItemClass != null) {
                for (Method m : hookClass.getMethods()) {
                    if (m.getName().equals("isSellAble") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(shopItemClass)) {
                        isSellAbleMethod = m;
                    }
                }
                for (Method m : hookClass.getMethods()) {
                    if (!m.getName().equals("getItemSellPrice")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length < 2) continue;
                    if (!p[0].isAssignableFrom(shopItemClass)) continue;
                    if (!ItemStack.class.isAssignableFrom(p[1])) continue;
                    // we accept various overloads, will try invoke at runtime
                    getItemSellPriceMethod = m;
                    break;
                }
            }

            // Ready if we can at least determine shop item and/or sell price
            if (getSellPriceMethod == null && (getShopItem1 == null && getShopItem2 == null)) {
                ready = false;
                notReadyReason = "Missing getSellPrice and getShopItem methods.";
                return;
            }

            ready = true;
        } catch (Throwable t) {
            ready = false;
            notReadyReason = t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "(no message)" : t.getMessage());
        }
    }

    public boolean isReady() { return ready; }

    public String getNotReadyReason() {
        String extra = "";
        if (!resolvedHookClassName.isEmpty()) extra += " hookClass=" + resolvedHookClassName;
        if (!resolvedSellPriceClassName.isEmpty()) extra += " sellPriceClass=" + resolvedSellPriceClassName;
        if (!resolvedShopItemClassName.isEmpty()) extra += " shopItemClass=" + resolvedShopItemClassName;
        return notReadyReason + (extra.isEmpty() ? "" : " (" + extra.trim() + ")");
    }

    public String getLastDebugReason() { return lastDebugReason; }

    public Double getTotalSellPrice(Player player, ItemStack item) {
        if (!ready) return null;

        lastDebugReason = "";

        // 1) Preferred: getSellPrice Optional
        Double viaOptional = tryGetSellPriceOptional(player, item);
        if (viaOptional != null) return viaOptional;

        // 2) Fallback: resolve ShopItem then getItemSellPrice
        Double viaShopItem = tryGetSellPriceFromShopItem(player, item);
        if (viaShopItem != null) return viaShopItem;

        return null;
    }

    private Double tryGetSellPriceOptional(Player player, ItemStack item) {
        if (getSellPriceMethod == null || sellPriceClass == null) return null;
        try {
            ItemStack clone = item.clone();
            clone.setAmount(Math.max(1, item.getAmount()));

            Object ownerArg = buildOwnerArg(getSellPriceMethod.getParameterTypes()[0], player);

            Object optObj = getSellPriceMethod.invoke(null, ownerArg, clone);
            if (!(optObj instanceof Optional<?> opt) || opt.isEmpty()) {
                lastDebugReason = "getSellPrice Optional empty (not sellable / not in shop / perms/limits/requirements).";
                return null;
            }

            Object sellPrice = opt.get();
            if (!sellPriceClass.isInstance(sellPrice)) {
                lastDebugReason = "getSellPrice returned unexpected type.";
                return null;
            }

            Object priceObj = null;
            if (priceNoArgMethod != null) {
                priceObj = priceNoArgMethod.invoke(sellPrice);
            } else if (priceOneArgMethod != null && vaultEcoType != null) {
                priceObj = priceOneArgMethod.invoke(sellPrice, vaultEcoType);
            }

            if (!(priceObj instanceof Number n)) {
                lastDebugReason = "SellPrice.getPrice(...) did not return a Number.";
                return null;
            }

            double price = n.doubleValue();
            if (price < 0) {
                lastDebugReason = "SellPrice returned -1 / no Vault price configured.";
                return null;
            }
            return price;
        } catch (Throwable t) {
            lastDebugReason = "Exception in getSellPrice: " + t.getClass().getSimpleName();
            return null;
        }
    }

    private Double tryGetSellPriceFromShopItem(Player player, ItemStack item) {
        try {
            Object shopItem = getShopItem(player, item);
            if (shopItem == null) {
                lastDebugReason = "ShopItem NOT FOUND for this ItemStack (not in any shop, or meta mismatch).";
                return null;
            }

            // isSellAble check if possible
            if (isSellAbleMethod != null) {
                Object ok = isSellAbleMethod.invoke(null, shopItem);
                if (ok instanceof Boolean b && !b) {
                    lastDebugReason = "ShopItem found but isSellAble=false.";
                    return null;
                }
            }

            if (getItemSellPriceMethod == null) {
                lastDebugReason = "ShopItem found but no getItemSellPrice method found.";
                return null;
            }

            int amount = Math.max(1, item.getAmount());
            Object priceObj = invokeGetItemSellPrice(shopItem, item, player, amount);
            if (!(priceObj instanceof Number n)) {
                lastDebugReason = "getItemSellPrice did not return a Number.";
                return null;
            }

            double price = n.doubleValue();
            if (price < 0) {
                lastDebugReason = "getItemSellPrice returned -1 / no Vault price configured.";
                return null;
            }
            lastDebugReason = "Price resolved via ShopItem fallback.";
            return price;

        } catch (Throwable t) {
            lastDebugReason = "Exception in ShopItem fallback: " + t.getClass().getSimpleName();
            return null;
        }
    }

    private Object getShopItem(Player player, ItemStack item) throws Exception {
        if (getShopItem1 != null) {
            return getShopItem1.invoke(null, item);
        }
        if (getShopItem2 != null) {
            Class<?> ownerT = getShopItem2.getParameterTypes()[0];
            Object ownerArg = buildOwnerArg(ownerT, player);
            return getShopItem2.invoke(null, ownerArg, item);
        }
        return null;
    }

    private Object invokeGetItemSellPrice(Object shopItem, ItemStack item, Player player, int amount) throws Exception {
        Method m = getItemSellPriceMethod;
        Class<?>[] p = m.getParameterTypes();

        // Try common overload patterns
        // (ShopItem, ItemStack, Player, int, int)
        if (p.length == 5) {
            if (Player.class.isAssignableFrom(p[2])) {
                return m.invoke(null, shopItem, item, player, amount, 0);
            } else {
                // maybe OfflinePlayer
                return m.invoke(null, shopItem, item, (OfflinePlayer) player, amount, 0);
            }
        }
        // (ShopItem, ItemStack, int, int)
        if (p.length == 4) {
            return m.invoke(null, shopItem, item, amount, 0);
        }
        // (ShopItem, ItemStack, Player, int)
        if (p.length == 4 && Player.class.isAssignableFrom(p[2])) {
            return m.invoke(null, shopItem, item, player, amount);
        }
        // (ShopItem, int)
        if (p.length == 2 && p[1] == int.class) {
            return m.invoke(null, shopItem, amount);
        }
        // Default attempt
        Object[] args = new Object[p.length];
        args[0] = shopItem;
        args[1] = item;
        for (int i = 2; i < p.length; i++) {
            if (p[i] == int.class) args[i] = amount;
            else if (Player.class.isAssignableFrom(p[i])) args[i] = player;
            else if (OfflinePlayer.class.isAssignableFrom(p[i])) args[i] = (OfflinePlayer) player;
            else args[i] = 0;
        }
        return m.invoke(null, args);
    }

    private static Object buildOwnerArg(Class<?> ownerParamType, Player player) {
        if (ownerParamType.isAssignableFrom(Player.class)) return player;
        if (ownerParamType.isAssignableFrom(OfflinePlayer.class)) return player;
        if (ownerParamType.isAssignableFrom(UUID.class)) return player.getUniqueId();
        return player;
    }

    private static Object buildVaultEcoType(Class<?> ecoTypeClass) {
        try {
            Method m = ecoTypeClass.getMethod("getFromString", String.class);
            return m.invoke(null, "VAULT");
        } catch (Throwable ignored) {}
        try {
            Method m = ecoTypeClass.getMethod("valueOf", String.class);
            return m.invoke(null, "VAULT");
        } catch (Throwable ignored) {}
        try {
            if (ecoTypeClass.isEnum()) {
                Object[] constants = ecoTypeClass.getEnumConstants();
                if (constants != null) {
                    for (Object c : constants) {
                        if (c.toString().equalsIgnoreCase("VAULT")) return c;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Class<?> tryLoadKnownOrScan(Plugin plugin, ClassLoader cl, String knownFqcn, String endsWith) {
        try {
            return cl.loadClass(knownFqcn);
        } catch (ClassNotFoundException ignored) {
            try {
                String found = findClassNameInJar(plugin, endsWith);
                if (found == null) return null;
                return cl.loadClass(found);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static String findClassNameInJar(Plugin plugin, String endsWith) {
        try {
            URL url = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            URI uri = url.toURI();
            File file = new File(uri);
            if (!file.isFile()) return null;

            try (JarFile jar = new JarFile(file)) {
                Enumeration<JarEntry> en = jar.entries();
                while (en.hasMoreElements()) {
                    JarEntry je = en.nextElement();
                    String name = je.getName();
                    if (!name.endsWith(endsWith)) continue;
                    return name.substring(0, name.length() - 6).replace('/', '.');
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
