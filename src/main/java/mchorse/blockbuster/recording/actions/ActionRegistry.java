package mchorse.blockbuster.recording.actions;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import net.minecraft.network.PacketByteBuf;

import java.util.HashMap;
import java.util.Map;

/**
 * Action registry (roadmap P104).
 *
 * <p><b>The byte-ID table is a disk and wire contract</b> — old {@code .dat}
 * files without an {@code Actions} name map use raw {@code Type} bytes. IDs 6
 * and 11 are intentional historical gaps; never reuse them.</p>
 */
public class ActionRegistry
{
    /**
     * Bi-directional map between class and byte ID
     */
    public static final BiMap<Class<? extends Action>, Byte> CLASS_TO_ID = HashBiMap.create();

    /**
     * Bi-directional map  map of action types mapped to corresponding class
     */
    public static final BiMap<String, Class<? extends Action>> NAME_TO_CLASS = HashBiMap.create();

    /**
     * A mapping between string named to byte type of the fixture
     */
    public static final Map<String, Byte> NAME_TO_ID = new HashMap<String, Byte>();

    /**
     * Next available id
     */
    private static byte NEXT_ID = 0;

    /**
     * Create an action from type
     */
    public static Action fromType(byte type) throws Exception
    {
        Class<? extends Action> clazz = CLASS_TO_ID.inverse().get(type);

        if (clazz == null)
        {
            throw new Exception("Action by type '" + type + "' wasn't found!");
        }

        return clazz.getConstructor().newInstance();
    }

    /**
     * Create an action from type
     */
    public static Action fromName(String name) throws Exception
    {
        Class<? extends Action> clazz = NAME_TO_CLASS.get(name);

        if (clazz == null)
        {
            throw new Exception("Action by type '" + name + "' wasn't found!");
        }

        return clazz.getConstructor().newInstance();
    }

    /**
     * Get type of the action
     */
    public static byte getType(Action action)
    {
        if (action == null)
        {
            return -1;
        }

        Byte type = CLASS_TO_ID.get(action.getClass());

        return type == null ? -1 : type;
    }

    public static int getRegisterdActionCount()
    {
        return CLASS_TO_ID.size();
    }

    public static int getMaxID()
    {
        return NEXT_ID - 1;
    }

    /**
     * Write an action to byte buffer
     */
    public static void toByteBuf(Action action, PacketByteBuf buffer)
    {
        byte type = getType(action);

        buffer.writeByte(type);

        if (action != null)
        {
            action.toBuf(buffer);
        }
    }

    /**
     * Create an action out of byte buffer
     */
    public static Action fromByteBuf(PacketByteBuf buffer)
    {
        byte type = buffer.readByte();

        if (type == -1)
        {
            return null;
        }

        try
        {
            Action action = fromType(type);

            action.fromBuf(buffer);

            return action;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Register given action with the next auto id
     */
    public static void register(String name, Class<? extends Action> clazz)
    {
        register(name, NEXT_ID, clazz);

        NEXT_ID++;
    }

    /**
     * Register given action with a fixed id
     */
    private static void register(String name, int id, Class<? extends Action> clazz)
    {
        if (CLASS_TO_ID.containsKey(clazz))
        {
            return;
        }

        CLASS_TO_ID.put(clazz, (byte) id);
        NAME_TO_ID.put(name, (byte) id);
        NAME_TO_CLASS.put(name, clazz);
    }

    static
    {
        /* Register Blockbuster actions — frozen 1.12.2 id table */
        register("chat", 1, ChatAction.class);
        register("swipe", 2, SwipeAction.class);
        register("drop", 3, DropAction.class);
        register("equip", 4, EquipAction.class);
        register("shoot_arrow", 5, ShootArrowAction.class);
        register("place_block", 7, PlaceBlockAction.class);
        register("mounting", 8, MountingAction.class);
        register("interact_block", 9, InteractBlockAction.class);
        register("break_block", 10, BreakBlockAction.class);
        register("morph", 12, MorphAction.class);
        register("attack", 13, AttackAction.class);
        register("damage", 14, DamageAction.class);
        register("morph_action", 15, MorphActionAction.class);
        register("command", 16, CommandAction.class);
        register("break_animation", 17, BreakBlockAnimation.class);
        register("use_item", 18, ItemUseAction.class);
        register("use_item_block", 19, ItemUseBlockAction.class);
        register("use_gun", 20, ShootGunAction.class);
        register("hotbar_change", 21, HotbarChangeAction.class);

        /* Set next ID to max */
        NEXT_ID = 22;

        register("interact_entity", InteractEntityAction.class);
        register("close_container", CloseContainerAction.class);

        /* wixo (CDC §6, R3) */
        register("explosion", ExplosionAction.class);
    }
}
