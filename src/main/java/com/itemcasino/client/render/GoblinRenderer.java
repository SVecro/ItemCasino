package com.itemcasino.client.render;

import com.itemcasino.ItemCasino;
import com.itemcasino.entity.GamblerGoblin;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

/**
 * The goblin, drawn with the humanoid rig everything from a zombie to a player uses.
 *
 * <p>A bespoke model would be a mesh, a layer definition, a render state and a pile of animation
 * code to maintain across versions, for a character who stands still and waves his arms. Borrowing
 * the rig costs one texture and gets walking, swinging and head-tracking for free; what makes him a
 * goblin is that he is two thirds the height of a player and wears the casino's own palette.
 */
public class GoblinRenderer
        extends HumanoidMobRenderer<GamblerGoblin, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {

    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(ItemCasino.id("gambler_goblin"), "main");

    private static final Identifier TEXTURE = ItemCasino.id("textures/entity/gambler_goblin.png");

    public GoblinRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(LAYER)), 0.3F);
    }

    /** Scaled down on the mesh rather than in the render, so his hitbox and his model agree. */
    public static LayerDefinition createLayer() {
        return LayerDefinition.create(
                HumanoidModel.createMesh(new CubeDeformation(-0.6F), 0.0F), 64, 64);
    }

    @Override
    public HumanoidRenderState createRenderState() {
        return new HumanoidRenderState();
    }

    @Override
    public Identifier getTextureLocation(HumanoidRenderState state) {
        return TEXTURE;
    }
}
