/*
 * Flight, autopilot, highway, avoidance, and HUD behaviour ported from
 * VolytraFly-Addon by Volizray (https://github.com/Volizray/VolytraFly-Addon),
 * originally a Meteor Client addon. That project is dedicated to the public
 * domain under CC0 1.0. See the LICENSE and NOTICE files in this repository.
 */

package com.volytrafly.rusher.hud;

import org.rusherhack.client.api.render.IRenderer2D;
import org.rusherhack.client.api.render.font.IFontRenderer;

/**
 * Dial and neon-panel drawing on top of {@link IRenderer2D}.
 * Meteor's HUD renderer paints arbitrary triangles, which is how the original
 * speedometer builds filled arcs and how its CRT effect slices every shape into
 * phosphor stripes. RusherHack's 2D renderer exposes lines, circles, and rectangles,
 * so the same geometry is stroked instead of filled.
 */
public final class HudDraw {
	public static final double START_ANGLE = Math.toRadians(135);
	public static final double SWEEP = Math.toRadians(270);
	private static final double SEGMENT = Math.toRadians(4);

	private HudDraw() {}

	public static int argb(int r, int g, int b, int a) {
		return ((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255);
	}

	public static int mix(int from, int to, double t) {
		return (int) Math.round(from + (to - from) * t);
	}

	public static int withAlpha(int color, double factor) {
		int a = (color >>> 24) & 255;
		int na = (int) Math.round(a * Math.max(0, Math.min(1, factor)));
		return (na << 24) | (color & 0x00FFFFFF);
	}

	public static void arc(IRenderer2D renderer, double cx, double cy, double radius, double a0, double a1, float thickness, int color) {
		if (radius <= 0 || thickness <= 0) return;
		int steps = Math.max(1, (int) Math.ceil(Math.abs(a1 - a0) / SEGMENT));
		double px = cx + Math.cos(a0) * radius;
		double py = cy + Math.sin(a0) * radius;
		for (int i = 1; i <= steps; i++) {
			double angle = a0 + (a1 - a0) * i / steps;
			double nx = cx + Math.cos(angle) * radius;
			double ny = cy + Math.sin(angle) * radius;
			renderer.drawLine(px, py, nx, ny, thickness, color);
			px = nx;
			py = ny;
		}
	}

	/** Dial arc from the start of the sweep through {@code fraction} of the range. */
	public static void sweep(IRenderer2D renderer, double cx, double cy, double radius, double fraction, float thickness, int color) {
		double f = Math.max(0, Math.min(1, fraction));
		if (f <= 0.001) return;
		arc(renderer, cx, cy, radius, START_ANGLE, START_ANGLE + SWEEP * f, thickness, color);
	}

	public static void circle(IRenderer2D renderer, double cx, double cy, double radius, float thickness, int color) {
		renderer.drawCircleOutline(cx, cy, radius, thickness, color);
	}

	public static void scanlines(IRenderer2D renderer, double width, double height, double strength) {
		if (strength <= 0) return;
		int alpha = (int) Math.round(70 * Math.max(0, Math.min(1, strength)));
		int color = argb(0, 0, 0, alpha);
		for (double y = 0; y < height; y += 3) {
			renderer.drawRectangle(0, y, width, 1, color);
		}
	}

	public static void text(IFontRenderer font, String text, double x, double y, int color, boolean shadow) {
		font.drawString(text, x, y, color, shadow);
	}
}
