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
import java.util.ArrayList;
import java.util.List;

/**
 * Speech box for {@link VolytraFlyModule#getAssistantText()}. The words, hold times,
 * and sorry/happy sign-offs are produced by the module, same as the Meteor assistant.
 */
public class VolytraAssistantHud extends HudElement {
	private static final String TITLE = "Volytra Assistant";
	private static final String SAMPLE = "Cruising along the East highway~";
	private static final int MESSAGE_LINES = 3;
	private static final double TYPE_CHARS_PER_SECOND = 45;

	private final CrtState crt = new CrtState();
	private String typedMessage = "";
	private double typedChars;
	private long lastNanos;

	public VolytraAssistantHud() {
		super("VolytraFly Assistant", "Explains what VolytraFly is doing.");
	}

	@Override
	public boolean shouldDrawBackground() {
		return false;
	}

	@Override
	public void renderContent(RenderContext context, double mouseX, double mouseY) {
		VolytraFlyModule module = VolytraFlyModule.get();
		boolean live = module != null && module.isToggled() && module.volytraAssistant.getValue();
		crt.update(live);
		if (!crt.visible()) {
			if (isToggled() && !live) setToggled(false);
			return;
		}

		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 0.016 : (now - lastNanos) / 1.0e9;
		lastNanos = now;

		String message = live ? module.getAssistantText() : SAMPLE;
		if (message == null) message = "";
		if (!message.equals(typedMessage)) {
			typedMessage = message;
			typedChars = 0;
		}
		typedChars = Math.min(message.length(), typedChars + dt * TYPE_CHARS_PER_SECOND);
		String shown = message.substring(0, Math.min(message.length(), (int) typedChars));

		double width = getWidth();
		double height = getHeight();
		IRenderer2D renderer = getRenderer();
		IFontRenderer font = getFontRenderer();
		Color base = module != null ? module.speedometerColor.getValue() : new Color(255, 128, 0);
		boolean sorry = live && module.isAssistantSorry();
		double alpha = crt.alpha();
		int border = HudDraw.withAlpha(HudDraw.argb(base.getRed(), base.getGreen(), base.getBlue(), 230), alpha);
		if (crt.linePhase()) {
			renderer.drawRectangle(0, height / 2, width * crt.lineFraction(), 2, border);
			return;
		}
		renderer.drawRoundedRectangle(1, 1, width - 2, height - 2, 8, 1.5f, 0, border);

		int title = HudDraw.withAlpha(HudDraw.argb(
			HudDraw.mix(base.getRed(), 0, 0.35),
			HudDraw.mix(base.getGreen(), 0, 0.35),
			HudDraw.mix(base.getBlue(), 0, 0.35), 220), alpha);
		font.drawString(TITLE, 12, 8, title, false);

		int body = sorry
			? HudDraw.withAlpha(HudDraw.argb(255, 120, 120, 255), alpha)
			: HudDraw.withAlpha(HudDraw.argb(
				HudDraw.mix(base.getRed(), 255, 0.25),
				HudDraw.mix(base.getGreen(), 255, 0.25),
				HudDraw.mix(base.getBlue(), 255, 0.25), 255), alpha);
		List<String> lines = wrap(font, shown, width - 24);
		double y = 8 + font.getFontHeight() + 4;
		int limit = Math.min(MESSAGE_LINES, lines.size());
		for (int i = 0; i < limit; i++) {
			font.drawString(lines.get(i), 12, y, body, false);
			y += font.getFontHeight() + 2;
		}
		if (module == null || module.crtOverlay.getValue()) HudDraw.scanlines(renderer, width, height, 0.75 * alpha);
		if (module != null && module.autoArrangeHud.getValue()) arrange(module);
	}

	private void arrange(VolytraFlyModule module) {
		SpeedometerHud speedometer = VolytraHuds.speedometer;
		if (speedometer == null || !speedometer.isToggled()) return;
		double inset = SpeedometerHud.DIAL_INSET * (speedometer.getWidth() / SpeedometerHud.BASE_SIZE);
		double support = speedometer.getStartY();
		ModeLightsHud lights = VolytraHuds.lights;
		if (lights != null && lights.isToggled() && module.modeLights.getValue()) {
			support = lights.getStartY();
		}
		setStartX(speedometer.getStartX() + inset);
		setStartY(support - getScaledHeight() - 6);
	}

	private static List<String> wrap(IFontRenderer font, String text, double maxWidth) {
		List<String> lines = new ArrayList<>();
		if (text.isEmpty()) {
			lines.add("");
			return lines;
		}
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (font.getStringWidth(candidate) > maxWidth && !line.isEmpty()) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (!line.isEmpty()) lines.add(line.toString());
		return lines;
	}

	@Override
	public double getWidth() {
		VolytraFlyModule module = VolytraFlyModule.get();
		double speedometerScale = module != null ? module.speedometerScale.getValue() : 2.0;
		return Math.max(180, SpeedometerHud.DIAL_RADIUS * 2 * speedometerScale);
	}

	@Override
	public double getHeight() {
		return 14 + getFontRenderer().getFontHeight() * (MESSAGE_LINES + 1) + 8;
	}
}
