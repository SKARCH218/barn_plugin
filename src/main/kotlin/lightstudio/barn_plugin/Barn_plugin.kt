package lightstudio.barn_plugin

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.ChatColor
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
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class Reward(val commandType: String, val commands: List<String>)
data class Village(val name: String, var mayor: UUID?, val koreanName: String, val luckPermsGroup: String)

class BarnPlugin : JavaPlugin(), Listener, CommandExecutor, TabCompleter {

    companion object {
        var econ: Economy? = null
    }

    internal val playerPoints = ConcurrentHashMap<UUID, Int>()
    internal val dailyQuota = ConcurrentHashMap<UUID, Int>()

    internal lateinit var databaseManager: DatabaseManager
    private val currentPlayerVillage = mutableMapOf<UUID, String>()
    private val currentPage = mutableMapOf<UUID, Int>()
    private val itemPoints = mutableMapOf<String, Int>()
    internal val messages = mutableMapOf<String, String>()
    private var guiTitle: String = ""
    private var guiLine: Int = 3
    private var quotaLimit: Int = 1000
    private val rewards = mutableMapOf<String, Reward>()
    private lateinit var dailyResetTime: LocalTime
    private var commandOnQuotaFail: String = ""

    private lateinit var langConfig: FileConfiguration
    private lateinit var keepConfig: FileConfiguration
    private lateinit var townConfig: FileConfiguration
    internal val villages = mutableMapOf<String, Village>()

    internal val playerTowns = mutableMapOf<UUID, String>()

    private var dailyResetTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val schedulerExecutor = Executors.newSingleThreadScheduledExecutor()

    private fun createGuiItem(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta?.setDisplayName(name)
        meta?.lore = lore
        item.itemMeta = meta
        return item
    }

    override fun onEnable() {
        // Plugin startup logic
        saveDefaultConfig()
        loadConfigs()

        databaseManager = DatabaseManager(this)
        databaseManager.connect()
        databaseManager.initializeVillages(villages.keys)

        Bukkit.getPluginManager().registerEvents(this, this)
        getCommand("헛간")?.setExecutor(this)
        getCommand("헛간")?.tabCompleter = this
        getCommand("헛간관리")?.setExecutor(this)
        getCommand("헛간관리")?.tabCompleter = this
        getCommand("소속확인")?.setExecutor(this)
        getCommand("마을랭킹")?.setExecutor(this)
        getCommand("barnadmin")?.setExecutor(this)
        getCommand("barnadmin")?.tabCompleter = this
        getCommand("마을관리")?.setExecutor(this)
        getCommand("마을관리")?.tabCompleter = this

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            BarnPlaceholderExpansion(this).register()
        } else {
            logger.warning(messages["placeholderapi_not_found"] ?: "PlaceholderAPI not found! Placeholder support will be disabled.")
        }

        if (!setupEconomy()) {
            logger.severe(String.format("[%s] - Disabled due to no Vault dependency found!", description.name));
            server.pluginManager.disablePlugin(this);
            return;
        }

        TownFeature(this)
        scheduleDailyReset()

        // Schedule periodic data saving
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                databaseManager.getPlayerVillage(player.uniqueId).thenAccept { village ->
                    village?.let {
                        databaseManager.savePlayerData(player.uniqueId, playerPoints[player.uniqueId] ?: 0, it)
                    }
                }
            }
        }, 72000L, 72000L) // Run every hour (20 ticks * 60 seconds * 60 minutes)
    }

    private fun setupEconomy(): Boolean {
        if (server.pluginManager.getPlugin("Vault") == null) {
            return false
        }
        val rsp = server.servicesManager.getRegistration(Economy::class.java)
        if (rsp == null) {
            return false
        }
        econ = rsp.provider
        return econ != null
    }

    override fun onDisable() {
        // Plugin shutdown logic
        for (player in Bukkit.getOnlinePlayers()) {
            databaseManager.getPlayerVillage(player.uniqueId).thenAccept { village ->
                village?.let {
                    databaseManager.savePlayerData(player.uniqueId, playerPoints[player.uniqueId] ?: 0, it)
                }
            }
        }
        databaseManager.disconnect()
        schedulerExecutor.shutdownNow()
    }

    private fun loadConfigs() {
        // Load config.yml
        reloadConfig()
        guiTitle = ChatColor.translateAlternateColorCodes('&', config.getString("gui.gui_title") ?: "&f&l:offset_-8::heotgan:")
        guiLine = config.getInt("gui.gui_line", 3)
        quotaLimit = config.getInt("quota_limit", 1000)
        dailyResetTime = LocalTime.parse(config.getString("daily_reset_time", "00:00"))
        commandOnQuotaFail = config.getString("command_on_quota_fail", "") ?: ""

        rewards.clear()
        config.getConfigurationSection("reward")?.getKeys(false)?.forEach { key ->
            val section = config.getConfigurationSection("reward.$key")
            if (section != null) {
                val commandType = section.getString("command-type", "console")!!
                val commands = section.getStringList("command")
                if (commands.isNotEmpty()) {
                    rewards[key] = Reward(commandType, commands)
                }
            }
        }

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
            val itemIdentifier = keepConfig.getString("items.$key.item")
            val point = keepConfig.getInt("items.$key.point")
            if (itemIdentifier != null && point > 0) {
                itemPoints[itemIdentifier] = point
            }
        }

        // Load town.yml
        val townFile = File(dataFolder, "town.yml")
        if (!townFile.exists()) {
            saveResource("town.yml", false)
        }
        townConfig = YamlConfiguration.loadConfiguration(townFile)
        villages.clear()
        townConfig.getConfigurationSection("villages")?.getKeys(false)?.forEach { villageName ->
            val mayorUUID = townConfig.getString("villages.$villageName.mayor")
            val mayor = if (mayorUUID.isNullOrEmpty()) null else UUID.fromString(mayorUUID)
            val koreanName = townConfig.getString("villages.$villageName.korean-name") ?: villageName
            val luckPermsGroup = townConfig.getString("villages.$villageName.luckperms-group") ?: villageName
            villages[villageName] = Village(villageName, mayor, koreanName, luckPermsGroup)
        }

        playerTowns.clear()
        townConfig.getConfigurationSection("players")?.getKeys(false)?.forEach { uuidString ->
            val uuid = UUID.fromString(uuidString)
            val town = townConfig.getString("players.$uuidString")
            if (town != null) {
                playerTowns[uuid] = town
            }
        }
    }

    private fun saveTownConfig() {
        villages.forEach { (villageName, village) ->
            townConfig.set("villages.$villageName.mayor", village.mayor?.toString() ?: "")
        }
        townConfig.save(File(dataFolder, "town.yml"))
    }

    private fun scheduleDailyReset() {
        dailyResetTask?.cancel(false)

        val now = LocalDateTime.now()
        var nextReset = now.with(dailyResetTime)
        if (now.isAfter(nextReset)) {
            nextReset = nextReset.plusDays(1)
        }
        val initialDelayMillis = ChronoUnit.MILLIS.between(now, nextReset)

        dailyResetTask = schedulerExecutor.schedule({
            Bukkit.getScheduler().runTask(this, Runnable { // Run on main thread for Bukkit API calls
                processVillageResults()
                scheduleDailyReset() // Reschedule for next reset time
            })
        }, initialDelayMillis, TimeUnit.MILLISECONDS)
    }

    private fun processVillageResults() {
        databaseManager.getAllPlayerQuotas().thenAccept { allPlayerQuotas ->
            val playersToPunish = mutableListOf<UUID>()
            // Iterate through players assigned to a village
            for ((playerUUID, villageName) in playerTowns) {
                val quota = allPlayerQuotas[playerUUID] ?: 0 // Get quota, default to 0 if not found
                if (quota < quotaLimit) {
                    playersToPunish.add(playerUUID)
                }
            }

            for (playerUUID in playersToPunish) {
                val player = Bukkit.getPlayer(playerUUID)
                if (player != null && player.isOnline) {
                    // Player is online, execute command immediately on main thread
                    Bukkit.getScheduler().runTask(this, Runnable {
                        val commandToExecute = commandOnQuotaFail.replace("{player}", player.name)
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandToExecute)
                        player.sendMessage(messages["quota_fail_message"] ?: "")
                    })
                } else {
                    // Player is offline, mark for punishment on next join
                    databaseManager.markPlayerFailed(playerUUID)
                }
            }

            databaseManager.resetAllQuotas().thenCompose { 
                val villageNames = villages.keys.toList()
                val futures = villageNames.map { villageName ->
                    databaseManager.getVillagePoints(villageName).thenCompose { dailyPoints ->
                        databaseManager.getCumulativeVillagePoints(villageName).thenCompose { cumulativePoints ->
                            databaseManager.setCumulativeVillagePoints(villageName, cumulativePoints + dailyPoints)
                        }
                    }
                }
                CompletableFuture.allOf(*futures.toTypedArray()).thenCompose {
                    databaseManager.resetVillagePoints(villages.keys)
                }
            }.thenRun {
                databaseManager.getAllVillagePoints().thenAccept { villagePoints ->
                    Bukkit.getScheduler().runTask(this, Runnable {
                        val sortedVillages = villagePoints.entries.sortedByDescending { it.value }
                        if (sortedVillages.isNotEmpty()) {
                            val totalPoints = sortedVillages.sumOf { it.value }
                            if (totalPoints == 0) {
                                Bukkit.broadcastMessage(messages["draw"] ?: "오늘은 무승부입니다!")
                            } else {
                                val winner = sortedVillages.first()
                                val winnerKoreanName = villages[winner.key]?.koreanName ?: winner.key
                                Bukkit.broadcastMessage(messages["village_win"]?.replace("%village%", winnerKoreanName)?.replace("%points%", winner.value.toString()) ?: "")
                            }
                        } else {
                            // Handle case where no villages have points (e.g., all 0 points)
                            Bukkit.broadcastMessage(messages["no_village_winner"] ?: "오늘의 할당치 1등 마을이 없습니다.")
                        }
                    })
                }
            }
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        databaseManager.getPlayerVillage(player.uniqueId).thenAccept { village ->
            if (village == null) {
                playerPoints[player.uniqueId] = 0
            } else {
                databaseManager.getPlayerPoints(player.uniqueId).thenAccept { points ->
                    playerPoints[player.uniqueId] = points
                }
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        databaseManager.getPlayerVillage(player.uniqueId).thenAccept { village ->
            village?.let {
                databaseManager.savePlayerData(player.uniqueId, playerPoints[player.uniqueId] ?: 0, it)
            }
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.view.title.contains(guiTitle)) {
            event.isCancelled = true

            // Only process clicks within the custom GUI, not the player's inventory
            if (event.clickedInventory != event.view.topInventory) {
                return
            }

            val clickedItem = event.currentItem ?: return
            if (clickedItem.type == Material.AIR) {
                return
            }

            // Handle pagination buttons
            if (clickedItem.itemMeta?.displayName == messages["gui_prev_page"]) {
                val currentPageNum = currentPage.getOrDefault(player.uniqueId, 0)
                if (currentPageNum > 0) {
                    currentPage[player.uniqueId] = currentPageNum - 1
                    // Re-open the GUI with the updated page
                    val villageNameInput = currentPlayerVillage[player.uniqueId] ?: return
                    val targetVillage = villages.values.firstOrNull { it.name.lowercase() == villageNameInput.lowercase() } ?: return
                    openBarnGUI(player, targetVillage) // Create a helper function to open the GUI
                }
                return
            } else if (clickedItem.itemMeta?.displayName == messages["gui_next_page"]) {
                val itemsPerPage = (guiLine - 1) * 9
                val totalPages = (itemPoints.size + itemsPerPage - 1) / itemsPerPage
                val currentPageNum = currentPage.getOrDefault(player.uniqueId, 0)
                if (currentPageNum < totalPages - 1) {
                    currentPage[player.uniqueId] = currentPageNum + 1
                    // Re-open the GUI with the updated page
                    val villageNameInput = currentPlayerVillage[player.uniqueId] ?: return
                    val targetVillage = villages.values.firstOrNull { it.name.lowercase() == villageNameInput.lowercase() } ?: return
                    openBarnGUI(player, targetVillage) // Create a helper function to open the GUI
                }
                return
            }

            // Find the itemIdentifier and its point based on the clickedItem
            val entry = itemPoints.entries.find { (itemIdentifier, _) ->
                if (itemIdentifier.contains(":")) { // ItemsAdder custom item
                    if (Bukkit.getPluginManager().getPlugin("ItemsAdder") != null) {
                        val isCustomItem = dev.lone.itemsadder.api.ItemsAdder.isCustomItem(clickedItem)
                        val clickedCustomItemName = dev.lone.itemsadder.api.ItemsAdder.getCustomItemName(clickedItem)
                        isCustomItem && clickedCustomItemName == itemIdentifier
                    } else {
                        false
                    }
                } else { // Vanilla item
                    Material.valueOf(itemIdentifier.uppercase()) == clickedItem.type
                }
            } ?: return

            val itemIdentifier = entry.key
            val itemPointPerUnit = entry.value

            val displayItemName: String = if (itemIdentifier.contains(":")) {
                if (Bukkit.getPluginManager().getPlugin("ItemsAdder") != null) {
                    dev.lone.itemsadder.api.CustomStack.getInstance(itemIdentifier)?.itemStack?.itemMeta?.displayName ?: itemIdentifier
                } else {
                    itemIdentifier
                }
            } else {
                try {
                    ItemStack(Material.valueOf(itemIdentifier.uppercase())).itemMeta?.displayName ?: itemIdentifier
                } catch (e: IllegalArgumentException) {
                    itemIdentifier
                }
            }

            var amountToProcess = 0
            when (event.click) {
                ClickType.LEFT -> amountToProcess = 64
                ClickType.RIGHT -> amountToProcess = 1
                ClickType.DROP, ClickType.CONTROL_DROP -> amountToProcess = clickedItem.amount // Amount in hand
                else -> {
                    return
                }
            }

            databaseManager.getPlayerQuota(player.uniqueId).thenAccept { currentQuotaPoints ->
                val availableQuotaPoints = quotaLimit - currentQuotaPoints

                val playerInventoryAmountOfItems = if (itemIdentifier.contains(":")) {
                    if (Bukkit.getPluginManager().getPlugin("ItemsAdder") != null) {
                        var amount = 0
                        for (item in player.inventory.contents) {
                            if (item == null || item.type.isAir) continue
                            if (dev.lone.itemsadder.api.ItemsAdder.isCustomItem(item)) {
                                if (dev.lone.itemsadder.api.ItemsAdder.getCustomItemName(item) == itemIdentifier) {
                                    amount += item.amount
                                }
                            }
                        }
                        amount
                    } else {
                        0
                    }
                } else {
                    player.inventory.all(clickedItem.type).values.sumOf { it.amount }
                }

                // Calculate how many items can be processed based on player's inventory and click type
                val itemsToConsider = min(amountToProcess, playerInventoryAmountOfItems)

                // Calculate potential points from these items
                val potentialPointsFromItems = itemsToConsider * itemPointPerUnit

                // Determine actual points earned based on available quota
                val pointsEarnedThisTransaction = min(potentialPointsFromItems, availableQuotaPoints)

                // Calculate actual number of items to remove based on points earned
                var actualItemsToProcess = if (itemPointPerUnit > 0) {
                    val calculatedAmount = pointsEarnedThisTransaction / itemPointPerUnit
                    if (pointsEarnedThisTransaction > 0 && calculatedAmount == 0) {
                        1 // If points earned is positive but calculated amount is 0, process at least 1 item
                    } else {
                        calculatedAmount
                    }
                } else {
                    0
                }
                actualItemsToProcess = min(actualItemsToProcess, itemsToConsider)

                if (actualItemsToProcess <= 0) {
                    if (availableQuotaPoints <= 0) {
                        player.sendMessage(messages["quota_full_message"] ?: "")
                    } else {
                        player.sendMessage(messages["inventory_no_item"] ?: "")
                    }
                    return@thenAccept
                }

                val village = currentPlayerVillage[player.uniqueId] ?: run {
                    player.sendMessage(messages["village_info_not_set"] ?: "")
                    player.closeInventory()
                    return@thenAccept
                }

                // Remove items from inventory
                if (itemIdentifier.contains(":")) {
                    if (Bukkit.getPluginManager().getPlugin("ItemsAdder") != null) {
                        val customStackToRemove = dev.lone.itemsadder.api.CustomStack.getInstance(itemIdentifier)
                        if (customStackToRemove != null) {
                            val itemStackToRemove = customStackToRemove.itemStack.clone()
                            itemStackToRemove.amount = actualItemsToProcess
                            player.inventory.removeItem(itemStackToRemove)
                        } else {
                            logger.warning("ItemsAdder custom item '$itemIdentifier' not found. Cannot remove.")
                        }
                    } else {
                        logger.warning("ItemsAdder is not installed or not loaded. Cannot remove custom item '$itemIdentifier'.")
                    }
                } else {
                    player.inventory.removeItem(ItemStack(clickedItem.type, actualItemsToProcess))
                }

                // Update player quota and village points
                databaseManager.setPlayerQuota(player.uniqueId, currentQuotaPoints + pointsEarnedThisTransaction).thenRun {
                    databaseManager.getVillagePoints(village).thenAccept { currentVillagePoints ->
                        databaseManager.setVillagePoints(village, currentVillagePoints + pointsEarnedThisTransaction).thenRun {
                            databaseManager.getCumulativeVillagePoints(village).thenAccept { currentCumulativePoints ->
                                databaseManager.setCumulativeVillagePoints(village, currentCumulativePoints + pointsEarnedThisTransaction).thenRun {
                                    // Feedback to player
                                    val newQuota = currentQuotaPoints + pointsEarnedThisTransaction
                                    val remainingQuota = quotaLimit - newQuota
                                    player.sendMessage(messages["item_stored_feedback"]
                                        ?.replace("%item%", displayItemName)
                                        ?.replace("%amount%", actualItemsToProcess.toString())
                                        ?.replace("%points%", pointsEarnedThisTransaction.toString())
                                        ?.replace("%current_quota%", newQuota.toString())
                                        ?.replace("%remaining_quota%", remainingQuota.toString()) ?: "")

                                    if (newQuota >= quotaLimit) {
                                        if (rewards.isNotEmpty()) {
                                            val randomRewardKey = rewards.keys.random()
                                            val reward = rewards[randomRewardKey]
                                            if (reward != null) {
                                                Bukkit.getScheduler().runTask(this@BarnPlugin, Runnable {
                                                    reward.commands.forEach { cmd ->
                                                        val processedCmd = cmd.replace("{player}", player.name)
                                                        if (reward.commandType.equals("player", ignoreCase = true)) {
                                                            player.performCommand(processedCmd)
                                                        } else {
                                                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCmd)
                                                        }
                                                    }
                                                })
                                            }
                                        } else {
                                            logger.warning("No rewards configured in config.yml.")
                                        }
                                        player.sendMessage(messages["quota_full_reward"] ?: "")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openBarnGUI(player: Player, targetVillage: Village) {
        val inventory: Inventory = Bukkit.createInventory(null, guiLine * 9, guiTitle)

        val itemsPerPage = (guiLine - 1) * 9
        val totalPages = (itemPoints.size + itemsPerPage - 1) / itemsPerPage
        val currentPageNum = currentPage.getOrDefault(player.uniqueId, 0)

        // Populate inventory with items from itemPoints for the current page
        val startIndex = currentPageNum * itemsPerPage
        val endIndex = min(startIndex + itemsPerPage, itemPoints.size)

        itemPoints.keys.toList().subList(startIndex, endIndex).forEachIndexed { index, itemIdentifier ->
            val itemStack: ItemStack? = if (itemIdentifier.contains(":")) { // ItemsAdder custom item
                if (Bukkit.getPluginManager().getPlugin("ItemsAdder") != null) {
                    dev.lone.itemsadder.api.CustomStack.getInstance(itemIdentifier)?.itemStack
                } else {
                    logger.warning("ItemsAdder is not installed or not loaded. Custom item '$itemIdentifier' will not be displayed.")
                    null
                }
            } else { // Vanilla item
                try {
                    ItemStack(Material.valueOf(itemIdentifier.uppercase()), 1)
                } catch (e: IllegalArgumentException) {
                    logger.warning("Item '$itemIdentifier' in keep.yml is not a valid vanilla Material and will not be displayed.")
                    null
                }
            }

            if (itemStack != null) {
                val meta = itemStack.itemMeta
                meta?.lore = listOf(
                    messages["item_lore_right_click"] ?: "",
                    messages["item_lore_left_click"] ?: ""
                )
                itemStack.itemMeta = meta
                inventory.setItem(index, itemStack)
            }
        }

        // Add quota display item
        val playerQuota = databaseManager.getPlayerQuota(player.uniqueId).join()
        val villageQuota = databaseManager.getVillagePoints(targetVillage.name).join()

        val quotaDisplayLore = listOf(
            messages["gui_player_quota"]?.replace("%current_quota%", playerQuota.toString())?.replace("%quota_limit%", quotaLimit.toString()) ?: "",
            messages["gui_village_quota"]?.replace("%current_village_quota%", villageQuota.toString()) ?: ""
        )
        val quotaDisplayItem = createGuiItem(Material.PAPER, messages["gui_quota_title"] ?: "할당량 정보", quotaDisplayLore)
        inventory.setItem(guiLine * 9 - 5, quotaDisplayItem) // Center of the last row

        // Add pagination buttons
        if (currentPageNum > 0) {
            val prevButton = createGuiItem(Material.ARROW, messages["gui_prev_page"] ?: "이전 페이지", listOf())
            inventory.setItem(guiLine * 9 - 9, prevButton) // Bottom-left corner
        }
        if (currentPageNum < totalPages - 1) {
            val nextButton = createGuiItem(Material.ARROW, messages["gui_next_page"] ?: "다음 페이지", listOf())
            inventory.setItem(guiLine * 9 - 1, nextButton) // Bottom-right corner
        }

        player.openInventory(inventory)
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
                val villageNameInput = args[0].lowercase()
                val targetVillage = villages.values.firstOrNull { it.koreanName.lowercase() == villageNameInput || it.name.lowercase() == villageNameInput }

                if (targetVillage == null) {
                    sender.sendMessage(messages["invalid_village_name"] ?: "")
                    return true
                }

                currentPlayerVillage[sender.uniqueId] = targetVillage.name

                val currentQuota = databaseManager.getPlayerQuota(sender.uniqueId).join()
                if (currentQuota >= quotaLimit) {
                    sender.sendMessage(messages["quota_full_message"] ?: "오늘 할당량을 다 채웠습니다!")
                    return true
                }

                openBarnGUI(sender, targetVillage)
                return true
            }

            "헛간관리" -> {
                if (!sender.hasPermission("barnplugin.admin")) {
                    sender.sendMessage(messages["no_permission"] ?: "")
                    return true
                }

                if (args.isEmpty() || args[0] == "도움말") {
                    sender.sendMessage("${ChatColor.WHITE}/헛간관리 초기화 - 모든 데이터 초기화")
                    sender.sendMessage("${ChatColor.WHITE}/헛간관리 <마을이름>포인트설정 <숫자>")
                    sender.sendMessage("${ChatColor.WHITE}/헛간관리 <마을이름>포인트")
                    sender.sendMessage("${ChatColor.WHITE}/헛간관리 플레이어할당량 <플레이어>")
                    sender.sendMessage("${ChatColor.WHITE}/헛간관리 플레이어할당량설정 <플레이어> <숫자>")
                    return true
                }

                when (args[0]) {
                    "초기화" -> {
                        databaseManager.deleteAllData().thenRun {
                            databaseManager.initializeVillages(villages.keys)
                            sender.sendMessage(messages["data_reset_success"] ?: "")
                        }
                    }
                    else -> {
                        val villageName = args.getOrNull(0)?.replace("포인트설정", "")?.replace("포인트", "")
                        if (villageName != null && villages.containsKey(villageName)) {
                            when {
                                args[0].endsWith("포인트설정") -> {
                                    if (args.size < 2) {
                                        sender.sendMessage(messages["enter_number"] ?: "")
                                        return true
                                    }
                                    val points = args[1].toIntOrNull()
                                    if (points != null) {
                                        databaseManager.setVillagePoints(villageName, points).thenRun {
                                            sender.sendMessage(messages["village_points_set"]?.replace("%village%", villageName)?.replace("%points%", points.toString()) ?: "")
                                        }
                                    } else {
                                        sender.sendMessage(messages["invalid_number"] ?: "")
                                    }
                                }
                                args[0].endsWith("포인트") -> {
                                    databaseManager.getVillagePoints(villageName).thenAccept { points ->
                                        sender.sendMessage(messages["village_points_current"]?.replace("%village%", villageName)?.replace("%points%", points.toString()) ?: "")
                                    }
                                }
                            }
                        } else if (args[0] == "플레이어할당량") {
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
                        } else if (args[0] == "플레이어할당량설정") {
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
                        } else {
                            sender.sendMessage(messages["unknown_command"] ?: "")
                        }
                    }
                }
                return true
            }

            "소속확인" -> {
                if (sender !is Player) {
                    sender.sendMessage(messages["player_only_command"] ?: "")
                    return true
                }

                val playerVillageId = playerTowns[sender.uniqueId]
                val playerVillage = villages[playerVillageId]

                when {
                    sender.isOp -> {
                        sender.sendMessage(messages["op_village_status"] ?: "")
                    }
                    playerVillage != null -> {
                        sender.sendMessage(messages["village_status"]?.replace("%village%", playerVillage.koreanName) ?: "")
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
                            val villageKoreanName = villages[entry.key]?.koreanName ?: entry.key
                            sender.sendMessage(messages["ranking_entry"]
                                ?.replace("%rank%", (index + 1).toString())
                                ?.replace("%village%", villageKoreanName)
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
                        scheduleDailyReset()
                        sender.sendMessage(messages["barnadmin_reloaded"] ?: "")
                    }
                    else -> {
                        sender.sendMessage(messages["unknown_command"] ?: "")
                    }
                }
                return true
            }

            "헛간포인트" -> {
                if (sender !is Player) {
                    sender.sendMessage(messages["player_only_command"] ?: "")
                    return true
                }

                if (args.isEmpty()) {
                    sender.sendMessage(messages["barnpoint_help"] ?: "")
                    return true
                }

                when (args[0].lowercase()) {
                    "확인" -> {
                        if (args.size < 2) {
                            sender.sendMessage(messages["barnpoint_check_usage"] ?: "")
                            return true
                        }
                        val villageName = args[1]
                        if (!villages.containsKey(villageName)) {
                            sender.sendMessage(messages["invalid_village_name"] ?: "")
                            return true
                        }
                        val villageKoreanName = villages[villageName]?.koreanName ?: villageName
                        databaseManager.getCumulativeVillagePoints(villageName).thenAccept { points ->
                            sender.sendMessage(messages["barnpoint_current"]
                                ?.replace("%village%", villageKoreanName)
                                ?.replace("%points%", points.toString()) ?: "")
                        }
                    }
                    "인출" -> {
                        if (args.size < 4) {
                            sender.sendMessage(messages["barnpoint_withdraw_usage"] ?: "")
                            return true
                        }
                        val villageName = args[1]
                        val amount = args[2].toIntOrNull()
                        val reason = args.slice(3 until args.size).joinToString(" ")

                        if (!villages.containsKey(villageName)) {
                            sender.sendMessage(messages["invalid_village_name"] ?: "")
                            return true
                        }

                        val village = villages[villageName]
                        if (village?.mayor != sender.uniqueId) {
                            sender.sendMessage(messages["barnpoint_no_permission"] ?: "")
                            return true
                        }

                        if (amount == null || amount <= 0) {
                            sender.sendMessage(messages["invalid_number"] ?: "")
                            return true
                        }

                        databaseManager.getCumulativeVillagePoints(villageName).thenAccept { currentPoints ->
                            if (currentPoints < amount) {
                                sender.sendMessage(messages["barnpoint_insufficient_funds"] ?: "")
                                return@thenAccept
                            }

                            databaseManager.setCumulativeVillagePoints(villageName, currentPoints - amount).thenRun {
                                econ?.depositPlayer(sender, amount.toDouble())
                                databaseManager.logTransaction(sender.uniqueId, villageName, amount, reason)
                                val villageKoreanName = villages[villageName]?.koreanName ?: villageName
                                sender.sendMessage(messages["barnpoint_withdraw_success"]
                                    ?.replace("%village%", villageKoreanName)
                                    ?.replace("%amount%", amount.toString())
                                    ?.replace("%reason%", reason) ?: "")
                                Bukkit.broadcastMessage(messages["barnpoint_broadcast_withdraw"]
                                    ?.replace("%player%", sender.name)
                                    ?.replace("%village%", villageKoreanName)
                                    ?.replace("%amount%", amount.toString())
                                    ?.replace("%reason%", reason) ?: "")
                            }
                        }
                    }
                    else -> {
                        sender.sendMessage(messages["unknown_command"] ?: "")
                    }
                }
                return true
            }
            "마을관리" -> {
                if (!sender.hasPermission("barnplugin.admin")) {
                    sender.sendMessage(messages["no_permission"] ?: "")
                    return true
                }
                if (args.isEmpty()) {
                    sender.sendMessage(messages["townadmin_help"] ?: "")
                    return true
                }
                when (args[0].lowercase()) {
                    "이장임명" -> {
                        if (args.size < 3) {
                            sender.sendMessage(messages["townadmin_setmayor_usage"] ?: "")
                            return true
                        }
                        val villageName = args[1]
                        val playerName = args[2]
                        if (!villages.containsKey(villageName)) {
                            sender.sendMessage(messages["invalid_village_name"] ?: "")
                            return true
                        }
                        val player = Bukkit.getOfflinePlayer(playerName)
                        villages[villageName]?.mayor = player.uniqueId
                        saveTownConfig()
                        sender.sendMessage(messages["townadmin_setmayor_success"]?.replace("%village%", villageName)?.replace("%player%", playerName) ?: "")
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
                    return villages.values.map { it.koreanName }.filter { it.startsWith(args[0], true) }.toMutableList()
                }
            }

            "헛간관리" -> {
                if (sender.hasPermission("barnplugin.admin")) {
                    val subCommands = mutableListOf("초기화", "플레이어할당량", "플레이어할당량설정")
                    villages.keys.forEach {
                        subCommands.add("${it}포인트설정")
                        subCommands.add("${it}포인트")
                    }

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

            "헛간포인트" -> {
                if (sender !is Player) return mutableListOf()

                if (args.size == 1) {
                    return listOf("확인", "인출").filter { it.startsWith(args[0], true) }.toMutableList()
                } else if (args.size == 2) {
                    when (args[0].lowercase()) {
                        "확인", "인출" -> {
                            return villages.keys.filter { it.startsWith(args[1], true) }.toMutableList()
                        }
                    }
                }
            }
            "마을관리" -> {
                if (sender.hasPermission("barnplugin.admin")) {
                    if (args.size == 1) {
                        return listOf("이장임명").filter { it.startsWith(args[0], true) }.toMutableList()
                    } else if (args.size == 2) {
                        when (args[0].lowercase()) {
                            "이장임명" -> {
                                return villages.keys.filter { it.startsWith(args[1], true) }.toMutableList()
                            }
                        }
                    } else if (args.size == 3) {
                        when (args[0].lowercase()) {
                            "이장임명" -> {
                                return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], true) }.toMutableList()
                            }
                        }
                    }
                }
            }
        }
        return null
    }
}

class BarnPlaceholderExpansion(private val plugin: BarnPlugin) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "barn"
    override fun getAuthor(): String = plugin.description.authors.firstOrNull() ?: "Unknown"
    override fun getVersion(): String = plugin.description.version
    override fun onPlaceholderRequest(player: Player?, identifier: String): String? {
        if (player == null) return ""

        return when (identifier) {
            "quota" -> plugin.databaseManager.getPlayerQuota(player.uniqueId).join().toString()
            "town" -> {
                val townId = plugin.playerTowns[player.uniqueId]
                val village = plugin.villages[townId]
                when {
                    player.isOp -> plugin.messages["op_village_status"]
                    village != null -> plugin.messages["village_status"]?.replace("%village%", village.koreanName)
                    else -> plugin.messages["no_village_status"]
                }
            }
            else -> null
        }
    }
}