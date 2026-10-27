/*
 * Flight, autopilot, highway, avoidance, and HUD behaviour ported from
 * VolytraFly-Addon by Volizray (https://github.com/Volizray/VolytraFly-Addon),
 * originally a Meteor Client addon. That project is dedicated to the public
 * domain under CC0 1.0. See the LICENSE and NOTICE files in this repository.
 *
 * The original draws a screen-space ring through Meteor's NametagUtils and a
 * triangle CRT sink. RusherHack's 3D line renderer draws the same predicted
 * point as a camera-facing ring in the world. Prediction (smoothing, gravity,
 * and collision) matches hud/FutureSightVisual.java.
 */

package com.volytrafly.rusher.hud;

import com.volytrafly.rusher.module.VolytraFlyModule;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.rusherhack.client.api.Globals;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.render.EventRender3D;
import org.rusherhack.client.api.render.IRenderer3D;

import java.awt.Color;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class FutureSightVisual implements Globals {
	private static final int CIRCLE_SEGMENTS = 28;
	private static final double STUB_FRACTION = 0.55;
	private static final int LOOKAHEAD_TICKS = 20;
	private static final double VELOCITY_SMOOTHING = 0.4;
	private static final double GRAVITY = 0.08;
	private static final double VERTICAL_DRAG = 0.98;
	private static final double RANGE = 256.0;
	private static final double SIZE_MULTIPLIER = 1.3;
	private static final double ROTATION_SPEED = 90.0;
	private static final double PULSE_PERIOD = 3.0;

	private final Map<UUID, Vec3> velocity = new HashMap<>();
	private final Map<UUID, Vec3> predictedOffset = new HashMap<>();
	private final Map<UUID, Vec3> previous = new HashMap<>();
	private final CrtState crt = new CrtState();

	public void tick(VolytraFlyModule module) {
		ClientLevel world = mc.level;
		boolean live = module != null && module.isToggled() && module.futureSight.getValue();
		crt.update(live);
		if (world == null || !(live || crt.visible())) {
			velocity.clear();
			predictedOffset.clear();
			previous.clear();
			return;
		}
		Set<UUID> present = new HashSet<>();
		for (Player player : world.players()) {
			if (player == mc.player) continue;
			UUID id = player.getUUID();
			present.add(id);
			Vec3 last = previous.get(id);
			Vec3 moved = last == null ? Vec3.ZERO : player.position().subtract(last);
			previous.put(id, player.position());
			Vec3 smoothed = velocity.compute(id, (key, prior) -> prior == null
				? moved
				: prior.scale(1 - VELOCITY_SMOOTHING).add(moved.scale(VELOCITY_SMOOTHING)));
			predictedOffset.put(id, predictOffset(world, player, smoothed));
		}
		velocity.keySet().retainAll(present);
		predictedOffset.keySet().retainAll(present);
		previous.keySet().retainAll(present);
	}

	private Vec3 predictOffset(ClientLevel world, Player player, Vec3 perTick) {
		boolean falling = !player.onGround() && !VolytraFlyModule.gliding(player) && !player.isInWater()
			&& !player.isInLava() && !player.onClimbable() && !player.isNoGravity();
		if (!falling) return perTick.scale(LOOKAHEAD_TICKS);

		AABB box = player.getBoundingBox();
		double vx = perTick.x, vy = perTick.y, vz = perTick.z;
		double dx = 0, dy = 0, dz = 0;
		for (int i = 0; i < LOOKAHEAD_TICKS; i++) {
			vy = (vy - GRAVITY) * VERTICAL_DRAG;
			if (vx != 0) {
				if (world.noCollision(player, box.move(dx + vx, dy, dz))) dx += vx;
				else vx = 0;
			}
			if (vz != 0) {
				if (world.noCollision(player, box.move(dx, dy, dz + vz))) dz += vz;
				else vz = 0;
			}
			if (world.noCollision(player, box.move(dx, dy + vy, dz))) dy += vy;
			else vy = 0;
		}
		return new Vec3(dx, dy, dz);
	}

	public void render(EventRender3D event, VolytraFlyModule module) {
		if (module == null || mc.player == null || mc.level == null) return;
		boolean live = module.isToggled() && module.futureSight.getValue();
		crt.update(live);
		if (!crt.visible()) return;

		Color colour = module.speedometerColor.getValue();
		int alpha = (int) Math.round(colour.getAlpha() * crt.alpha());
		int color = HudDraw.argb(colour.getRed(), colour.getGreen(), colour.getBlue(), alpha);
		double nowSeconds = System.nanoTime() / 1_000_000_000.0;
		double rotation = Math.toRadians((nowSeconds * ROTATION_SPEED) % 360.0);
		double pulse = 1 + 0.125 * Math.sin((2 * Math.PI * nowSeconds) / PULSE_PERIOD);
		double rangeSq = RANGE * RANGE;
		Vec3 camera = mc.gameRenderer.getMainCamera().position();

		IRenderer3D renderer = event.getRenderer();
		renderer.begin(event.getMatrixStack());
		renderer.setDepthTest(false);
		for (Player player : mc.level.players()) {
			if (player == mc.player) continue;
			if (module.futureSightIgnoreFriends.getValue() && RusherHackAPI.getRelationManager().isFriend(player)) continue;
			if (mc.player.position().distanceToSqr(player.position()) > rangeSq) continue;

			double partial = event.getPartialTicks();
			Vec3 pos = new Vec3(
				lerp(player.xOld, player.getX(), partial),
				lerp(player.yOld, player.getY(), partial) + player.getBbHeight() / 2.0,
				lerp(player.zOld, player.getZ(), partial)
			);
			Vec3 offset = predictedOffset.getOrDefault(player.getUUID(), Vec3.ZERO);
			Vec3 future = pos.add(offset);
			double radius = (player.getBbWidth() / 2.0) * SIZE_MULTIPLIER * pulse;

			Vec3 toCamera = camera.subtract(future);
			if (toCamera.lengthSqr() < 1.0E-6) continue;
			Vec3 forward = toCamera.normalize();
			Vec3 worldUp = new Vec3(0, 1, 0);
			Vec3 right = forward.cross(worldUp);
			if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0);
			right = right.normalize();
			Vec3 up = right.cross(forward).normalize();

			drawRing(renderer, future, right, up, radius, rotation, color);
			Vec3 from = pos;
			Vec3 toward = future.subtract(from);
			double distance = toward.length();
			if (distance - radius > 0.15) {
				Vec3 end = from.add(toward.normalize().scale(distance - radius));
				drawDashed(renderer, from, end, color);
			}
		}
		renderer.end();
	}

	private static void drawRing(IRenderer3D renderer, Vec3 center, Vec3 right, Vec3 up, double radius, double rotation, int color) {
		Vec3 previous = null;
		for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
			double angle = rotation + (2 * Math.PI * i) / CIRCLE_SEGMENTS;
			Vec3 point = center.add(right.scale(Math.cos(angle) * radius)).add(up.scale(Math.sin(angle) * radius));
			if (previous != null) renderer.drawLine(previous, point, 2.5f, color);
			previous = point;
		}
		double stub = radius * STUB_FRACTION;
		for (int i = 0; i < 4; i++) {
			double angle = rotation + (Math.PI / 2) * i;
			Vec3 dir = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
			renderer.drawLine(center.add(dir.scale(radius)), center.add(dir.scale(radius + stub)), 2.5f, color);
		}
	}

	private static void drawDashed(IRenderer3D renderer, Vec3 from, Vec3 to, int color) {
		Vec3 delta = to.subtract(from);
		double length = delta.length();
		if (length < 1.0E-4) return;
		Vec3 step = delta.scale(1 / length);
		double dash = 0.45;
		for (double start = 0; start < length; start += dash * 2) {
			double end = Math.min(start + dash, length);
			renderer.drawLine(from.add(step.scale(start)), from.add(step.scale(end)), 2f, color);
		}
	}

	private static double lerp(double from, double to, double t) {
		return from + (to - from) * t;
	}
}
