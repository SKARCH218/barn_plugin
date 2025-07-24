package lightstudio.barn_plugin

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.min
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.bukkit.ChatColor

class BarnPlugin : JavaPlugin(), Listener, CommandExecutor, TabCompleter {

    companion object {
        const val VILLAGE_MODAKRI = "모닥리"
        const val VILLAGE_HWADEOKRI = "화덕리"
    }

    private lateinit var databaseManager: DatabaseManager
    private val currentPlayerVillage = mutableMapOf<UUID, String>()
    private val itemPoints = mutableMapOf<Material, Int>()
    private val messages = mutableMapOf<String, String>()
    private var guiTitle: String = ""
    private var quotaLimit: Int = 1000
    private lateinit var rewardItem: Material
    private var rewardAmount: Int = 1
    private lateinit var dailyResetTime: LocalTime
    private var commandOnQuotaFail: String = ""

    private lateinit var langConfig: FileConfiguration
    private lateinit var keepConfig: FileConfiguration

    private val schedulerExecutor = Executors.newSingleThreadScheduledExecutor()

    override fun onEnable() {
        // Plugin startup logic
        saveDefaultConfig()
        loadConfigs()

        databaseManager = DatabaseManager(this)
        databaseManager.connect()

        Bukkit.getPluginManager().registerEvents(this, this)
        getCommand("헛간")?.setExecutor(this)
        getCommand("헛간")?.tabCompleter = this
        getCommand("헛간관리")?.setExecutor(this)
        getCommand("헛간관리")?.tabCompleter = this
        getCommand("소속확인")?.setExecutor(this)
        getCommand("마을랭킹")?.setExecutor(this)
        getCommand("barnadmin")?.setExecutor(this)
        getCommand("barnadmin")?.tabCompleter = this

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            BarnPlaceholderExpansion(this).register()
        } else {
            logger.warning(messages["placeholderapi_not_found"] ?: "PlaceholderAPI not found! Placeholder support will be disabled.")
        }

        scheduleDailyReset()
        logger.info(messages["plugin_enabled"] ?: "BarnPlugin has been enabled!")
    }

    override fun onDisable() {
        // Plugin shutdown logic
        databaseManager.disconnect()
        schedulerExecutor.shutdownNow()
        logger.info(messages["plugin_disabled"] ?: "BarnPlugin has been disabled!")
    }

    private fun loadConfigs() {
        // Load config.yml
        reloadConfig()
        guiTitle = ChatColor.translateAlternateColorCodes('&', config.getString("gui_title") ?: "&f&l:offset_-8::heotgan:")
        quotaLimit = config.getInt("quota_limit", 1000)
        rewardItem = Material.valueOf(config.getString("reward_item", "DIAMOND")!!.uppercase())
        rewardAmount = config.getInt("reward_amount", 1)
        dailyResetTime = LocalTime.parse(config.getString("daily_reset_time", "00:00"))
        commandOnQuotaFail = config.getString("command_on_quota_fail", "") ?: ""

        // Load lang.yml
        val langFile = File(dataFolder, "lang.yml")
        if (!langFile.exists()) {
            saveResource("lang.yml", false)
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile)
        messages.clear() // Clear existing messages before loading new ones
        langConfig.getConfigurationSection("messages")?.getKeys(false)?.forEach { key ->
            messages[key] = ChatColor.translateAlternateColorCodes('&', langConfig.getString("messages.$key") ?: "")
        }

        // Load keep.yml
        val keepFile = File(dataFolder, "keep.yml")
        if (!keepFile.exists()) {
            saveResource("keep.yml", false)
        }
        keepConfig = YamlConfiguration.loadConfiguration(keepFile)
        itemPoints.clear() // Clear existing items before loading new ones
        keepConfig.getConfigurationSection("items")?.getKeys(false)?.forEach { key ->
            try {
                val material = Material.valueOf(key.uppercase())
                itemPoints[material] = keepConfig.getInt("items.$key")
            } catch (e: IllegalArgumentException) {
                logger.warning("Invalid material \'$key\' in keep.yml items section.")
            }
        }
    }

    private fun scheduleDailyReset() {
        val now = LocalDateTime.now()
        var nextReset = now.with(dailyResetTime)
        if (now.isAfter(nextReset)) {
            nextReset = nextReset.plusDays(1)
        }
        val initialDelayMillis = ChronoUnit.MILLIS.between(now, nextReset)

        schedulerExecutor.schedule({
            Bukkit.getScheduler().runTask(this, Runnable { // Run on main thread for Bukkit API calls
                processVillageResults()
                scheduleDailyReset() // Reschedule for next reset time
            })
        }, initialDelayMillis, TimeUnit.MILLISECONDS)
    }

    private fun processVillageResults() {
        databaseManager.getAllPlayerQuotas().thenAccept { allPlayerQuotas ->
            val playersToPunish = mutableListOf<UUID>()
            for ((playerUUID, quota) in allPlayerQuotas) {
                if (quota < quotaLimit) {
                    playersToPunish.add(playerUUID)
                }
            }

            for (playerUUID in playersToPunish) {
                val player = Bukkit.getPlayer(playerUUID)
                if (player != null && player.isOnline) {
                    // Player is online, execute command immediately
                    val commandToExecute = commandOnQuotaFail.replace("{player}", player.name)
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandToExecute)
                    player.sendMessage(messages["quota_fail_message"] ?: "")
                } else {
                    // Player is offline, mark for punishment on next join
                    databaseManager.markPlayerFailed(playerUUID)
                }
            }

            databaseManager.resetAllQuotas().thenRun {
                databaseManager.resetVillagePoints()
            }
        }

        databaseManager.getVillagePoints(VILLAGE_MODAKRI).thenAccept { modakriPoints ->
            databaseManager.getVillagePoints(VILLAGE_HWADEOKRI).thenAccept { hwadeokriPoints ->
                when {
                    modakriPoints > hwadeokriPoints -> {
                        Bukkit.broadcastMessage(messages["modakri_win"]?.replace("%points%", modakriPoints.toString()) ?: "")
                    }
                    hwadeokriPoints > modakriPoints -> {
                        Bukkit.broadcastMessage(messages["hwadeokri_win"]?.replace("%points%", hwadeokriPoints.toString()) ?: "")
                    }
                    else -> {
                        Bukkit.broadcastMessage(messages["draw"] ?: "")
                    }
                }
            }
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        databaseManager.isPlayerFailed(player.uniqueId).thenAccept { isFailed ->
            if (isFailed) {
                val commandToExecute = commandOnQuotaFail.replace("{player}", player.name)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandToExecute)
                player.sendMessage(messages["quota_fail_message"] ?: "")
                databaseManager.removePlayerFailed(player.uniqueId)
            }
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.view.title.contains(guiTitle)) {
            event.isCancelled = true

            val clickedItem = event.currentItem ?: return
            if (clickedItem.type == Material.AIR) return

            val itemType = clickedItem.type
            val point = itemPoints[itemType] ?: return // Get point from config

            var amountToProcess = 0
            when (event.click) {
                ClickType.LEFT -> amountToProcess = 64
                ClickType.RIGHT -> amountToProcess = 1
                ClickType.DROP, ClickType.CONTROL_DROP -> amountToProcess = clickedItem.amount // Amount in hand
                else -> return
            }

            databaseManager.getPlayerQuota(player.uniqueId).thenAccept { currentQuota ->
                val availableQuota = quotaLimit - currentQuota

                val playerInventoryAmount = player.inventory.all(itemType).values.sumOf { it.amount }
                var finalAmount = min(amountToProcess, playerInventoryAmount)

                if (finalAmount <= 0) {
                    player.sendMessage(messages["inventory_no_item"] ?: "")
                    return@thenAccept
                }

                finalAmount = min(finalAmount, availableQuota)

                if (finalAmount <= 0) {
                    player.sendMessage(messages["quota_exceeded"] ?: "")
                    return@thenAccept
                }

                val village = currentPlayerVillage[player.uniqueId] ?: run {
                    player.sendMessage(messages["village_info_not_set"] ?: "")
                    player.closeInventory()
                    return@thenAccept
                }

                player.inventory.removeItem(ItemStack(itemType, finalAmount))
                databaseManager.setPlayerQuota(player.uniqueId, currentQuota + finalAmount).thenRun {
                    databaseManager.getVillagePoints(village).thenAccept { currentVillagePoints ->
                        databaseManager.setVillagePoints(village, currentVillagePoints + (point * finalAmount)).thenRun {
                            // Feedback to player
                            val newQuota = currentQuota + finalAmount
                            val remainingQuota = quotaLimit - newQuota
                            val earnedPoints = point * finalAmount
                            player.sendMessage(messages["item_stored_feedback"]
                                ?.replace("%item%", itemType.name)
                                ?.replace("%amount%", finalAmount.toString())
                                ?.replace("%points%", earnedPoints.toString())
                                ?.replace("%current_quota%", newQuota.toString())
                                ?.replace("%remaining_quota%", remainingQuota.toString()) ?: "")

                            if (newQuota >= quotaLimit) {
                                player.inventory.addItem(ItemStack(rewardItem, rewardAmount).apply {
                                    itemMeta = itemMeta?.apply { setDisplayName("${ChatColor.BLUE}보상 아이템") }
                                })
                                player.sendMessage(messages["quota_full_reward"] ?: "")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (command.name) {
            "헛간" -> {
                if (sender !is Player) {
                    sender.sendMessage(messages["player_only_command"] ?: "")
                    return true
                }
                if (args.isEmpty()) {
                    sender.sendMessage(messages["invalid_village_name"] ?: "")
                    return true
                }
                val villageName = args[0]
                if (villageName != VILLAGE_MODAKRI && villageName != VILLAGE_HWADEOKRI) {
                    sender.sendMessage(messages["invalid_village_name"] ?: "")
                    return true
                }

                currentPlayerVillage[sender.uniqueId] = villageName
                val inventory: Inventory = Bukkit.createInventory(null, 27, guiTitle) // 3 rows

                // Populate inventory with items from itemPoints
                itemPoints.keys.forEachIndexed { index, material ->
                    if (index < 27) { // Max 27 slots for a 3-row chest
                        inventory.setItem(index, ItemStack(material, 1))
                    }
                }
                sender.openInventory(inventory)
                return true
            }

            "헛간관리" -> {
                if (!sender.hasPermission("barnplugin.admin")) {
                    sender.sendMessage(messages["no_permission"] ?: "")
                    return true
                }

                if (args.isEmpty() || args[0] == "도움말") {
                    sender.sendMessage("${ChatColor.WHITE}/헛간관리 초기화 - 모든 데이터 초기화")
                    sender.sendMessage("${ChatColor.WHITE}/헛간관리 모닥리포인트설정 <숫자>")
                    sender.sendMessage("${ChatColor.WHITE}/헛간관리 화덕리포인트설정 <숫자>")
                    sender.sendMessage("${ChatColor.WHITE}/헛간관리 모닥리포인트")
                    sender.sendMessage("${ChatColor.WHITE}/헛간관리 화덕리포인트")
                    sender.sendMessage("${ChatColor.WHITE}/헛간관리 플레이어할당량 <플레이어>")
                    sender.sendMessage("${ChatColor.WHITE}/헛간관리 플레이어할당량설정 <플레이어> <숫자>")
                    return true
                }

                when (args[0]) {
                    "초기화" -> {
                        databaseManager.deleteAllData().thenRun {
                            sender.sendMessage(messages["data_reset_success"] ?: "")
                        }
                    }
                    "모닥리포인트설정" -> {
                        if (args.size < 2) {
                            sender.sendMessage(messages["enter_number"] ?: "")
                            return true
                        }
                        val points = args[1].toIntOrNull()
                        if (points != null) {
                            databaseManager.setVillagePoints(VILLAGE_MODAKRI, points).thenRun {
                                sender.sendMessage(messages["modakri_points_set"]?.replace("%points%", points.toString()) ?: "")
                            }
                        } else {
                            sender.sendMessage(messages["invalid_number"] ?: "")
                        }
                    }
                    "화덕리포인트설정" -> {
                        if (args.size < 2) {
                            sender.sendMessage(messages["enter_number"] ?: "")
                            return true
                        }
                        val points = args[1].toIntOrNull()
                        if (points != null) {
                            databaseManager.setVillagePoints(VILLAGE_HWADEOKRI, points).thenRun {
                                sender.sendMessage(messages["hwadeokri_points_set"]?.replace("%points%", points.toString()) ?: "")
                            }
                        } else {
                            sender.sendMessage(messages["invalid_number"] ?: "")
                        }
                    }
                    "모닥리포인트" -> {
                        databaseManager.getVillagePoints(VILLAGE_MODAKRI).thenAccept { points ->
                            sender.sendMessage(messages["modakri_points_current"]?.replace("%points%", points.toString()) ?: "")
                        }
                    }
                    "화덕리포인트" -> {
                        databaseManager.getVillagePoints(VILLAGE_HWADEOKRI).thenAccept { points ->
                            sender.sendMessage(messages["hwadeokri_points_current"]?.replace("%points%", points.toString()) ?: "")
                        }
                    }
                    "플레이어할당량" -> {
                        if (args.size < 2) {
                            sender.sendMessage(messages["enter_player_name"] ?: "")
                            return true
                        }
                        val targetPlayer = Bukkit.getOfflinePlayer(args[1])
                        databaseManager.getPlayerQuota(targetPlayer.uniqueId).thenAccept { quota ->
                            sender.sendMessage(messages["player_quota_current"]
                                ?.replace("%player%", targetPlayer.name ?: "Unknown")
                                ?.replace("%quota%", quota.toString())
                                ?.replace("%limit%", quotaLimit.toString()) ?: "")
                        }
                    }
                    "플레이어할당량설정" -> {
                        if (args.size < 3) {
                            sender.sendMessage(messages["enter_player_name_and_quota"] ?: "")
                            return true
                        }
                        val targetPlayer = Bukkit.getOfflinePlayer(args[1])
                        val quota = args[2].toIntOrNull()
                        if (quota != null) {
                            databaseManager.setPlayerQuota(targetPlayer.uniqueId, quota).thenRun {
                                sender.sendMessage(messages["player_quota_set"]
                                    ?.replace("%player%", targetPlayer.name ?: "Unknown")
                                    ?.replace("%quota%", quota.toString()) ?: "")
                            }
                        } else {
                            sender.sendMessage(messages["invalid_number"] ?: "")
                        }
                    }
                    else -> {
                        sender.sendMessage(messages["unknown_command"] ?: "")
                    }
                }
                return true
            }

            "소속확인" -> {
                if (sender !is Player) {
                    sender.sendMessage(messages["player_only_command"] ?: "")
                    return true
                }

                when {
                    sender.isOp -> {
                        sender.sendMessage(messages["op_village_status"] ?: "")
                    }
                    sender.hasPermission("group.modack") -> {
                        sender.sendMessage(messages["modakri_village_status"] ?: "")
                    }
                    sender.hasPermission("group.hwaduck") -> {
                        sender.sendMessage(messages["hwadeokri_village_status"] ?: "")
                    }
                    else -> {
                        sender.sendMessage(messages["no_village_status"] ?: "")
                    }
                }
                return true
            }

            "마을랭킹" -> {
                databaseManager.getAllVillagePoints().thenAccept { villagePoints ->
                    val sortedPoints = villagePoints.entries.sortedByDescending { it.value }
                    sender.sendMessage(messages["ranking_title"] ?: "")
                    if (sortedPoints.isEmpty()) {
                        sender.sendMessage("${ChatColor.GRAY}아직 기록된 마을 포인트가 없습니다.")
                    } else {
                        sortedPoints.forEachIndexed { index, entry ->
                            sender.sendMessage(messages["ranking_entry"]
                                ?.replace("%rank%", (index + 1).toString())
                                ?.replace("%village%", entry.key)
                                ?.replace("%points%", entry.value.toString()) ?: "")
                        }
                    }
                }
                return true
            }

            "barnadmin" -> {
                if (!sender.hasPermission("barnplugin.admin")) {
                    sender.sendMessage(messages["no_permission"] ?: "")
                    return true
                }
                if (args.isEmpty() || args[0].equals("help", true)) {
                    sender.sendMessage(messages["barnadmin_help"] ?: "")
                    return true
                }
                when (args[0].lowercase()) {
                    "reload" -> {
                        loadConfigs()
                        sender.sendMessage(messages["barnadmin_reloaded"] ?: "")
                    }
                    else -> {
                        sender.sendMessage(messages["unknown_command"] ?: "")
                    }
                }
                return true
            }
        }
        return false
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String>? {
        when (command.name) {
            "헛간" -> {
                if (args.size == 1) {
                    return mutableListOf(VILLAGE_MODAKRI, VILLAGE_HWADEOKRI).filter { it.startsWith(args[0], true) }.toMutableList()
                }
            }

            "헛간관리" -> {
                if (sender.hasPermission("barnplugin.admin")) {
                    val subCommands = listOf(
                        "도움말",
                        "초기화",
                        "모닥리포인트설정",
                        "화덕리포인트설정",
                        "모닥리포인트",
                        "화덕리포인트",
                        "플레이어할당량",
                        "플레이어할당량설정"
                    )

                    if (args.size == 1) {
                        return subCommands.filter { it.startsWith(args[0], true) }.toMutableList()
                    } else if (args.size == 2) {
                        when (args[0]) {
                            "플레이어할당량", "플레이어할당량설정" -> {
                                return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }.toMutableList()
                            }
                        }
                    }
                }
            }
            "마을랭킹" -> {
                // No tab completion for /마을랭킹 as it has no arguments
                return mutableListOf()
            }
            "barnadmin" -> {
                if (sender.hasPermission("barnplugin.admin")) {
                    if (args.size == 1) {
                        return listOf("reload", "help").filter { it.startsWith(args[0], true) }.toMutableList()
                    }
                }
            }
        }
        return null
    }

    inner class BarnPlaceholderExpansion(private val plugin: BarnPlugin) : PlaceholderExpansion() {
        override fun getIdentifier(): String = "skriptplaceholders"
        override fun getAuthor(): String = plugin.description.authors.firstOrNull() ?: "Unknown"
        override fun getVersion(): String = plugin.description.version
        override fun onPlaceholderRequest(player: Player?, identifier: String): String? {
            if (player == null) return ""

            return when (identifier) {
                "할당량" -> plugin.databaseManager.getPlayerQuota(player.uniqueId).join().toString()
                "소속마을" -> {
                    when {
                        player.isOp -> plugin.messages["op_village_status"]
                        player.hasPermission("group.modack") -> plugin.messages["modakri_village_status"]
                        player.hasPermission("group.hwaduck") -> plugin.messages["hwadeokri_village_status"]
                        else -> plugin.messages["no_village_status"]
                    }
                }
                else -> null
            }
        }
    }
}