package chihalu.automated.tree.harvesting.logic;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

public final class TreeHarvestManager {
	private static final int MAX_LOGS = 512;
	private static final int MAX_LEAVES = 2048;
	private static final int HORIZONTAL_RADIUS = 6;
	private static final int VERTICAL_BELOW = 4;
	private static final int VERTICAL_ABOVE = 32;

	private TreeHarvestManager() {
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

		boolean harvestedLogs = breakBlocks(world, logs, tool, frame);
		boolean harvestedLeaves = !leaves.isEmpty() && breakBlocks(world, leaves, leafTool, frame);

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

	private static boolean breakBlocks(ServerWorld world, Set<BlockPos> positions, ItemStack tool, ItemFrameEntity frame) {
		boolean brokeAny = false;

		for (BlockPos pos : positions) {
			BlockState state = world.getBlockState(pos);
			if (state.isAir()) {
				continue;
			}

			BlockEntity blockEntity = world.getBlockEntity(pos);
			Block.dropStacks(state, world, pos, blockEntity, frame, tool);
			world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
			world.syncWorldEvent(null, 2001, pos, Block.getRawIdFromState(state));
			brokeAny = true;
		}

		return brokeAny;
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
			if (canPlaceSapling(world, anchor, saplingState)
				&& canPlaceSapling(world, anchor.add(1, 0, 0), saplingState)
				&& canPlaceSapling(world, anchor.add(0, 0, 1), saplingState)
				&& canPlaceSapling(world, anchor.add(1, 0, 1), saplingState)) {
				world.setBlockState(anchor, saplingState, Block.NOTIFY_ALL);
				world.setBlockState(anchor.add(1, 0, 0), saplingState, Block.NOTIFY_ALL);
				world.setBlockState(anchor.add(0, 0, 1), saplingState, Block.NOTIFY_ALL);
				world.setBlockState(anchor.add(1, 0, 1), saplingState, Block.NOTIFY_ALL);
			}
			return;
		}

		if (canPlaceSapling(world, base, saplingState)) {
			world.setBlockState(base, saplingState, Block.NOTIFY_ALL);
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
		if (logState.isIn(BlockTags.PALE_OAK_LOGS)) {
			return Blocks.PALE_OAK_SAPLING.getDefaultState();
		}
		return null;
	}

	private static boolean requiresTwoByTwo(BlockState logState) {
		return logState.isIn(BlockTags.DARK_OAK_LOGS)
			|| logState.isIn(BlockTags.SPRUCE_LOGS)
			|| logState.isIn(BlockTags.JUNGLE_LOGS)
			|| logState.isIn(BlockTags.PALE_OAK_LOGS);
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

	private static boolean canPlaceSapling(ServerWorld world, BlockPos pos, BlockState saplingState) {
		if (!world.isAir(pos)) {
			return false;
		}
		return saplingState.canPlaceAt(world, pos);
	}

	private static boolean withinRadius(BlockPos center, BlockPos pos, int radius) {
		return Math.abs(pos.getX() - center.getX()) <= radius
			&& Math.abs(pos.getZ() - center.getZ()) <= radius;
	}
}
