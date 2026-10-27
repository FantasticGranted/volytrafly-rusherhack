/*
 * Flight, autopilot, highway, avoidance, and HUD behaviour ported from
 * VolytraFly-Addon by Volizray (https://github.com/Volizray/VolytraFly-Addon),
 * originally a Meteor Client addon. That project is dedicated to the public
 * domain under CC0 1.0. See the LICENSE and NOTICE files in this repository.
 */

package com.volytrafly.rusher.hud;

import com.volytrafly.rusher.module.VolytraFlyModule;

/**
 * Holds the HUD elements the module turns on. Meteor registers these through its
 * HUD system the first time a visual setting is enabled; RusherHack elements are
 * registered at plugin load and toggled from here.
 */
public final class VolytraHuds {
	public static SpeedometerHud speedometer;
	public static ElytraFuelHud fuel;
	public static ModeLightsHud lights;
	public static VolytraAssistantHud assistant;

	private VolytraHuds() {}

	public static void bind(SpeedometerHud speedometer, ElytraFuelHud fuel, ModeLightsHud lights, VolytraAssistantHud assistant) {
		VolytraHuds.speedometer = speedometer;
		VolytraHuds.fuel = fuel;
		VolytraHuds.lights = lights;
		VolytraHuds.assistant = assistant;
	}

	/** Turns a visual's element on when its setting is on. Power-off is handled by the element. */
	public static void sync(VolytraFlyModule module) {
		if (module == null || !module.isToggled()) return;
		if (speedometer != null && module.speedometer.getValue()) speedometer.setToggled(true);
		if (fuel != null && module.fuelGauge.getValue()) fuel.setToggled(true);
		if (lights != null && module.modeLights.getValue()) lights.setToggled(true);
		if (assistant != null && module.volytraAssistant.getValue()) assistant.setToggled(true);
	}
}
