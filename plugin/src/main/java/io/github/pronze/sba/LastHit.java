package io.github.pronze.sba;

import lombok.Data;
import lombok.NonNull;
import org.bukkit.entity.Player;

import java.util.HashMap;
@Data
public class LastHit {
    public enum HitType {
        MELEE,
    }
    @NonNull
    private Player who;
    @NonNull
    private HitType type;
    @NonNull
    private Long when;

    private static HashMap<Player, LastHit> lastHits = new HashMap();

    public static void setLastHit(Player who, LastHit hit) {
        lastHits.put(who, hit);
    }
    public static void setLastHit(Player damaged, Player damager, HitType type) {
        lastHits.put(damaged, new LastHit(damager, type, System.currentTimeMillis()));
    }
    public static LastHit getLastHit(Player who) {
        return lastHits.get(who);
    }
}