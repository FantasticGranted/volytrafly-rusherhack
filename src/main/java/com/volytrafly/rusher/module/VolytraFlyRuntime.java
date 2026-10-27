/*
 * Flight, autopilot, highway, avoidance, and HUD behaviour ported from
 * VolytraFly-Addon by Volizray (https://github.com/Volizray/VolytraFly-Addon),
 * originally a Meteor Client addon. That project is dedicated to the public
 * domain under CC0 1.0. See the LICENSE and NOTICE files in this repository.
 */

package com.volytrafly.rusher.module;

import com.volytrafly.rusher.hud.FutureSightVisual;
import com.volytrafly.rusher.util.InventoryActions;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.rusherhack.client.api.Globals;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.player.EventMove;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.core.event.stage.Stage;
import org.rusherhack.core.event.subscribe.Subscribe;

/**
 * Listeners that must keep running after the module is toggled off.
 * Meteor subscribed these on the global event bus from {@code onDeactivate};
 * a toggled-off RusherHack module does not receive events, so they live here.
 */
public final class VolytraFlyRuntime implements Globals {
	private final FutureSightVisual futureSight = new FutureSightVisual();

	@Subscribe(stage = Stage.PRE)
	private void onMove(EventMove event) {
		VolytraFlyModule module = VolytraFlyModule.get();
		if (module == null || module.isToggled() || !module.awaitGroundChestSwap) return;
		LocalPlayer player = mc.player;
		if (player == null || !player.onGround()) return;
		ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
		if (InventoryActions.hasGlider(chest)) InventoryActions.swapChest();
		module.awaitGroundChestSwap = false;
	}

	@Subscribe(stage = Stage.POST)
	private void onTick(EventUpdate event) {
		VolytraFlyModule module = VolytraFlyModule.get();
		futureSight.tick(module);
		if (module == null || !module.instaDropActive) return;
		LocalPlayer player = mc.player;
		if (player != null && VolytraFlyModule.gliding(player) && player.connection != null) {
			player.setDeltaMovement(0, 0, 0);
			player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, player.horizontalCollision));
		} else if (module != null) {
			module.instaDropActive = false;
		}
	}

	@Subscribe(stage = Stage.POST)
	private void onRender3D(EventRender3D event) {
		futureSight.render(event, VolytraFlyModule.get());
	}
}
