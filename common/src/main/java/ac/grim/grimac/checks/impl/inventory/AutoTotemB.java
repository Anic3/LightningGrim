package ac.grim.grimac.checks.impl.inventory;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.inventory.Inventory;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;

/**
 * Detects auto-totem via inventory click (CLICK_WINDOW) to move a totem into offhand.
 *
 * Some auto-totem implementations open the inventory silently and click a totem
 * into the offhand slot (protocol slot 45) immediately after a totem pops.
 * The reaction window is the same constraint as AutoTotemA:
 *   ping + MIN_HUMAN_REACTION_MS
 *
 * Slot 45 (protocol) = offhand slot in the player's own inventory (windowId = 0).
 */
@CheckData(name = "AutoTotemB",
        description = "Moved totem to offhand via inventory click impossibly fast after pop",
        experimental = true,
        setback = 5)
public class AutoTotemB extends Check implements PacketCheck {

    private static final int PROTOCOL_OFFHAND_SLOT = Inventory.SLOT_OFFHAND; // slot 45

    private long totemPopSentNs = -1;
    private volatile long minHumanReactionNs = 100_000_000L;

    public AutoTotemB(GrimPlayer player) {
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
        if (status.getEntityId() == player.entityID && status.getStatus() == 35) {
            totemPopSentNs = System.nanoTime();
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (totemPopSentNs == -1) return;
        if (event.getPacketType() != PacketType.Play.Client.CLICK_WINDOW) return;

        WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);

        // Only care about the player's own inventory (windowId 0)
        if (click.getWindowId() != 0) return;
        // Must be clicking the offhand slot
        if (click.getSlot() != PROTOCOL_OFFHAND_SLOT) return;

        // The carried/cursor item is what the player is placing into the slot
        ItemStack carried = click.getCarriedItemStack();
        if (carried == null || carried.getType() != ItemTypes.TOTEM_OF_UNDYING) return;

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
