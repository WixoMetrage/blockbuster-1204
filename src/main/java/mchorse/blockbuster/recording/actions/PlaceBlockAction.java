package mchorse.blockbuster.recording.actions;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import mchorse.blockbuster.Blockbuster;
import mchorse.blockbuster.legacy.LegacyIdMap;
import mchorse.blockbuster.recording.scene.SceneSeek;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.entity.LivingEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Guess what this action does
 *
 * Does exactly what you think, no less, no more.
 *
 * <p>The {@code (block registry name, metadata byte)} pair is pre-flattening
 * state — kept verbatim on disk, resolved through the P71 id shim only at
 * apply/display time.</p>
 *
 * <h2>S22 P296 — the {@code State} carrier</h2>
 *
 * <p>1.12.2 stored {@code block.getMetaFromState(state)}, i.e. as much of the
 * real placed {@link BlockState} as 4-bit metadata could hold; the port had
 * nothing but {@code meta = 0}, so every recorded stair/log/slab/door/fence/
 * chest/repeater replayed in its <b>default</b> state (wrong facing, wrong
 * half, wrong axis, never waterlogged). 1.20.4 has no metadata at all, so this
 * is one of the few places the modern format has to be <i>richer</i> than 1.12's
 * — 4 bits cannot express a 1.20.4 state.</p>
 *
 * <p>{@link #state} is that carrier: the {@code BlockArgumentParser} form
 * ({@code minecraft:oak_stairs[facing=east,half=bottom,shape=straight,
 * waterlogged=false]}), stored under the <b>optional</b> {@code State} string
 * tag. Why a string rather than {@code NbtHelper.fromBlockState}:</p>
 * <ul>
 * <li>It is a scalar, exactly like {@code Block} and {@code Meta} — one
 * {@code putString}/{@code writeString} on disk and on the wire, no nested
 * compound to keep symmetric.</li>
 * <li>The recording editor is the reason this format is user-facing at all
 * ({@code GuiPlaceBlockActionPanel} edits {@code Block}/{@code Meta} as raw
 * text). A state string is readable and <i>editable</i> in that panel; an NBT
 * compound would be neither.</li>
 * <li>Totality costs one {@code catch}: an unparseable / unknown-property /
 * absent state warns once and falls back to the legacy {@code (Block, Meta)}
 * path, which then degrades to the P71 placeholder — never a crash.</li>
 * </ul>
 *
 * <p><b>Backward compatible both ways:</b> the tag is written only when
 * non-empty, so every 2.7.2 record (and every record this port wrote before
 * P296) resaves byte-identically and still resolves through
 * {@link LegacyIdMap#blockState(String, int)}; a 1.12.2 reader would ignore an
 * unknown extra key anyway. Bucket captures made before wixo R3 carry
 * <b>no</b> state (flowing_water / flowing_lava, meta 0, next to the aimed
 * block). Since wixo R3 a bucket is captured with
 * its resulting state, see {@code ActionHandler.onPlayerPlacedFluid}.</p>
 *
 * <p>{@link #changeOrigin} / {@link #flip} do <b>not</b> transform the state:
 * legacy never rotated or mirrored the stored metadata either, so a flipped
 * recording keeps the originally-recorded facing (parity, not an oversight).</p>
 */
public class PlaceBlockAction extends InteractBlockAction
{
    public byte metadata;
    public String block = "";

    /**
     * P296 — the full placed {@link BlockState} in
     * {@code BlockArgumentParser} form, or {@code ""} when absent (legacy
     * records, bucket captures). Authoritative over {@code (block, metadata)}
     * when it parses.
     */
    public String state = "";

    /** Distinct unparseable state strings already warned about (warn-once). */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    public PlaceBlockAction()
    {}

    public PlaceBlockAction(BlockPos pos, byte metadata, String block)
    {
        this(pos, metadata, block, "");
    }

    public PlaceBlockAction(BlockPos pos, byte metadata, String block, String state)
    {
        super(pos);
        this.metadata = metadata;
        this.block = block;
        this.state = state == null ? "" : state;
    }

    /**
     * The {@code BlockArgumentParser} string form of a live
     * {@link BlockState} — what {@code ActionHandler.onBlockPlaced} captures
     * into {@link #state}.
     */
    public static String stringify(BlockState state)
    {
        return state == null ? "" : BlockArgumentParser.stringifyBlockState(state);
    }

    /**
     * The {@link BlockState} this action places: the captured full state when
     * present and parseable, otherwise the P71 shim's resolution of the legacy
     * {@code (block, metadata)} pair (which itself degrades to
     * {@link LegacyIdMap#PLACEHOLDER_BLOCK}).
     *
     * <p>Total by contract — a garbage / stale / modded-property state string
     * logs one warning and falls through, it never throws. Extracted from
     * {@link #apply(LivingEntity)} so the resolution is headless-testable
     * against the very code the world write uses.</p>
     */
    public BlockState resolveState()
    {
        if (!this.state.isEmpty())
        {
            try
            {
                return BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), this.state, false).blockState();
            }
            catch (Exception e)
            {
                if (WARNED.add(this.state))
                {
                    Blockbuster.LOGGER.warn("P296 recorded block state \"{}\" could not be parsed — falling back to ({}, {})", this.state, this.block, this.metadata);
                }
            }
        }

        return LegacyIdMap.blockState(this.block, this.metadata);
    }

    /** wixo (R2): replayed by a fast-forward through the timeline. */
    @Override
    public boolean modifiesWorld()
    {
        return true;
    }

    /**
     * Resolve the placed state (P296 {@code State} first, then the legacy
     * {@code (block, metadata)} pair through the P71 id shim), set it, and play
     * the vanilla place sound.
     *
     * <p>Parity notes vs legacy {@code PlaceBlockAction.apply}:</p>
     * <ul>
     * <li>The blacklist is checked against the <b>current</b> world state at
     * the position (the block being replaced), never the block being placed —
     * a load-bearing legacy quirk.</li>
     * <li>Legacy resolved {@code Block.REGISTRY.getObject} directly and then
     * {@code block.getStateFromMeta(this.metadata)}, i.e. it <b>did</b> restore
     * the orientation 1.12 metadata could hold. The port restores it from the
     * P296 {@code State} carrier instead, and routes the legacy pair through
     * {@link LegacyIdMap#blockState} when there is none, which flattens vanilla
     * {@code (name, meta)} families, falls through to the live registry for
     * <b>registered</b> mod-namespaced ids (so a captured
     * {@code blockbuster:director} replays as a director, as it did in 1.12.2),
     * and degrades only genuinely unresolvable ids to the shim's placeholder
     * rather than crashing (total-reader contract). Legacy left an unresolvable
     * id as {@code minecraft:air} — {@code Block.REGISTRY} is defaulted, so its
     * {@code block != null} guard never fired — where the port places the
     * P211-frozen {@code stone} placeholder; a visible marker beats a silently
     * deleted block.</li>
     * <li>Place sound uses {@code state.getSoundGroup()} with volume
     * {@code (v + 1) / 2} and pitch {@code p * 0.8}, matching 1.12.2.</li>
     * </ul>
     */
    @Override
    public void apply(LivingEntity actor)
    {
        World world = actor.getWorld();
        BlockState existing = world.getBlockState(this.pos);

        /* Blacklist is checked against the block being replaced, not placed. */
        if (BLACKLIST.contains(Registries.BLOCK.getId(existing.getBlock())))
        {
            return;
        }

        BlockState state = this.resolveState();

        world.setBlockState(this.pos, state);

        /* wixo (CDC §6, R3): what BlockItem.place does after setting the block.
         * The second half of a door, bed or tall plant is placed by onPlaced;
         * legacy got it from replaying the item use on top of this action,
         * which also placed a second block. A fluid (bucket, waterlogging)
         * starts flowing as it did in the take. */
        ItemStack held = actor.getMainHandStack();

        state.getBlock().onPlaced(world, this.pos, state, actor, held.isEmpty() ? ItemStack.EMPTY : held.copy());

        FluidState fluid = state.getFluidState();

        if (!fluid.isEmpty())
        {
            world.scheduleFluidTick(this.pos, fluid.getFluid(), fluid.getFluid().getTickRate(world));
        }

        BlockSoundGroup sound = world.getBlockState(this.pos).getSoundGroup();

        if (!SceneSeek.seeking) world.playSound(null, this.pos, sound.getPlaceSound(), SoundCategory.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
    }

    @Override
    public void fromBuf(PacketByteBuf buf)
    {
        super.fromBuf(buf);
        this.metadata = buf.readByte();
        this.block = buf.readString();
        this.state = buf.readString();
    }

    @Override
    public void toBuf(PacketByteBuf buf)
    {
        super.toBuf(buf);
        buf.writeByte(this.metadata);
        buf.writeString(this.block);
        buf.writeString(this.state);
    }

    @Override
    public void fromNBT(NbtCompound tag)
    {
        super.fromNBT(tag);
        this.metadata = tag.getByte("Meta");
        this.block = tag.getString("Block");

        /* Absent State (every legacy record) → "", i.e. the pure legacy path */
        this.state = tag.getString("State");
    }

    @Override
    public void toNBT(NbtCompound tag)
    {
        super.toNBT(tag);
        tag.putByte("Meta", this.metadata);
        tag.putString("Block", this.block);

        /* Conditional: a stateless action must resave byte-identically to the
         * 2.7.2 file it came from (RecordGoldenTest's hard byte gate). */
        if (!this.state.isEmpty())
        {
            tag.putString("State", this.state);
        }
    }
}
