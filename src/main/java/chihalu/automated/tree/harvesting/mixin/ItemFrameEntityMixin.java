package chihalu.automated.tree.harvesting.mixin;

import chihalu.automated.tree.harvesting.access.HarvestableItemFrame;
import net.minecraft.entity.decoration.ItemFrameEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ItemFrameEntity.class)
public class ItemFrameEntityMixin implements HarvestableItemFrame {
	@Unique
	private long automatedTreeHarvesting$lastCheckTime = Long.MIN_VALUE;

	@Override
	public boolean automated_tree_harvesting$shouldProcess(long worldTime) {
		if (automatedTreeHarvesting$lastCheckTime == Long.MIN_VALUE) {
			automatedTreeHarvesting$lastCheckTime = worldTime;
			return true;
		}
		if (worldTime - automatedTreeHarvesting$lastCheckTime < 20) {
			return false;
		}
		automatedTreeHarvesting$lastCheckTime = worldTime;
		return true;
	}
}
