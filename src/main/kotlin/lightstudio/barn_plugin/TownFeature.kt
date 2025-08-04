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
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.*
import org.bukkit.Location
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.potion.PotionEffectType

class TownFeature(private val plugin: BarnPlugin) : Listener, CommandExecutor, TabCompleter {

    private val townConfigFile: File = File(plugin.dataFolder, "town.yml")
    private val townConfig: FileConfiguration = YamlConfiguration.loadConfiguration(townConfigFile)
    private val playerPreviousLocation = mutableMapOf<UUID, Location>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        val townCommand = plugin.getCommand("town")
        townCommand?.setExecutor(this)
        townCommand?.tabCompleter = this
        saveDefaultTownConfig()
    }

    private fun saveDefaultTownConfig() {
        if (!townConfigFile.exists()) {
            plugin.saveResource("town.yml", false)
        }
    }

    private fun savePlayerTowns() {
        townConfig.set("players", null) // Clear existing player data
        plugin.playerTowns.forEach { (uuid, town) ->
            townConfig.set("players.$uuid", town)
        }
        townConfig.save(townConfigFile)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("§c/town <비율|초기화|전체초기화|구매|바로구매|구매열기|set>")
            return true
        }

        when (args[0].lowercase()) {
            "비율" -> showTownRatios(sender)
            "초기화" -> resetPlayerTown(sender, if (args.size > 1) args[1] else null)
            "전체초기화" -> resetAllTowns(sender)
            "구매" -> if (sender is Player) openRealEstateGUI(sender) else sender.sendMessage("플레이어만 사용 가능합니다.")
            "바로구매" -> if (sender is Player) purchaseRealEstateDirectly(sender) else sender.sendMessage("플레이어만 사용 가능합니다.")
            "구매열기" -> if (sender is Player) openPurchaseGUIIgnoringPermissions(sender) else sender.sendMessage("플레이어만 사용 가능합니다.")
            "set" -> setPlayerTown(sender, if (args.size > 1) args[1] else null, if (args.size > 2) args[2] else null)
            else -> sender.sendMessage("§c알 수 없는 명령어입니다.")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
        val completions = mutableListOf<String>()
        if (args.size == 1) {
            completions.addAll(listOf("비율", "초기화", "전체초기화", "구매", "바로구매", "구매열기", "set").filter { it.startsWith(args[0], ignoreCase = true) })
        } else if (args.size == 2) {
            when (args[0].lowercase()) {
                "초기화", "set" -> completions.addAll(Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) })
            }
        } else if (args.size == 3 && args[0].equals("set", ignoreCase = true)) {
            completions.addAll(listOf("모닥리", "화덕리").filter { it.startsWith(args[2], ignoreCase = true) })
        }
        return completions
    }

    private fun showTownRatios(sender: CommandSender) {
        val modakriPopulation = plugin.playerTowns.values.count { it == "modak-ri" }
        val hwadeokriPopulation = plugin.playerTowns.values.count { it == "hwadeok-ri" }
        sender.sendMessage("§6[마을 현황]")
        sender.sendMessage("§e모닥리: ${modakriPopulation}명")
        sender.sendMessage("§c화덕리: ${hwadeokriPopulation}명")
    }

    private fun resetPlayerTown(sender: CommandSender, targetName: String?) {
        if (!sender.hasPermission("barn.admin")) {
            sender.sendMessage("§c권한이 없습니다!")
            return
        }
        if (targetName == null) {
            sender.sendMessage("§c초기화할 플레이어의 이름을 입력해주세요.")
            return
        }
        val target = Bukkit.getOfflinePlayer(targetName)
        if (plugin.playerTowns.containsKey(target.uniqueId)) {
            plugin.playerTowns.remove(target.uniqueId)
            savePlayerTowns()
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user ${target.uniqueId} parent set default")
            sender.sendMessage("§a${targetName}님의 마을 배정을 초기화했습니다.")
        } else {
            sender.sendMessage("§c${targetName}님은 마을 배정이 되어있지 않습니다.")
        }
    }

    private fun resetAllTowns(sender: CommandSender) {
        if (!sender.hasPermission("barn.admin")) {
            sender.sendMessage("§c권한이 없습니다!")
            return
        }
        plugin.playerTowns.keys.forEach { uuid ->
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user $uuid parent set default")
        }
        plugin.playerTowns.clear()
        savePlayerTowns()
        sender.sendMessage("§c모든 부동산 데이터를 초기화했습니다!")
    }

    private fun setPlayerTown(sender: CommandSender, targetName: String?, townName: String?) {
        if (!sender.hasPermission("barn.admin")) {
            sender.sendMessage("§c권한이 없습니다!")
            return
        }
        if (targetName == null || townName == null) {
            sender.sendMessage("§c/town set <player> <town>")
            return
        }
        val target = Bukkit.getOfflinePlayer(targetName)
        val townId = when (townName.lowercase()) {
            "모닥리" -> "modak-ri"
            "화덕리" -> "hwadeok-ri"
            else -> {
                sender.sendMessage("§c알 수 없는 마을 이름입니다.")
                return
            }
        }
        plugin.playerTowns[target.uniqueId] = townId
        savePlayerTowns()
        val assignedVillage = plugin.villages[townId]
        if (assignedVillage != null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user ${target.uniqueId} parent set ${assignedVillage.luckPermsGroup}")
            sender.sendMessage("§a${targetName}님을 ${assignedVillage.koreanName} 마을로 설정했습니다.")
        } else {
            sender.sendMessage("§c내부 오류: 마을 정보를 찾을 수 없습니다.")
        }
    }

    private fun openRealEstateGUI(player: Player) {
        if (plugin.playerTowns.containsKey(player.uniqueId)) {
            player.sendMessage("§c이미 마을 배정을 받았습니다!")
            return
        }
        val gui = Bukkit.createInventory(null, 9, "부동산")
        gui.setItem(4, ItemStack(Material.EMERALD).apply { itemMeta = itemMeta?.apply { setDisplayName("§a구매 버튼") } })
        gui.setItem(2, ItemStack(Material.ENDER_EYE).apply { itemMeta = itemMeta?.apply { setDisplayName("§6모닥리 미리보기") } })
        gui.setItem(6, ItemStack(Material.ENDER_EYE).apply { itemMeta = itemMeta?.apply { setDisplayName("§6화덕리 미리보기") } })
        player.openInventory(gui)
    }

    private fun purchaseRealEstateDirectly(player: Player) {
        if (!player.isOp) {
            player.sendMessage("§c권한이 없습니다!")
            return
        }
        purchaseRealEstate(player)
    }

    private fun openPurchaseGUIIgnoringPermissions(player: Player) {
        if (!player.isOp) {
            player.sendMessage("§c권한이 없습니다!")
            return
        }
        openRealEstateGUI(player)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.title != "부동산") return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return

        when (event.rawSlot) {
            2 -> {
                previewTown(player, "모닥리")
                player.closeInventory()
            }
            4 -> {
                player.closeInventory()
                if (plugin.playerTowns.containsKey(player.uniqueId)) {
                    player.sendMessage("§c이미 마을 배정을 받았습니다!")
                    return
                }
                val landPrice = plugin.config.getDouble("land_price")
                if (landPrice > 0) {
                    if (BarnPlugin.econ?.has(player, landPrice) == false) {
                        player.sendMessage("§c잔액이 부족합니다! (${landPrice}원 필요)")
                        return
                    }
                    BarnPlugin.econ?.withdrawPlayer(player, landPrice)
                    player.sendMessage("§a${landPrice}원이 차감되었습니다.")
                } else {
                    player.sendMessage("§a오늘은 땅값이 무료입니다!")
                }
                purchaseRealEstate(player)
                val warpLocation = plugin.config.getString("warp_location_after_purchase", "중앙역2")
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "warp ${warpLocation} ${player.name}")
            }
            6 -> {
                previewTown(player, "화덕리")
                player.closeInventory()
            }
        }
    }

    private fun purchaseRealEstate(player: Player) {
        val modakriPopulation = plugin.playerTowns.values.count { it == "modak-ri" }
        val hwadeokriPopulation = plugin.playerTowns.values.count { it == "hwadeok-ri" }
        val modakriQuota = townConfig.getInt("quotas.modak-ri", 30)
        val hwadeokriQuota = townConfig.getInt("quotas.hwadeok-ri", 30)
        var nextAssignment = townConfig.getString("next-assignment", "modak-ri")

        val assignedTown = if (modakriPopulation < modakriQuota && hwadeokriPopulation < hwadeokriQuota) {
            if (nextAssignment == "modak-ri") {
                townConfig.set("next-assignment", "hwadeok-ri")
                "modak-ri"
            } else {
                townConfig.set("next-assignment", "modak-ri")
                "hwadeok-ri"
            }
        } else if (modakriPopulation < modakriQuota) {
            "modak-ri"
        } else if (hwadeokriPopulation < hwadeokriQuota) {
            "hwadeok-ri"
        } else {
            if (nextAssignment == "modak-ri") {
                townConfig.set("next-assignment", "hwadeok-ri")
                "modak-ri"
            } else {
                townConfig.set("next-assignment", "modak-ri")
                "hwadeok-ri"
            }
        }

        plugin.playerTowns[player.uniqueId] = assignedTown
        savePlayerTowns()

        val assignedTownKorean = plugin.villages[assignedTown]?.koreanName ?: assignedTown
        player.sendTitle("§a축하합니다!", "§f당신은 ${assignedTownKorean} 마을에 배정되었습니다!", 10, 70, 20)
        player.playSound(player.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f)
        val luckPermsGroup = plugin.villages[assignedTown]?.luckPermsGroup ?: assignedTown
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user ${player.uniqueId} parent set $luckPermsGroup")

        val landDeed = ItemStack(Material.PAPER).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§6[부동산] §f땅 문서")
                lore = listOf("§f", "§f우클릭 시 해당 구역을 구매합니다.", "§f", "§7- 하꼬의숲 -")
                addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3, true)
                addItemFlags(ItemFlag.HIDE_ENCHANTS)
                setCustomModelData(200381)
            }
        }
        player.inventory.addItem(landDeed)
    }

    private fun previewTown(player: Player, townName: String) {
        val location = when (townName) {
            "모닥리" -> org.bukkit.Location(Bukkit.getWorld("town_world"), 547.0, 79.0, 251.0)
            "화덕리" -> org.bukkit.Location(Bukkit.getWorld("town_world"), -282.0, 86.0, -113.0)
            else -> return
        }
        playerPreviousLocation[player.uniqueId] = player.location
        player.teleport(location)
        player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY, 99999 * 20, 1, false, false))
        player.playSound(player.location, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)
        player.sendTitle("", "§8<Shift> 누를 시 돌아가기", 10, 100, 10)
        player.sendMessage(" §6[!] §fShift 누를 시 이전 장소로 이동 합니다.")
    }

    @EventHandler
    fun onPlayerToggleSneak(event: PlayerToggleSneakEvent) {
        val player = event.player
        if (event.isSneaking) { // Only trigger on pressing shift
            val previousLocation = playerPreviousLocation[player.uniqueId]
            if (previousLocation != null) {
                player.teleport(previousLocation)
                player.removePotionEffect(PotionEffectType.INVISIBILITY)
                playerPreviousLocation.remove(player.uniqueId)
                player.sendMessage("§a이전 위치로 돌아왔습니다.")
            }
        }
    }
}