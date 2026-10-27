/*
 * Flight, autopilot, highway, avoidance, and HUD behaviour ported from
 * VolytraFly-Addon by Volizray (https://github.com/Volizray/VolytraFly-Addon),
 * originally a Meteor Client addon. That project is dedicated to the public
 * domain under CC0 1.0. See the LICENSE and NOTICE files in this repository.
 */

package com.volytrafly.rusher.hud;

import com.volytrafly.rusher.module.VolytraFlyModule;
import net.minecraft.world.entity.Entity;
import org.rusherhack.client.api.feature.hud.HudElement;
import org.rusherhack.client.api.render.IRenderer2D;
import org.rusherhack.client.api.render.RenderContext;
import org.rusherhack.client.api.render.font.IFontRenderer;

import java.awt.Color;
import java.util.Locale;

/**
 * Horizontal speed in blocks per second, from 0 up to maximum-horizontal-speed.
 * The needle follows the camera entity's real movement, and rubberband teleports
 * are ignored the same way as the Meteor speedometer.
 */
public class SpeedometerHud extends HudElement {
	public static final double BASE_SIZE = 190;
	public static final double DIAL_INSET = 10;
	public static final double DIAL_RADIUS = BASE_SIZE / 2 - DIAL_INSET;
	private static final double TICKS_PER_SECOND = 20;
	private static final double PREVIEW_FRACTION = 0.62;
	private static final int IGNORE_TICKS_AFTER_CORRECTION = 3;
	private static final double MIN_PLAUSIBLE_BLOCKS_PER_TICK = 8;

	private final CrtState crt = new CrtState();
	private double shownSpeed;
	private double measuredSpeed;
	private Entity lastEntity;
	private double lastX, lastZ;
	private int lastCorrections;
	private int ignoreTicks;
	private boolean placed;

	public SpeedometerHud() {
		super("VolytraFly Speedometer", "Horizontal speed in blocks per second, up to maximum-horizontal-speed.");
	}

	@Override
	public boolean shouldDrawBackground() {
		return false;
	}

	@Override
	public void tick() {
		Entity entity = mc.getCameraEntity();
		if (entity == null) entity = mc.player;
		if (entity == null) {
			lastEntity = null;
			measuredSpeed = 0;
			return;
		}
		double px = entity.getX();
		double pz = entity.getZ();
		VolytraFlyModule module = VolytraFlyModule.get();
		if (module != null) {
			int corrections = module.getPositionCorrections();
			if (corrections != lastCorrections) {
				lastCorrections = corrections;
				ignoreTicks = IGNORE_TICKS_AFTER_CORRECTION;
			}
		}
		if (entity != lastEntity) measuredSpeed = 0;
		else {
			double moved = Math.hypot(px - lastX, pz - lastZ);
			double maxPlausible = Math.max(MIN_PLAUSIBLE_BLOCKS_PER_TICK, (module != null ? module.horizontalSpeed.getValue() : 15) * 2);
			if (ignoreTicks > 0) ignoreTicks--;
			else if (moved <= maxPlausible) measuredSpeed = moved;
		}
		lastEntity = entity;
		lastX = px;
		lastZ = pz;
	}

	@Override
	public void renderContent(RenderContext context, double mouseX, double mouseY) {
		VolytraFlyModule module = VolytraFlyModule.get();
		boolean live = module != null && module.isToggled() && module.speedometer.getValue();
		crt.update(live);
		if (!crt.visible()) {
			if (isToggled() && !live) setToggled(false);
			return;
		}
		placeDefault();

		double scale = module != null ? module.speedometerScale.getValue() : 2.0;
		double size = BASE_SIZE * scale;
		IRenderer2D renderer = getRenderer();
		IFontRenderer font = getFontRenderer();
		Color base = module != null ? module.speedometerColor.getValue() : new Color(255, 128, 0);
		Color green = module != null ? module.greenColor.getValue() : new Color(0, 255, 70);
		double alpha = crt.alpha();

		if (crt.linePhase()) {
			double width = size * crt.lineFraction();
			renderer.drawRectangle((size - width) / 2, size / 2, width, 2, HudDraw.argb(base.getRed(), base.getGreen(), base.getBlue(), (int) (220 * alpha)));
			return;
		}

		double max = (module != null ? module.horizontalSpeed.getValue() : 15) * TICKS_PER_SECOND;
		if (max < 0.001) max = 0.001;
		double target = live ? measuredSpeed * TICKS_PER_SECOND : max * PREVIEW_FRACTION;
		shownSpeed += (target - shownSpeed) * 0.35;
		double fraction = Math.max(0, Math.min(1, shownSpeed / max));

		double cx = size / 2;
		double cy = size / 2;
		double outer = DIAL_RADIUS * scale;
		int track = HudDraw.withAlpha(HudDraw.argb(base.getRed(), base.getGreen(), base.getBlue(), 90), alpha);
		HudDraw.arc(renderer, cx, cy, outer, HudDraw.START_ANGLE, HudDraw.START_ANGLE + HudDraw.SWEEP, Math.max(1.5f, (float) (4 * scale)), track);

		for (int i = 0; i <= 10; i++) {
			double f = i / 10.0;
			double angle = HudDraw.START_ANGLE + HudDraw.SWEEP * f;
			boolean major = i % 2 == 0;
			double len = (major ? 10 : 6) * scale;
			int[] zone = zone(f, base, green);
			int tick = HudDraw.withAlpha(HudDraw.argb(zone[0], zone[1], zone[2], f <= fraction ? 230 : 110), alpha);
			double x1 = cx + Math.cos(angle) * (outer - len);
			double y1 = cy + Math.sin(angle) * (outer - len);
			double x2 = cx + Math.cos(angle) * (outer - 6 * scale);
			double y2 = cy + Math.sin(angle) * (outer - 6 * scale);
			renderer.drawLine(x1, y1, x2, y2, major ? 2f : 1.2f, tick);
			if (major) {
				String label = formatSpeed(max * f, max);
				double lx = cx + Math.cos(angle) * (outer - len - 14 * scale) - font.getStringWidth(label) / 2;
				double ly = cy + Math.sin(angle) * (outer - len - 14 * scale) - font.getFontHeight() / 2;
				font.drawString(label, lx, ly, tick, false);
			}
		}

		int[] needleZone = zone(fraction, base, green);
		int lit = HudDraw.withAlpha(HudDraw.argb(needleZone[0], needleZone[1], needleZone[2], 255), alpha);
		HudDraw.sweep(renderer, cx, cy, outer - 2 * scale, fraction, Math.max(2f, (float) (5 * scale)), lit);
		HudDraw.circle(renderer, cx, cy, outer * 0.42, Math.max(1.5f, (float) (2 * scale)), HudDraw.withAlpha(HudDraw.argb(base.getRed(), base.getGreen(), base.getBlue(), 220), alpha));

		double needleAngle = HudDraw.START_ANGLE + HudDraw.SWEEP * fraction;
		double needleLength = outer - 16 * scale;
		renderer.drawLine(cx, cy, cx + Math.cos(needleAngle) * needleLength, cy + Math.sin(needleAngle) * needleLength, Math.max(1.6f, (float) (2.2 * scale)), lit);
		renderer.drawCircle(cx, cy, 4 * scale, lit);

		String reading = formatSpeed(shownSpeed, max);
		font.drawString(reading, cx - font.getStringWidth(reading) / 2, cy + outer * 0.48, lit, true);
		String unit = "Blocks Per Second";
		int unitColor = HudDraw.withAlpha(HudDraw.argb(needleZone[0], needleZone[1], needleZone[2], 200), alpha);
		font.drawString(unit, cx - font.getStringWidth(unit) / 2, cy + outer * 0.48 + font.getFontHeight() + 2, unitColor, false);

		if (module == null || module.crtOverlay.getValue()) HudDraw.scanlines(renderer, size, size, 0.75 * alpha);
	}

	private void placeDefault() {
		if (placed || mc.getWindow() == null) return;
		placed = true;
		double expectedX = mc.getWindow().getGuiScaledWidth() * 0.25;
		double expectedY = mc.getWindow().getGuiScaledHeight() * 0.25;
		if (Math.abs(getX() - expectedX) < 3 && Math.abs(getY() - expectedY) < 3) {
			setStartX(mc.getWindow().getGuiScaledWidth() - getScaledWidth() - 8);
			setStartY(mc.getWindow().getGuiScaledHeight() - getScaledHeight() - 28);
		}
	}

	private static int[] zone(double fraction, Color base, Color green) {
		double t = Math.max(0, Math.min(1, (fraction - 0.75) / 0.25));
		return new int[]{
			HudDraw.mix(base.getRed(), green.getRed(), t),
			HudDraw.mix(base.getGreen(), green.getGreen(), t),
			HudDraw.mix(base.getBlue(), green.getBlue(), t)
		};
	}

	private static String formatSpeed(double value, double max) {
		if (max >= 10) return String.valueOf(Math.round(value));
		return String.format(Locale.ROOT, "%.1f", value);
	}

	@Override
	public double getWidth() {
		VolytraFlyModule module = VolytraFlyModule.get();
		double scale = module != null ? module.speedometerScale.getValue() : 2.0;
		return BASE_SIZE * scale;
	}

	@Override
	public double getHeight() {
		return getWidth();
	}
}
