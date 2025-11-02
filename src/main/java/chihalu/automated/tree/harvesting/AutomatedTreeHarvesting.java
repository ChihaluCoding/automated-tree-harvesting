package chihalu.automated.tree.harvesting;

import chihalu.automated.tree.harvesting.access.HarvestableItemFrame;
import chihalu.automated.tree.harvesting.logic.TreeHarvestManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.TypeFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutomatedTreeHarvesting implements ModInitializer {
	public static final String MOD_ID = "automated-tree-harvesting";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerTickEvents.END_WORLD_TICK.register(this::handleWorldTick);
		LOGGER.info("Automated tree harvesting enabled");
	}

	private void handleWorldTick(ServerWorld world) {
		long time = world.getTime();
		world.getEntitiesByType(
			TypeFilter.instanceOf(ItemFrameEntity.class),
			Entity::isAlive
		).forEach(frame -> {
			if (((HarvestableItemFrame) frame).automated_tree_harvesting$shouldProcess(time)) {
				TreeHarvestManager.onFrameTick(world, frame);
			}
		});
	}
}
