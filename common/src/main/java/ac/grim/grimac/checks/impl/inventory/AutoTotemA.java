package ac.grim.grimac.checks.impl.inventory;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;

/**
 * Detects auto-totem via the F-key swap (SWAP_ITEM_WITH_OFFHAND).
 *
 * When a totem pops, the server sends ENTITY_STATUS 35 to the client.
 * Auto-totem mods listen for this event and immediately press F to move
 * the totem from the main hand into the offhand.
 *
 * The minimum legitimate reaction time is:
 *   ping (round-trip) + human reaction (~150ms)
 * We flag if the reaction arrives in less than:
 *   ping + MIN_HUMAN_REACTION_MS
 */
@CheckData(name = "AutoTotemA",
        description = "Swapped totem to offhand via F-key impossibly fast after pop",
        experimental = true,
        setback = 5)
public class AutoTotemA extends Check implements PacketCheck {

    // Nanosecond timestamp when the server sent the totem-pop notification (-1 = no pending pop)
    private long totemPopSentNs = -1;
    // Configurable human reaction floor in nanoseconds
    private volatile long minHumanReactionNs = 100_000_000L;

    public AutoTotemA(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        minHumanReactionNs = config.getIntElse(getConfigName() + ".min-human-reaction-ms", 100) * 1_000_000L;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_STATUS) return;
        WrapperPlayServerEntityStatus status = new WrapperPlayServerEntityStatus(event);
        // Status 35 = totem of undying used / popped for the target entity
        if (status.getEntityId() == player.entityID && status.getStatus() == 35) {
            totemPopSentNs = System.nanoTime();
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (totemPopSentNs == -1) return;
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING) return;

        WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
        if (digging.getAction() != DiggingAction.SWAP_ITEM_WITH_OFFHAND) return;

        // The held item is what gets moved to the offhand – it should be a totem
        ItemStack held = player.inventory.getHeldItem();
        if (held == null || held.getType() != ItemTypes.TOTEM_OF_UNDYING) return;

        long elapsed   = System.nanoTime() - totemPopSentNs;
        long pingNs    = (long) player.getTransactionPing() * 1_000_000L;
        long threshold = pingNs + minHumanReactionNs;

        if (elapsed < threshold) {
            flagAndAlertWithSetback(String.format("reaction=%dms threshold=%dms",
                    elapsed / 1_000_000L, threshold / 1_000_000L));
        } else {
            reward();
        }
        totemPopSentNs = -1;
    }
}
