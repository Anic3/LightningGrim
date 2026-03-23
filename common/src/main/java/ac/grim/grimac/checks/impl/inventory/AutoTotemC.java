package ac.grim.grimac.checks.impl.inventory;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.inventory.Inventory;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;

/**
 * Detects auto-totem that silently clicks a totem into the offhand without
 * the player legitimately having their inventory open.
 *
 * Legitimate players must open their inventory (server sends OPEN_WINDOW, or
 * the player presses E and the client sends no packet but the server tracks it)
 * before making inventory clicks. Auto-totem implementations sometimes click
 * directly to the offhand slot without ever opening the inventory screen,
 * or they open/close the inventory in the same tick they make the click.
 *
 * This check flags when:
 *   - CLICK_WINDOW targets the offhand slot with a TOTEM_OF_UNDYING AND
 *   - The player's inventory was NOT open at the time of the click
 */
@CheckData(name = "AutoTotemC",
        description = "Placed totem in offhand via click without inventory open",
        experimental = true,
        setback = 5)
public class AutoTotemC extends Check implements PacketCheck {

    private static final int PROTOCOL_OFFHAND_SLOT = Inventory.SLOT_OFFHAND;

    public AutoTotemC(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.CLICK_WINDOW) return;

        WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);

        // Player inventory only
        if (click.getWindowId() != 0) return;
        // Must be targeting the offhand slot
        if (click.getSlot() != PROTOCOL_OFFHAND_SLOT) return;

        // Cursor item must be a totem
        ItemStack carried = click.getCarriedItemStack();
        if (carried == null || carried.getType() != ItemTypes.TOTEM_OF_UNDYING) return;

        // Inventory must be closed – if it's open the player may legitimately be swapping
        if (player.hasInventoryOpen) {
            reward();
            return;
        }

        if (flagAndAlertWithSetback("inventory-closed")) {
            if (shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
