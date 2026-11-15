package mtr.block;

import mtr.data.IGui;
import mtr.data.RailwayData;
import mtr.mappings.BlockEntityClientSerializableMapper;
import mtr.mappings.EntityBlockMapper;
import mtr.mappings.Text;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public abstract class BlockPSDAPGDoorBase extends BlockPSDAPGBase implements EntityBlockMapper {

	public static final int MAX_OPEN_VALUE = 32;

	public static final BooleanProperty END = BooleanProperty.create("end");
	public static final BooleanProperty UNLOCKED = BooleanProperty.create("unlocked");
	public static final BooleanProperty TEMP = BooleanProperty.create("temp");

	@Override
	public BlockState updateShape(BlockState state, Direction direction, BlockState newState, LevelAccessor world, BlockPos pos, BlockPos posFrom) {
		if (IBlock.getSideDirection(state) == direction && !newState.is(this)) {
			return Blocks.AIR.defaultBlockState();
		} else {
			final BlockState superState = super.updateShape(state, direction, newState, world, pos, posFrom);
			if (superState.getBlock() == Blocks.AIR) {
				return superState;
			} else {
				final boolean end = world.getBlockState(pos.relative(IBlock.getSideDirection(state).getOpposite())).getBlock() instanceof BlockPSDAPGGlassEndBase;
				return superState.setValue(END, end);
			}
		}
	}

	@Override
	public void playerWillDestroy(Level world, BlockPos pos, BlockState state, Player player) {
		BlockPos offsetPos = pos;
		if (IBlock.getStatePropertySafe(state, HALF) == DoubleBlockHalf.UPPER) {
			offsetPos = offsetPos.below();
		}
		if (IBlock.getStatePropertySafe(state, SIDE) == EnumSide.RIGHT) {
			offsetPos = offsetPos.relative(IBlock.getSideDirection(state));
		}
		IBlock.onBreakCreative(world, player, offsetPos);
		super.playerWillDestroy(world, pos, state, player);
	}

	@Override
	public void tick(BlockState state, ServerLevel world, BlockPos pos) {
		final BlockEntity entity = world.getBlockEntity(pos);
		if (IBlock.getStatePropertySafe(state, UNLOCKED) && entity instanceof TileEntityPSDAPGDoorBase) {
			((TileEntityPSDAPGDoorBase) entity).setOpen(0);
		}
	}

	@Override
	public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult) {
		return IBlock.checkHoldingBrush(world, player, () -> {
			final boolean unlocked = IBlock.getStatePropertySafe(state, UNLOCKED);
			for (int y = -1; y <= 1; y++) {
				final BlockState scanState = world.getBlockState(pos.above(y));
				if (state.is(scanState.getBlock())) {
					lockDoor(world, pos.above(y), scanState, !unlocked);
				}
			}
			player.displayClientMessage(!unlocked ? Text.translatable("gui.mtr.psd_apg_door_unlocked") : Text.translatable("gui.mtr.psd_apg_door_locked"), true);
		});
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext collisionContext) {
		final BlockEntity entity = world.getBlockEntity(pos);
		return entity instanceof TileEntityPSDAPGDoorBase && ((TileEntityPSDAPGDoorBase) entity).isOpen() ? Shapes.empty() : super.getCollisionShape(state, world, pos, collisionContext);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(END, FACING, HALF, SIDE, UNLOCKED);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState blockState, BlockEntityType<T> type) {
		// The redstone state is only needed on the logical server; skipping the client avoids
		// ticking every door block entity while rendering.
		if (level.isClientSide) {
			return null;
		}

		return (world, pos, state, blockEntity) -> {
			if (blockEntity instanceof TileEntityPSDAPGDoorBase) {
				((TileEntityPSDAPGDoorBase) blockEntity).redstoneTick();
			}
		};
	}

	private static void lockDoor(Level world, BlockPos pos, BlockState state, boolean unlocked) {
		final Direction facing = IBlock.getStatePropertySafe(state, FACING);
		final BlockPos leftPos = pos.relative(facing.getCounterClockWise());
		final BlockPos rightPos = pos.relative(facing.getClockWise());
		final BlockState leftState = world.getBlockState(leftPos);
		final BlockState rightState = world.getBlockState(rightPos);

		if (leftState.is(state.getBlock())) {
			final BlockState toggled = leftState.setValue(UNLOCKED, unlocked);
			world.setBlockAndUpdate(leftPos, toggled);
		}

		if (rightState.is(state.getBlock())) {
			final BlockState toggled = rightState.setValue(UNLOCKED, unlocked);
			world.setBlockAndUpdate(rightPos, toggled);
		}

		world.setBlockAndUpdate(pos, state.setValue(UNLOCKED, unlocked));
	}

	public static abstract class TileEntityPSDAPGDoorBase extends BlockEntityClientSerializableMapper implements IGui {

		private int open;
		private float openClient;
		/**
		 * Cached redstone state, refreshed by {@link #redstoneTick()} once per tick on the bottom half.
		 * External writes (trains, lifts, the scheduled close tick) read this instead of querying the
		 * world, which keeps {@link #setOpen(int)} allocation-free and cheap.
		 */
		private boolean redstonePowered;
		private boolean firstTick = true;
		private long lastExternalTick = -1000;

		private static final String KEY_OPEN = "open";

		private static final int REDSTONE_OPEN_STEP = 2;
		private static final int REDSTONE_DETECT_DEPTH = 3;
		private static final int EXTERNAL_GRACE_TICKS = 2;

		public TileEntityPSDAPGDoorBase(BlockEntityType<?> type, BlockPos pos, BlockState state) {
			super(type, pos, state);
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			open = compoundTag.getInt(KEY_OPEN);
		}

		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			compoundTag.putInt(KEY_OPEN, open);
		}

		public AABB getRenderBoundingBox() {
			return new AABB(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
		}

		public void setOpen(int open) {
			lastExternalTick = level == null ? 0 : level.getGameTime();
			// While a redstone signal holds the door open, trains/lifts/scheduled ticks must not close it.
			if (redstonePowered) {
				return;
			}
			applyOpen(open);
		}

		private void applyOpen(int open) {
			if (open != this.open) {
				this.open = open;
				setChanged();
				syncData();
			}
		}

		/**
		 * Called server-side once per tick for every door block entity. Only the bottom half does the
		 * redstone scan and drives both halves, so the detection cost is paid once per door.
		 */
		public void redstoneTick() {
			final Level world = level;
			if (world == null) {
				return;
			}

			if (IBlock.getStatePropertySafe(getBlockState(), BlockPSDAPGDoorBase.HALF) == DoubleBlockHalf.UPPER) {
				return;
			}

			final boolean enabled = RailwayData.isRedstoneDoorControlEnabled();
			final boolean wasFirstTick = firstTick;
			firstTick = false;

			if (!enabled) {
				// Feature off: behave like before the feature existed. The only leftovers are doors
				// this feature had opened when the switch was flipped or when the chunk was reloaded,
				// so release those once and otherwise stay completely inert (no world lookups).
				if (redstonePowered || wasFirstTick) {
					redstonePowered = false;
					final TileEntityPSDAPGDoorBase upperBlockEntity = getUpperBlockEntity(world);
					if (upperBlockEntity != null) {
						upperBlockEntity.redstonePowered = false;
					}
					if (open > 0 && world.getGameTime() - lastExternalTick > EXTERNAL_GRACE_TICKS) {
						applyOpen(0);
						if (upperBlockEntity != null) {
							upperBlockEntity.applyOpen(0);
						}
					}
				}
				return;
			}

			final TileEntityPSDAPGDoorBase upperBlockEntity = getUpperBlockEntity(world);
			redstonePowered = isRedstonePowered(world);
			if (upperBlockEntity != null) {
				upperBlockEntity.redstonePowered = redstonePowered;
			}

			if (redstonePowered) {
				stepDoor(upperBlockEntity, MAX_OPEN_VALUE);
			} else if (world.getGameTime() - lastExternalTick > EXTERNAL_GRACE_TICKS) {
				stepDoor(upperBlockEntity, 0);
			}
		}

		private void stepDoor(TileEntityPSDAPGDoorBase upperBlockEntity, int target) {
			final int newOpen = open < target ? Math.min(target, open + REDSTONE_OPEN_STEP) : Math.max(target, open - REDSTONE_OPEN_STEP);
			applyOpen(newOpen);
			if (upperBlockEntity != null) {
				// Always write through so a half that was changed externally while the bottom was
				// held open gets pulled back in sync (applyOpen ignores unchanged values).
				upperBlockEntity.applyOpen(newOpen);
			}
		}

		private TileEntityPSDAPGDoorBase getUpperBlockEntity(Level world) {
			final BlockEntity blockEntity = world.getBlockEntity(worldPosition.above());
			return blockEntity instanceof TileEntityPSDAPGDoorBase ? (TileEntityPSDAPGDoorBase) blockEntity : null;
		}

		private boolean isRedstonePowered(Level world) {
			final BlockState state = getBlockState();
			if (!IBlock.getStatePropertySafe(state, BlockPSDAPGDoorBase.UNLOCKED)) {
				return false;
			}

			final Direction sideDirection = IBlock.getSideDirection(state);
			for (int i = 1; i <= REDSTONE_DETECT_DEPTH; i++) {
				final BlockPos checkPos = worldPosition.below(i);
				if (isPowered(world, checkPos) || isPowered(world, checkPos.relative(sideDirection))) {
					return true;
				}
			}

			return false;
		}

		private static boolean isPowered(Level world, BlockPos pos) {
			return world.hasNeighborSignal(pos) || world.getSignal(pos, Direction.UP) > 0;
		}

		public float getOpen(float lastFrameDuration) {
			final float change = lastFrameDuration * 0.95F;
			if (Math.abs(open - SMALL_OFFSET_16 * 2 - openClient) < change) {
				openClient = open - SMALL_OFFSET_16 * 2;
			} else if (openClient < open) {
				openClient += change;
			} else {
				openClient -= change;
			}
			return openClient / 32;
		}

		public boolean isOpen() {
			return open > 0;
		}

		public int getOpenValue() {
			return open;
		}
	}
}