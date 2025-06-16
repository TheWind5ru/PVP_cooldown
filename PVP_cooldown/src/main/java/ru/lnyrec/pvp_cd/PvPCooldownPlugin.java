// PvPCooldownPlugin.java
package ru.lnyrec.pvp_cd; // Важно использовать свой уникальный пакет

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PvPCooldownPlugin extends JavaPlugin implements Listener {

    // --- Переменные состояния плагина ---
    private final HashMap<UUID, Long> cooldowns = new HashMap<>(); // Хранит UUID игрока и время окончания кулдауна (в миллисекундах)
    private final HashMap<UUID, BossBar> bossBars = new HashMap<>(); // Хранит UUID игрока и его BossBar для отображения кулдауна

    // --- Настраиваемые параметры из config.yml ---
    private long cooldownDuration; // Длительность кулдауна в миллисекундах
    private Set<String> commandWhitelist; // Список разрешенных команд во время кулдауна

    // Параметры BossBar
    private boolean bossBarEnabled;
    private String bossBarTitleFormat;
    private BarColor bossBarColor;
    private BarStyle bossBarStyle;

    // Параметры блокировки команд
    private boolean commandBlockingEnabled;
    private String blockedCommandMessage;

    // Параметры предотвращения выхода из игры (Combat Log)
    private boolean logoutPreventionEnabled;
    private String logoutPunishment; // "NONE", "KILL", "DAMAGE"
    private double logoutDamageAmount;
    private String logoutMessage;
    private boolean broadcastLogoutMessageEnabled; // Новая опция: включить/выключить сообщение в общий чат
    private String broadcastLogoutMessage; // Новая опция: текст сообщения в общий чат

    // Сообщения игрокам
    private String cooldownStartMessage;
    private String cooldownEndMessage;

    // --- Методы жизненного цикла плагина ---

    @Override
    public void onEnable() {
        // Сохраняем конфигурацию по умолчанию, если она еще не существует
        saveDefaultConfig();
        // Загружаем все параметры из config.yml
        loadConfig();

        // Регистрируем слушателей событий
        Bukkit.getPluginManager().registerEvents(this, this);

        // Запускаем задачу для обновления BossBar каждую секунду
        new BukkitRunnable() {
            @Override
            public void run() {
                if (bossBarEnabled) {
                    updateBossBars();
                }
                // Проверяем и завершаем кулдауны для игроков, которые могли выйти из игры
                // Или для тех, кто остался в кулдауне, но их BossBar уже пропал
                new HashSet<>(cooldowns.keySet()).forEach(uuid -> {
                    if (Bukkit.getPlayer(uuid) == null && bossBars.get(uuid) == null) {
                        cooldowns.remove(uuid);
                    }
                });
            }
        }.runTaskTimer(this, 0L, 20L); // 0L задержка, 20L тиков (1 секунда)
    }

    @Override
    public void onDisable() {
        // При выключении плагина убираем все активные BossBar'ы
        bossBars.values().forEach(BossBar::removeAll);
        bossBars.clear(); // Очищаем мапу после удаления
        cooldowns.clear(); // Очищаем все кулдауны
    }

    // --- Методы работы с конфигурацией ---

    private void loadConfig() {
        // Загружаем длительность кулдауна и конвертируем в миллисекунды
        cooldownDuration = getConfig().getLong("cooldown-duration-seconds", 10) * 1000L;

        // Загружаем параметры BossBar
        bossBarEnabled = getConfig().getBoolean("bossbar.enabled", true);
        bossBarTitleFormat = getConfig().getString("bossbar.title-format", "&cPvP Cooldown: &f%seconds%s remaining");
        try {
            bossBarColor = BarColor.valueOf(getConfig().getString("bossbar.color", "RED").toUpperCase());
        } catch (IllegalArgumentException e) {
            getLogger().warning("Неверный цвет BossBar в конфиге. Используется RED. " + e.getMessage());
            bossBarColor = BarColor.RED;
        }
        try {
            bossBarStyle = BarStyle.valueOf(getConfig().getString("bossbar.style", "SOLID").toUpperCase());
        } catch (IllegalArgumentException e) {
            getLogger().warning("Неверный стиль BossBar в конфиге. Используется SOLID. " + e.getMessage());
            bossBarStyle = BarStyle.SOLID;
        }

        // Загружаем параметры блокировки команд
        commandBlockingEnabled = getConfig().getBoolean("command-blocking.enabled", true);
        blockedCommandMessage = getConfig().getString("command-blocking.blocked-message", "&cВы не можете использовать команды во время PvP кулдауна!");
        commandWhitelist = new HashSet<>(getConfig().getStringList("command-blocking.whitelist"));

        // Загружаем параметры предотвращения выхода из игры
        logoutPreventionEnabled = getConfig().getBoolean("logout-prevention.enabled", true);
        logoutPunishment = getConfig().getString("logout-prevention.punishment", "KILL").toUpperCase();
        logoutDamageAmount = getConfig().getDouble("logout-prevention.damage-amount", 10.0);
        logoutMessage = getConfig().getString("logout-prevention.message", "&cВы не можете выйти из игры во время PvP кулдауна! Вы были наказаны.");
        broadcastLogoutMessageEnabled = getConfig().getBoolean("logout-prevention.broadcast-message.enabled", false); // Новая опция
        broadcastLogoutMessage = getConfig().getString("logout-prevention.broadcast-message.message", "&e%nickname% покинул игру во время боя!"); // Новая опция

        // Загружаем сообщения
        cooldownStartMessage = getConfig().getString("cooldown-start-message", "&cВы находитесь в PvP кулдауне!");
        cooldownEndMessage = getConfig().getString("cooldown-end-message", "&aВаш PvP кулдаун завершился.");
    }

    // --- Методы управления кулдауном ---

    /**
     * Проверяет, находится ли игрок в кулдауне.
     * @param player Игрок для проверки.
     * @return true, если игрок в кулдауне, иначе false.
     */
    private boolean isInCooldown(Player player) {
        return cooldowns.containsKey(player.getUniqueId()) && cooldowns.get(player.getUniqueId()) > System.currentTimeMillis();
    }

    /**
     * Запускает или обновляет кулдаун для игрока.
     * @param player Игрок, для которого запускается/обновляется кулдаун.
     */
    private void startCooldown(Player player) {
        // Игроки с правом pvp_cd.bypass_cd не попадают в кулдаун
        if (player.hasPermission("pvp_cd.bypass_cd")) {
            return;
        }

        long endTime = System.currentTimeMillis() + cooldownDuration;
        boolean wasInCooldown = isInCooldown(player);

        cooldowns.put(player.getUniqueId(), endTime);

        if (!wasInCooldown) {
            player.sendMessage(color(cooldownStartMessage));
        }

        if (bossBarEnabled) {
            BossBar bossBar = bossBars.get(player.getUniqueId());
            if (bossBar == null) {
                // Создаем новый BossBar, если его еще нет
                bossBar = Bukkit.createBossBar(color(bossBarTitleFormat.replace("%seconds%", String.valueOf(cooldownDuration / 1000))), bossBarColor, bossBarStyle);
                bossBar.addPlayer(player);
                bossBars.put(player.getUniqueId(), bossBar);
            }
            // Всегда обновляем BossBar, так как кулдаун мог сброситься или измениться
            updateSingleBossBar(player, bossBar);
        }
    }

    /**
     * Завершает кулдаун для игрока.
     * @param player Игрок, для которого завершается кулдаун.
     */
    private void endCooldown(Player player) {
        // Только если игрок действительно был в кулдауне
        if (cooldowns.remove(player.getUniqueId()) != null) {
            player.sendMessage(color(cooldownEndMessage));
        }

        BossBar bossBar = bossBars.remove(player.getUniqueId());
        if (bossBar != null) {
            bossBar.removeAll(); // Удаляем BossBar
        }
    }

    /**
     * Обновляет состояние всех активных BossBar'ов.
     */
    private void updateBossBars() {
        // Используем копию keySet, чтобы избежать ConcurrentModificationException
        for (UUID uuid : new HashSet<>(cooldowns.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                // Игрок вышел из игры, удаляем его из кулдаунов и BossBar'ов
                BossBar bossBar = bossBars.remove(uuid);
                if (bossBar != null) {
                    bossBar.removeAll();
                }
                cooldowns.remove(uuid);
                continue;
            }
            updateSingleBossBar(player, bossBars.get(uuid));
        }
    }

    /**
     * Обновляет один BossBar для конкретного игрока.
     * @param player Игрок.
     * @param bossBar BossBar игрока.
     */
    private void updateSingleBossBar(Player player, BossBar bossBar) {
        if (bossBar == null || !bossBars.containsKey(player.getUniqueId())) {
            // Если BossBar уже нет или его нет в мапе, значит кулдаун для него закончился
            // или его вообще не было, ничего не делаем.
            return;
        }

        long remainingTime = cooldowns.get(player.getUniqueId()) - System.currentTimeMillis();
        if (remainingTime <= 0) {
            endCooldown(player);
        } else {
            double progress = (double) remainingTime / cooldownDuration;
            bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress))); // Ограничиваем прогресс от 0.0 до 1.0
            // +1 для округления секунд вверх, чтобы не показывать 0с, пока есть время
            bossBar.setTitle(color(bossBarTitleFormat.replace("%seconds%", String.valueOf(remainingTime / 1000 + 1))));
        }
    }


    // --- Обработчики событий ---

    /**
     * Обрабатывает событие нанесения урона сущности другой сущностью.
     */
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Проверяем, что и нападающий, и жертва являются игроками
        if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof Player)) {
            return; // Если это не PvP между игроками, выходим
        }

        Player damager = (Player) event.getDamager();
        Player damaged = (Player) event.getEntity();

        // Запускаем кулдаун для обоих игроков, если у них нет права обхода
        startCooldown(damager);
        startCooldown(damaged);
    }

    /**
     * Обрабатывает событие выполнения команды игроком.
     */
    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!commandBlockingEnabled) {
            return;
        }

        Player player = event.getPlayer();
        // Игроки с правом pvp_cd.bypass_cd могут использовать команды
        if (player.hasPermission("pvp_cd.bypass_cd")) {
            return;
        }

        if (isInCooldown(player)) {
            // Извлекаем базовую команду (например, из "/warp home" получаем "warp")
            String command = event.getMessage().substring(1).split(" ")[0].toLowerCase();
            if (!commandWhitelist.contains(command)) {
                event.setCancelled(true); // Отменяем выполнение команды
                player.sendMessage(color(blockedCommandMessage));
            }
        }
    }

    /**
     * Обрабатывает событие выхода игрока из игры.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!logoutPreventionEnabled) {
            return;
        }

        Player player = event.getPlayer();
        // Игроки с правом pvp_cd.bypass_cd не наказываются при выходе
        if (player.hasPermission("pvp_cd.bypass_cd")) {
            // Если игрок вышел с правом, просто завершаем его кулдаун без наказания
            endCooldown(player);
            return;
        }

        if (isInCooldown(player)) {
            player.sendMessage(color(logoutMessage)); // Отправляем сообщение перед наказанием

            switch (logoutPunishment) {
                case "KILL":
                    player.setHealth(0.0); // Убивает игрока
                    break;
                case "DAMAGE":
                    if (player.getHealth() - logoutDamageAmount <= 0) {
                        player.setHealth(0.0); // Убивает, если урон смертельный
                    } else {
                        player.setHealth(player.getHealth() - logoutDamageAmount);
                    }
                    break;
                case "NONE":
                default:
                    // Ничего не делаем
                    break;
            }
            // Завершаем кулдаун немедленно при выходе, если было наказание
            endCooldown(player);

            // Отправляем сообщение в общий чат, если включено
            if (broadcastLogoutMessageEnabled) {
                String message = broadcastLogoutMessage.replace("%nickname%", player.getName());
                Bukkit.broadcastMessage(color(message));
            }
        }
    }

    // --- Вспомогательные методы ---

    /**
     * Преобразует сообщения, заменяя '&' на '§' для поддержки цветовых кодов Minecraft.
     * @param message Исходное сообщение.
     * @return Сообщение с обработанными цветовыми кодами.
     */
    private String color(String message) {
        return message.replace("&", "§");
    }
}