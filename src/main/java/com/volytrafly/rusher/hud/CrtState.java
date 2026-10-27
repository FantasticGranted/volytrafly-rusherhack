/*
 * Flight, autopilot, highway, avoidance, and HUD behaviour ported from
 * VolytraFly-Addon by Volizray (https://github.com/Volizray/VolytraFly-Addon),
 * originally a Meteor Client addon. That project is dedicated to the public
 * domain under CC0 1.0. See the LICENSE and NOTICE files in this repository.
 */

package com.volytrafly.rusher.hud;

/**
 * Power-on / power-off timeline shared by the original {@code CrtEffect}.
 * The phosphor-stripe pass cannot be reproduced with RusherHack's renderer, so
 * callers use {@link #alpha()} and {@link #linePhase()} and draw scanlines themselves.
 */
public final class CrtState {
	private static final double DURATION = 0.25;
	private static final double LINE_END = 0.38;
	private static final double OFF_SPEEDUP = 1.25;

	private double t;
	private long lastNanos;

	public void update(boolean show) {
		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 0.016 : (now - lastNanos) / 1.0e9;
		lastNanos = now;
		dt = Math.min(0.25, Math.max(0, dt));
		double step = dt / DURATION;
		if (show) t = Math.min(1, t + step);
		else t = Math.max(0, t - step * OFF_SPEEDUP);
	}

	public boolean visible() {
		return t > 0.001;
	}

	/** 0 while off, 1 while fully on. */
	public double alpha() {
		if (t >= 1) return 1;
		if (t < LINE_END) return 0.15 + 0.35 * (t / LINE_END);
		return Math.min(1, (t - LINE_END) / (1 - LINE_END) * 1.4);
	}

	/** True during the horizontal "tube line" before the picture unfolds. */
	public boolean linePhase() {
		return t > 0 && t < LINE_END;
	}

	public double lineFraction() {
		if (t >= LINE_END) return 1;
		return t / LINE_END;
	}
}
