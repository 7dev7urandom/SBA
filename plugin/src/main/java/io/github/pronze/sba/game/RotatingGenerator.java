package io.github.pronze.sba.game;

import io.github.pronze.sba.utils.ShopUtil;
import lombok.Getter;
import lombok.Setter;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.screamingsandals.bedwars.Main;
import org.screamingsandals.bedwars.api.events.BedwarsResourceSpawnEvent;
import org.screamingsandals.bedwars.api.game.Game;
import org.screamingsandals.bedwars.game.ItemSpawner;
import io.github.pronze.sba.MessageKeys;
import io.github.pronze.sba.SBA;
import io.github.pronze.sba.config.SBAConfig;
import io.github.pronze.sba.lib.lang.LanguageService;
import io.github.pronze.sba.utils.Logger;
import io.github.pronze.sba.utils.SBAUtil;
import org.screamingsandals.lib.hologram.Hologram;
import org.screamingsandals.lib.hologram.HologramManager;
import org.screamingsandals.lib.item.builder.ItemFactory;
import org.screamingsandals.lib.player.PlayerMapper;
import org.screamingsandals.lib.tasker.TaskerTime;
import org.screamingsandals.lib.utils.Pair;
import org.screamingsandals.lib.utils.reflect.Reflect;
import org.screamingsandals.lib.world.LocationMapper;

import java.util.ArrayList;
import java.util.List;

public class RotatingGenerator implements IRotatingGenerator {
    private Location location;
    private List<String> lines;
    private int time;
    @Getter
    @Setter
    private int tierLevel = 1;
    @Getter
    private final ItemSpawner itemSpawner;
    @Getter
    private final ItemStack stack;

    private BukkitTask hologramTask;
    private Hologram hologram;
    private List<Item> spawnedItems;
    private Game game;

    @SuppressWarnings("unchecked")
    public RotatingGenerator(ItemSpawner itemSpawner, ItemStack stack, Location location, Game game) {
        this.game = game;
        this.itemSpawner = itemSpawner;
        this.stack = stack;
        this.location = location;
        this.time = getTimeFromTypeAndTier(itemSpawner.getItemSpawnerType().getMaterial(), tierLevel);
        this.lines = LanguageService
                .getInstance()
                .get(MessageKeys.ROTATING_GENERATOR_FORMAT)
                .toStringList();
        this.spawnedItems = (List<Item>) Reflect.getField(itemSpawner, "spawnedItems");
    }

    @Override
    public void spawn(@NotNull List<Player> viewers) {
        Logger.trace("RotatingGenerator::spawn ({},{})", this,viewers);

        final var holoHeight = SBAConfig.getInstance()
                .node("floating-generator", "height").getDouble(2.0);

        hologram = HologramManager.hologram(LocationMapper.wrapLocation(location.clone().add(0, holoHeight, 0)));
        hologram.item(ItemFactory.build(stack).orElseThrow())
                .itemPosition(Hologram.ItemPosition.BELOW)
                .rotationMode(Hologram.RotationMode.Y)
                .rotationTime(Pair.of(1, TaskerTime.TICKS));

        hologram.show();
        viewers.forEach(player -> hologram.addViewer(PlayerMapper.wrapPlayer(player)));
        scheduleTasks();
    }

    @Override
    public void addViewer(@NotNull Player player) {
        hologram.addViewer(PlayerMapper.wrapPlayer(player));
    }

    @Override
    public void removeViewer(@NotNull Player player) {
        hologram.removeViewer(PlayerMapper.wrapPlayer(player));
    }

    @SuppressWarnings("unchecked")
    protected void scheduleTasks() {
        // cancel tasks if pending
        SBAUtil.cancelTask(hologramTask);

        hologramTask = new BukkitRunnable() {
            @Override
            public void run() {
                hologram.show();
                //Logger.trace("RotatingGenerator::hologramTask ({},{})", this,hologramTask);

                boolean full = itemSpawner.getMaxSpawnedResources() <= spawnedItems.size();
                time--;
//                Logger.info("Rotating time " + time + ", );

                final var format = LanguageService
                        .getInstance()
                        .get(MessageKeys.ROTATING_GENERATOR_FORMAT)
                        .toStringList();

                final var newLines = new ArrayList<String>();
                final var matName = itemSpawner.getItemSpawnerType().getMaterial() ==
                        Material.EMERALD ? "§a" + LanguageService
                        .getInstance()
                        .get(MessageKeys.EMERALD)
                        .toString() :
                        "§b" + LanguageService
                                .getInstance()
                                .get(MessageKeys.DIAMOND)
                                .toString();

                for (String line : format) {
                    newLines.add(line
                            .replace("%tier%", ShopUtil.romanNumerals.get(tierLevel))
                            .replace("%material%", matName + "§6")
                            .replace("%seconds%", String.valueOf(time)));
                }

                update(newLines);

                if (time < 0) {
                    time = getTimeFromTypeAndTier(itemSpawner.getItemSpawnerType().getMaterial(), tierLevel) - 1; //itemSpawner.getItemSpawnerType().getInterval();
                    // Spawn item
                    BedwarsSBAResourceSpawnEvent resourceSpawnEvent = new BedwarsSBAResourceSpawnEvent(game, itemSpawner,
                            itemSpawner.type.getStack(1));
                    Main.getInstance().getServer().getPluginManager().callEvent(resourceSpawnEvent);

                    if (resourceSpawnEvent.isCancelled()) {
                        return;
                    }

                    ItemStack resource = resourceSpawnEvent.getResource();

                    resource.setAmount(itemSpawner.nextMaxSpawn(resource.getAmount(), null));

                    if (resource.getAmount() > 0) {
                        Location loc = itemSpawner.getLocation().clone().add(0, 0.05, 0);
                        Item item = loc.getWorld().dropItem(loc, resource);
                        double spread = itemSpawner.type.getSpread();
                        if (spread != 1.0) {
                            item.setVelocity(item.getVelocity().multiply(spread));
                        }
                        item.setPickupDelay(0);
                        itemSpawner.add(item);
                    }

                }
            }
        }.runTaskTimer(SBA.getPluginInstance(), 0L, 20L);
    }

    @Override
    public void update(@NotNull List<String> newLines) {
        if (newLines.equals(lines)) {
            return;
        }
        for (int i = 0; i < newLines.size(); i++) {
            hologram.replaceLine(i, Component.text(newLines.get(i)));
        }
        this.lines = new ArrayList<>(newLines);
    }

    public void destroy() {
        Logger.trace("RotatingGenerator::destroy ({})", this);

        SBAUtil.cancelTask(hologramTask);
        if (hologram != null) {
            hologram.destroy();
            hologram = null;
        }
    }

    @Override
    public void setLocation(@NotNull Location location) {
        this.location = location;
    }

    static int getTimeFromTypeAndTier(Material material, int tier){
        switch(material) {
            case EMERALD:
                switch(tier) {
                    // case 1: return 56;
                    case 2:
                        return 40;
                    case 3:
                        return 28;
                    default:
                        return 56;
                }
            case DIAMOND:
                switch(tier) {
//                    case 1: return 30;
                    case 2: return 24;
                    case 3: return 12;
                    default:
                        return 30;
            }
            default: return -1;
        }
    }

}
