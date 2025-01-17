package com.leon.bugreport;

import com.leon.bugreport.extensions.BugReportPair;
import com.leon.bugreport.extensions.PlanHook;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static com.leon.bugreport.BugReportManager.*;

public class BugReportDatabase {
    public static HikariDataSource dataSource;

    public BugReportDatabase() {
        createConnection();

        addColumnIfNotExists("player_data", "player_id", "TEXT, last_login_timestamp BIGINT DEFAULT 0");
        addColumnIfNotExists("bug_reports", "archived",  "INTEGER DEFAULT 0");
        addColumnIfNotExists("bug_reports", "report_id", "INT AUTO_INCREMENT PRIMARY KEY");
        addColumnIfNotExists("bug_reports", "location",  "TEXT");
        addColumnIfNotExists("bug_reports", "gamemode",  "TEXT");

        fixReportID();
        makeAllHeadersEqualReport_ID();
        addTimestampColumn();
    }

    private static void addTimestampColumn() {
        try (Connection connection = dataSource.getConnection()) {
            try (ResultSet archivedResultSet = connection.getMetaData().getColumns(null, null, "player_data", "last_login_timestamp")) {
                if (!archivedResultSet.next()) {
                    connection.createStatement().execute("ALTER TABLE player_data ADD COLUMN last_login_timestamp BIGINT DEFAULT 0");
                }
            }
            try (ResultSet archivedResultSet = connection.getMetaData().getColumns(null, null, "bug_reports", "timestamp")) {
                if (!archivedResultSet.next()) {
                    connection.createStatement().execute("ALTER TABLE bug_reports ADD COLUMN timestamp BIGINT");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to add missing columns.");
            plugin.getLogger().severe(e.getMessage());
        }
    }

    public static void setPlayerLastLoginTimestamp(UUID playerId) {
        try (Connection connection = dataSource.getConnection()) {
            if (getPlayerLastLoginTimestamp(playerId) == 0) {
                PreparedStatement playerDataStatement = connection.prepareStatement("INSERT INTO player_data(player_id, last_login_timestamp) VALUES(?, ?)");
                playerDataStatement.setString(1, playerId.toString());
                playerDataStatement.setLong(2, System.currentTimeMillis());
                playerDataStatement.executeUpdate();
                playerDataStatement.close();
            } else {
                PreparedStatement playerDataStatement = connection.prepareStatement("UPDATE player_data SET last_login_timestamp = ? WHERE player_id = ?");
                playerDataStatement.setLong(1, System.currentTimeMillis());
                playerDataStatement.setString(2, playerId.toString());
                playerDataStatement.executeUpdate();
                playerDataStatement.close();
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to set player last login timestamp.");
            plugin.getLogger().severe(e.getMessage());
        }
    }

    public static long getPlayerLastLoginTimestamp(UUID playerId) {
        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement statement = connection.prepareStatement("SELECT last_login_timestamp FROM player_data WHERE player_id = ?");
            statement.setString(1, playerId.toString());
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getLong("last_login_timestamp");
            }
            statement.close();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to get player last login timestamp.");
            plugin.getLogger().severe(e.getMessage());
        }
        return 0;
    }

    public static @Nullable Location getBugReportLocation(Integer reportIDGUI) {
        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement statement = connection.prepareStatement("SELECT location FROM bug_reports WHERE report_id = ?");
            statement.setInt(1, reportIDGUI);
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                String locationString = resultSet.getString("Location");
                if (locationString != null) {
                    String[] locationSplit = locationString.split(",");
                    return new Location(
                            Bukkit.getWorld(locationSplit[0]),
                            Double.parseDouble(locationSplit[1]),
                            Double.parseDouble(locationSplit[2]),
                            Double.parseDouble(locationSplit[3])
                    );
                }
            }
            statement.close();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to get bug report location.");
            plugin.getLogger().severe(e.getMessage());
        }
        return null;
    }

    private void addColumnIfNotExists(String tableName, String columnName, String columnDefinition) {
        try (Connection connection = dataSource.getConnection()) {
            try (ResultSet resultSet = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
                if (!resultSet.next()) {
                    String query = String.format("ALTER TABLE %s ADD COLUMN %s %s", tableName, columnName, columnDefinition);
                    connection.createStatement().execute(query);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to add missing columns.");
            plugin.getLogger().severe(e.getMessage());
        }
    }

    private void makeAllHeadersEqualReport_ID() {
        try (Connection connection = dataSource.getConnection()) {
            ResultSet resultSet = connection.createStatement().executeQuery("SELECT * FROM bug_reports");
            while (resultSet.next()) {
                int report_id = resultSet.getInt("report_id");
                String header = resultSet.getString("header");
                String[] lines = header.split("\n");
                StringBuilder newHeader = new StringBuilder();
                for (String line : lines) {
                    if (line.startsWith("Report ID:")) {
                        newHeader.append("Report ID: ").append(report_id);
                    } else {
                        newHeader.append(line);
                    }
                    newHeader.append("\n");
                }
                PreparedStatement statement = connection.prepareStatement("UPDATE bug_reports SET header = ? WHERE report_id = ?");
                statement.setString(1, newHeader.toString().trim());
                statement.setInt(2, report_id);
                statement.executeUpdate();
                statement.close();
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to make all headers equal report_id.");
            plugin.getLogger().severe(e.getMessage());
        }
    }

    private void fixReportID() {
        try (Connection connection = dataSource.getConnection()) {
            ResultSet resultSet = connection.createStatement().executeQuery("SELECT * FROM bug_reports WHERE report_id IS NULL OR report_id = 0");
            while (resultSet.next()) {
                int report_id = resultSet.getInt("report_id");
                int rowNumber = resultSet.getRow();
                if (report_id != rowNumber) {
                    PreparedStatement statement = connection.prepareStatement("UPDATE bug_reports SET report_id = ? WHERE report_id = ?");
                    statement.setInt(1, rowNumber);
                    statement.setInt(2, report_id);
                    statement.executeUpdate();
                    statement.close();
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to fix report_id.");
            plugin.getLogger().severe(e.getMessage());
        }

    }

    public static void createConnection() {
        loadConfig();
        String databaseType = Objects.requireNonNull(config.getString("databaseType"));
        ConfigurationSection databaseSection = Objects.requireNonNull(config.getConfigurationSection("database"));

        if (databaseType.equalsIgnoreCase("local")) {
            plugin.getLogger().info("Connecting to local database");
            connectLocal();
        } else if (databaseType.equalsIgnoreCase("mysql")) {
            plugin.getLogger().info("Connecting to remote database");

            String host = databaseSection.getString("host");
            int port = databaseSection.getInt("port");
            String database = databaseSection.getString("database");
            String username = databaseSection.getString("username");
            String password = databaseSection.getString("password");

            connectRemote(host, port, database, username, password);
        } else {
            plugin.getLogger().warning("Invalid database type. Please use 'local' or 'mysql'.");
        }
    }

    public void addBugReport(String username, @NotNull UUID playerId, String world, String header, String fullMessage, String location, String gamemode) {
        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO bug_reports(player_id, header, message, username, world, archived, report_id, timestamp, location, gamemode) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            int report_id = 1;
            ResultSet resultSet = connection.createStatement().executeQuery("SELECT report_id FROM bug_reports ORDER BY report_id DESC LIMIT 1");
            if (resultSet.next()) {
                report_id = resultSet.getInt("report_id") + 1;
            }
            statement.setString(1, playerId.toString());
            statement.setString(2, header);
            statement.setString(3, fullMessage);
            statement.setString(4, username);
            statement.setString(5, world);
            statement.setInt(6, 0);
            statement.setInt(7, report_id);
            statement.setLong(8, System.currentTimeMillis());
            statement.setString(9, location);
            statement.setString(10, gamemode);

            if (Bukkit.getPluginManager().isPluginEnabled("Plan")) {
                PlanHook.getInstance().updateHook(playerId, username);
            }

            statement.executeUpdate();
            statement.close();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to add bug report.");
            plugin.getLogger().severe(e.getMessage());
        }
    }

    public static long loadBugReportCountForPlayer(@NotNull UUID playerID) {
        int count = 0;

        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM bug_reports WHERE player_id = ?");
            statement.setString(1, playerID.toString());
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                count = resultSet.getInt(1);
            }
            resultSet.close();
            statement.close();
        } catch (SQLException e) {
            plugin.getLogger().severe(e.getMessage());
        }

        return count;
    }

    public static long loadArchivedBugReportCountForPlayer(@NotNull UUID playerID) {
        int count = 0;

        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM bug_reports WHERE player_id = ? AND archived = 1");
            statement.setString(1, playerID.toString());
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                count = resultSet.getInt(1);
            }
            resultSet.close();
            statement.close();
        } catch (SQLException e) {
            plugin.getLogger().severe(e.getMessage());
        }

        return count;
    }

    public static long loadNonArchivedBugReportCountForPlayer(@NotNull UUID playerID) {
        int count = 0;

        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM bug_reports WHERE player_id = ? AND archived = 0");
            statement.setString(1, playerID.toString());
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                count = resultSet.getInt(1);
            }
            resultSet.close();
            statement.close();
        } catch (SQLException e) {
            plugin.getLogger().severe(e.getMessage());
        }

        return count;
    }

    public static long loadBugReportCount() {
        int count = 0;

        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM bug_reports");
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                count = resultSet.getInt(1);
            }
            resultSet.close();
            statement.close();
        } catch (SQLException e) {
            plugin.getLogger().severe(e.getMessage());
        }

        return count;
    }

    public static @NotNull List<BugReportPair<String, String>> loadBugReportCountsPerPlayer() {
        List<BugReportPair<String, String>> reports = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            String sql = "SELECT username, message FROM bug_reports";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String username = resultSet.getString("username");
                    String message = resultSet.getString("message");
                    reports.add(new BugReportPair<>(username, message));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe(e.getMessage());
        }
        return reports;
    }

    public static @NotNull List<BugReportPair<String, String>> loadBugReportAllPlayer(String playerName) {
        List<BugReportPair<String, String>> reports = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement statement = connection.prepareStatement("SELECT timestamp, message FROM bug_reports WHERE username = ?");
            statement.setString(1, playerName);
            ResultSet resultSet = statement.executeQuery();

            while (resultSet.next()) {
                long timestamp = resultSet.getLong("timestamp");
                String message = resultSet.getString("message");
                String timestampToString = translateTimestampToDate(timestamp);
                reports.add(new BugReportPair<>(timestampToString, message));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe(e.getMessage());
        }
        return reports;
    }

    public static long loadArchivedBugReportCount() {
        int count = 0;

        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM bug_reports WHERE archived = 1");
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                count = resultSet.getInt(1);
                resultSet.close();
                statement.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe(e.getMessage());
        }

        return count;
    }

    public static long loadNonArchivedBugReportCount() {
        int count = 0;

        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM bug_reports WHERE archived = 0");
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                count = resultSet.getInt(1);
                resultSet.close();
                statement.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe(e.getMessage());
        }

        return count;
    }

    public static @NotNull Map<UUID, List<String>> loadBugReports() {
        Map<UUID, List<String>> bugReports = new HashMap<>();

        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement statement = connection.prepareStatement("SELECT * FROM bug_reports ORDER BY report_id ASC");
            ResultSet resultSet = statement.executeQuery();

            while (resultSet.next()) {
                UUID playerId = UUID.fromString(resultSet.getString("player_id"));
                String header = resultSet.getString("header");
                String fullMessage = resultSet.getString("message");
                String username = resultSet.getString("username");
                String world = resultSet.getString("world");
                String archived = resultSet.getString("archived");
                String report_id = resultSet.getString("report_id");
                long timestamp = resultSet.getLong("timestamp");
                String location = resultSet.getString("location");
                String gamemode = resultSet.getString("gamemode");

                List<String> reports = bugReports.getOrDefault(getStaticUUID(), new ArrayList<>(Collections.singletonList("DUMMY")));
                reports.add(
                    "Username: " + username + "\n" +
                    "UUID: " + playerId + "\n" +
                    "World: " + world + "\n" +
                    "Full Message: " + fullMessage + "\n" +
                    "Header: " + header + "\n" +
                    "Archived: " + archived + "\n" +
                    "Report ID: " + report_id + "\n" +
                    "Timestamp: " + timestamp + "\n" +
                    "Location: " + location + "\n" +
                    "Gamemode: " + gamemode
                );

                if (Bukkit.getPluginManager().isPluginEnabled("Plan")) {
                    PlanHook.getInstance().updateHook(playerId, username);
                }

                bugReports.put(getStaticUUID(), reports);
            }

            resultSet.close();
            statement.close();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load bug reports.");
            if (e.getMessage().startsWith("[SQLITE_CORRUPT]")) {
                plugin.getLogger().severe("Your database is corrupted. Please delete the database file and restart the server.");
                plugin.getLogger().severe("File path: plugins/BugReport/bugreports.db");
                plugin.getLogger().severe("If you need help, please join the discord server: https://discord.gg/ZvdNYqmsbx");
            } else {
                plugin.getLogger().severe(e.getMessage());
            }
        }

        return bugReports;
    }

    public static @NotNull UUID getStaticUUID() {
        return UUID.fromString("00000000-0000-0000-0000-000000000000");
    }

    private static void connectRemote(String host, Integer port, String database, String username, String password) {
        HikariConfig hikariConfig = new HikariConfig();
        try {
            hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false");
            hikariConfig.setUsername(username);
            hikariConfig.setPassword(password);
            dataSource = new HikariDataSource(hikariConfig);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to connect to remote database.");
            plugin.getLogger().severe(e.getMessage());
        }

        plugin.getLogger().info("Connected to remote database");
        createTables();
    }

    private static void connectLocal() {
        try {
            File databaseFile = new File("plugins/BugReport/bugreports.db");
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            dataSource = new HikariDataSource(hikariConfig);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to connect to local database.");
            plugin.getLogger().severe(e.getMessage());
        }

        plugin.getLogger().info("Connected to local database");
        createTables();
    }

    private static void createTables() {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("CREATE TABLE IF NOT EXISTS bug_reports(rowid INTEGER, player_id TEXT, header TEXT, message TEXT, username TEXT, world TEXT, archived INTEGER DEFAULT 0, report_id INTEGER, timestamp BIGINT)");
            connection.createStatement().execute("CREATE TABLE IF NOT EXISTS player_data(player_id TEXT, last_login_timestamp BIGINT DEFAULT 0)");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to create tables.");
            plugin.getLogger().severe(e.getMessage());
        }
    }

    public static void updateBugReportArchive(int reportIndex, int archived) {
        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement statement = connection.prepareStatement("UPDATE bug_reports SET archived = ? WHERE report_id = ?");
            statement.setInt(1, archived);
            statement.setInt(2, reportIndex);
            statement.executeUpdate();
            statement.close();
            loadBugReports();

            List<String> reports = bugReports.getOrDefault(getStaticUUID(), new ArrayList<>(Collections.singletonList("DUMMY")));
            String existingHeader = reports.stream()
                .filter(reportString -> reportString.contains("Report ID: " + reportIndex))
                .findFirst()
                .orElse(null);
            int existingHeaderPosition = reports.indexOf(existingHeader);

            String[] lines = existingHeader != null ? existingHeader.split("\n") : new String[0];
            StringBuilder newHeader = new StringBuilder();
            for (String line : lines) {
                if (line.startsWith("Archived:")) {
                    newHeader.append("Archived: ").append(archived);
                } else {
                    newHeader.append(line);
                }
                newHeader.append("\n");
            }
            reports.set(existingHeaderPosition, newHeader.toString().trim());
            bugReports.put(getStaticUUID(), reports);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to update bug report archive status.");
            plugin.getLogger().severe(e.getMessage());
        }
    }

    public static void deleteBugReport(int reportIndex) {
        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement statement = connection.prepareStatement("DELETE FROM bug_reports WHERE report_id = ?");
            statement.setInt(1, reportIndex);
            statement.executeUpdate();
            statement.close();

            loadBugReports();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to delete bug report.");
            plugin.getLogger().severe(e.getMessage());
        }
    }
}
