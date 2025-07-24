package lightstudio.barn_plugin

import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DatabaseManager(private val plugin: JavaPlugin) {

    private var connection: Connection? = null
    private lateinit var executor: ExecutorService

    fun connect() {
        executor = Executors.newCachedThreadPool()
        CompletableFuture.runAsync({ // Run connection on a separate thread
            try {
                val dbFile = File(plugin.dataFolder, "barn.db")
                if (!dbFile.exists()) {
                    dbFile.parentFile.mkdirs()
                    dbFile.createNewFile()
                }
                val url = "jdbc:sqlite:${dbFile.absolutePath}"
                connection = DriverManager.getConnection(url)
                plugin.logger.info("SQLite connection established.")
                createTables()
            } catch (e: SQLException) {
                plugin.logger.severe("Failed to connect to SQLite database: ${e.message}")
            }
        }, executor)
    }

    fun disconnect() {
        CompletableFuture.runAsync({ // Run disconnection on a separate thread
            try {
                connection?.close()
                plugin.logger.info("SQLite connection closed.")
            } catch (e: SQLException) {
                plugin.logger.severe("Failed to close SQLite connection: ${e.message}")
            } finally {
                executor.shutdown()
            }
        }, executor)
    }

    private fun createTables() {
        val createVillagePointsTable = """
            CREATE TABLE IF NOT EXISTS village_points (
                village_name TEXT PRIMARY KEY,
                points INTEGER NOT NULL
            );
        """.trimIndent()

        val createPlayerQuotasTable = """
            CREATE TABLE IF NOT EXISTS player_quotas (
                player_uuid TEXT PRIMARY KEY,
                quota INTEGER NOT NULL
            );
        """.trimIndent()

        val createFailedQuotasTable = """
            CREATE TABLE IF NOT EXISTS failed_quotas (
                player_uuid TEXT PRIMARY KEY
            );
        """.trimIndent()

        try {
            connection?.createStatement()?.use { statement ->
                statement.execute(createVillagePointsTable)
                statement.execute(createPlayerQuotasTable)
                statement.execute(createFailedQuotasTable)
            }
            plugin.logger.info("Database tables created or already exist.")
        } catch (e: SQLException) {
            plugin.logger.severe("Failed to create tables: ${e.message}")
        }
    }

    fun getVillagePoints(villageName: String): CompletableFuture<Int> {
        return CompletableFuture.supplyAsync({ 
            var points = 0
            val sql = "SELECT points FROM village_points WHERE village_name = ?"
            try {
                connection?.prepareStatement(sql)?.use { pstmt ->
                    pstmt.setString(1, villageName)
                    val rs = pstmt.executeQuery()
                    if (rs.next()) {
                        points = rs.getInt("points")
                    }
                }
            } catch (e: SQLException) {
                plugin.logger.severe("Failed to get village points for $villageName: ${e.message}")
            }
            points
        }, executor)
    }

    fun setVillagePoints(villageName: String, points: Int): CompletableFuture<Void> {
        return CompletableFuture.runAsync({ 
            val sql = "INSERT OR REPLACE INTO village_points (village_name, points) VALUES (?, ?)"
            try {
                connection?.prepareStatement(sql)?.use { pstmt ->
                    pstmt.setString(1, villageName)
                    pstmt.setInt(2, points)
                    pstmt.executeUpdate()
                }
            } catch (e: SQLException) {
                plugin.logger.severe("Failed to set village points for $villageName: ${e.message}")
            }
        }, executor)
    }

    fun getPlayerQuota(playerUUID: UUID): CompletableFuture<Int> {
        return CompletableFuture.supplyAsync({ 
            var quota = 0
            val sql = "SELECT quota FROM player_quotas WHERE player_uuid = ?"
            try {
                connection?.prepareStatement(sql)?.use { pstmt ->
                    pstmt.setString(1, playerUUID.toString())
                    val rs = pstmt.executeQuery()
                    if (rs.next()) {
                        quota = rs.getInt("quota")
                    }
                }
            } catch (e: SQLException) {
                plugin.logger.severe("Failed to get player quota for $playerUUID: ${e.message}")
            }
            quota
        }, executor)
    }

    fun setPlayerQuota(playerUUID: UUID, quota: Int): CompletableFuture<Void> {
        return CompletableFuture.runAsync({ 
            val sql = "INSERT OR REPLACE INTO player_quotas (player_uuid, quota) VALUES (?, ?)"
            try {
                connection?.prepareStatement(sql)?.use { pstmt ->
                    pstmt.setString(1, playerUUID.toString())
                    pstmt.setInt(2, quota)
                    pstmt.executeUpdate()
                }
            } catch (e: SQLException) {
                plugin.logger.severe("Failed to set player quota for $playerUUID: ${e.message}")
            }
        }, executor)
    }

    fun getAllVillagePoints(): CompletableFuture<Map<String, Int>> {
        return CompletableFuture.supplyAsync({ 
            val pointsMap = mutableMapOf<String, Int>()
            val sql = "SELECT village_name, points FROM village_points"
            try {
                connection?.createStatement()?.use { statement ->
                    val rs = statement.executeQuery(sql)
                    while (rs.next()) {
                        pointsMap[rs.getString("village_name")] = rs.getInt("points")
                    }
                }
            } catch (e: SQLException) {
                plugin.logger.severe("Failed to get all village points: ${e.message}")
            }
            pointsMap
        }, executor)
    }

    fun resetAllQuotas(): CompletableFuture<Void> {
        return CompletableFuture.runAsync({ 
            val sql = "DELETE FROM player_quotas"
            try {
                connection?.createStatement()?.use { statement ->
                    statement.executeUpdate(sql)
                }
            } catch (e: SQLException) {
                plugin.logger.severe("Failed to reset all quotas: ${e.message}")
            }
        }, executor)
    }

    fun resetVillagePoints(): CompletableFuture<Void> {
        return CompletableFuture.runAsync({ 
            setVillagePoints(BarnPlugin.VILLAGE_MODAKRI, 0).join()
            setVillagePoints(BarnPlugin.VILLAGE_HWADEOKRI, 0).join()
        }, executor)
    }

    fun deleteAllData(): CompletableFuture<Void> {
        return CompletableFuture.runAsync({ 
            try {
                connection?.createStatement()?.use { statement ->
                    statement.executeUpdate("DELETE FROM village_points")
                    statement.executeUpdate("DELETE FROM player_quotas")
                    statement.executeUpdate("DELETE FROM failed_quotas") // Also delete failed quotas
                }
            } catch (e: SQLException) {
                plugin.logger.severe("Failed to delete all data: ${e.message}")
            }
        }, executor)
    }

    fun markPlayerFailed(playerUUID: UUID): CompletableFuture<Void> {
        return CompletableFuture.runAsync({ 
            val sql = "INSERT OR REPLACE INTO failed_quotas (player_uuid) VALUES (?)"
            try {
                connection?.prepareStatement(sql)?.use { pstmt ->
                    pstmt.setString(1, playerUUID.toString())
                    pstmt.executeUpdate()
                }
            } catch (e: SQLException) {
                plugin.logger.severe("Failed to mark player $playerUUID as failed: ${e.message}")
            }
        }, executor)
    }

    fun isPlayerFailed(playerUUID: UUID): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync({ 
            var failed = false
            val sql = "SELECT player_uuid FROM failed_quotas WHERE player_uuid = ?"
            try {
                connection?.prepareStatement(sql)?.use { pstmt ->
                    pstmt.setString(1, playerUUID.toString())
                    val rs = pstmt.executeQuery()
                    failed = rs.next()
                }
            } catch (e: SQLException) {
                plugin.logger.severe("Failed to check if player $playerUUID is failed: ${e.message}")
            }
            failed
        }, executor)
    }

    fun removePlayerFailed(playerUUID: UUID): CompletableFuture<Void> {
        return CompletableFuture.runAsync({ 
            val sql = "DELETE FROM failed_quotas WHERE player_uuid = ?"
            try {
                connection?.prepareStatement(sql)?.use { pstmt ->
                    pstmt.setString(1, playerUUID.toString())
                    pstmt.executeUpdate()
                }
            } catch (e: SQLException) {
                plugin.logger.severe("Failed to remove player $playerUUID from failed: ${e.message}")
            }
        }, executor)
    }

    fun getAllPlayerQuotas(): CompletableFuture<Map<UUID, Int>> {
        return CompletableFuture.supplyAsync({ 
            val quotasMap = mutableMapOf<UUID, Int>()
            val sql = "SELECT player_uuid, quota FROM player_quotas"
            try {
                connection?.createStatement()?.use { statement ->
                    val rs = statement.executeQuery(sql)
                    while (rs.next()) {
                        quotasMap[UUID.fromString(rs.getString("player_uuid"))] = rs.getInt("quota")
                    }
                }
            } catch (e: SQLException) {
                plugin.logger.severe("Failed to get all player quotas: ${e.message}")
            }
            quotasMap
        }, executor)
    }
}
