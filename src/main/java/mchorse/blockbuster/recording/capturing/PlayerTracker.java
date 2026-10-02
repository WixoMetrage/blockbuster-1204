package mchorse.blockbuster.recording.capturing;

import java.util.Arrays;
import java.util.List;

import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.recording.RecordRecorder;
import mchorse.blockbuster.recording.actions.AttackAction;
import mchorse.blockbuster.recording.actions.CloseContainerAction;
import mchorse.blockbuster.recording.actions.EquipAction;
import mchorse.blockbuster.recording.actions.HotbarChangeAction;
import mchorse.blockbuster.recording.actions.SwipeAction;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;

/**
 * Player tracker class (roadmap P114) — 1:1 port of the 2.7.2 class.
 *
 * <p>This class tracks player's properties such as arm swing and player
 * equipped inventory. That's it.</p>
 *
 * <p>Yarn 1.20.4 mapping (recorded per parity rules):
 * {@code armorInventory} &rarr; {@code getInventory().armor} (index 0 = feet
 * .. 3 = head, same order 1.12.2 used); {@code getHeldItemMainhand/Offhand}
 * &rarr; {@code getMainHandStack/getOffHandStack}; {@code mainInventory}
 * &rarr; {@code getInventory().main} (first 9 = hotbar);
 * {@code inventory.currentItem} &rarr; {@code getInventory().selectedSlot};
 * {@code ItemStack.areItemStacksEqual} &rarr; {@code ItemStack.areEqual}
 * (value compare, item+count+nbt); {@code isSwingInProgress}/
 * {@code swingProgress} &rarr; {@code handSwinging}/{@code handSwingProgress};
 * {@code isPlayerSleeping} &rarr; {@code isSleeping};
 * {@code openContainer}/{@code inventoryContainer} &rarr;
 * {@code currentScreenHandler}/{@code playerScreenHandler}.</p>
 *
 * <p>The serialized armor/hand slot byte stays in the legacy index space
 * (mainhand 0, feet 1, legs 2, chest 3, head 4, offhand 5) — the same values
 * {@code EntityEquipmentSlot.getSlotIndex()} produced and which
 * {@link EquipAction} resolves via {@code EquipmentSlot.getArmorStandSlotId()}.
 * </p>
 */
public class PlayerTracker
{
    /**
     * Record recorder to which tracked stuff are going to be added
     */
    public RecordRecorder recorder;

    /* Items to track (null-initialised: reference-compare + null semantics) */
    private ItemStack[] items = new ItemStack[6];
    private ItemStack[] hotbar = new ItemStack[9];

    /**
     * Runtime variable to track the complete hotbar for the fist frame
     */
    private boolean trackedHotbar = false;

    private ScreenHandler container;

    public PlayerTracker(RecordRecorder recorder)
    {
        this.recorder = recorder;

        Arrays.fill(this.hotbar, ItemStack.EMPTY);
    }

    /**
     * Track player's properties like armor, hand held items and hand swing.
     * Statement order matches 1.12.2 exactly.
     */
    public void track(PlayerEntity player)
    {
        this.trackSwing(player.handSwinging, player.handSwingProgress, player.isSleeping());
        this.trackHeldItem(player.getMainHandStack(), player.getOffHandStack(), player.getInventory().selectedSlot);
        this.trackHotBar(player.getInventory().main);
        this.trackArmor(player.getInventory().armor);
        this.trackContainerClose(player.currentScreenHandler, player.playerScreenHandler);
    }

    /**
     * Track armor inventory (feet..head map to slots 1..4).
     */
    void trackArmor(List<ItemStack> armor)
    {
        for (int i = 1; i < 5; i++)
        {
            this.trackItemToSlot(armor.get(i - 1), i);
        }
    }

    /**
     * Track held items (main hand &rarr; slot 0 with hotbar index, off hand
     * &rarr; slot 5).
     */
    void trackHeldItem(ItemStack mainhand, ItemStack offhand, int currentItem)
    {
        this.trackItemToSlot(mainhand, 0, currentItem);
        this.trackItemToSlot(offhand, 5);
    }

    void trackHotBar(List<ItemStack> playerHotbar)
    {
        for (int i = 0; i < playerHotbar.size() && i < this.hotbar.length; i++)
        {
            if (!ItemStack.areEqual(this.hotbar[i], playerHotbar.get(i)) || !this.trackedHotbar)
            {
                this.recorder.actions.add(new HotbarChangeAction(i, playerHotbar.get(i).copy()));

                this.hotbar[i] = playerHotbar.get(i).copy();
            }
        }

        this.trackedHotbar = true;
    }

    /**
     * Track item to slot.
     *
     * This is a simple utility method that reduces number of lines for both
     * hands.
     */
    boolean trackItemToSlot(ItemStack item, int slot)
    {
        return this.trackItemToSlot(item, slot, -1);
    }

    boolean trackItemToSlot(ItemStack item, int slot, int hotbarslot)
    {
        if (!item.isEmpty())
        {
            if (item != this.items[slot])
            {
                this.items[slot] = item;
                this.recorder.actions.add((hotbarslot != -1) ? new EquipAction((byte) slot, (byte) hotbarslot, item) : new EquipAction((byte) slot, item));

                return true;
            }
        }
        else if (this.items[slot] != null)
        {
            this.items[slot] = null;
            this.recorder.actions.add((hotbarslot != -1) ? new EquipAction((byte) slot, (byte) hotbarslot, null) : new EquipAction((byte) slot, null));

            return true;
        }

        return false;
    }

    /**
     * Track the hand swing (like when you do the tap-tap with left-click).
     *
     * <p>{@code sleeping} is necessary since for some reason when a player is
     * falling asleep, {@code handSwinging} is true while
     * {@code handSwingProgress} equals 0, which makes the player swipe every
     * tick in bed. So gotta check that so it wouldn't look like Steve is
     * beating his meat.</p>
     */
    void trackSwing(boolean handSwinging, float swingProgress, boolean sleeping)
    {
        if (handSwinging && swingProgress == 0 && !sleeping)
        {
            this.recorder.actions.add(new SwipeAction());

            if (Blockbuster.recordSwipeAttacks.get())
            {
                this.recorder.actions.add(new AttackAction());
            }
        }
    }

    /**
     * Track whether player has closed a container.
     */
    void trackContainerClose(ScreenHandler current, ScreenHandler playerScreen)
    {
        if (this.container != null && current == playerScreen && this.container != current)
        {
            this.recorder.actions.add(new CloseContainerAction());
        }

        this.container = current;
    }
}
