package chihalu.automated.tree.harvesting.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public final class TreeHarvestManager {
	private static final int MAX_LOGS = 512;
	private static final int MAX_LEAVES = 2048;
	private static final int HORIZONTAL_RADIUS = 6;
	private static final int VERTICAL_BELOW = 4;
	private static final int VERTICAL_ABOVE = 32;
	private static final long REPLANT_DELAY_TICKS = 200L;
	private static final Map<PendingKey, PendingPlant> PENDING_PLANTS = new HashMap<>();
	private static final TagKey<Block> PALE_OAK_LOGS_TAG = TagKey.of(RegistryKeys.BLOCK, Identifier.of("minecraft", "pale_oak_logs"));
	private static final Identifier PALE_OAK_SAPLING_ID = Identifier.of("minecraft", "pale_oak_sapling");

	private TreeHarvestManager() {
	}

	private record PendingKey(RegistryKey<World> worldKey, BlockPos pos) {
		private boolean matches(ServerWorld world) {
			return world.getRegistryKey().equals(worldKey);
		}
	}

	private static final class PendingPlant {
		private final BlockState saplingState;
		private final BlockState belowState;
		private final long createdTick;

		private PendingPlant(BlockState saplingState, BlockState belowState, long createdTick) {
			this.saplingState = saplingState;
			this.belowState = belowState;
			this.createdTick = createdTick;
		}
	}

	public static void onFrameTick(ServerWorld world, ItemFrameEntity frame) {
		ItemStack tool = frame.getHeldItemStack();
		if (!isAxe(tool)) {
			return;
		}

		BlockPos base = findTreeBase(world, frame);
		if (base == null) {
			return;
		}

		Set<BlockPos> logs = collectLogs(world, base);
		if (logs.isEmpty()) {
			return;
		}

		BlockState baseState = world.getBlockState(base);
		ItemStack shears = findShears(world, frame);
		Set<BlockPos> leaves = collectLeaves(world, logs, base);
		ItemStack leafTool = shears.isEmpty() ? ItemStack.EMPTY : shears;

		boolean harvestedLogs = breakBlocks(world, logs, tool, frame, base);
		boolean harvestedLeaves = !leaves.isEmpty() && breakBlocks(world, leaves, leafTool, frame, base);

		if (harvestedLogs) {
			tryReplantSapling(world, base, baseState, logs);
		}

		if (harvestedLogs || harvestedLeaves) {
			world.playSound(
				null,
				base,
				SoundEvents.BLOCK_WOOD_BREAK,
				SoundCategory.BLOCKS,
				1.0F,
				0.9F + world.getRandom().nextFloat() * 0.2F
			);
		}
	}

	private static boolean isAxe(ItemStack stack) {
		return !stack.isEmpty() && stack.isIn(ItemTags.AXES);
	}

	private static boolean isShears(ItemStack stack) {
		return !stack.isEmpty() && stack.isOf(Items.SHEARS);
	}

	private static BlockPos findTreeBase(ServerWorld world, ItemFrameEntity frame) {
		BlockPos support = frame.getAttachedBlockPos();
		BlockPos min = support.add(-HORIZONTAL_RADIUS, -VERTICAL_BELOW, -HORIZONTAL_RADIUS);
		BlockPos max = support.add(HORIZONTAL_RADIUS, VERTICAL_ABOVE, HORIZONTAL_RADIUS);

		BlockPos closest = null;
		double closestDistance = Double.MAX_VALUE;

		for (BlockPos pos : BlockPos.iterate(min, max)) {
			BlockState state = world.getBlockState(pos);
			if (!state.isIn(BlockTags.LOGS)) {
				continue;
			}
			double dx = frame.getX() - (pos.getX() + 0.5);
			double dy = frame.getY() - (pos.getY() + 0.5);
			double dz = frame.getZ() - (pos.getZ() + 0.5);
			double distance = dx * dx + dy * dy + dz * dz;
			if (distance < closestDistance) {
				closestDistance = distance;
				closest = pos.toImmutable();
			}
		}

		if (closest == null) {
			return null;
		}

		int dx = closest.getX() - support.getX();
		int dy = closest.getY() - support.getY();
		int dz = closest.getZ() - support.getZ();

		if (Math.abs(dx) + Math.abs(dz) != 1) {
			return null;
		}

		if (dy < -1 || dy > 2) {
			return null;
		}

		return closest;
	}

	private static Set<BlockPos> collectLogs(ServerWorld world, BlockPos start) {
		Set<BlockPos> collected = new HashSet<>();
		Set<BlockPos> visited = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);

		int minY = start.getY() - VERTICAL_BELOW;
		int maxY = start.getY() + VERTICAL_ABOVE;

		while (!queue.isEmpty() && collected.size() < MAX_LOGS) {
			BlockPos current = queue.removeFirst();
			if (!visited.add(current)) {
				continue;
			}
			if (!withinRadius(start, current, HORIZONTAL_RADIUS) || current.getY() < minY || current.getY() > maxY) {
				continue;
			}
			BlockState state = world.getBlockState(current);
			if (!state.isIn(BlockTags.LOGS)) {
				continue;
			}
			collected.add(current.toImmutable());

			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) {
							continue;
						}
						BlockPos neighbor = current.add(dx, dy, dz);
						if (visited.contains(neighbor)) {
							continue;
						}
						if (!withinRadius(start, neighbor, HORIZONTAL_RADIUS) || neighbor.getY() < minY || neighbor.getY() > maxY) {
							continue;
						}
						if (world.getBlockState(neighbor).isIn(BlockTags.LOGS)) {
							queue.addLast(neighbor);
						}
					}
				}
			}
		}
		return collected;
	}

	private static Set<BlockPos> collectLeaves(ServerWorld world, Set<BlockPos> logs, BlockPos base) {
		Set<BlockPos> collected = new HashSet<>();
		Set<BlockPos> seen = new HashSet<>(logs);
		Deque<BlockPos> queue = new ArrayDeque<>(logs);

		int minY = base.getY() - VERTICAL_BELOW;
		int maxY = base.getY() + VERTICAL_ABOVE + 6;

		while (!queue.isEmpty() && collected.size() < MAX_LEAVES) {
			BlockPos current = queue.removeFirst();

			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) {
							continue;
						}
						BlockPos neighbor = current.add(dx, dy, dz);
						if (!seen.add(neighbor)) {
							continue;
						}
						if (!withinRadius(base, neighbor, HORIZONTAL_RADIUS + 2) || neighbor.getY() < minY || neighbor.getY() > maxY) {
							continue;
						}
						BlockState state = world.getBlockState(neighbor);
						if (!state.isIn(BlockTags.LEAVES)) {
							continue;
						}
						collected.add(neighbor.toImmutable());
						queue.addLast(neighbor);
					}
				}
			}
		}
		return collected;
	}

	private static ItemStack findShears(ServerWorld world, ItemFrameEntity sourceFrame) {
		Box searchBox = sourceFrame.getBoundingBox().expand(2.0D);
		List<ItemFrameEntity> frames = world.getEntitiesByClass(
			ItemFrameEntity.class,
			searchBox,
			frame -> frame != sourceFrame && isShears(frame.getHeldItemStack())
		);

		return frames.isEmpty() ? ItemStack.EMPTY : frames.get(0).getHeldItemStack();
	}

	private static boolean breakBlocks(
		ServerWorld world,
		Set<BlockPos> positions,
		ItemStack tool,
		ItemFrameEntity frame,
		BlockPos dropTarget
	) {
		boolean brokeAny = false;
		List<ItemStack> collectedDrops = new ArrayList<>();

		for (BlockPos pos : positions) {
			BlockState state = world.getBlockState(pos);
			if (state.isAir()) {
				continue;
			}

			BlockEntity blockEntity = world.getBlockEntity(pos);
			List<ItemStack> drops = Block.getDroppedStacks(state, world, pos, blockEntity, frame, tool);
			for (ItemStack drop : drops) {
				if (!drop.isEmpty()) {
					collectedDrops.add(drop.copy());
				}
			}
			state.onStacksDropped(world, pos, tool, true);
			world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
			world.syncWorldEvent(null, 2001, pos, Block.getRawIdFromState(state));
			brokeAny = true;
		}

		if (brokeAny) {
			spawnCollectedDrops(world, dropTarget, collectedDrops);
		}

		return brokeAny;
	}

	private static void spawnCollectedDrops(ServerWorld world, BlockPos dropTarget, List<ItemStack> drops) {
		if (dropTarget == null || drops.isEmpty()) {
			return;
		}

		double x = dropTarget.getX() + 0.5D;
		double y = dropTarget.getY() + 0.25D;
		double z = dropTarget.getZ() + 0.5D;

		for (ItemStack stack : drops) {
			if (stack.isEmpty()) {
				continue;
			}
			ItemEntity item = new ItemEntity(world, x, y, z, stack.copy());
			item.setVelocity(0.0D, 0.0D, 0.0D);
			item.setToDefaultPickupDelay();
			world.spawnEntity(item);
		}
	}

	private static void tryReplantSapling(ServerWorld world, BlockPos base, BlockState baseState, Set<BlockPos> logs) {
		BlockState saplingState = resolveSaplingState(baseState);
		if (saplingState == null) {
			return;
		}

		if (requiresTwoByTwo(baseState)) {
			BlockPos anchor = findTwoByTwoAnchor(logs, base);
			if (anchor == null) {
				return;
			}

			BlockPos east = anchor.add(1, 0, 0);
			BlockPos south = anchor.add(0, 0, 1);
			BlockPos southEast = anchor.add(1, 0, 1);

			BlockState anchorBelow = world.getBlockState(anchor.down());
			BlockState eastBelow = world.getBlockState(east.down());
			BlockState southBelow = world.getBlockState(south.down());
			BlockState southEastBelow = world.getBlockState(southEast.down());

			if (canPlaceSapling(world, anchor, saplingState)
				&& canPlaceSapling(world, east, saplingState)
				&& canPlaceSapling(world, south, saplingState)
				&& canPlaceSapling(world, southEast, saplingState)) {
				schedulePlant(world, anchor, saplingState, anchorBelow);
				schedulePlant(world, east, saplingState, eastBelow);
				schedulePlant(world, south, saplingState, southBelow);
				schedulePlant(world, southEast, saplingState, southEastBelow);
			}
			return;
		}

		if (canPlaceSapling(world, base, saplingState)) {
			BlockState belowState = world.getBlockState(base.down());
			schedulePlant(world, base, saplingState, belowState);
		}
	}

	private static BlockState resolveSaplingState(BlockState logState) {
		if (logState.isIn(BlockTags.OAK_LOGS)) {
			return Blocks.OAK_SAPLING.getDefaultState();
		}
		if (logState.isIn(BlockTags.SPRUCE_LOGS)) {
			return Blocks.SPRUCE_SAPLING.getDefaultState();
		}
		if (logState.isIn(BlockTags.BIRCH_LOGS)) {
			return Blocks.BIRCH_SAPLING.getDefaultState();
		}
		if (logState.isIn(BlockTags.JUNGLE_LOGS)) {
			return Blocks.JUNGLE_SAPLING.getDefaultState();
		}
		if (logState.isIn(BlockTags.ACACIA_LOGS)) {
			return Blocks.ACACIA_SAPLING.getDefaultState();
		}
		if (logState.isIn(BlockTags.DARK_OAK_LOGS)) {
			return Blocks.DARK_OAK_SAPLING.getDefaultState();
		}
		if (logState.isIn(BlockTags.CHERRY_LOGS)) {
			return Blocks.CHERRY_SAPLING.getDefaultState();
		}
		BlockState paleOakSapling = getOptionalPaleOakSapling();
		if (paleOakSapling != null && isPaleOakLog(logState)) {
			return paleOakSapling;
		}
		return null;
	}

	private static boolean requiresTwoByTwo(BlockState logState) {
		return logState.isIn(BlockTags.DARK_OAK_LOGS)
			|| logState.isIn(BlockTags.SPRUCE_LOGS)
			|| logState.isIn(BlockTags.JUNGLE_LOGS)
			|| isPaleOakLog(logState);
	}

	private static boolean isPaleOakLog(BlockState state) {
		return state.isIn(PALE_OAK_LOGS_TAG);
	}

	private static BlockState getOptionalPaleOakSapling() {
		if (!Registries.BLOCK.getIds().contains(PALE_OAK_SAPLING_ID)) {
			return null;
		}
		Block block = Registries.BLOCK.get(PALE_OAK_SAPLING_ID);
		return block == Blocks.AIR ? null : block.getDefaultState();
	}

	private static BlockPos findTwoByTwoAnchor(Set<BlockPos> logs, BlockPos base) {
		int y = base.getY();
		for (int dx = -1; dx <= 0; dx++) {
			for (int dz = -1; dz <= 0; dz++) {
				BlockPos anchor = new BlockPos(base.getX() + dx, y, base.getZ() + dz);
				if (logs.contains(anchor)
					&& logs.contains(anchor.add(1, 0, 0))
					&& logs.contains(anchor.add(0, 0, 1))
					&& logs.contains(anchor.add(1, 0, 1))) {
					return anchor;
				}
			}
		}
		return null;
	}

	private static void schedulePlant(ServerWorld world, BlockPos pos, BlockState saplingState, BlockState belowState) {
		PendingKey key = new PendingKey(world.getRegistryKey(), pos.toImmutable());
		if (PENDING_PLANTS.containsKey(key)) {
			return;
		}

		BlockPos hopperPos = pos.down();
		world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

		if (!world.getBlockState(hopperPos).isOf(Blocks.HOPPER)) {
			BlockState hopperState = Blocks.HOPPER.getDefaultState().with(HopperBlock.FACING, Direction.DOWN);
			world.setBlockState(hopperPos, hopperState, Block.NOTIFY_ALL);
		}

		PENDING_PLANTS.put(key, new PendingPlant(saplingState, belowState, world.getTime()));
	}

	private static boolean canPlaceSapling(ServerWorld world, BlockPos pos, BlockState saplingState) {
		if (!world.isAir(pos)) {
			return false;
		}
		return saplingState.canPlaceAt(world, pos);
	}

	private static boolean hasItemsOnColumn(ServerWorld world, BlockPos soilPos) {
		double minX = soilPos.getX() + 0.05;
		double minZ = soilPos.getZ() + 0.05;
		double maxX = soilPos.getX() + 0.95;
		double maxZ = soilPos.getZ() + 0.95;
		double minY = soilPos.getY();
		double maxY = soilPos.getY() + 1.5;

		Box columnBox = new Box(minX, minY, minZ, maxX, maxY, maxZ);
		return !world.getEntitiesByClass(
			ItemEntity.class,
			columnBox,
			item -> !item.isRemoved()
		).isEmpty();
	}

	private static boolean isLowerChainReady(ServerWorld world, BlockPos startHopperPos) {
		BlockPos current = startHopperPos.down();
		int depth = 0;
		while (depth < 8) {
			BlockState state = world.getBlockState(current);
			if (!state.isOf(Blocks.HOPPER)) {
				return true;
			}

			BlockEntity blockEntity = world.getBlockEntity(current);
			if (!(blockEntity instanceof HopperBlockEntity hopper)) {
				return false;
			}

			boolean changed = HopperBlockEntity.extract(world, hopper);
			List<ItemEntity> aboveItems = HopperBlockEntity.getInputItemEntities(world, hopper);
			for (ItemEntity item : aboveItems) {
				if (item.isRemoved()) {
					continue;
				}
				if (HopperBlockEntity.extract(hopper, item)) {
					changed = true;
				}
			}
			if (changed) {
				HopperBlockEntity.extract(world, hopper);
			}

			if (!hopper.isEmpty() || hasItemsOnColumn(world, current.up())) {
				return false;
			}

			current = current.down();
			depth++;
		}
		return true;
	}

	private static boolean withinRadius(BlockPos center, BlockPos pos, int radius) {
		return Math.abs(pos.getX() - center.getX()) <= radius
			&& Math.abs(pos.getZ() - center.getZ()) <= radius;
	}

	public static void tick(ServerWorld world) {
		Iterator<Map.Entry<PendingKey, PendingPlant>> iterator = PENDING_PLANTS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<PendingKey, PendingPlant> entry = iterator.next();
			PendingKey key = entry.getKey();
			PendingPlant pending = entry.getValue();
			if (!key.matches(world)) {
				continue;
			}

			if (world.getTime() - pending.createdTick < REPLANT_DELAY_TICKS) {
				continue;
			}

			BlockPos soilPos = key.pos();
			BlockPos hopperPos = soilPos.down();

			if (!world.getBlockState(soilPos).isAir()) {
				iterator.remove();
				continue;
			}

			BlockState hopperState = world.getBlockState(hopperPos);
			if (!hopperState.isOf(Blocks.HOPPER)) {
				iterator.remove();
				continue;
			}

			BlockEntity blockEntity = world.getBlockEntity(hopperPos);
			if (!(blockEntity instanceof HopperBlockEntity hopper)) {
				world.setBlockState(hopperPos, pending.belowState, Block.NOTIFY_ALL);
				iterator.remove();
				continue;
			}

			boolean changed = false;
			if (HopperBlockEntity.extract(world, hopper)) {
				changed = true;
			}

			List<ItemEntity> aboveItems = HopperBlockEntity.getInputItemEntities(world, hopper);
			for (ItemEntity item : aboveItems) {
				if (item.isRemoved()) {
					continue;
				}
				if (HopperBlockEntity.extract(hopper, item)) {
					changed = true;
				}
			}
			if (changed) {
				HopperBlockEntity.extract(world, hopper);
			}

			boolean itemsRemainAbove = hasItemsOnColumn(world, soilPos);
			if (!hopper.isEmpty() || itemsRemainAbove || !isLowerChainReady(world, hopperPos)) {
				continue;
			}

			HopperBlockEntity.extract(world, hopper);
			BlockState storedBelow = pending.belowState;
			world.setBlockState(hopperPos, storedBelow, Block.NOTIFY_ALL);
			if (pending.saplingState.canPlaceAt(world, soilPos)) {
				world.setBlockState(soilPos, pending.saplingState, Block.NOTIFY_ALL);
			} else {
				Block.dropStack(world, soilPos, new ItemStack(pending.saplingState.getBlock()));
			}
			iterator.remove();
		}
	}
}
