package io.github.pronze.sba.game;

import org.bukkit.inventory.ItemStack;
import org.screamingsandals.bedwars.api.events.BedwarsResourceSpawnEvent;
import org.screamingsandals.bedwars.api.game.Game;
import org.screamingsandals.bedwars.api.game.ItemSpawner;

public class BedwarsSBAResourceSpawnEvent extends BedwarsResourceSpawnEvent {
    public BedwarsSBAResourceSpawnEvent(Game game, ItemSpawner spawner, ItemStack resource) {
        super(game, spawner, resource);
    }
}
