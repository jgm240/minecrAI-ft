package dev.minecraft;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public final class ModEntities {
	private static final Identifier ID = Identifier.of(MinecrAIft.MOD_ID, "ai_player");

	public static final EntityType<AiPlayerEntity> AI_PLAYER = Registry.register(
		Registries.ENTITY_TYPE, ID,
		EntityType.Builder.create(AiPlayerEntity::new, SpawnGroup.MISC)
			.dimensions(0.6f, 1.8f)
			.maxTrackingRange(10)
			.build(RegistryKey.of(RegistryKeys.ENTITY_TYPE, ID)));

	private ModEntities() {}

	public static void register() {
		FabricDefaultAttributeRegistry.register(AI_PLAYER, AiPlayerEntity.createAttributes());
	}
}
