/*
 * Flight, autopilot, highway, avoidance, and HUD behaviour ported from
 * VolytraFly-Addon by Volizray (https://github.com/Volizray/VolytraFly-Addon),
 * originally a Meteor Client addon. That project is dedicated to the public
 * domain under CC0 1.0. See the LICENSE and NOTICE files in this repository.
 */

package com.volytrafly.rusher.hud;

import com.volytrafly.rusher.module.VolytraFlyModule;
import com.volytrafly.rusher.util.InventoryActions;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.rusherhack.client.api.feature.hud.HudElement;
import org.rusherhack.client.api.render.IRenderer2D;
import org.rusherhack.client.api.render.RenderContext;
import org.rusherhack.client.api.render.font.IFontRenderer;

import java.awt.Color;

/**
 * Remaining durability on the worn elytra, drawn as the same 270 degree dial
 * as the speedometer. Low durability fades toward the secondary colour.
 */
public class ElytraFuelHud extends HudElement {
	static final double BASE_SIZE = 110;
	private static final double PREVIEW_FRACTION = 0.4;
	private final CrtState crt = new CrtState();
	private double shownFraction = 1;

	public ElytraFuelHud() {
		super("VolytraFly Fuel", "Elytra durability, drawn as a fuel gauge beside the speedometer.");
	}

	@Override
	public boolean shouldDrawBackground() {
		return false;
	}

	@Override
	public void renderContent(RenderContext context, double mouseX, double mouseY) {
		VolytraFlyModule module = VolytraFlyModule.get();
		boolean live = module != null && module.isToggled() && module.fuelGauge.getValue();
		crt.update(live);
		if (!crt.visible()) {
			if (isToggled() && !live) setToggled(false);
			return;
		}
		double scale = module != null ? module.fuelGaugeScale.getValue() : 1.25;
		double size = BASE_SIZE * scale;
		IRenderer2D renderer = getRenderer();
		IFontRenderer font = getFontRenderer();
		Color base = module != null ? module.speedometerColor.getValue() : new Color(255, 128, 0);
		Color warn = module != null ? module.greenColor.getValue() : new Color(0, 255, 70);
		double alpha = crt.alpha();
		if (crt.linePhase()) {
			double width = size * crt.lineFraction();
			renderer.drawRectangle((size - width) / 2, size / 2, width, 2, HudDraw.argb(base.getRed(), base.getGreen(), base.getBlue(), (int) (220 * alpha)));
			return;
		}

		double target = live ? durabilityFraction() : PREVIEW_FRACTION;
		shownFraction += (target - shownFraction) * 0.3;
		double fraction = Math.max(0, Math.min(1, shownFraction));
		// Empty is the warning colour. The original fades into secondary over the last quarter.
		double warnT = fraction < 0.25 ? 1 - (fraction / 0.25) : 0;
		int r = HudDraw.mix(base.getRed(), warn.getRed(), warnT);
		int g = HudDraw.mix(base.getGreen(), warn.getGreen(), warnT);
		int b = HudDraw.mix(base.getBlue(), warn.getBlue(), warnT);
		int lit = HudDraw.withAlpha(HudDraw.argb(r, g, b, 255), alpha);

		double cx = size / 2;
		double cy = size / 2;
		double outer = size / 2 - 8 * scale;
		HudDraw.arc(renderer, cx, cy, outer, HudDraw.START_ANGLE, HudDraw.START_ANGLE + HudDraw.SWEEP, Math.max(1.4f, (float) (3 * scale)), HudDraw.withAlpha(HudDraw.argb(base.getRed(), base.getGreen(), base.getBlue(), 80), alpha));
		HudDraw.sweep(renderer, cx, cy, outer, fraction, Math.max(2f, (float) (4 * scale)), lit);
		double angle = HudDraw.START_ANGLE + HudDraw.SWEEP * fraction;
		renderer.drawLine(cx, cy, cx + Math.cos(angle) * (outer - 10 * scale), cy + Math.sin(angle) * (outer - 10 * scale), 2f, lit);

		String reading = Math.round(fraction * 100) + "%";
		font.drawString(reading, cx - font.getStringWidth(reading) / 2, cy + outer * 0.35, lit, true);
		String unit = "Elytra";
		font.drawString(unit, cx - font.getStringWidth(unit) / 2, cy + outer * 0.35 + font.getFontHeight() + 1, HudDraw.withAlpha(HudDraw.argb(r, g, b, 200), alpha), false);
		if (module == null || module.crtOverlay.getValue()) HudDraw.scanlines(renderer, size, size, 0.75 * alpha);
		if (module != null && module.autoArrangeHud.getValue()) arrange(module, size);
	}

	private void arrange(VolytraFlyModule module, double size) {
		SpeedometerHud speedometer = VolytraHuds.speedometer;
		if (speedometer == null || !speedometer.isToggled()) return;
		setStartX(speedometer.getStartX() - size - 4);
		setStartY(speedometer.getStartY() + speedometer.getScaledHeight() - getScaledHeight());
	}

	private static double durabilityFraction() {
		if (mc.player == null) return 0;
		ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
		if (!chest.is(Items.ELYTRA) || chest.getMaxDamage() <= 0) return 0;
		return Math.max(0, Math.min(1, InventoryActions.durabilityLeft(chest) / (double) chest.getMaxDamage()));
	}

	@Override
	public double getWidth() {
		VolytraFlyModule module = VolytraFlyModule.get();
		double scale = module != null ? module.fuelGaugeScale.getValue() : 1.25;
		return BASE_SIZE * scale;
	}

	@Override
	public double getHeight() {
		return getWidth();
	}
}
