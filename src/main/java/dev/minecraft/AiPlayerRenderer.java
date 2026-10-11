package dev.minecraft;

import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.WeakHashMap;

/** Player model with one of the vanilla default skins, chosen from the entity's UUID. */
public class AiPlayerRenderer extends BipedEntityRenderer<AiPlayerEntity, PlayerEntityRenderState, PlayerEntityModel> {
	private static final String[] SKINS = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};
	private static final Map<PlayerEntityRenderState, Identifier> CHOSEN = new WeakHashMap<>();

	public AiPlayerRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new PlayerEntityModel(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
	}

	@Override
	public PlayerEntityRenderState createRenderState() {
		return new PlayerEntityRenderState();
	}

	@Override
	public void updateRenderState(AiPlayerEntity entity, PlayerEntityRenderState state, float tickDelta) {
		super.updateRenderState(entity, state, tickDelta);
		int i = Math.floorMod(entity.getUuid().hashCode(), SKINS.length);
		CHOSEN.put(state, Identifier.of("minecraft", "textures/entity/player/wide/" + SKINS[i] + ".png"));
	}

	@Override
	public Identifier getTexture(PlayerEntityRenderState state) {
		return CHOSEN.getOrDefault(state, Identifier.of("minecraft", "textures/entity/player/wide/steve.png"));
	}
}
