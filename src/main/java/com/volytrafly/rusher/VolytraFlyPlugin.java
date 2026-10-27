/*
 * Flight, autopilot, highway, avoidance, and HUD behaviour ported from
 * VolytraFly-Addon by Volizray (https://github.com/Volizray/VolytraFly-Addon),
 * originally a Meteor Client addon. That project is dedicated to the public
 * domain under CC0 1.0. See the LICENSE and NOTICE files in this repository.
 */

package com.volytrafly.rusher;

import com.volytrafly.rusher.hud.ElytraFuelHud;
import com.volytrafly.rusher.hud.ModeLightsHud;
import com.volytrafly.rusher.hud.SpeedometerHud;
import com.volytrafly.rusher.hud.VolytraAssistantHud;
import com.volytrafly.rusher.hud.VolytraHuds;
import com.volytrafly.rusher.module.VolytraFlyModule;
import com.volytrafly.rusher.module.VolytraFlyRuntime;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.plugin.Plugin;

/**
 * RusherHack entrypoint. Registers the VolytraFly module, its HUD elements, and the
 * always-on listener that keeps insta-drop, the ground chest-swap, and Future Sight
 * running after the module itself has stopped listening.
 */
public class VolytraFlyPlugin extends Plugin {
	private final VolytraFlyRuntime runtime = new VolytraFlyRuntime();

	@Override
	public void onLoad() {
		this.getLogger().info("Initializing VolytraFly");

		VolytraFlyModule module = new VolytraFlyModule();
		RusherHackAPI.getModuleManager().registerFeature(module);

		SpeedometerHud speedometer = new SpeedometerHud();
		ElytraFuelHud fuel = new ElytraFuelHud();
		ModeLightsHud lights = new ModeLightsHud();
		VolytraAssistantHud assistant = new VolytraAssistantHud();
		VolytraHuds.bind(speedometer, fuel, lights, assistant);

		RusherHackAPI.getHudManager().registerFeature(speedometer);
		RusherHackAPI.getHudManager().registerFeature(fuel);
		RusherHackAPI.getHudManager().registerFeature(lights);
		RusherHackAPI.getHudManager().registerFeature(assistant);

		RusherHackAPI.getEventBus().subscribe(runtime);
	}

	@Override
	public void onUnload() {
		RusherHackAPI.getEventBus().unsubscribe(runtime);
		this.getLogger().info("VolytraFly unloaded");
	}
}
