package io.github.pronze.sba.listener;

import io.github.pronze.sba.LastHit;
import io.github.pronze.sba.MessageKeys;
import io.github.pronze.sba.Permissions;
import io.github.pronze.sba.SBA;
import io.github.pronze.sba.config.SBAConfig;
import io.github.pronze.sba.data.DegradableItem;
import io.github.pronze.sba.game.Arena;
import io.github.pronze.sba.game.ArenaManager;
import io.github.pronze.sba.game.IArena;
import io.github.pronze.sba.lib.lang.LanguageService;
import io.github.pronze.sba.utils.Logger;
import io.github.pronze.sba.utils.SBAUtil;
import io.github.pronze.sba.utils.ShopUtil;
import io.github.pronze.sba.wrapper.SBAPlayerWrapper;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitRunnable;
import org.screamingsandals.bedwars.Main;
import org.screamingsandals.bedwars.api.game.GameStatus;
import org.screamingsandals.bedwars.game.GamePlayer;
import org.screamingsandals.lib.player.PlayerMapper;
import org.screamingsandals.lib.utils.annotations.Service;
import org.screamingsandals.lib.utils.annotations.methods.OnPostEnable;
import io.github.pronze.lib.pronzelib.scoreboards.Scoreboard;
import io.github.pronze.lib.pronzelib.scoreboards.ScoreboardManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Service
public class PlayerListener implements Listener {
    private final List<Material> allowedDropItems = new ArrayList<>();
    private final List<Material> generatorDropItems = new ArrayList<>();

    @OnPostEnable
    public void registerListener() {
        SBA.getInstance().registerListener(this);
        allowedDropItems.clear();
        generatorDropItems.clear();
        allowedDropItems.addAll(SBAUtil.parseMaterialFromConfig("allowed-item-drops"));
        generatorDropItems.addAll(SBAUtil.parseMaterialFromConfig("running-generator-drops"));
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        final var player = e.getEntity();

        if (!Main.getInstance().isPlayerPlayingAnyGame(player)) {
            return;
        }

        final var game = Main.getInstance().getGameOfPlayer(player);
        if (game.getStatus() != GameStatus.RUNNING) {
            return;
        }

        final var arena = ArenaManager
                .getInstance()
                .get(game.getName())
                .orElseThrow();

        final var itemArr = new ArrayList<ItemStack>();
        final var sword = Main.isLegacy() ? new ItemStack(Material.valueOf("WOOD_SWORD"))
                : new ItemStack(Material.WOODEN_SWORD);

        Arrays.stream(player
                .getInventory()
                .getContents()
                .clone())
                .filter(Objects::nonNull)
                .forEach(stack -> {
                    final String name = stack.getType().name();
                    var endStr = name.substring(name.contains("_") ? name.indexOf("_") + 1 : 0);
                    switch (endStr) {
                        case "SWORD":
                            itemArr.add(ShopUtil.downgradeItem(stack, DegradableItem.WEAPONARY));
                            break;
                        case "PICKAXE":
                            itemArr.add(ShopUtil.downgradeItem(stack, DegradableItem.PICKAXE));
                            break;
                        case "AXE":
                            itemArr.add(ShopUtil.downgradeItem(stack, DegradableItem.AXE));
                            break;
                        case "LEGGINGS":
                        case "BOOTS":
                        case "CHESTPLATE":
                        case "HELMET":
                        case "SHEARS":
                            itemArr.add(stack);
                            break;
                    }
                });

        itemArr.add(sword);
        arena.getPlayerData(player.getUniqueId()).ifPresent(playerData -> playerData.setInventory(itemArr));

        if (SBAConfig.getInstance().getBoolean("give-killer-resources", true)) {
//            final var killer = e.getEntity().getKiller();
            LastHit lastHit = LastHit.getLastHit(e.getEntity());
            if (lastHit != null && System.currentTimeMillis() - 6000 < lastHit.getWhen()
                    && Main.getInstance().isPlayerPlayingAnyGame(lastHit.getWho())
                    && lastHit.getWho().getGameMode() == GameMode.SURVIVAL) {
                Arrays.stream(player.getInventory().getContents())
                        .filter(Objects::nonNull)
                        .forEach(drop -> {
                            if (generatorDropItems.contains(drop.getType())) {
                                lastHit.getWho().sendMessage("+" + drop.getAmount() + " "
                                        + drop.getType().name().toLowerCase().replace("_", " "));
                                lastHit.getWho().getInventory().addItem(drop);
                            }
                        });
            }
        }

        final var gVictim = Main.getPlayerGameProfile(player);
        final var victimTeam = game.getTeamOfPlayer(player);

        if (SBAConfig.getInstance().getBoolean("respawn-cooldown.enabled", true) &&
                victimTeam.isAlive() && game.isPlayerInAnyTeam(player) &&
                game.getTeamOfPlayer(player).isTargetBlockExists()) {

            new BukkitRunnable() {
                final GamePlayer gamePlayer = gVictim;
                final Player player = gamePlayer.player;
                final String respawnTitle = LanguageService
                        .getInstance()
                        .get(MessageKeys.RESPAWN_COUNTDOWN_TITLE)
                        .toString();
                final String respawnSubtitle = LanguageService
                        .getInstance()
                        .get(MessageKeys.RESPAWN_COUNTDOWN_SUBTITLE)
                        .toString();
                final SBAPlayerWrapper wrappedPlayer = PlayerMapper.wrapPlayer(player).as(SBAPlayerWrapper.class);
                int livingTime = SBAConfig.getInstance().getInt("respawn-cooldown.time", 5);
                byte buffer = 2;

                @Override
                public void run() {
                    if (!Main.isPlayerInGame(player)) {
                        this.cancel();
                        return;
                    }

                    // send custom title because we disabled BedWars from showing any title
                    if (livingTime > 0) {
                        SBAUtil.sendTitle(wrappedPlayer, respawnTitle,
                                respawnSubtitle.replace("%time%", String.valueOf(livingTime)),
                                0, 20, 0);

                        LanguageService
                                .getInstance()
                                .get(MessageKeys.RESPAWN_COUNTDOWN_MESSAGE)
                                .replace("%time%", String.valueOf(livingTime))
                                .send(wrappedPlayer);
                        livingTime--;
                    }

                    if (livingTime == 0) {
                        if (gVictim.isSpectator && buffer > 0) {
                            buffer--;
                        } else {
                            LanguageService
                                    .getInstance()
                                    .get(MessageKeys.RESPAWNED_MESSAGE)
                                    .send(wrappedPlayer);

                            var respawnedTitle = LanguageService
                                    .getInstance()
                                    .get(MessageKeys.RESPAWNED_TITLE)
                                    .toString();

                            SBAUtil.sendTitle(wrappedPlayer, respawnedTitle, "",
                                    5, 40, 5);
                            ShopUtil.giveItemToPlayer(itemArr, player,
                                    Main.getInstance().getGameByName(game.getName()).getTeamOfPlayer(player)
                                            .getColor());
                            ShopUtil.applyTeamUpgrades(player, game);
                            this.cancel();
                        }
                    }
                }
            }.runTaskTimer(SBA.getPluginInstance(), 0L, 20L);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        if (event.getCurrentItem() == null)
            return;

        if (!(event.getWhoClicked() instanceof Player))
            return;

        final var player = (Player) event.getWhoClicked();

        if (!Main.isPlayerInGame(player))
            return;

        if (SBAConfig.getInstance().getBoolean("disable-armor-inventory-movement", true) &&
                event.getSlotType() == SlotType.ARMOR)
            event.setCancelled(true);

        final var topSlot = event.getView().getTopInventory();
        final var bottomSlot = event.getView().getBottomInventory();
        final var clickedInventory = event.getClickedInventory();
        final var typeName = event.getCurrentItem().getType().name();

        if (clickedInventory == null)
            return;

        if (clickedInventory.equals(bottomSlot)
                && SBAConfig.getInstance().getBoolean("block-players-putting-certain-items-onto-chest", true)
                && (topSlot.getType() == InventoryType.CHEST || topSlot.getType() == InventoryType.ENDER_CHEST)
                && bottomSlot.getType() == InventoryType.PLAYER) {
            if (event.getCurrentItem().getType() == Material.WOODEN_SWORD) {
                event.setResult(Event.Result.DENY);
                LanguageService
                        .getInstance()
                        .get(MessageKeys.CANNOT_PUT_ITEM_IN_CHEST)
                        .send(PlayerMapper.wrapPlayer(player));
            }
        }
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent evt) {
        final var player = evt.getPlayer();

        if (!Main.isPlayerInGame(player))
            return;
        if (!SBAConfig.getInstance().getBoolean("block-item-drops", true))
            return;

        final var ItemDrop = evt.getItemDrop().getItemStack();
        final var type = ItemDrop.getType();

        if (!allowedDropItems.contains(type) && !type.name().endsWith("WOOL")) {
            evt.setCancelled(true);
            player.getInventory().remove(ItemDrop);
        }
    }

    @EventHandler
    public void itemDamage(PlayerItemDamageEvent event) {
        if (!Main.isPlayerInGame(event.getPlayer())) {
            return;
        }

        if (SBAConfig.getInstance().node("disable-item-damage").getBoolean(true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent e) {
        final var player = e.getPlayer();
        final var uuid = player.getUniqueId();
        ScoreboardManager
                .getInstance()
                .fromCache(uuid)
                .ifPresent(Scoreboard::destroy);

        final var wrappedPlayer = PlayerMapper.wrapPlayer(player)
                .as(SBAPlayerWrapper.class);
        SBA.getInstance()
                .getPartyManager()
                .getPartyOf(wrappedPlayer)
                .ifPresent(party -> {
                    party.removePlayer(wrappedPlayer);
                    if (party.getMembers().size() == 1) {
                        SBA.getInstance()
                                .getPartyManager()
                                .disband(party.getUUID());
                        return;
                    }
                    if (party.getPartyLeader().equals(wrappedPlayer)) {
                        party
                                .getMembers()
                                .stream()
                                .findAny()
                                .ifPresentOrElse(member -> {
                                    party.setPartyLeader(member);
                                    LanguageService
                                            .getInstance()
                                            .get(MessageKeys.PARTY_MESSAGE_PROMOTED_LEADER)
                                            .replace("%player%", member.getName())
                                            .send(party.getMembers().toArray(new SBAPlayerWrapper[0]));

                                }, () -> SBA.getInstance().getPartyManager()
                                        .disband(party.getUUID()));
                    }
                    LanguageService
                            .getInstance()
                            .get(MessageKeys.PARTY_MESSAGE_OFFLINE_LEFT)
                            .replace("%player%", player.getName())
                            .send(party.getMembers().stream().filter(member -> !wrappedPlayer.equals(member))
                                    .toArray(SBAPlayerWrapper[]::new));
                });
        SBA.getInstance().getPlayerWrapperService().unregister(player);
    }
    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerDamageByPlayer(EntityDamageByEntityEvent event) {
        if(event.getEntity() instanceof Player) {
            Player damager = null;
            if(event.getDamager() instanceof Player) damager = (Player) event.getDamager();
            else if (event.getDamager() instanceof Projectile && ((Projectile) event.getDamager()).getShooter() instanceof Player) damager = ((Player)((Projectile) event.getDamager()).getShooter());
            if(damager != null) LastHit.setLastHit((Player) event.getEntity(), damager, LastHit.HitType.MELEE);
        }
    }
    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        final var entity = event.getEntity();
        if (entity instanceof Player) {
            final var player = (Player) entity;

            if (Main.isPlayerInGame(player) && event.getCause() != EntityDamageEvent.DamageCause.FALL) {
                final var game = Main.getInstance().getGameOfPlayer(player);
                ArenaManager
                        .getInstance()
                        .get(game.getName()).ifPresent(iArena -> {
                            if(iArena.isPlayerHidden(player)) {
                                iArena.removeHiddenPlayer(player);
                                player.sendMessage(ChatColor.RED + "You took damage and lost your invisibility");
                            }
                        });

                if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
                    event.setDamage(SBAConfig.getInstance().node("explosion-damage").getDouble(1.0D));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        final var item = event.getItem();
        final var player = event.getPlayer();

        if (!Main.isPlayerInGame(player))
            return;

        if (item.getType() == Material.POTION) {
            final var potionMeta = (PotionMeta) item.getItemMeta();
            boolean isInvis = false;
            if (potionMeta.getBasePotionData().getType() == PotionType.INVISIBILITY) {
                isInvis = true;
            } else {
                if (potionMeta.hasCustomEffects()) {
                    isInvis = potionMeta
                            .getCustomEffects()
                            .stream()
                            .anyMatch(potionEffect -> potionEffect.getType().getName()
                                    .equalsIgnoreCase(PotionEffectType.INVISIBILITY.getName()));
                }
            }

            if (isInvis) {
                Logger.info("Player is now invis and vanished");
                final var playerGame = Main.getInstance().getGameOfPlayer(player);
                ArenaManager
                        .getInstance()
                        .get(playerGame.getName())
                        .ifPresent(arena -> arena.addHiddenPlayer(player));
            }

            event.setReplacement(new ItemStack(Material.AIR));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onEquipt(PlayerItemHeldEvent event) {
        final var player = event.getPlayer();

        if (!Main.isPlayerInGame(player))
            return;

        final var playerGame = Main.getInstance().getGameOfPlayer(player);
        ArenaManager
                .getInstance()
                .get(playerGame.getName())
                .ifPresent(arena -> {
                    if (arena.isPlayerHidden(player)) {
                        arena.updateHiddenPlayer(player);
                    }
                });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent e) {
        final var player = e.getPlayer();
        SBA.getInstance().getPlayerWrapperService().register(player);

        if (player.hasPermission(Permissions.UPGRADE.getKey())) {
            if (SBA.getInstance().isPendingUpgrade()) {
                Bukkit.getScheduler().runTaskLater(SBA.getPluginInstance(), () -> {
                    player.sendMessage(
                            "§6[SBA]: Plugin has detected a version change, do you want to upgrade internal files?");
                    player.sendMessage("Type /sba upgrade to upgrade file");
                    player.sendMessage("§cif you want to cancel the upgrade files do /sba cancel");
                }, 40L);
            }
        }
    }

}
