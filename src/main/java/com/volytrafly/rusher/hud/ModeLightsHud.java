/*
 * Flight, autopilot, highway, avoidance, and HUD behaviour ported from
 * VolytraFly-Addon by Volizray (https://github.com/Volizray/VolytraFly-Addon),
 * originally a Meteor Client addon. That project is dedicated to the public
 * domain under CC0 1.0. See the LICENSE and NOTICE files in this repository.
 */

package com.volytrafly.rusher.hud;

import com.volytrafly.rusher.module.VolytraFlyModule;
import org.rusherhack.client.api.feature.hud.HudElement;
import org.rusherhack.client.api.render.IRenderer2D;
import org.rusherhack.client.api.render.RenderContext;
import org.rusherhack.client.api.render.font.IFontRenderer;

import java.awt.Color;

/**
 * Lights for Mapping, Building, Bypass, Avoidance, Anti Slam, Autopilot, and Highway Mode.
 */
public class ModeLightsHud extends HudElement {
	private static final int MAX_ROWS = 5;
	private static final String[] LABELS = {"Mapping", "Building", "Bypass", "Avoidance", "Anti Slam", "Autopilot", "Highway Mode"};
	private final CrtState crt = new CrtState();

	public ModeLightsHud() {
		super("VolytraFly Switches", "Lights for each VolytraFly mode.");
	}

	@Override
	public boolean shouldDrawBackground() {
		return false;
	}

	@Override
	public void renderContent(RenderContext context, double mouseX, double mouseY) {
		VolytraFlyModule module = VolytraFlyModule.get();
		boolean live = module != null && module.isToggled() && module.modeLights.getValue();
		crt.update(live);
		if (!crt.visible()) {
			if (isToggled() && !live) setToggled(false);
			return;
		}
		double width = getWidth();
		double height = getHeight();
		IRenderer2D renderer = getRenderer();
		IFontRenderer font = getFontRenderer();
		Color base = module != null ? module.speedometerColor.getValue() : new Color(255, 128, 0);
		double alpha = crt.alpha();
		int border = HudDraw.withAlpha(HudDraw.argb(base.getRed(), base.getGreen(), base.getBlue(), 230), alpha);
		if (crt.linePhase()) {
			renderer.drawRectangle(0, height / 2, width * crt.lineFraction(), 2, border);
			return;
		}
		renderer.drawRoundedRectangle(1, 1, width - 2, height - 2, 8, 1.5f, 0, border);

		boolean[] on = states(module, live);
		double rowHeight = font.getFontHeight() + 8;
		int rows = Math.min(MAX_ROWS, LABELS.length);
		int columns = (int) Math.ceil(LABELS.length / (double) rows);
		double columnWidth = width / columns;
		for (int i = 0; i < LABELS.length; i++) {
			int column = i / rows;
			int row = i % rows;
			double x = 12 + column * columnWidth;
			double y = 12 + row * rowHeight;
			int light = on[i]
				? HudDraw.withAlpha(HudDraw.argb(base.getRed(), base.getGreen(), base.getBlue(), 255), alpha)
				: HudDraw.withAlpha(HudDraw.argb(base.getRed(), base.getGreen(), base.getBlue(), 60), alpha);
			renderer.drawCircle(x + 5, y + font.getFontHeight() / 2.0, 5, light);
			int text = HudDraw.withAlpha(HudDraw.argb(
				HudDraw.mix(base.getRed(), 255, on[i] ? 0.25 : 0),
				HudDraw.mix(base.getGreen(), 255, on[i] ? 0.25 : 0),
				HudDraw.mix(base.getBlue(), 255, on[i] ? 0.25 : 0),
				on[i] ? 235 : 140), alpha);
			font.drawString(LABELS[i], x + 16, y, text, false);
		}
		if (module == null || module.crtOverlay.getValue()) HudDraw.scanlines(renderer, width, height, 0.75 * alpha);
		if (module != null && module.autoArrangeHud.getValue()) arrange();
	}

	private void arrange() {
		SpeedometerHud speedometer = VolytraHuds.speedometer;
		if (speedometer == null || !speedometer.isToggled()) return;
		double inset = SpeedometerHud.DIAL_INSET * (speedometer.getWidth() / SpeedometerHud.BASE_SIZE);
		setStartX(speedometer.getStartX() + inset);
		setStartY(speedometer.getStartY() - getScaledHeight() - 6);
	}

	private static boolean[] states(VolytraFlyModule module, boolean live) {
		if (module == null || !live) return new boolean[]{true, false, true, false, true, false, true};
		return new boolean[]{
			module.mappingMode.getValue(),
			module.buildingMode.getValue(),
			module.bypassMode.getValue(),
			module.playerAvoidance.getValue(),
			module.antiSlam.getValue(),
			module.autoPilot.getValue(),
			module.highwayMode.getValue()
		};
	}

	@Override
	public double getWidth() {
		VolytraFlyModule module = VolytraFlyModule.get();
		double speedometerScale = module != null ? module.speedometerScale.getValue() : 2.0;
		return Math.max(160, SpeedometerHud.DIAL_RADIUS * 2 * speedometerScale);
	}

	@Override
	public double getHeight() {
		int rows = Math.min(MAX_ROWS, LABELS.length);
		double row = getFontRenderer().getFontHeight() + 8;
		return 16 + rows * row;
	}
}
