package com.leon.bugreport;

import com.leon.bugreport.discord.LinkDiscord;
import com.leon.bugreport.extensions.PlanHook;
import com.leon.bugreport.listeners.ReportCreatedEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

import static com.leon.bugreport.API.DataSource.getPlayerHead;
import static com.leon.bugreport.BugReportDatabase.getStaticUUID;
import static com.leon.bugreport.BugReportSettings.getSettingsGUI;
import static com.leon.bugreport.commands.BugReportCommand.stringColorToColorCode;
import static com.leon.bugreport.gui.bugreportGUI.openBugReportDetailsGUI;

public class BugReportManager implements Listener {
    public static Map<UUID, List<String>> bugReports;
    private static BugReportDatabase database;
    public static Plugin plugin;

    public static FileConfiguration config;
    public static File configFile;

    public static String language;
    public static BugReportLanguage lang;

    private final LinkDiscord discord;
    private final List<Category> reportCategories;

    public static String pluginTitle;
    public static ChatColor pluginColor;

    public BugReportManager(Plugin plugin) throws Exception {
        BugReportManager.plugin = plugin;
        bugReports = new HashMap<> ();
        database = new BugReportDatabase();

        loadBugReports();
        loadConfig();
        checkConfig();

        String webhookURL = config.getString("webhookURL", "");
        pluginTitle = Objects.requireNonNull(config.getString("pluginTitle", "[Bug Report]"));
        pluginColor = stringColorToColorCode(Objects.requireNonNull(config.getString("pluginColor", "Yellow").toUpperCase()));

        discord = new LinkDiscord(webhookURL);
        reportCategories = loadReportCategories();
    }

    public static boolean checkCategoryConfig() {
        if (!config.contains("reportCategories")) {
            plugin.getLogger().warning(DefaultLanguageSelector.getTextElseDefault(language, "missingReportCategoryMessage"));
            return false;
        }

        List<Map<?, ?>> categoryList = config.getMapList("reportCategories");
        for (Map<?, ?> categoryMap : categoryList) {
            Object[] keys = categoryMap.keySet().toArray();
            Object[] values = categoryMap.values().toArray();

            for (int i = 0; i < keys.length; i++) {
                if (values[i] == null) {
                    plugin.getLogger().warning(DefaultLanguageSelector.getTextElseDefault(language, "missingValueMessage").replace("%key%", keys[i].toString()));
                    return false;
                }
            }
        }
        return true;
    }

    public static void loadConfig() {
        configFile = new File (plugin.getDataFolder(), "config.yml");

        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(configFile);

        language = config.getString("language", "en");
        lang = new BugReportLanguage(plugin, "languages.yml");
    }

    public static void checkConfig() {
        Map<String, ?> newValues = new HashMap<>() {
            {
                put("webhookURL", "https://discord.com/api/webhooks/");
                put("enableDiscordWebhook", true);
                put("enablePluginReportCategories", false);
                put("enableBugReportNotifications", false);
                put("discordEmbedTitle", "New Bug Report");
                put("discordEmbedColor", "Yellow");
                put("discordEmbedFooter", "Bug Report V0.9.1");
                put("discordEmbedThumbnail", "https://www.spigotmc.org/data/resource_icons/110/110732.jpg");
                put("discordEnableThumbnail", true);
                put("discordEnableUserAuthor", true);
                put("discordIncludeDate", true);
                put("useTitleInsteadOfMessage", false);
                put("enablePlayerHeads", true);
                put("refreshPlayerHeadCache", "1d");
                put("language", "en");
                put("max-reports-per-player", 50);
                put("report-confirmation-message", "Thanks for submitting a report!");
                put("pluginColor", "Yellow");
                put("pluginTitle", "[Bug Report]");
            }
        };

        for (Map.Entry<String, ?> entry : newValues.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!config.contains(key)) {
                config.set(key, value);
            }
        }
        saveConfig();
    }

    private @Nullable List<Category> loadReportCategories() {
        if (checkCategoryConfig()) {
            List<Category> categories = new ArrayList<>();
            List<Map<?, ?>> categoryList = config.getMapList("reportCategories");

            for (Map<?, ?> categoryMap : categoryList) {
                String name = categoryMap.get("name").toString();
                int id = Integer.parseInt(categoryMap.get("id").toString());

                String description = categoryMap.get("description").toString();
                String itemString = categoryMap.get("item").toString();
                String color = categoryMap.get("color").toString().toUpperCase();

                Material itemMaterial = Material.matchMaterial(itemString);
                if (itemMaterial == null) {
                    continue;
                }

                ItemStack itemStack = new ItemStack(itemMaterial);
                ItemMeta itemMeta = itemStack.getItemMeta();
                Objects.requireNonNull(itemMeta).setDisplayName(ChatColor.YELLOW + name);
                itemMeta.setLore(Collections.singletonList(ChatColor.GRAY + description));
                itemStack.setItemMeta(itemMeta);
                categories.add(new Category(id, name, color, itemStack));
            }

            return categories;
        } else {
            plugin.getLogger().warning(DefaultLanguageSelector.getTextElseDefault(language, "wentWrongLoadingCategoriesMessage"));
            return null;
        }
    }

    public List<Category> getReportCategories() {
        return reportCategories;
    }

    public static void saveConfig() {
        try {
            config.save(configFile);
        } catch (Exception e) {
            plugin.getLogger().warning("Error saving config.yml: " + e.getMessage());
        }
    }

    public void setWebhookURL(String webhookURL) {
        config.set("webhookURL", webhookURL);
        saveConfig();
        discord.setWebhookURL(webhookURL);
    }

    public void submitBugReport(@NotNull Player player, String message, Integer categoryId) {
        UUID playerId = player.getUniqueId();
        List<String> reports = bugReports.getOrDefault(getStaticUUID(), new ArrayList<>(Collections.singletonList("DUMMY")));
        String playerName = player.getName();
        String playerUUID = playerId.toString();
        String worldName = player.getWorld().getName();
        String location = player.getWorld().getName() + ", " + player.getLocation().getBlockX() + ", " + player.getLocation().getBlockY() + ", " + player.getLocation().getBlockZ();
        String gamemode = player.getGameMode().toString();

        String reportID = reports.stream()
            .filter(report -> report.contains("Report ID: "))
            .reduce((first, second) -> second)
            .map(report -> Arrays.stream(report.split("\n"))
                .filter(line -> line.contains("Report ID:"))
                .findFirst()
                .orElse("Report ID: 0")
            )
            .map(reportIDLine -> reportIDLine.split(": ")[1].trim())
            .orElse("0");

        String reportIDInt = String.valueOf(Integer.parseInt(reportID) + 1);
        String header = "Username: " + playerName + "\n" +
                "UUID: " + playerUUID + "\n" +
                "World: " + worldName + "\n" +
                "hasBeenRead: 0" + "\n" +
                "Category ID: " + categoryId + "\n" +
                "Full Message: " + message + "\n" +
                "Archived: 0" + "\n" +
                "Report ID: " + reportIDInt + "\n" +
                "Timestamp: " + System.currentTimeMillis() + "\n" +
                "Location: " + location + "\n" +
                "Gamemode: " + gamemode;

        reports.add(header);
        bugReports.put(playerId, reports);

        if (Bukkit.getPluginManager().isPluginEnabled("Plan")) {
            PlanHook.getInstance().updateHook(playerId, playerName);
        }

        database.addBugReport(playerName, playerId, worldName, header, message, location, gamemode);

        if (config.getBoolean("enableBugReportNotifications", true)) {
            String defaultMessage = pluginColor + pluginTitle + " " + ChatColor.GRAY + DefaultLanguageSelector.getTextElseDefault(language, "bugReportNotificationMessage").replace("%player%", ChatColor.AQUA + playerName + ChatColor.GRAY);

            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (onlinePlayer.hasPermission("bugreport.notify")) {
                    onlinePlayer.sendMessage(defaultMessage);
                }
            }
        }

        if (config.getBoolean("enableDiscordWebhook", true)) {
            String webhookURL = config.getString("webhookURL", "");
            if (webhookURL.isEmpty()) {
                plugin.getLogger().warning(DefaultLanguageSelector.getTextElseDefault(language, "missingDiscordWebhookURLMessage"));
            }

            try {
                discord.sendBugReport(message, worldName, playerName, location, gamemode);
            } catch (Exception e) {
                plugin.getLogger().warning("Error sending bug report to Discord: " + e.getMessage());
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            ReportCreatedEvent reportEvent = new ReportCreatedEvent(header);
            Bukkit.getServer().getPluginManager().callEvent(reportEvent);
        });
    }

    public static @NotNull Inventory generateBugReportGUI(@NotNull Player player, boolean showArchived) {
        loadBugReports();

        int itemsPerPage = 27;
        int navigationRow = 36;

        List<String> reports = bugReports.getOrDefault(getStaticUUID(), new ArrayList<>(Collections.singletonList("DUMMY")));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (String report : reports) {
                String[] reportLines = report.split("\n");
                Map<String, String> reportData = new HashMap<>();
                for (String line : reportLines) {
                    int colonIndex = line.indexOf(":");
                    if (colonIndex >= 0) {
                        String key = line.substring(0, colonIndex).trim();
                        String value = line.substring(colonIndex + 1).trim();
                        reportData.put(key, value);
                    }
                }
                String username = reportData.get("Username");
                getPlayerHead(username);
            }
        });

        List<String> filteredReports = getFilteredReports(showArchived, reports);

        int totalPages = Math.max(1, (int) Math.ceil((double) filteredReports.size() / itemsPerPage));
        int currentPage = Math.max(1, Math.min(getCurrentPage(player), totalPages));

        Inventory gui = Bukkit.createInventory(
                null,
                45,
                ChatColor.YELLOW + (showArchived ? "Archived Bugs" : "Bug Report") + " - " + Objects.requireNonNull(BugReportLanguage.getTitleFromLanguage("pageInfo"))
                    .replace("%currentPage%", String.valueOf(currentPage))
                    .replace("%totalPages%", String.valueOf(totalPages)));

        int startIndex = (currentPage - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, filteredReports.size());
        int slotIndex = 0;

        for (int i = startIndex; i < endIndex; i++) {
            String report = filteredReports.get(i);
            String reportID = getReportID(report);
            String firstLine = report.split("\n")[0];

            String username = firstLine.split(": ")[1];
            ItemStack playerHead = getPlayerHead(username);

            ItemStack reportItem = new ItemStack(playerHead);

            ItemMeta itemMeta = reportItem.getItemMeta();
            Objects.requireNonNull(itemMeta).setDisplayName(ChatColor.YELLOW + "Bug Report #" + reportID);
            itemMeta.setLore(Collections.singletonList(ChatColor.GRAY + firstLine));

            reportItem.setItemMeta(itemMeta);

            gui.setItem(slotIndex, reportItem);
            slotIndex++;
        }

        ItemStack settingsButton = createButton(Material.CHEST, ChatColor.YELLOW + BugReportLanguage.getTitleFromLanguage("settings"));
        ItemStack closeButton = createButton(Material.BARRIER, ChatColor.RED + BugReportLanguage.getTitleFromLanguage("close"));
        ItemStack pageIndicator = createButton(Material.PAPER, ChatColor.YELLOW + Objects.requireNonNull(BugReportLanguage.getTitleFromLanguage("pageInfo"))
            .replace("%currentPage%", String.valueOf(currentPage))
            .replace("%totalPages%", String.valueOf(totalPages)));

        if (BugReportManager.getCurrentPage(player) == 1) {
            gui.setItem(36, new ItemStack(Material.AIR));
        } else {
            createNavigationButtons("back", gui, 36);
        }
        if (BugReportManager.getCurrentPage(player) == BugReportManager.getTotalPages()) {
            gui.setItem(44, new ItemStack(Material.AIR));
        } else {
            createNavigationButtons("forward", gui, 44);
        }

        gui.setItem(navigationRow + 2, settingsButton);
        gui.setItem(navigationRow + 4, pageIndicator);
        gui.setItem(navigationRow + 6, closeButton);

        return gui;
    }

    private static String getReportID(String report) {
        String[] reportLines = report.split("\n");

        Map<String, String> reportData = new HashMap<>();
        for (String line : reportLines) {
            int colonIndex = line.indexOf(":");
            if (colonIndex >= 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                reportData.put(key, value);
            }
        }

		return reportData.get("Report ID");
    }

    private static void createNavigationButtons(String forward, @NotNull Inventory bugReportGUI, int index) {
        ItemStack forwardButton = new ItemStack(Material.ARROW);
        ItemMeta forwardMeta = forwardButton.getItemMeta();
        Objects.requireNonNull(forwardMeta).setDisplayName(ChatColor.GREEN + BugReportLanguage.getTitleFromLanguage(forward));
        forwardButton.setItemMeta(forwardMeta);
        bugReportGUI.setItem(index, forwardButton);
    }

    @NotNull
    private static List<String> getFilteredReports(boolean showArchived, @NotNull List<String> reports) {
        List<String> filteredReports = new ArrayList<>();
        for (String report : reports) {
            if ((showArchived && report.contains("Archived: 1")) || (!showArchived && !report.contains("DUMMY") && !report.contains("Archived: 1"))) {
                filteredReports.add(report);
            }
        }

        filteredReports.sort((r1, r2) -> {
            int id1 = Integer.parseInt(extractReportIDFromReport(r1));
            int id2 = Integer.parseInt(extractReportIDFromReport(r2));
            return Integer.compare(id1, id2);
        });
        return filteredReports;
    }

    private static @NotNull String extractReportIDFromReport(@NotNull String report) {
        String[] reportLines = report.split("\n");
        for (String line : reportLines) {
            int colonIndex = line.indexOf(":");
            if (colonIndex >= 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                if ("Report ID".equals(key)) {
                    return value;
                }
            }
        }
        return "0";
    }

    public static @NotNull Inventory getArchivedBugReportsGUI(Player player) {
        return generateBugReportGUI(player, true);
    }

    public static @NotNull Inventory getBugReportGUI(Player player) {
        return generateBugReportGUI(player, false);
    }

    public static @NotNull ItemStack createButton(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        Objects.requireNonNull(meta).setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private static void loadBugReports() {
        bugReports = BugReportDatabase.loadBugReports();
    }

    public static class BugReportListener implements Listener {
        private final Map<UUID, Boolean> closingInventoryMap;

        public BugReportListener() {
            this.closingInventoryMap = new HashMap<>();
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onInventoryClick(@NotNull InventoryClickEvent event) {
            String TitleText = ChatColor.stripColor(event.getView().getTitle());

            boolean isArchivedGUI = TitleText.startsWith("Archived Bugs");

            if (!TitleText.startsWith("Bug Report") && !isArchivedGUI) {
                return;
            }

            event.setCancelled(true);

            Player player = (Player) event.getWhoClicked();
            Inventory clickedInventory = event.getClickedInventory();
            if (clickedInventory == null) {
                return;
            }

            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType() == Material.AIR) {
                return;
            }

            ItemMeta itemMeta = clickedItem.getItemMeta();
            if (itemMeta == null || !itemMeta.hasDisplayName()) {
                return;
            }

            String displayName = itemMeta.getDisplayName();
            String customDisplayName = BugReportLanguage.getEnglishVersionFromLanguage(displayName);

            switch (customDisplayName) {
                case "Back" -> {
                    int currentPage = getCurrentPage(player);
                    if (currentPage > 1) {
                        setCurrentPage(player, currentPage - 1);
                        player.openInventory(isArchivedGUI ? getArchivedBugReportsGUI(player) : getBugReportGUI(player));
                    }
                }
                case "Forward" -> {
                    int currentPage = getCurrentPage(player);
                    if (currentPage < getTotalPages()) {
                        setCurrentPage(player, currentPage + 1);
                        player.openInventory(isArchivedGUI ? getArchivedBugReportsGUI(player) : getBugReportGUI(player));
                    }
                }
                case "Settings" -> player.openInventory(getSettingsGUI());
                case "Close" -> {
                    closingInventoryMap.put(player.getUniqueId(), true);
                    player.closeInventory();
                }
            }
            if (displayName.startsWith(ChatColor.YELLOW + "Bug Report #")) {
                int reportID = Integer.parseInt(displayName.substring(14));
                List<String> reports = bugReports.getOrDefault(getStaticUUID(), new ArrayList<>(Collections.singletonList("DUMMY")));
                String report = reports.stream()
                    .filter(reportString -> reportString.contains("Report ID: " + reportID))
                    .findFirst()
                    .orElse(null);

                openBugReportDetailsGUI(player, report, reportID, isArchivedGUI);
            }

            if (customDisplayName.equals("Settings")) {
                player.openInventory(getSettingsGUI());
            }

            if (customDisplayName.equals("Close")) {
                closingInventoryMap.put(player.getUniqueId(), true);
                player.closeInventory();
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onInventoryClose(@NotNull InventoryCloseEvent event) {
            if (event.getView().getTitle().startsWith(ChatColor.YELLOW + "Bug Report")) {
                Player player = (Player) event.getPlayer();
                UUID playerId = player.getUniqueId();

                if (closingInventoryMap.getOrDefault(playerId, false)) {
                    closingInventoryMap.put(playerId, false);
                    return;
                }

                closingInventoryMap.remove(playerId);
            }
        }
    }

    public static int getCurrentPage(@NotNull Player player) {
        return player.getMetadata("currentPage").get(0).asInt();
    }

    public static int getTotalPages() {
        List<String> reports = bugReports.getOrDefault(getStaticUUID(), new ArrayList<>(Collections.singletonList("DUMMY")));
        return (int) Math.ceil((double) reports.size() / 27);
    }

    public static void setCurrentPage(@NotNull Player player, int page) {
        player.setMetadata("currentPage", new FixedMetadataValue (plugin, page));
    }

    /**
     * Check if a key exists in the config, and if it does, return the value.
     * If checkForBoolean is true, return the value.
     * If checkForBoolean is false, return if the key exists.
     */
    public static boolean checkForKey(String key, Boolean checkForBoolean) {
        if (!config.contains(key) && config.get(key) != null) {
            return false;
        }

        if (checkForBoolean) {
            return config.getBoolean(key);
        } else {
            return true;
        }
    }

    public static @NotNull String translateTimestampToDate(long timestamp) {
        Date date = new Date(timestamp);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);

        int day = calendar.get(Calendar.DAY_OF_MONTH);
        int hour = calendar.get(Calendar.HOUR);
        int minute = calendar.get(Calendar.MINUTE);

        String daySuffix = getDayOfMonthSuffix(day);
        String amPm = calendar.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
        String hourString = String.valueOf(hour);
        String minuteString = String.valueOf(minute);

        if (hour < 10) hourString = "0" + hourString;
        if (minute < 10) minuteString = "0" + minuteString;

        return new StringJoiner(" ")
            .add(calendar.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.ENGLISH))
            .add(day + daySuffix + ",")
            .add(String.valueOf(calendar.get(Calendar.YEAR)))
            .add("at")
            .add(hourString + ":" + minuteString)
            .add(amPm)
            .toString();
    }

    private static String getDayOfMonthSuffix(int day) {
        return switch (day) {
            case 1, 21, 31 -> "st";
            case 2, 22 -> "nd";
            case 3, 23 -> "rd";
            default -> "th";
        };
    }

    public record BugReportDetailsListener(Inventory gui, Integer reportIDGUI) implements Listener {
        @EventHandler(priority = EventPriority.NORMAL)
        public void onInventoryClick(@NotNull InventoryClickEvent event) {
            String title = event.getView().getTitle();
            boolean isArchivedDetails = title.startsWith(ChatColor.YELLOW + "Archived Bug Details");

            if (!title.startsWith(ChatColor.YELLOW + "Bug Report Details - #") && !title.startsWith(ChatColor.YELLOW + "Archived Bug Details")) {
                return;
            }

            event.setCancelled(true);

            Player player = (Player) event.getWhoClicked();
            UUID playerId = player.getUniqueId();

            Inventory clickedInventory = event.getClickedInventory();
            ItemStack clickedItem = event.getCurrentItem();

            if (clickedInventory == null || clickedItem == null || clickedItem.getType() == Material.AIR) {
                return;
            }

            ItemMeta itemMeta = clickedItem.getItemMeta();

            if (itemMeta == null || !itemMeta.hasDisplayName() || itemMeta.getDisplayName().equals(" ")) {
                return;
            }

            String customDisplayName = BugReportLanguage.getEnglishVersionFromLanguage(itemMeta.getDisplayName());

            switch (customDisplayName) {
                case "Back" -> player .openInventory(isArchivedDetails ? getArchivedBugReportsGUI(player) : getBugReportGUI(player));
                case "Unarchive" -> {
                    BugReportDatabase.updateBugReportArchive(reportIDGUI, 0);

                    player.openInventory(isArchivedDetails ? getArchivedBugReportsGUI(player) : getBugReportGUI(player));
                    player.sendMessage(ChatColor.YELLOW + "Bug Report #" + reportIDGUI + " has been unarchived.");

                    HandlerList.unregisterAll(this);
                }
                case "Archive" -> {
                    // TODO: Something is causing both Archive and Delete to be called twice and delete the incorrect ID!
                    BugReportDatabase.updateBugReportArchive(reportIDGUI, 1);

                    player.openInventory(isArchivedDetails ? getArchivedBugReportsGUI(player) : getBugReportGUI(player));
                    player.sendMessage(ChatColor.YELLOW + "Bug Report #" + reportIDGUI + " has been archived.");

                    HandlerList.unregisterAll(this);
                }
                case "Delete" -> {
                    // TODO: Something is causing both Archive and Delete to be called twice and delete the incorrect ID!
                    BugReportDatabase.deleteBugReport(reportIDGUI);

                    List<String> reports = bugReports.getOrDefault(getStaticUUID(), new ArrayList<>(Collections.singletonList("DUMMY")));
                    reports.removeIf(report -> report.contains("Report ID: " + reportIDGUI));
                    bugReports.put(playerId, reports);

                    player.openInventory(isArchivedDetails ? getArchivedBugReportsGUI(player) : getBugReportGUI(player));
                    player.sendMessage(ChatColor.RED + "Bug Report #" + reportIDGUI + " has been deleted.");

                    HandlerList.unregisterAll(this);
                }
                case "Location (Click to teleport)" -> {
                    if (checkForKey("useTitleInsteadOfMessage", true)) {
                        player.sendTitle (pluginColor + pluginTitle, ChatColor.GREEN + "Teleporting to the location of Bug Report #" + reportIDGUI + "...", 10, 70, 20);
                    } else {
                        player.sendMessage(ChatColor.YELLOW + "Teleporting to the location of Bug Report #" + reportIDGUI + "...");
                    }

                    Location teleportLocation = BugReportDatabase.getBugReportLocation(reportIDGUI);
                    if (teleportLocation != null) {
                        player.teleport(teleportLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    } else {
                        player.sendMessage(ChatColor.RED + "The location of Bug Report #" + reportIDGUI + " is not available.");
                    }
                }
                default -> {
                    return;
                }
            }

            HandlerList.unregisterAll(this);
        }
    }

    public static @NotNull ItemStack createEmptyItem() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        Objects.requireNonNull(meta).setDisplayName(" ");
        item.setItemMeta(meta);

        return item;
    }

    public static @NotNull ItemStack createInfoItem(Material material, String name, String value,
            Boolean @NotNull... longMessage) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        Objects.requireNonNull(meta).setDisplayName(name);

        if (longMessage.length > 0 && longMessage[0]) {
            List<String> lore = new ArrayList<>();
            String[] words = value.split(" ");
            StringBuilder currentLine = new StringBuilder();
            for (String word : words) {
                if (currentLine.length() + word.length() > 30) {
                    lore.add(currentLine.toString());
                    currentLine = new StringBuilder();
                }

                currentLine.append(word).append(" ");
            }

            if (!currentLine.isEmpty()) lore.add(currentLine.toString());
            meta.setLore(lore);
        } else {
            meta.setLore(Collections.singletonList(value));
        }

        item.setItemMeta(meta);
        return item;
    }
}