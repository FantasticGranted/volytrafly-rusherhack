/*
 * Flight, autopilot, highway, avoidance, and HUD behaviour ported from
 * VolytraFly-Addon by Volizray (https://github.com/Volizray/VolytraFly-Addon),
 * originally a Meteor Client addon. That project is dedicated to the public
 * domain under CC0 1.0. See the LICENSE and NOTICE files in this repository.
 *
 * The original class is modules/movement/volytrafly/VolytraFly.java. Meteor
 * setting groups, PlayerMoveEvent, and InvUtils are replaced with RusherHack
 * settings, EventMove, and inventory clicks. The acceleration curve, highway
 * navigator, and avoidance steering are the same decisions as that file.
 */

package com.volytrafly.rusher.module;

import com.mojang.blaze3d.platform.InputConstants;
import com.volytrafly.rusher.hud.VolytraHuds;
import com.volytrafly.rusher.util.InventoryActions;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lwjgl.glfw.GLFW;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.accessors.client.IMixinKeyMapping;
import org.rusherhack.client.api.bind.key.KeyboardKey;
import org.rusherhack.client.api.bind.key.MouseKey;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.client.input.EventKeyboard;
import org.rusherhack.client.api.events.client.input.EventMouse;
import org.rusherhack.client.api.events.network.EventPacket;
import org.rusherhack.client.api.events.player.EventMove;
import org.rusherhack.client.api.events.render.EventRender2D;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.font.IFontRenderer;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.core.bind.key.IKey;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.stage.Stage;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NullSetting;
import org.rusherhack.core.setting.NumberSetting;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

public class VolytraFlyModule extends ToggleableModule {
	private static VolytraFlyModule instance;

	public static VolytraFlyModule get() {
		return instance;
	}

	public static boolean gliding(LivingEntity entity) {
		return entity.isFallFlying();
	}

	// RusherHack has no SettingGroup type. Each Meteor group is a NullSetting parent.
	private final NullSetting sgGeneral = new NullSetting("General", "Speed curves, takeoff, and the limits applied to every mode");
	private final NullSetting sgMapping = new NullSetting("Mapping", "Pauses horizontal movement until nearby chunks finish loading");
	private final NullSetting sgBuilding = new NullSetting("Building Mode", "Eases speed down when blocks are nearby");
	private final NullSetting sgBypass = new NullSetting("Bypass Mode", "Keeps real horizontal velocity and accelerates it");
	private final NullSetting sgAvoidance = new NullSetting("Player Avoidance System", "Moves you away from nearby players, skulls, arrows, and blocks");
	private final NullSetting sgLanding = new NullSetting("Landing", "Slows the fall as you approach the ground");
	private final NullSetting sgInventory = new NullSetting("Inventory", "Elytra replacement, chest swap, and firework stocking");
	private final NullSetting sgAutopilot = new NullSetting("Autopilot", "Flies forward above a minimum height and can fire fireworks");
	private final NullSetting sgHighway = new NullSetting("Highway Mode", "Snaps onto the nearest world highway and flies along it");
	private final NullSetting sgVisuals = new NullSetting("Visuals", "Speedometer, switches, fuel gauge, assistant, and Future Sight");

	public final BooleanSetting mappingMode = new BooleanSetting("mapping-mode", "Pauses the player's horizontal movement until nearby chunks are loaded", false);
	public final BindSetting mappingModeKeybind = new BindSetting("mapping-mode-keybind", "Key to toggle Mapping Mode", NullKey.INSTANCE);
	public final NumberSetting<Integer> mappingRenderRadius = new NumberSetting<>("render-radius", "How many chunks around you need to load before you start moving again", 9, 0, 32).clampMin().incremental(1).setVisibility(mappingMode::getValue);

	public final BooleanSetting buildingMode = new BooleanSetting("building-mode", "Slows you down when blocks are nearby", false);
	public final BindSetting buildingModeKeybind = new BindSetting("building-mode-keybind", "Key to toggle Building Mode", NullKey.INSTANCE);
	public final NumberSetting<Double> buildingModeDistance = new NumberSetting<>("building-mode-distance", "Distance from a block that triggers the slowdown", 15.0, 0.1, 30.0).clampMin().incremental(0.1).setVisibility(buildingMode::getValue);
	public final NumberSetting<Double> buildingModeMinSpeed = new NumberSetting<>("building-mode-min-speed", "Speed you ease down to when near blocks", 0.7, 0.0, 1.0).clampMin().incremental(0.01).setVisibility(buildingMode::getValue);

	public final BooleanSetting bypassMode = new BooleanSetting("bypass-mode", "Switches out acceleration with additive flight to behave more like normal elytra flight", false);
	public final BindSetting bypassModeKeybind = new BindSetting("bypass-mode-keybind", "Key to toggle Bypass Mode", NullKey.INSTANCE);

	public final BooleanSetting playerAvoidance = new BooleanSetting("player-avoidance", "Distances you from other players when they get close", false);
	public final BindSetting playerAvoidanceKeybind = new BindSetting("player-avoidance-keybind", "Key to toggle Player Avoidance.", NullKey.INSTANCE);
	public final NumberSetting<Double> avoidanceRadius = new NumberSetting<>("radius", "Distance at which a player triggers avoidance", 30.0, 0.0, 50.0).clampMin().incremental(0.1).setVisibility(playerAvoidance::getValue);
	public final BooleanSetting avoidanceIgnoreFriends = new BooleanSetting("ignore-friends", "Ignores players on your friends list", true).setVisibility(playerAvoidance::getValue);
	public final BooleanSetting avoidWitherSkulls = new BooleanSetting("avoid-wither-skulls", "Also avoids wither skulls", true).setVisibility(playerAvoidance::getValue);
	public final NumberSetting<Double> witherSkullRadius = new NumberSetting<>("wither-skull-radius", "Distance at which a wither skull triggers avoidance", 10.0, 0.0, 50.0).clampMin().incremental(0.1).setVisibility(() -> playerAvoidance.getValue() && avoidWitherSkulls.getValue());
	public final BooleanSetting avoidArrows = new BooleanSetting("avoid-arrows", "Also avoids arrows", false).setVisibility(playerAvoidance::getValue);
	public final NumberSetting<Double> arrowRadius = new NumberSetting<>("arrow-radius", "Distance at which an arrow triggers avoidance", 50.0, 0.0, 50.0).clampMin().incremental(0.1).setVisibility(() -> playerAvoidance.getValue() && avoidArrows.getValue());
	public final BooleanSetting avoidBlocks = new BooleanSetting("avoid-blocks", "Also avoids nearby blocks while moving away from players and wither skulls", true).setVisibility(playerAvoidance::getValue);
	public final NumberSetting<Double> blockAvoidanceRadius = new NumberSetting<>("block-radius", "Distance at which a block triggers avoidance", 3.0, 0.0, 10.0).clampMin().incremental(0.1).setVisibility(() -> playerAvoidance.getValue() && avoidBlocks.getValue());
	public final BooleanSetting avoidanceLateral = new BooleanSetting("sidestep", "Tries to move sideways to an incoming player rather than simply away", true).setVisibility(playerAvoidance::getValue);

	public final BooleanSetting antiSlam = new BooleanSetting("anti-slam", "Slows you down when landing to prevent fall damage", true);
	public final BindSetting antiSlamKeybind = new BindSetting("anti-slam-keybind", "Key to toggle anti-slam", NullKey.INSTANCE);
	public final NumberSetting<Double> antiSlamDistance = new NumberSetting<>("anti-slam-distance", "Distance from the ground where slowing begins", 10.0, 0.1, 30.0).clampMin().incremental(0.1).setVisibility(antiSlam::getValue);
	public final NumberSetting<Double> antiSlamMinDistance = new NumberSetting<>("anti-slam-min-distance", "Distance from the ground where speed reaches the minimum value", 0.5, 0.0, 5.0).clampMin().incremental(0.1).setVisibility(antiSlam::getValue);
	public final NumberSetting<Double> antiSlamMinSpeed = new NumberSetting<>("anti-slam-min-speed", "The speed to slow down to before landing", 0.2, 0.0, 1.0).clampMin().incremental(0.01).setVisibility(antiSlam::getValue);

	public final NumberSetting<Double> startSpeed = new NumberSetting<>("minimum-horizontal-speed", "The speed you start at when moving horizontally, before acceleration kicks in", 2.999, 0.0, 10000.0).clampMin().incremental(0.001);
	public final NumberSetting<Double> horizontalSpeed = new NumberSetting<>("maximum-horizontal-speed", "The fastest horizontal speed will go (blocks per tick)", 14.999, 0.0, 10000.0).clampMin().incremental(0.001);
	public final NumberSetting<Double> accelerationPlateau = new NumberSetting<>("horizontal-acceleration-plateau", "The horizontal speed where acceleration will tend to 0", 14.999, 0.01, 10000.0).clampMin().incremental(0.001);
	public final NumberSetting<Double> accelerationStep = new NumberSetting<>("horizontal-acceleration-step", "How fast horizontal speed ramps up", 0.3, 0.01, 5.0).clampMin().clampMax().incremental(0.01);
	public final NumberSetting<Double> verticalStartSpeed = new NumberSetting<>("minimum-vertical-speed", "The speed you start at when moving vertically, before acceleration kicks in", 5.999, 0.0, 10000.0).clampMin().incremental(0.001);
	public final NumberSetting<Double> verticalSpeed = new NumberSetting<>("maximum-vertical-speed", "The fastest vertical speed will go (blocks per tick)", 5.999, 0.0, 10000.0).clampMin().incremental(0.001);
	public final NumberSetting<Double> verticalAccelerationPlateau = new NumberSetting<>("vertical-acceleration-plateau", "The vertical speed where acceleration will tend to 0", 29.999, 0.01, 10000.0).clampMin().incremental(0.001);
	public final NumberSetting<Double> verticalAccelerationStep = new NumberSetting<>("vertical-acceleration-step", "How fast vertical speed ramps up", 1.0, 0.01, 5.0).clampMin().clampMax().incremental(0.01);
	public final NumberSetting<Integer> accelerationDelay = new NumberSetting<>("acceleration-delay", "Adds a slight delay before accelerating. 1 Tick is necessary to avoid getting stuck.", 1, 0, 100).clampMin().incremental(1);
	public final NumberSetting<Double> fallMultiplier = new NumberSetting<>("fall-multiplier", "Multiplier for how fast you fall naturally", 0.0, 0.0, 20.0).clampMin().incremental(0.01);
	public final BooleanSetting accelerateUpward = new BooleanSetting("accelerate-upward", "Also accelerates upwards. Not recommended if vertical speed goes above 5.999", false);
	public final BooleanSetting autoTakeOff = new BooleanSetting("auto-take-off", "Takes off automatically without needing to double jump", false);
	public final BooleanSetting stopInWater = new BooleanSetting("stop-in-water", "Stops flying when you touch water", false);
	public final BooleanSetting dontGoIntoUnloadedChunks = new BooleanSetting("no-unloaded-chunks", "Stops you from flying into unloaded chunks", false);
	public final BooleanSetting noCrash = new BooleanSetting("no-crash", "Stops you from flying into walls", false);
	public final NumberSetting<Integer> crashLookAhead = new NumberSetting<>("crash-look-ahead", "Distance to look ahead for walls", 3, 1, 15).clampMin().clampMax().incremental(1).setVisibility(noCrash::getValue);
	public final BooleanSetting instaDrop = new BooleanSetting("insta-drop", "Instantly drops you out of flight", false);
	public final BooleanSetting limitMaxHeight = new BooleanSetting("limit-max-height", "Stops you from flying above a set height", true);
	public final NumberSetting<Double> maxHeight = new NumberSetting<>("max-height", "The max height that you will be able to reach", 500.0, -128.0, 500.0).incremental(1.0).setVisibility(limitMaxHeight::getValue);
	public final BooleanSetting rubberbandSpeedCap = new BooleanSetting("rubberband-speed-cap", "Checks if rubberbanding is happening frequently near this value. If it is, it'll limit your speed for a little while for smoother flight.", true);
	public final NumberSetting<Double> rubberbandSpeedCapThreshold = new NumberSetting<>("rubberband-speed-cap-threshold", "The speed you think rubberbands are happening frequently at and to limit your speed to if so", 200.0, 0.0, 400.0).clampMin().incremental(1.0).setVisibility(rubberbandSpeedCap::getValue);

	public final BooleanSetting replace = new BooleanSetting("elytra-replace", "Replaces a broken elytra with a new one", false);
	public final NumberSetting<Integer> replaceDurability = new NumberSetting<>("replace-durability", "Durability left on the elytra before it's replaced", 2, 1, 500).clampMin().clampMax().incremental(1).setVisibility(replace::getValue);
	public final EnumSetting<ChestSwapMode> chestSwap = new EnumSetting<>("chest-swap", "Swaps to an elytra when toggling this module", ChestSwapMode.Never);
	public final BooleanSetting autoReplenish = new BooleanSetting("replenish-fireworks", "Moves fireworks into a chosen hotbar slot", false);
	public final NumberSetting<Integer> replenishSlot = new NumberSetting<>("replenish-slot", "Hotbar slot to move fireworks into", 9, 1, 9).clampMin().clampMax().incremental(1).setVisibility(autoReplenish::getValue);

	public final BooleanSetting autoPilot = new BooleanSetting("auto-pilot", "Moves forward automatically while elytra flying", false);
	public final BindSetting autoPilotKeybind = new BindSetting("auto-pilot-keybind", "Key to toggle Autopilot.", NullKey.INSTANCE);
	public final BooleanSetting useFireworks = new BooleanSetting("use-fireworks", "Uses fireworks automatically at an interval", false).setVisibility(autoPilot::getValue);
	public final NumberSetting<Double> autoPilotFireworkDelay = new NumberSetting<>("firework-delay", "Seconds between automatic firework uses", 8.0, 1.0, 20.0).clampMin().incremental(0.1).setVisibility(useFireworks::getValue);
	public final NumberSetting<Double> autoPilotMinimumHeight = new NumberSetting<>("minimum-height", "Minimum height autopilot needs before it flies forward", 120.0, -128.0, 260.0).incremental(1.0).setVisibility(autoPilot::getValue);

	public final BooleanSetting highwayMode = new BooleanSetting("highway-mode", "Uses a mixture of mob pathfinding and custom navigation to find highways and travel along them", false);
	public final BindSetting highwayModeKeybind = new BindSetting("highway-mode-keybind", "Key to toggle Highway Mode", NullKey.INSTANCE);
	public final EnumSetting<HighwayDirection> highwayDirection = new EnumSetting<>("highway-direction", "Which way to fly along the highway, relative to the world centre (0, 0)", HighwayDirection.AwayFromCentre).setVisibility(highwayMode::getValue);
	public final EnumSetting<Intelligence> highwayIntelligence = new EnumSetting<>("intelligence", "How smart Highway Mode is when finding paths. Higher intelligence will use more CPU power.", Intelligence.High).setVisibility(highwayMode::getValue);

	public final BooleanSetting autoArrangeHud = new BooleanSetting("auto-arrange-HUD", "Arranges the HUD neatly on the screen", true).setVisibility(this::isVisualsOn);
	public final BooleanSetting speedometer = new BooleanSetting("speedometer", "Shows a speedometer HUD while VolytraFly is on", true).onChange(enabled -> { if (Boolean.TRUE.equals(enabled)) VolytraHuds.sync(this); });
	public final NumberSetting<Double> speedometerScale = new NumberSetting<>("speedometer-scale", "Size of the speedometer", 2.0, 0.3, 10.0).clampMin().incremental(0.01).setVisibility(speedometer::getValue);
	public final BooleanSetting modeLights = new BooleanSetting("switches", "Shows a panel while VolytraFly is on, with lights for each toggleable mode", true).onChange(enabled -> { if (Boolean.TRUE.equals(enabled)) VolytraHuds.sync(this); });
	public final BooleanSetting fuelGauge = new BooleanSetting("fuel-gauge", "Shows a gauge next to the speedometer for elytra durability", true).onChange(enabled -> { if (Boolean.TRUE.equals(enabled)) VolytraHuds.sync(this); });
	public final NumberSetting<Double> fuelGaugeScale = new NumberSetting<>("fuel-gauge-scale", "Size of the fuel gauge", 1.25, 0.3, 3.0).clampMin().incremental(0.01).setVisibility(fuelGauge::getValue);
	public final BooleanSetting volytraAssistant = new BooleanSetting("volytra-assistant", "A friendly assistant to explain what VolytraFly is doing while you fly", true).onChange(enabled -> { if (Boolean.TRUE.equals(enabled)) VolytraHuds.sync(this); });
	public final BooleanSetting futureSight = new BooleanSetting("future-sight", "Creates targets showing where a player will be in the next second", true);
	public final BooleanSetting futureSightIgnoreFriends = new BooleanSetting("future-sight-ignore-friends", "Targets won't show on friends", true).setVisibility(futureSight::getValue);
	public final BooleanSetting crtOverlay = new BooleanSetting("CRT-overlay", "Keeps a retro CRT look on the visuals", true).setVisibility(this::isVisualsOn);
	public final ColorSetting speedometerColor = new ColorSetting("primary-colour", "Main colour of the speedometer, fuel gauge, switches and assistant", new Color(255, 128, 0, 255)).setVisibility(this::isVisualsOn);
	public final ColorSetting greenColor = new ColorSetting("secondary-colour", "The colour the speedometer fades into at the top of its range, and the fuel gauge fades into as elytra runs low", new Color(0, 255, 70, 255)).setVisibility(this::isVisualsOn);

	private static final double RUBBERBAND_CAP_TOLERANCE_PERCENT = 5.0;
	private static final double RUBBERBAND_CAP_DURATION_SECONDS = 10.0;
	private static final int SPEED_PRESERVATION_REWIND_TICKS = 15;
	private static final int AVOIDANCE_STUCK_TICKS = 3;
	private static final double HIGHWAY_BLOCK_CLEARANCE = 0.1;
	private static final int RUBBERBAND_CAP_TRIGGER_COUNT = 3;
	private static final long RUBBERBAND_CAP_TRIGGER_WINDOW_NANOS = 3_000_000_000L;
	private static final int BUILDING_MODE_SLOWDOWN_TICKS = 5;
	private static final int VERTICAL_STEP_TIMEOUT_TICKS = 40;
	private static final double HIGHWAY_CENTRE_RADIUS = 1.0;
	private static final double HIGHWAY_STUCK_MOVE_EPSILON = 0.05;
	private static final double HIGHWAY_NODE_HEIGHT = 0.2;
	private static final double HIGHWAY_HARD_MARGIN = 0.02;
	private static final double HIGHWAY_HEIGHT_BAND = 0.3;
	private static final int HIGHWAY_MAX_LOOKAHEAD_NODES = 48;
	private static final int HIGHWAY_DETOUR_RADIUS = 3;
	private static final int HIGHWAY_DETOUR_MAX_CELLS = 4000;
	private static final String[] ASSISTANT_HAPPY_ENDINGS = {"~", "^^", "<3", ":3"};
	private static final String[] ASSISTANT_SORRY_ENDINGS = {"T~T", "...", ":<"};
	private static final long ASSISTANT_HAPPY_HOLD_MS = 1500;
	private static final long ASSISTANT_SORRY_HOLD_MS = 3000;
	private static final long ASSISTANT_SITUATION_LINGER_MS = 600;
	private static final long ASSISTANT_GENERAL_MIN_SHOW_MS = 2500;
	private static final String ASSISTANT_BASELINE_PREFIX = "g-base-";

	private boolean lastJumpPressed;
	private boolean incrementJumpTimer;
	private boolean lastForwardPressed;
	private int jumpTimer;
	private double velX, velY, velZ;
	private double ticksLeft;
	private Vec3 forward = Vec3.ZERO, right = Vec3.ZERO;
	private double acceleration;
	private boolean atMaxSpeed;
	private Vec3 lastGlidePos;
	private double lastGlideIntendedSpeed;
	private int accelerationDelayTicks;
	private int bypassAccelerationDelayTicks;
	private double verticalAcceleration;
	private boolean atMaxVerticalSpeed;
	private int verticalAccelerationDelayTicks;
	private boolean mappingWaitingForChunks;
	private int rubberbandCapTicksLeft;
	private double rubberbandCapSpeed;
	private final ArrayDeque<Long> rubberbandQualifyingTimestamps = new ArrayDeque<>();

	private boolean buildingModeEngaged;
	private double buildingModeEntryHorizontalSpeed;
	private double buildingModeEntryVerticalSpeed;
	private int buildingModeTicksElapsed;

	private boolean avoidanceSteering;
	private Vec3 avoidanceLateralDir;
	private int avoidanceStuckTicksCount;
	private boolean verticalStepActive;
	private boolean verticalStepUp;
	private double verticalStepStartY;
	private int verticalStepTicks;

	private Vec3 highwayAxis;
	private double highwayCruiseY = Double.NaN;
	private Parrot highwayPathMob;
	private ClientLevel highwayPathMobWorld;
	private final ArrayList<Vec3> highwayPathNodes = new ArrayList<>();
	private int highwayPathIndex;
	private Vec3 highwayGoal;
	private int highwayTicksSinceRepath;
	private Vec3 highwaySteerTarget;
	private double highwayTargetY = Double.NaN;
	private Vec3 highwayLastPos;
	private int highwayStuckTicks;
	private int highwayMovingTicks;
	private int highwayEscalation;

	private volatile String assistantKey;
	private volatile String assistantText = "";
	private volatile boolean assistantNegative;
	private long assistantSinceMs;
	private long assistantLastActiveMs;
	private String assistantLastEnding = "";
	private final Random assistantRandom = new Random();
	private final AtomicInteger positionCorrections = new AtomicInteger();
	private final AtomicLongArray assistantSituationSeen = new AtomicLongArray(AssistantSituation.values().length);

	/** Read by the always-on runtime after this module has stopped receiving events. */
	public boolean awaitGroundChestSwap;
	public boolean instaDropActive;

	public VolytraFlyModule() {
		super("VolytraFly", "Specifically designed to maximise elytrafly capabilities and speed on 6b6t", ModuleCategory.MOVEMENT);
		instance = this;
		this.addAliases("volytra-fly");

		sgMapping.addSubSettings(mappingMode, mappingModeKeybind, mappingRenderRadius);
		sgBuilding.addSubSettings(buildingMode, buildingModeKeybind, buildingModeDistance, buildingModeMinSpeed);
		sgBypass.addSubSettings(bypassMode, bypassModeKeybind);
		sgAvoidance.addSubSettings(playerAvoidance, playerAvoidanceKeybind, avoidanceRadius, avoidanceIgnoreFriends, avoidWitherSkulls, witherSkullRadius, avoidArrows, arrowRadius, avoidBlocks, blockAvoidanceRadius, avoidanceLateral);
		sgLanding.addSubSettings(antiSlam, antiSlamKeybind, antiSlamDistance, antiSlamMinDistance, antiSlamMinSpeed);
		sgGeneral.addSubSettings(startSpeed, horizontalSpeed, accelerationPlateau, accelerationStep, verticalStartSpeed, verticalSpeed, verticalAccelerationPlateau, verticalAccelerationStep, accelerationDelay, fallMultiplier, accelerateUpward, autoTakeOff, stopInWater, dontGoIntoUnloadedChunks, noCrash, crashLookAhead, instaDrop, limitMaxHeight, maxHeight, rubberbandSpeedCap, rubberbandSpeedCapThreshold);
		sgInventory.addSubSettings(replace, replaceDurability, chestSwap, autoReplenish, replenishSlot);
		sgAutopilot.addSubSettings(autoPilot, autoPilotKeybind, useFireworks, autoPilotFireworkDelay, autoPilotMinimumHeight);
		sgHighway.addSubSettings(highwayMode, highwayModeKeybind, highwayDirection, highwayIntelligence);
		sgVisuals.addSubSettings(autoArrangeHud, speedometer, speedometerScale, modeLights, fuelGauge, fuelGaugeScale, volytraAssistant, futureSight, futureSightIgnoreFriends, crtOverlay, speedometerColor, greenColor);

		this.registerSettings(sgGeneral, sgMapping, sgBuilding, sgBypass, sgAvoidance, sgLanding, sgInventory, sgAutopilot, sgHighway, sgVisuals);
	}

	public int getPositionCorrections() {
		return positionCorrections.get();
	}

	private boolean isVisualsOn() {
		return speedometer.getValue() || fuelGauge.getValue() || modeLights.getValue() || futureSight.getValue() || volytraAssistant.getValue();
	}

	@Override
	public void onEnable() {
		Player player = mc.player;
		VolytraHuds.sync(this);
		resetAssistant();

		atMaxSpeed = false;
		lastJumpPressed = false;
		jumpTimer = 0;
		ticksLeft = 0;
		accelerationDelayTicks = 0;
		bypassAccelerationDelayTicks = 0;
		lastGlidePos = null;
		lastGlideIntendedSpeed = 0;

		double currentHorizontalSpeed = player != null ? Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z) : 0;
		acceleration = Math.max(startSpeed.getValue(), Math.min(currentHorizontalSpeed, horizontalSpeed.getValue()));
		if (acceleration >= horizontalSpeed.getValue()) atMaxSpeed = true;
		resetVerticalAcceleration();

		rubberbandCapTicksLeft = 0;
		rubberbandQualifyingTimestamps.clear();
		buildingModeEngaged = false;
		resetHighwayState();

		if (player == null) return;
		if ((chestSwap.getValue() == ChestSwapMode.Always || chestSwap.getValue() == ChestSwapMode.WaitForGround)
			&& !InventoryActions.wearingGlider(player) && isToggled()) {
			InventoryActions.swapChest();
		}
	}

	@Override
	public void onDisable() {
		mappingWaitingForChunks = false;
		resetAssistant();
		if ((autoPilot.getValue() || highwayMode.getValue()) && mc.options != null) {
			mc.options.keyUp.setDown(false);
		}
		releaseAvoidance();
		releaseVerticalStep();
		releaseHighwayKeys();
		resetHighwayState();
		highwayPathMob = null;
		highwayPathMobWorld = null;

		Player player = mc.player;
		if (player == null) return;

		if (chestSwap.getValue() == ChestSwapMode.Always && InventoryActions.wearingGlider(player)) {
			InventoryActions.swapChest();
		} else if (chestSwap.getValue() == ChestSwapMode.WaitForGround) {
			awaitGroundChestSwap = true;
		}
		if (gliding(player) && instaDrop.getValue()) {
			instaDropActive = true;
		}
	}

	@Subscribe(stage = Stage.PRE)
	private void onMove(EventMove event) {
		Player player = mc.player;
		ClientLevel world = mc.level;
		if (player == null || world == null) return;
		if (!InventoryActions.wearingGlider(player)) return;

		autoTakeoff();
		updatePlayerAvoidance();

		if (gliding(player)) {
			boolean bypass = bypassMode.getValue();
			if (rubberbandCapTicksLeft > 0) assistantFlag(AssistantSituation.RUBBERBAND_CAP);

			if (bypass) {
				double horizontalSpeedNow = Math.hypot(event.getX(), event.getZ());
				if (horizontalSpeedNow > 1.0E-4) {
					double newSpeed;
					if (bypassAccelerationDelayTicks < accelerationDelay.getValue()) {
						bypassAccelerationDelayTicks++;
						newSpeed = horizontalSpeedNow;
					} else {
						newSpeed = nextSpeedFor(horizontalSpeedNow, accelerationStep.getValue(), accelerationPlateau.getValue(), horizontalSpeedCap());
					}
					double scale = newSpeed / horizontalSpeedNow;
					velX = event.getX() * scale;
					velZ = event.getZ() * scale;
				} else {
					bypassAccelerationDelayTicks = 0;
					velX = event.getX();
					velZ = event.getZ();
				}
			} else {
				velX = 0;
				velZ = 0;
			}
			velY = event.getY();

			forward = Vec3.directionFromRotation(0, player.getYRot()).scale(0.1);
			right = Vec3.directionFromRotation(0, player.getYRot() + 90).scale(0.1);

			if (player.isInWater() && stopInWater.getValue()) {
				if (player instanceof net.minecraft.client.player.LocalPlayer local && local.connection != null) {
					local.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
				}
				return;
			}

			handleFallMultiplier();
			handleAutopilot();
			if (!bypass) {
				resetRampOnCollision(player);
				handleAcceleration();
				handleHorizontalSpeed(world);
			}
			applyHighwaySteering();
			handleVerticalAcceleration();
			handleVerticalSpeed();
			limitHighwayVerticalStep();
			applyHighwaySlopeCoupling();
			handleAntiSlam(world);
			handleBuildingMode(world);
			handleMaxHeight();
			applyHighwayClearanceGuard(player, world);

			int chunkX = (int) ((player.getX() + velX) / 16);
			int chunkZ = (int) ((player.getZ() + velZ) / 16);
			if (dontGoIntoUnloadedChunks.getValue() && !world.getChunkSource().hasChunk(chunkX, chunkZ)) {
				assistantFlag(AssistantSituation.UNLOADED_CHUNKS);
				event.setMotion(0, velY, 0);
			} else {
				event.setMotion(velX, velY, velZ);
			}
		} else {
			mappingWaitingForChunks = false;
			if (lastForwardPressed) {
				mc.options.keyUp.setDown(false);
				lastForwardPressed = false;
			}
		}

		if (noCrash.getValue() && gliding(player)) {
			Vec3 velocity = player.getDeltaMovement();
			if (velocity.lengthSqr() > 1.0E-8) {
				Vec3 lookAheadPos = player.position().add(velocity.normalize().scale(crashLookAhead.getValue()));
				BlockHitResult hitResult = world.clip(new ClipContext(player.position(), new Vec3(lookAheadPos.x, player.getY(), lookAheadPos.z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
				if (hitResult.getType() == HitResult.Type.BLOCK) {
					assistantFlag(AssistantSituation.NO_CRASH);
					event.setMotion(0, velY, 0);
				}
			}
		}

		if (gliding(player)) {
			lastGlidePos = player.position();
			lastGlideIntendedSpeed = Math.hypot(event.getX(), event.getZ());
		} else {
			lastGlidePos = null;
			lastGlideIntendedSpeed = 0;
		}
	}

	@Subscribe(stage = Stage.POST)
	private void onTick(EventUpdate event) {
		if (rubberbandCapTicksLeft > 0) rubberbandCapTicksLeft--;
		VolytraHuds.sync(this);

		if (autoReplenish.getValue()) {
			InventoryActions.replenishHotbar(replenishSlot.getValue() - 1);
		}

		Player player = mc.player;
		if (replace.getValue() && player != null) {
			ItemStack chestStack = player.getItemBySlot(EquipmentSlot.CHEST);
			if (chestStack.is(Items.ELYTRA) && InventoryActions.durabilityLeft(chestStack) <= replaceDurability.getValue()) {
				InventoryActions.equipElytra(replaceDurability.getValue());
			}
		}
	}

	@Subscribe(stage = Stage.PRE)
	private void onPacket(EventPacket.Receive event) {
		if (!(event.getPacket() instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket)) return;
		positionCorrections.incrementAndGet();
		Player player = mc.player;
		ClientLevel world = mc.level;
		boolean inFlight = player != null && world != null && gliding(player);

		if (inFlight && rubberbandSpeedCap.getValue()) {
			double speedBps = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z) * 20;
			double thresholdBps = rubberbandSpeedCapThreshold.getValue();
			double toleranceBps = thresholdBps * (RUBBERBAND_CAP_TOLERANCE_PERCENT / 100.0);
			if (speedBps >= thresholdBps && speedBps <= thresholdBps + toleranceBps) {
				long now = System.nanoTime();
				rubberbandQualifyingTimestamps.addLast(now);
				while (!rubberbandQualifyingTimestamps.isEmpty() && now - rubberbandQualifyingTimestamps.peekFirst() > RUBBERBAND_CAP_TRIGGER_WINDOW_NANOS) {
					rubberbandQualifyingTimestamps.pollFirst();
				}
				if (rubberbandQualifyingTimestamps.size() >= RUBBERBAND_CAP_TRIGGER_COUNT) {
					rubberbandCapTicksLeft = (int) Math.round(RUBBERBAND_CAP_DURATION_SECONDS * 20);
					rubberbandCapSpeed = thresholdBps / 20;
					rubberbandQualifyingTimestamps.clear();
				}
			}
		}

		if (inFlight) {
			int rewindTicks = SPEED_PRESERVATION_REWIND_TICKS;
			double keptSpeed = rewindSpeedFor(acceleration, accelerationStep.getValue(), accelerationPlateau.getValue(), startSpeed.getValue(), rewindTicks);
			if (keptSpeed < acceleration) {
				acceleration = keptSpeed;
				atMaxSpeed = false;
				accelerationDelayTicks = 0;
				verticalAcceleration = rewindSpeedFor(verticalAcceleration, verticalAccelerationStep.getValue(), verticalAccelerationPlateau.getValue(), verticalStartSpeed.getValue(), rewindTicks);
				atMaxVerticalSpeed = false;
				verticalAccelerationDelayTicks = 0;
			}
			return;
		}
		zeroAcceleration();
	}

	@Subscribe(stage = Stage.POST)
	private void onRender2D(EventRender2D event) {
		if (!mappingMode.getValue() || !mappingWaitingForChunks || volytraAssistant.getValue()) return;
		if (mc.getWindow() == null) return;
		String text = "VolytraFly: Waiting for chunks to load (render radius: " + mappingRenderRadius.getValue() + ")";
		IFontRenderer font = RusherHackAPI.fonts().getFontRenderer();
		var renderer = RusherHackAPI.getRenderer2D();
		boolean building = renderer.isBuilding();
		if (!building) renderer.begin(event.getGraphics(), font);
		double width = font.getStringWidth(text);
		font.drawString(text, (mc.getWindow().getGuiScaledWidth() - width) / 2.0, mc.getWindow().getGuiScaledHeight() / 2.0 + 20, 0xFFFFA500, true);
		if (!building) renderer.end();
	}

	@Subscribe(stage = Stage.ON)
	private void onKey(EventKeyboard event) {
		if (mc.screen != null || event.getAction() != GLFW.GLFW_PRESS) return;
		toggleBind(mappingModeKeybind, mappingMode, event.getKey(), false);
		toggleBind(buildingModeKeybind, buildingMode, event.getKey(), false);
		toggleBind(bypassModeKeybind, bypassMode, event.getKey(), false);
		toggleBind(playerAvoidanceKeybind, playerAvoidance, event.getKey(), false);
		toggleBind(antiSlamKeybind, antiSlam, event.getKey(), false);
		toggleBind(autoPilotKeybind, autoPilot, event.getKey(), false);
		toggleBind(highwayModeKeybind, highwayMode, event.getKey(), false);
	}

	@Subscribe(stage = Stage.ON)
	private void onMouse(EventMouse.Key event) {
		if (mc.screen != null || event.getAction() != GLFW.GLFW_PRESS) return;
		int button = event.getButton();
		toggleBind(mappingModeKeybind, mappingMode, button, true);
		toggleBind(buildingModeKeybind, buildingMode, button, true);
		toggleBind(bypassModeKeybind, bypassMode, button, true);
		toggleBind(playerAvoidanceKeybind, playerAvoidance, button, true);
		toggleBind(antiSlamKeybind, antiSlam, button, true);
		toggleBind(autoPilotKeybind, autoPilot, button, true);
		toggleBind(highwayModeKeybind, highwayMode, button, true);
	}

	private static void toggleBind(BindSetting bind, BooleanSetting setting, int code, boolean mouse) {
		IKey key = bind.getValue();
		if (key == null || key instanceof NullKey) return;
		boolean match = mouse ? key instanceof MouseKey mouseKey && mouseKey.getKeyCode() == code
			: key instanceof KeyboardKey keyboardKey && keyboardKey.getKeyCode() == code;
		if (match) setting.setValue(!setting.getValue());
	}

	private void autoTakeoff() {
		Player player = mc.player;
		if (player == null) return;
		if (incrementJumpTimer) jumpTimer++;
		boolean jumpPressed = mc.options.keyJump.isDown();
		if (autoTakeOff.getValue() && jumpPressed) {
			if (!lastJumpPressed && !gliding(player)) {
				jumpTimer = 0;
				incrementJumpTimer = true;
			}
			if (jumpTimer >= 8) {
				jumpTimer = 0;
				incrementJumpTimer = false;
				player.setJumping(false);
				player.setSprinting(true);
				player.jumpFromGround();
				if (player instanceof net.minecraft.client.player.LocalPlayer local && local.connection != null) {
					local.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
				}
			}
		}
		lastJumpPressed = jumpPressed;
	}

	private void handleAutopilot() {
		Player player = mc.player;
		if (player == null || !gliding(player)) return;
		if (!highwayMode.getValue()) resetHighwayState();
		highwaySteerTarget = null;
		highwayTargetY = Double.NaN;

		if (!avoidanceSteering) {
			if (highwayMode.getValue()) {
				driveHighwayMode(player);
			} else {
				releaseHighwayKeys();
				if (autoPilot.getValue() && player.getY() > autoPilotMinimumHeight.getValue()) {
					mc.options.keyUp.setDown(true);
					lastForwardPressed = true;
				}
			}
		} else if (highwayMode.getValue()) {
			assistantMarkActive();
			assistantSay("avoiding", false, false, "Wuh-oh, a player! I'm making a detour");
		}

		if (useFireworks.getValue()) {
			if (ticksLeft <= 0) {
				ticksLeft = autoPilotFireworkDelay.getValue() * 20;
				if (mc.gameMode == null || !InventoryActions.useFirework(player)) return;
			}
			ticksLeft--;
		}
	}

	// --- acceleration -------------------------------------------------------

	private void handleHorizontalSpeed(ClientLevel world) {
		boolean a = false;
		boolean b = false;
		if (mc.options.keyUp.isDown()) {
			velX += forward.x * acceleration * 10;
			velZ += forward.z * acceleration * 10;
			a = true;
		} else if (mc.options.keyDown.isDown()) {
			velX -= forward.x * acceleration * 10;
			velZ -= forward.z * acceleration * 10;
			a = true;
		}
		if (mc.options.keyRight.isDown()) {
			velX += right.x * acceleration * 10;
			velZ += right.z * acceleration * 10;
			b = true;
		} else if (mc.options.keyLeft.isDown()) {
			velX -= right.x * acceleration * 10;
			velZ -= right.z * acceleration * 10;
			b = true;
		}
		if (a && b) {
			double diagonal = 1 / Math.sqrt(2);
			velX *= diagonal;
			velZ *= diagonal;
		}

		if (mappingMode.getValue()) {
			mappingWaitingForChunks = !areChunksLoadedInRadius(world, mappingRenderRadius.getValue());
			if (mappingWaitingForChunks) {
				assistantFlag(AssistantSituation.CHUNKS);
				velX = 0;
				velZ = 0;
				resetHorizontalAcceleration();
			}
		} else {
			mappingWaitingForChunks = false;
		}
	}

	private boolean areChunksLoadedInRadius(ClientLevel world, int radius) {
		if (mc.player == null) return false;
		int centerX = (int) Math.floor(mc.player.getX()) >> 4;
		int centerZ = (int) Math.floor(mc.player.getZ()) >> 4;
		int radiusSq = radius * radius;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dz * dz > radiusSq) continue;
				if (!world.getChunkSource().hasChunk(centerX + dx, centerZ + dz)) return false;
			}
		}
		return true;
	}

	private void handleVerticalSpeed() {
		if (mc.options.keyJump.isDown()) velY += 0.5 * verticalAcceleration;
		else if (mc.options.keyShift.isDown()) velY -= 0.5 * verticalAcceleration;
	}

	private void handleFallMultiplier() {
		if (velY < 0) velY *= fallMultiplier.getValue();
		else if (velY > 0) velY = 0;
	}

	private void handleAntiSlam(ClientLevel world) {
		if (!antiSlam.getValue() || velY >= 0) return;
		double currentSpeed = -velY;
		double minSpeed = antiSlamMinSpeed.getValue();
		if (currentSpeed <= minSpeed) return;
		double startDist = antiSlamDistance.getValue();
		double minDist = Math.min(antiSlamMinDistance.getValue(), startDist);
		double distanceToGround = distanceToGroundBelow(world, startDist);
		if (distanceToGround >= startDist) return;
		double allowedSpeed = distanceToGround <= minDist
			? minSpeed
			: minSpeed + ((distanceToGround - minDist) / Math.max(startDist - minDist, 0.0001)) * (currentSpeed - minSpeed);
		if (allowedSpeed < currentSpeed) {
			velY = -allowedSpeed;
			assistantFlag(AssistantSituation.LANDING);
		}
	}

	private double distanceToGroundBelow(ClientLevel world, double maxDistance) {
		Player player = mc.player;
		if (player == null) return maxDistance;
		AABB box = player.getBoundingBox();
		double y = player.getY();
		double[] xs = {box.minX, (box.minX + box.maxX) / 2.0, box.maxX};
		double[] zs = {box.minZ, (box.minZ + box.maxZ) / 2.0, box.maxZ};
		double nearest = maxDistance;
		for (double x : xs) {
			for (double z : zs) {
				Vec3 start = new Vec3(x, y, z);
				BlockHitResult hit = world.clip(new ClipContext(start, start.add(0, -maxDistance, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
				if (hit.getType() != HitResult.Type.BLOCK) continue;
				double distance = y - hit.getLocation().y;
				if (distance < nearest) nearest = distance;
			}
		}
		return nearest;
	}

	private void handleBuildingMode(ClientLevel world) {
		if (!buildingMode.getValue() || !isNearAnyBlock(world, buildingModeDistance.getValue())) {
			buildingModeEngaged = false;
			return;
		}
		zeroAcceleration();
		double minSpeed = buildingModeMinSpeed.getValue();
		double verticalTarget = minSpeed * 1.5;
		double horizontal = Math.hypot(velX, velZ);
		double vertical = Math.abs(velY);
		if (!buildingModeEngaged) {
			buildingModeEntryHorizontalSpeed = Math.max(horizontal, minSpeed);
			buildingModeEntryVerticalSpeed = Math.max(vertical, verticalTarget);
			buildingModeTicksElapsed = 0;
			buildingModeEngaged = true;
		} else {
			buildingModeTicksElapsed++;
		}
		assistantFlag(AssistantSituation.BUILDING);
		double t = Math.min(1.0, buildingModeTicksElapsed / (double) BUILDING_MODE_SLOWDOWN_TICKS);
		double horizontalCap = buildingModeEntryHorizontalSpeed + (minSpeed - buildingModeEntryHorizontalSpeed) * t;
		double verticalCap = buildingModeEntryVerticalSpeed + (verticalTarget - buildingModeEntryVerticalSpeed) * t;
		if (horizontal > horizontalCap && horizontal > 1.0E-8) {
			double scale = horizontalCap / horizontal;
			velX *= scale;
			velZ *= scale;
		}
		if (vertical > verticalCap) velY = Math.signum(velY) * verticalCap;
	}

	private boolean isNearAnyBlock(ClientLevel world, double distance) {
		Player player = mc.player;
		if (player == null) return false;
		return world.getBlockCollisions(player, player.getBoundingBox().inflate(distance)).iterator().hasNext();
	}

	private void handleMaxHeight() {
		if (!limitMaxHeight.getValue() || velY <= 0 || mc.player == null) return;
		double limit = maxHeight.getValue();
		double currentY = mc.player.getY();
		if (currentY >= limit) {
			velY = 0;
			assistantFlag(AssistantSituation.HEIGHT_LIMIT);
		} else if (currentY + velY > limit) {
			velY = limit - currentY;
			assistantFlag(AssistantSituation.HEIGHT_LIMIT);
		}
	}

	private boolean isMovementKeyPressed() {
		return mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
	}

	private void resetRampOnCollision(Player player) {
		if (lastGlidePos == null || !player.horizontalCollision || lastGlideIntendedSpeed < 1.0E-3) return;
		Vec3 now = player.position();
		double moved = Math.hypot(now.x - lastGlidePos.x, now.z - lastGlidePos.z);
		if (moved >= lastGlideIntendedSpeed - 1.0E-3) return;
		double kept = Math.max(startSpeed.getValue(), moved);
		if (kept < acceleration) acceleration = kept;
		atMaxSpeed = acceleration >= horizontalSpeed.getValue();
		accelerationDelayTicks = 0;
	}

	private void handleAcceleration() {
		if (!isMovementKeyPressed()) {
			resetHorizontalAcceleration();
			return;
		}
		if (atMaxSpeed) return;
		if (accelerationDelayTicks < accelerationDelay.getValue()) {
			accelerationDelayTicks++;
			return;
		}
		double cap = horizontalSpeedCap();
		acceleration = nextSpeedFor(acceleration, accelerationStep.getValue(), accelerationPlateau.getValue(), cap);
		if (acceleration >= horizontalSpeed.getValue()) atMaxSpeed = true;
	}

	private static double curveGain(double currentSpeed, double step, double plateau) {
		if (plateau <= 0) return 0;
		double remainingToPlateau = Math.max(0, plateau - currentSpeed);
		return step * (remainingToPlateau / plateau);
	}

	private static double nextSpeedFor(double currentSpeed, double step, double plateau, double cap) {
		return Math.min(currentSpeed + curveGain(currentSpeed, step, plateau), cap);
	}

	private static double rewindSpeedFor(double currentSpeed, double step, double plateau, double floor, int ticks) {
		double speed = currentSpeed;
		for (int i = 0; i < ticks && speed > floor; i++) {
			speed = Math.max(floor, speed - curveGain(speed, step, plateau));
		}
		return speed;
	}

	private void zeroAcceleration() {
		resetHorizontalAcceleration();
		resetVerticalAcceleration();
	}

	private void resetVerticalAcceleration() {
		atMaxVerticalSpeed = false;
		verticalAccelerationDelayTicks = 0;
		verticalAcceleration = verticalStartSpeed.getValue();
	}

	private void resetHorizontalAcceleration() {
		atMaxSpeed = false;
		accelerationDelayTicks = 0;
		acceleration = startSpeed.getValue();
		bypassAccelerationDelayTicks = 0;
	}

	private double horizontalSpeedCap() {
		double cap = horizontalSpeed.getValue();
		if (rubberbandCapTicksLeft > 0) cap = Math.min(cap, rubberbandCapSpeed);
		return cap;
	}

	private void handleVerticalAcceleration() {
		boolean movingUp = mc.options.keyJump.isDown();
		boolean movingDown = mc.options.keyShift.isDown();
		if (!movingUp && !movingDown) {
			resetVerticalAcceleration();
			return;
		}
		if (movingUp && !accelerateUpward.getValue()) {
			resetVerticalAcceleration();
			return;
		}
		if (atMaxVerticalSpeed) return;
		if (verticalAccelerationDelayTicks < accelerationDelay.getValue()) {
			verticalAccelerationDelayTicks++;
			return;
		}
		verticalAcceleration = nextSpeedFor(verticalAcceleration, verticalAccelerationStep.getValue(), verticalAccelerationPlateau.getValue(), verticalSpeed.getValue());
		if (verticalAcceleration >= verticalSpeed.getValue()) atMaxVerticalSpeed = true;
	}

	// --- highway ------------------------------------------------------------

	private void driveHighwayMode(Player player) {
		ClientLevel world = mc.level;
		if (world == null) {
			releaseHighwayKeys();
			return;
		}
		assistantMarkActive();
		if (highwayAxis == null) armHighwayAxis(player);
		Vec3 pos = player.position();

		if (highwayDirection.getValue() == HighwayDirection.TowardsCentre && Math.hypot(pos.x, pos.z) < HIGHWAY_CENTRE_RADIUS) {
			highwayStuckTicks = 0;
			highwayMovingTicks = 0;
			highwayEscalation = 0;
			highwayLastPos = pos;
			Vec3 centre = new Vec3(0, Double.isNaN(highwayCruiseY) ? pos.y : highwayCruiseY, 0);
			assistantSay("centre", false, false, "We've reached the centre");
			highwaySteerTarget = centre;
			holdHighwayDrivingKeys();
			applyHighwayVerticalHold(player, centre.y);
			return;
		}

		if (highwayLastPos != null && !mappingWaitingForChunks) {
			if (pos.distanceTo(highwayLastPos) < HIGHWAY_STUCK_MOVE_EPSILON) {
				highwayStuckTicks++;
				highwayMovingTicks = 0;
			} else {
				highwayStuckTicks = 0;
				highwayMovingTicks++;
				if (highwayMovingTicks >= 60) highwayEscalation = 0;
			}
		}
		highwayLastPos = pos;
		if (highwayStuckTicks >= Math.round(highwayIntelligence.getValue().stuckTimeout * 20)) {
			highwayStuckTicks = 0;
			highwayEscalation = Math.min(highwayEscalation + 1, 6);
			highwayPathNodes.clear();
			if (highwayEscalation == 1) assistantSay("stuck1", true, false, "We're a lil' stuck", "I'll try and get us out");
			else if (highwayEscalation == 2) assistantSay("stuck2", true, false, "We're still stuck", "Lemme try a lil' harder to get us free");
			else assistantSay("stuck3", true, false, "Aww no luck!", "I'll try to think of a more creative way out");
		}

		highwayTicksSinceRepath++;
		boolean routeLeft = highwayPathNodes.size() >= 2 && highwayPathIndex < highwayPathNodes.size() - 1;
		if (!routeLeft || highwayTicksSinceRepath >= highwayIntelligence.getValue().repathInterval) {
			computeHighwayPath(world, pos);
		}

		Vec3 target = null;
		if (highwayPathNodes.size() >= 2) {
			advanceHighwayPathIndex(pos);
			target = pickHighwayWaypoint(player, world, pos);
		}
		if (target == null) {
			target = highwayGoal != null ? highwayGoal : goalOnHighway(pos, 0, 0);
			if (highwayPathMob == null) assistantSay("nopathfinder", true, false, "I'm having a lil' trouble navigating", "I'll head straight for the highway instead");
			else assistantSay("noroute", true, false, "I can't see a clear route", "We're just gonna head straight for the highway and hope for the best");
		} else {
			updateHighwayAssistant(pos, target);
		}
		highwaySteerTarget = target;
		holdHighwayDrivingKeys();
		applyHighwayVerticalHold(player, target.y);
	}

	private Parrot highwayPathMob(ClientLevel world) {
		if (highwayPathMob == null || highwayPathMobWorld != world) {
			try {
				highwayPathMob = new Parrot(net.minecraft.world.entity.EntityType.PARROT, world);
				highwayPathMobWorld = world;
			} catch (RuntimeException e) {
				highwayPathMob = null;
				highwayPathMobWorld = null;
			}
		}
		return highwayPathMob;
	}

	private Vec3 goalOnHighway(Vec3 pos, double lateralOffset, double heightOffset) {
		Vec3 axis = highwayAxis == null ? new Vec3(1, 0, 0) : highwayAxis;
		Vec3 lateral = new Vec3(-axis.z, 0, axis.x);
		double lat = pos.x * lateral.x + pos.z * lateral.z;
		double along = pos.x * axis.x + pos.z * axis.z;
		double range = highwayIntelligence.getValue().pathRange - 2;
		double ahead = Math.abs(lat) < 2.0 ? range : Math.min(range, Math.abs(lat) + 8.0);
		double reach = along + ahead;
		if (highwayDirection.getValue() == HighwayDirection.TowardsCentre) reach = Math.min(reach, 0);
		double gx = axis.x * reach + lateral.x * lateralOffset;
		double gz = axis.z * reach + lateral.z * lateralOffset;
		double dx = gx - pos.x, dz = gz - pos.z;
		double dist = Math.hypot(dx, dz);
		if (dist > range && dist > 1.0E-6) {
			gx = pos.x + dx / dist * range;
			gz = pos.z + dz / dist * range;
		}
		double cruiseY = Double.isNaN(highwayCruiseY) ? pos.y : highwayCruiseY;
		ClientLevel world = mc.level;
		double gy = cruiseY + heightOffset;
		if (world != null) gy = Math.max(world.getMinY() + 2, Math.min(world.getMinY() + world.getHeight() - 3, gy));
		return new Vec3(gx, gy, gz);
	}

	private void computeHighwayPath(ClientLevel world, Vec3 pos) {
		highwayTicksSinceRepath = 0;
		Parrot mob = highwayPathMob(world);
		if (mob == null) return;
		var followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
		if (followRange != null && followRange.getBaseValue() != highwayIntelligence.getValue().pathRange) {
			followRange.setBaseValue(highwayIntelligence.getValue().pathRange);
		}
		mob.setPos(pos.x, pos.y, pos.z);
		PathNavigation navigation = mob.getNavigation();
		ArrayList<Vec3> goals = new ArrayList<>(6);
		double step = 6.0 * Math.max(1, highwayEscalation);
		double firstSide = (highwayEscalation % 2 == 1) ? 1 : -1;
		if (highwayEscalation == 0) goals.add(goalOnHighway(pos, 0, 0));
		goals.add(goalOnHighway(pos, firstSide * step, 0));
		goals.add(goalOnHighway(pos, -firstSide * step, 0));
		goals.add(goalOnHighway(pos, 0, step));
		if (highwayEscalation > 0) goals.add(goalOnHighway(pos, 0, 0));

		for (Vec3 goal : goals) {
			Path path;
			try {
				path = navigation.createPath(BlockPos.containing(goal.x, goal.y, goal.z), 1);
			} catch (RuntimeException e) {
				continue;
			}
			if (path == null || path.getNodeCount() < 2) continue;
			BlockPos end = path.getNodePos(path.getNodeCount() - 1);
			Vec3 endPos = new Vec3(end.getX() + 0.5, end.getY() + HIGHWAY_NODE_HEIGHT, end.getZ() + 0.5);
			if (endPos.distanceTo(pos) < 2.0) continue;
			ArrayList<Vec3> nodes = new ArrayList<>(path.getNodeCount());
			for (int i = 0; i < path.getNodeCount(); i++) {
				BlockPos node = path.getNodePos(i);
				nodes.add(new Vec3(node.getX() + 0.5, node.getY() + HIGHWAY_NODE_HEIGHT, node.getZ() + 0.5));
			}
			Player player = mc.player;
			if (player != null) {
				if (!repairHighwayPath(world, player, nodes)) continue;
				if (nodes.getLast().distanceTo(pos) < 2.0) continue;
			}
			highwayPathNodes.clear();
			highwayPathNodes.addAll(nodes);
			highwayPathIndex = 0;
			highwayGoal = goal;
			return;
		}
	}

	private boolean repairHighwayPath(ClientLevel world, Player player, ArrayList<Vec3> nodes) {
		double loose = HIGHWAY_HARD_MARGIN;
		double strict = HIGHWAY_BLOCK_CLEARANCE - 1.0E-4;
		int n = nodes.size();
		boolean[] free = new boolean[n];
		boolean[] bad = new boolean[n];
		boolean anyBad = false;
		free[0] = true;
		for (int i = 1; i < n; i++) free[i] = highwayPointFree(world, player, nodes.get(i), loose);
		for (int i = 1; i < n; i++) {
			bad[i] = !free[i] || (free[i - 1] && highwayLineBlocked(world, player, nodes.get(i - 1), nodes.get(i), loose));
			anyBad |= bad[i];
		}
		if (!anyBad) return true;
		ArrayList<Vec3> out = new ArrayList<>(n + 8);
		int i = 0;
		while (i < n) {
			if (!bad[i]) {
				out.add(nodes.get(i));
				i++;
				continue;
			}
			int j = i;
			while (j < n && bad[j]) j++;
			if (j == n) break;
			List<BlockPos> detour = highwayDetour(world, player, highwayNodeCell(out.getLast()), highwayNodeCell(nodes.get(j)), strict);
			if (detour == null) detour = highwayDetour(world, player, highwayNodeCell(out.getLast()), highwayNodeCell(nodes.get(j)), loose);
			if (detour == null) return false;
			for (BlockPos cell : detour) {
				out.add(new Vec3(cell.getX() + 0.5, cell.getY() + HIGHWAY_NODE_HEIGHT, cell.getZ() + 0.5));
			}
			i = j;
		}
		if (out.size() < 2) return false;
		nodes.clear();
		nodes.addAll(out);
		return true;
	}

	private static BlockPos highwayNodeCell(Vec3 node) {
		return new BlockPos((int) Math.floor(node.x), (int) Math.round(node.y - HIGHWAY_NODE_HEIGHT), (int) Math.floor(node.z));
	}

	private boolean highwayPointFree(ClientLevel world, Player player, Vec3 p, double margin) {
		return highestBlockingY(world, highwayHitboxBox(player, p.x, p.y, p.z, margin), null) == Integer.MIN_VALUE;
	}

	private boolean highwayLineBlocked(ClientLevel world, Player player, Vec3 from, Vec3 to, double margin) {
		Vec3 delta = to.subtract(from);
		int steps = Math.max(1, (int) Math.ceil(delta.length() / 0.5));
		for (int k = 1; k <= steps; k++) {
			double t = (double) k / steps;
			if (!highwayPointFree(world, player, from.add(delta.scale(t)), margin)) return true;
		}
		return false;
	}

	private List<BlockPos> highwayDetour(ClientLevel world, Player player, BlockPos from, BlockPos to, double margin) {
		int minX = Math.min(from.getX(), to.getX()) - HIGHWAY_DETOUR_RADIUS, maxX = Math.max(from.getX(), to.getX()) + HIGHWAY_DETOUR_RADIUS;
		int minY = Math.max(world.getMinY(), Math.min(from.getY(), to.getY()) - HIGHWAY_DETOUR_RADIUS);
		int maxY = Math.min(world.getMinY() + world.getHeight() - 1, Math.max(from.getY(), to.getY()) + HIGHWAY_DETOUR_RADIUS);
		int minZ = Math.min(from.getZ(), to.getZ()) - HIGHWAY_DETOUR_RADIUS, maxZ = Math.max(from.getZ(), to.getZ()) + HIGHWAY_DETOUR_RADIUS;
		HashMap<BlockPos, BlockPos> cameFrom = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		cameFrom.put(from, from);
		queue.add(from);
		while (!queue.isEmpty() && cameFrom.size() < HIGHWAY_DETOUR_MAX_CELLS) {
			BlockPos cur = queue.poll();
			Vec3 curPoint = new Vec3(cur.getX() + 0.5, cur.getY() + HIGHWAY_NODE_HEIGHT, cur.getZ() + 0.5);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) continue;
						BlockPos next = cur.offset(dx, dy, dz);
						if (next.getX() < minX || next.getX() > maxX || next.getY() < minY || next.getY() > maxY || next.getZ() < minZ || next.getZ() > maxZ || cameFrom.containsKey(next)) continue;
						Vec3 nextPoint = new Vec3(next.getX() + 0.5, next.getY() + HIGHWAY_NODE_HEIGHT, next.getZ() + 0.5);
						if (!highwayPointFree(world, player, nextPoint, margin)) continue;
						if (highwayLineBlocked(world, player, curPoint, nextPoint, margin)) continue;
						cameFrom.put(next, cur);
						if (next.equals(to)) {
							LinkedList<BlockPos> cells = new LinkedList<>();
							for (BlockPos c = cameFrom.get(next); !c.equals(from); c = cameFrom.get(c)) cells.addFirst(c);
							return cells;
						}
						queue.add(next);
					}
				}
			}
		}
		return null;
	}

	private void advanceHighwayPathIndex(Vec3 pos) {
		int last = highwayPathNodes.size() - 1;
		while (highwayPathIndex < last) {
			Vec3 cur = highwayPathNodes.get(highwayPathIndex);
			Vec3 seg = highwayPathNodes.get(highwayPathIndex + 1).subtract(cur);
			double len2 = seg.lengthSqr();
			if (len2 < 1.0E-6 || pos.subtract(cur).dot(seg) / len2 >= 1.0) highwayPathIndex++;
			else break;
		}
	}

	private Vec3 pickHighwayWaypoint(Player player, ClientLevel world, Vec3 pos) {
		int first = highwayPathIndex + 1;
		if (first >= highwayPathNodes.size()) return null;
		int last = Math.min(highwayPathNodes.size() - 1, highwayPathIndex + HIGHWAY_MAX_LOOKAHEAD_NODES);
		AABB startBox = highwayHitboxBox(player, pos.x, pos.y, pos.z, HIGHWAY_BLOCK_CLEARANCE);
		for (int k = last; k > first; k--) {
			Vec3 candidate = highwayPathNodes.get(k);
			if (highwayRouteClear(world, player, pos, candidate, startBox)) return candidate;
		}
		return highwayPathNodes.get(first);
	}

	private boolean highwayRouteClear(ClientLevel world, Player player, Vec3 from, Vec3 to, AABB startBox) {
		if (Math.abs(to.y - from.y) > HIGHWAY_HEIGHT_BAND) return highwaySweepClear(world, player, from, to, startBox);
		Vec3 above = new Vec3(from.x, to.y, from.z);
		return highwaySweepClear(world, player, from, above, startBox) && highwaySweepClear(world, player, above, to, startBox);
	}

	private boolean highwaySweepClear(ClientLevel world, Player player, Vec3 from, Vec3 to, AABB startBox) {
		Vec3 delta = to.subtract(from);
		int steps = Math.max(1, (int) Math.ceil(delta.length() / 0.5));
		for (int i = 1; i <= steps; i++) {
			double t = (double) i / steps;
			AABB box = highwayHitboxBox(player, from.x + delta.x * t, from.y + delta.y * t, from.z + delta.z * t, HIGHWAY_BLOCK_CLEARANCE);
			if (highestBlockingY(world, box, startBox) != Integer.MIN_VALUE) return false;
		}
		return true;
	}

	private void holdHighwayDrivingKeys() {
		if (!isKeyPhysicallyPressed(mc.options.keyUp)) mc.options.keyUp.setDown(true);
		if (!isKeyPhysicallyPressed(mc.options.keyDown)) mc.options.keyDown.setDown(false);
		if (!isKeyPhysicallyPressed(mc.options.keyLeft)) mc.options.keyLeft.setDown(false);
		if (!isKeyPhysicallyPressed(mc.options.keyRight)) mc.options.keyRight.setDown(false);
		lastForwardPressed = mc.options.keyUp.isDown();
	}

	private void applyHighwayVerticalHold(Player player, double targetY) {
		highwayTargetY = targetY;
		double verticalError = targetY - player.getY();
		mc.options.keyJump.setDown(verticalError > HIGHWAY_HEIGHT_BAND);
		mc.options.keyShift.setDown(verticalError < -HIGHWAY_HEIGHT_BAND);
		if (Math.abs(verticalError) <= HIGHWAY_HEIGHT_BAND) velY = verticalError;
	}

	private void applyHighwaySteering() {
		if (highwaySteerTarget == null || mc.player == null) return;
		double speed = Math.hypot(velX, velZ);
		if (speed < 1.0E-6) return;
		Vec3 pos = mc.player.position();
		double dx = highwaySteerTarget.x - pos.x;
		double dz = highwaySteerTarget.z - pos.z;
		double horizontalDistance = Math.hypot(dx, dz);
		if (horizontalDistance < 1.0E-3) {
			velX = 0;
			velZ = 0;
			return;
		}
		double step = Math.min(speed, horizontalDistance);
		velX = dx / horizontalDistance * step;
		velZ = dz / horizontalDistance * step;
	}

	private void applyHighwaySlopeCoupling() {
		if (highwaySteerTarget == null || mc.player == null) return;
		Vec3 pos = mc.player.position();
		double dy = highwaySteerTarget.y - pos.y;
		if (Math.abs(dy) <= HIGHWAY_HEIGHT_BAND || Math.abs(velY) < 1.0E-6) return;
		double horizontal = Math.hypot(velX, velZ);
		if (horizontal < 1.0E-6) return;
		double horizontalDistance = Math.hypot(highwaySteerTarget.x - pos.x, highwaySteerTarget.z - pos.z);
		double share = Math.min(1.0, Math.abs(velY) / Math.abs(dy));
		double allowed = horizontalDistance * share;
		if (allowed < horizontal) {
			double scale = allowed / horizontal;
			velX *= scale;
			velZ *= scale;
		}
	}

	private static Vec3 nearestHighwayDirection(double x, double z) {
		double distToXAxis = Math.abs(z);
		double distToZAxis = Math.abs(x);
		double distToDiag1 = Math.abs(z - x) / Math.sqrt(2);
		double distToDiag2 = Math.abs(z + x) / Math.sqrt(2);
		double nearest = Math.min(Math.min(distToXAxis, distToZAxis), Math.min(distToDiag1, distToDiag2));
		if (nearest == distToXAxis) return new Vec3(1, 0, 0);
		if (nearest == distToZAxis) return new Vec3(0, 0, 1);
		if (nearest == distToDiag1) return new Vec3(1, 0, 1);
		return new Vec3(1, 0, -1);
	}

	private void armHighwayAxis(Player player) {
		double x = player.getX();
		double z = player.getZ();
		Vec3 dir = nearestHighwayDirection(x, z);
		double dot = x * dir.x + z * dir.z;
		boolean awayFromCentre = highwayDirection.getValue() == HighwayDirection.AwayFromCentre;
		double sign = (dot >= 0) == awayFromCentre ? 1 : -1;
		highwayAxis = new Vec3(dir.x * sign, 0, dir.z * sign).normalize();
		highwayCruiseY = player.getY();
		assistantSay("armed", false, true, "Locked onto the " + highwayHeadingName(highwayAxis) + " highway", "Here we go");
	}

	private void resetHighwayState() {
		highwayAxis = null;
		highwayCruiseY = Double.NaN;
		highwayGoal = null;
		highwaySteerTarget = null;
		highwayTargetY = Double.NaN;
		highwayPathNodes.clear();
		highwayPathIndex = 0;
		highwayTicksSinceRepath = 0;
		highwayStuckTicks = 0;
		highwayMovingTicks = 0;
		highwayEscalation = 0;
		highwayLastPos = null;
	}

	private int highestBlockingY(ClientLevel world, AABB box, AABB ignoreBox) {
		int top = Integer.MIN_VALUE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int maxX = (int) Math.floor(box.maxX), maxY = (int) Math.floor(box.maxY), maxZ = (int) Math.floor(box.maxZ);
		for (int bx = (int) Math.floor(box.minX); bx <= maxX; bx++) {
			for (int by = (int) Math.floor(box.minY) - 1; by <= maxY; by++) {
				for (int bz = (int) Math.floor(box.minZ); bz <= maxZ; bz++) {
					pos.set(bx, by, bz);
					VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
					if (shape.isEmpty()) continue;
					AABB blockBox = shape.bounds().move(pos.getX(), pos.getY(), pos.getZ());
					if (!box.intersects(blockBox)) continue;
					if (ignoreBox != null && ignoreBox.intersects(blockBox)) continue;
					top = Math.max(top, by);
				}
			}
		}
		return top;
	}

	private void limitHighwayVerticalStep() {
		if (Double.isNaN(highwayTargetY) || !highwayMode.getValue() || mc.player == null) return;
		double error = highwayTargetY - mc.player.getY();
		if (velY > 0) velY = Math.min(velY, Math.max(0, error));
		else if (velY < 0) velY = Math.max(velY, Math.min(0, error));
	}

	private void applyHighwayClearanceGuard(Player player, ClientLevel world) {
		if (!highwayMode.getValue() || highwayAxis == null) return;
		if (Math.abs(velX) + Math.abs(velY) + Math.abs(velZ) < 1.0E-6) return;
		double x = player.getX(), y = player.getY(), z = player.getZ();
		AABB start = highwayHitboxBox(player, x, y, z, HIGHWAY_BLOCK_CLEARANCE);
		AABB hardStart = highwayHitboxBox(player, x, y, z, HIGHWAY_HARD_MARGIN);
		double horizontalBefore = Math.hypot(velX, velZ);
		double allowedY = clampHighwayAxis(world, player, x, y, z, 1, velY, start, hardStart);
		y += allowedY;
		double allowedX = clampHighwayAxis(world, player, x, y, z, 0, velX, start, hardStart);
		x += allowedX;
		double allowedZ = clampHighwayAxis(world, player, x, y, z, 2, velZ, start, hardStart);
		velX = allowedX;
		velY = allowedY;
		velZ = allowedZ;
		double horizontalAfter = Math.hypot(velX, velZ);
		if (horizontalAfter < horizontalBefore - 1.0E-3) {
			double kept = Math.max(startSpeed.getValue(), horizontalAfter);
			if (kept < acceleration) acceleration = kept;
			atMaxSpeed = acceleration >= horizontalSpeed.getValue();
			accelerationDelayTicks = 0;
		}
	}

	private double clampHighwayAxis(ClientLevel world, Player player, double x, double y, double z, int axis, double delta, AABB start, AABB hardStart) {
		if (Math.abs(delta) < 1.0E-9) return delta;
		int steps = Math.max(1, (int) Math.ceil(Math.abs(delta) / 0.25));
		for (int i = 1; i <= steps; i++) {
			if (!highwayAxisMoveSafe(world, player, x, y, z, axis, delta * i / steps, start, hardStart)) {
				double lo = (double) (i - 1) / steps, hi = (double) i / steps;
				for (int k = 0; k < 6; k++) {
					double mid = (lo + hi) / 2;
					if (highwayAxisMoveSafe(world, player, x, y, z, axis, delta * mid, start, hardStart)) lo = mid;
					else hi = mid;
				}
				return delta * lo;
			}
		}
		return delta;
	}

	private boolean highwayAxisMoveSafe(ClientLevel world, Player player, double x, double y, double z, int axis, double move, AABB start, AABB hardStart) {
		double nx = x + (axis == 0 ? move : 0), ny = y + (axis == 1 ? move : 0), nz = z + (axis == 2 ? move : 0);
		if (highestBlockingY(world, highwayHitboxBox(player, nx, ny, nz, HIGHWAY_BLOCK_CLEARANCE), start) != Integer.MIN_VALUE) return false;
		return highestBlockingY(world, highwayHitboxBox(player, nx, ny, nz, HIGHWAY_HARD_MARGIN), hardStart) == Integer.MIN_VALUE;
	}

	private AABB highwayHitboxBox(Player player, double x, double y, double z, double margin) {
		AABB base = player.getBoundingBox();
		double halfWidth = (base.maxX - base.minX) / 2;
		double halfDepth = (base.maxZ - base.minZ) / 2;
		double height = base.maxY - base.minY;
		return new AABB(x - halfWidth, y, z - halfDepth, x + halfWidth, y + height, z + halfDepth).inflate(margin);
	}

	private void releaseHighwayKeys() {
		releaseHorizontalKeys();
		if (mc.options == null) return;
		if (!isKeyPhysicallyPressed(mc.options.keyJump)) mc.options.keyJump.setDown(false);
		if (!isKeyPhysicallyPressed(mc.options.keyShift)) mc.options.keyShift.setDown(false);
	}

	// --- assistant ----------------------------------------------------------

	public String getAssistantText() {
		boolean highway = highwayMode.getValue();
		Player player = mc.player;
		boolean flying = player != null && gliding(player);
		long now = System.currentTimeMillis();
		AssistantSituation situation = flying ? currentAssistantSituation(highway) : null;
		if (situation != null) {
			boolean stale = !highway && assistantKey != null && !assistantKey.startsWith("g-");
			assistantSayGeneral(situation, stale);
		} else if (!highway) {
			boolean holdingSituation = assistantKey != null && assistantKey.startsWith("g-") && !assistantKey.startsWith(ASSISTANT_BASELINE_PREFIX) && now - assistantSinceMs < ASSISTANT_GENERAL_MIN_SHOW_MS;
			if (!holdingSituation) {
				boolean stale = assistantKey != null && !assistantKey.startsWith("g-");
				assistantSayBaseline(flying, stale);
			}
		}
		if (highway && now - assistantLastActiveMs > 1000) {
			assistantSay("idle", false, true, "Hey there - ooh you've got highway mode on", "Just take off and I'll guide us along the highway");
		}
		return assistantText.isEmpty() ? null : assistantText;
	}

	private void assistantSayBaseline(boolean flying, boolean force) {
		if (!flying) assistantSay(ASSISTANT_BASELINE_PREFIX + "ready", false, force, "Hey there", "Just take off and I'll keep an eye on some stuff for ya");
		else if (autoPilot.getValue()) assistantSay(ASSISTANT_BASELINE_PREFIX + "autopilot", false, force, "I'll take control for now", "Sit back and enjoy the flight");
		else if (mappingMode.getValue()) assistantSay(ASSISTANT_BASELINE_PREFIX + "mapping", false, force, "Mapping Mode is on", "I'll stop us periodically to let chunks load");
		else if (buildingMode.getValue()) assistantSay(ASSISTANT_BASELINE_PREFIX + "building", false, force, "Building Mode is on", "I'm gonna slow us down when we get near blocks");
		else assistantSay(ASSISTANT_BASELINE_PREFIX + "flying", false, force, "Flying smoothly");
	}

	public boolean isAssistantSorry() {
		return assistantNegative;
	}

	private void assistantFlag(AssistantSituation situation) {
		assistantSituationSeen.set(situation.ordinal(), System.currentTimeMillis());
	}

	private AssistantSituation currentAssistantSituation(boolean highway) {
		long now = System.currentTimeMillis();
		for (AssistantSituation situation : AssistantSituation.values()) {
			if (situation == AssistantSituation.CHUNKS && highway) continue;
			if (now - assistantSituationSeen.get(situation.ordinal()) < ASSISTANT_SITUATION_LINGER_MS) return situation;
		}
		return null;
	}

	private void assistantSayGeneral(AssistantSituation situation, boolean force) {
		String key = "g-" + situation.name();
		switch (situation) {
			case CHUNKS -> assistantSay(key + mappingRenderRadius.getValue(), false, force, "Waiting for chunks to load", "Render radius is " + mappingRenderRadius.getValue());
			case NO_CRASH -> assistantSay(key, false, force, "Uh-oh, a threat! I'll try and keep us safe");
			case UNLOADED_CHUNKS -> assistantSay(key, false, force, "The chunks ahead haven't loaded yet, I'll stop us until they load");
			case RUBBERBAND_CAP -> assistantSay(key, false, force, "The server's speed limit might have changed briefly~ I'll cap our speed for a lil' bit to match and then speed up again");
			case AVOIDANCE -> assistantSay(key, false, force, "A threat is pretty close, so I'll distance us a lil' bit");
			case BUILDING -> assistantSay(key, false, force, "Blocks detected nearby! Slowing us down");
			case LANDING -> assistantSay(key, false, force, "Easing our descent to avoid damage");
			case HEIGHT_LIMIT -> assistantSay(key, false, force, "We've reached the height limit");
		}
	}

	private void resetAssistant() {
		for (int i = 0; i < assistantSituationSeen.length(); i++) assistantSituationSeen.set(i, 0);
		assistantKey = null;
		assistantText = "";
		assistantNegative = false;
		assistantLastActiveMs = 0;
	}

	private void assistantMarkActive() {
		assistantLastActiveMs = System.currentTimeMillis();
	}

	private void assistantSay(String key, boolean negative, boolean force, String... sentences) {
		if (key.equals(assistantKey)) return;
		long now = System.currentTimeMillis();
		if (!force && assistantKey != null) {
			long hold = assistantNegative ? ASSISTANT_SORRY_HOLD_MS : ASSISTANT_HAPPY_HOLD_MS;
			boolean urgent = negative && !assistantNegative;
			if (!urgent && now - assistantSinceMs < hold) return;
		}
		StringBuilder sb = new StringBuilder();
		for (String sentence : sentences) {
			if (!sb.isEmpty()) sb.append(' ');
			sb.append(sentence).append(assistantEnding(negative));
		}
		assistantKey = key;
		assistantNegative = negative;
		assistantSinceMs = now;
		assistantText = sb.toString();
	}

	private String assistantEnding(boolean negative) {
		String[] pool = negative ? ASSISTANT_SORRY_ENDINGS : ASSISTANT_HAPPY_ENDINGS;
		String ending;
		int guard = 0;
		do {
			ending = pool[assistantRandom.nextInt(pool.length)];
		} while (ending.equals(assistantLastEnding) && ++guard < 10);
		assistantLastEnding = ending;
		boolean attached = ending.equals("~") || ending.equals("...");
		return attached ? ending : " " + ending;
	}

	private static String highwayHeadingName(Vec3 axis) {
		String ns = axis.z < -0.1 ? "north" : axis.z > 0.1 ? "south" : "";
		String ew = axis.x > 0.1 ? "east" : axis.x < -0.1 ? "west" : "";
		if (ns.isEmpty()) return ew;
		if (ew.isEmpty()) return ns;
		return ns + "-" + ew;
	}

	private void updateHighwayAssistant(Vec3 pos, Vec3 target) {
		Vec3 axis = highwayAxis;
		if (axis == null || currentAssistantSituation(true) != null) return;
		if (mappingWaitingForChunks) {
			assistantSay("chunks", false, false, "Waiting for chunks ahead to load", "One moment");
			return;
		}
		double dy = target.y - pos.y;
		if (dy > 1.5) {
			assistantSay("climbing", false, false, "Something's in the way", "I'll try and take us over the top");
			return;
		}
		if (dy < -1.5) {
			assistantSay("descending", false, false, "Returning us to cruising height");
			return;
		}
		Vec3 lateral = new Vec3(-axis.z, 0, axis.x);
		double lat = pos.x * lateral.x + pos.z * lateral.z;
		double targetLat = target.x * lateral.x + target.z * lateral.z;
		if (Math.abs(lat) > 3.0) assistantSay("merging", false, false, "Merging onto the " + highwayHeadingName(axis) + " highway");
		else if (Math.abs(targetLat - lat) > 2.5) assistantSay("swerving", false, false, "Ah! There's an obstacle - manoeuvring around it");
		else assistantSay("cruising", false, false, "Cruising along the " + highwayHeadingName(axis) + " highway");
	}

	// --- avoidance ----------------------------------------------------------

	private void updatePlayerAvoidance() {
		if (!playerAvoidance.getValue()) {
			releaseAvoidance();
			releaseVerticalStep();
			return;
		}
		if (isMovementKeyPhysicallyPressed() || isKeyPhysicallyPressed(mc.options.keyShift)) {
			releaseAvoidance();
			releaseVerticalStep();
			return;
		}
		Vec3 away = findAvoidanceAwayVector();
		if (away == null) {
			releaseAvoidance();
			releaseVerticalStep();
			return;
		}
		Player player = mc.player;
		if (player == null || !gliding(player)) return;
		if (verticalStepActive) {
			avoidanceSteering = true;
			assistantFlag(AssistantSituation.AVOIDANCE);
			stepVerticalStep();
			return;
		}
		steerTowardsDirection(away);
		avoidanceSteering = true;
		assistantFlag(AssistantSituation.AVOIDANCE);
		updateAvoidanceStuckDetection();
	}

	private void steerTowardsDirection(Vec3 dir) {
		Player player = mc.player;
		if (player == null) return;
		double targetYaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
		double relative = wrapDegrees(targetYaw - player.getYRot());
		int octant = ((int) Math.round(relative / 45.0) % 8 + 8) % 8;
		mc.options.keyUp.setDown(octant == 0 || octant == 1 || octant == 7);
		mc.options.keyDown.setDown(octant == 3 || octant == 4 || octant == 5);
		mc.options.keyRight.setDown(octant == 1 || octant == 2 || octant == 3);
		mc.options.keyLeft.setDown(octant == 5 || octant == 6 || octant == 7);
	}

	private Vec3 findAvoidanceAwayVector() {
		Player self = mc.player;
		ClientLevel world = mc.level;
		if (self == null || world == null) return null;
		double[] acc = {0, 0};
		boolean foundThreat = false;
		for (Player player : world.players()) {
			if (player == self) continue;
			if (avoidanceIgnoreFriends.getValue() && RusherHackAPI.getRelationManager().isFriend(player)) continue;
			if (accumulateThreat(player.position(), avoidanceRadius.getValue(), acc)) foundThreat = true;
		}
		if (avoidWitherSkulls.getValue() || avoidArrows.getValue()) {
			for (Entity entity : world.entitiesForRendering()) {
				if (avoidWitherSkulls.getValue() && entity instanceof WitherSkull) {
					if (accumulateThreat(entity.position(), witherSkullRadius.getValue(), acc)) foundThreat = true;
				} else if (avoidArrows.getValue() && entity instanceof Arrow) {
					if (accumulateThreat(entity.position(), arrowRadius.getValue(), acc)) foundThreat = true;
				}
			}
		}
		if (avoidBlocks.getValue() && isBlockAvoidanceTriggered(world)) {
			if (accumulateNearbyBlockThreats(world, blockAvoidanceRadius.getValue(), acc)) foundThreat = true;
		}
		if (!foundThreat) return null;
		Vec3 direction = new Vec3(acc[0], 0, acc[1]);
		if (direction.lengthSqr() == 0) return null;
		direction = direction.normalize();
		if (avoidanceLateral.getValue()) direction = lateralize(direction);
		else avoidanceLateralDir = null;
		return direction;
	}

	private boolean isBlockAvoidanceTriggered(ClientLevel world) {
		Player self = mc.player;
		if (self == null) return false;
		Vec3 selfPos = self.position();
		double radius = avoidanceRadius.getValue();
		if (radius > 0) {
			double radiusSq = radius * radius;
			for (Player player : world.players()) {
				if (player == self) continue;
				if (avoidanceIgnoreFriends.getValue() && RusherHackAPI.getRelationManager().isFriend(player)) continue;
				if (selfPos.distanceToSqr(player.position()) < radiusSq) return true;
			}
		}
		double skullRadius = witherSkullRadius.getValue();
		if (avoidWitherSkulls.getValue() && skullRadius > 0) {
			double skullRadiusSq = skullRadius * skullRadius;
			for (Entity entity : world.entitiesForRendering()) {
				if (entity instanceof WitherSkull && selfPos.distanceToSqr(entity.position()) < skullRadiusSq) return true;
			}
		}
		return false;
	}

	private Vec3 lateralize(Vec3 away) {
		Vec3 perpA = new Vec3(-away.z, 0, away.x);
		Vec3 perpB = new Vec3(away.z, 0, -away.x);
		boolean sideA;
		if (avoidanceLateralDir != null) sideA = avoidanceLateralDir.dot(perpA) >= avoidanceLateralDir.dot(perpB);
		else {
			Player player = mc.player;
			Vec3 vel = player != null ? player.getDeltaMovement() : Vec3.ZERO;
			Vec3 horizontalVel = new Vec3(vel.x, 0, vel.z);
			sideA = !(horizontalVel.lengthSqr() > 1.0E-4 && horizontalVel.dot(perpB) > horizontalVel.dot(perpA));
		}
		double baseAngle = sideA ? 90 : -90;
		Vec3 optionA = rotateHorizontal(away, baseAngle - 10);
		Vec3 optionB = rotateHorizontal(away, baseAngle + 10);
		Vec3 chosen = optionA.dot(away) >= optionB.dot(away) ? optionA : optionB;
		avoidanceLateralDir = chosen;
		return chosen;
	}

	private Vec3 rotateHorizontal(Vec3 v, double degrees) {
		double rad = Math.toRadians(degrees);
		double cos = Math.cos(rad);
		double sin = Math.sin(rad);
		return new Vec3(v.x * cos - v.z * sin, 0, v.x * sin + v.z * cos);
	}

	private boolean accumulateThreat(Vec3 threatPos, double radius, double[] acc) {
		if (radius <= 0 || mc.player == null) return false;
		double distanceSq = mc.player.position().distanceToSqr(threatPos);
		if (distanceSq >= radius * radius || distanceSq == 0) return false;
		Vec3 awayFromThreat = mc.player.position().subtract(threatPos);
		awayFromThreat = new Vec3(awayFromThreat.x, 0, awayFromThreat.z);
		if (awayFromThreat.lengthSqr() == 0) return false;
		double weight = 1 - (Math.sqrt(distanceSq) / radius);
		awayFromThreat = awayFromThreat.normalize().scale(weight);
		acc[0] += awayFromThreat.x;
		acc[1] += awayFromThreat.z;
		return true;
	}

	private boolean accumulateNearbyBlockThreats(ClientLevel world, double radius, double[] acc) {
		if (radius <= 0 || mc.player == null) return false;
		boolean foundThreat = false;
		BlockPos center = mc.player.blockPosition();
		int r = (int) Math.ceil(radius);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = 0; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState state = world.getBlockState(pos);
					boolean isCobweb = state.is(Blocks.COBWEB);
					if (!isCobweb && state.getCollisionShape(world, pos).isEmpty()) continue;
					Vec3 blockCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
					if (accumulateThreat(blockCenter, radius, acc)) foundThreat = true;
				}
			}
		}
		return foundThreat;
	}

	private void updateAvoidanceStuckDetection() {
		Player player = mc.player;
		if (player == null) return;
		avoidanceStuckTicksCount = player.horizontalCollision ? avoidanceStuckTicksCount + 1 : 0;
		if (avoidanceStuckTicksCount < AVOIDANCE_STUCK_TICKS) return;
		beginVerticalStep();
	}

	private void beginVerticalStep() {
		Player player = mc.player;
		if (player == null) return;
		boolean upClear = hasVerticalClearance(1.0);
		boolean downClear = hasVerticalClearance(-1.0);
		if (upClear) verticalStepUp = true;
		else if (downClear) verticalStepUp = false;
		else {
			avoidanceStuckTicksCount = 0;
			return;
		}
		verticalStepActive = true;
		verticalStepStartY = player.getY();
		verticalStepTicks = 0;
		avoidanceStuckTicksCount = 0;
		releaseHorizontalKeys();
	}

	private boolean hasVerticalClearance(double dy) {
		Player player = mc.player;
		ClientLevel world = mc.level;
		if (player == null || world == null) return false;
		AABB box = player.getBoundingBox().move(0, dy, 0);
		return !world.getBlockCollisions(player, box).iterator().hasNext();
	}

	private void stepVerticalStep() {
		Player player = mc.player;
		if (player == null) {
			releaseVerticalStep();
			return;
		}
		verticalStepTicks++;
		double traveled = Math.abs(player.getY() - verticalStepStartY);
		if (traveled >= 1.0 || verticalStepTicks > VERTICAL_STEP_TIMEOUT_TICKS) {
			releaseVerticalStep();
			return;
		}
		if (!isKeyPhysicallyPressed(mc.options.keyJump)) mc.options.keyJump.setDown(verticalStepUp);
		if (!isKeyPhysicallyPressed(mc.options.keyShift)) mc.options.keyShift.setDown(!verticalStepUp);
	}

	private void releaseVerticalStep() {
		avoidanceStuckTicksCount = 0;
		if (verticalStepActive && mc.options != null) {
			if (!isKeyPhysicallyPressed(mc.options.keyJump)) mc.options.keyJump.setDown(false);
			if (!isKeyPhysicallyPressed(mc.options.keyShift)) mc.options.keyShift.setDown(false);
		}
		verticalStepActive = false;
		verticalStepTicks = 0;
	}

	private void releaseHorizontalKeys() {
		if (mc.options == null) return;
		if (!isKeyPhysicallyPressed(mc.options.keyUp)) mc.options.keyUp.setDown(false);
		if (!isKeyPhysicallyPressed(mc.options.keyDown)) mc.options.keyDown.setDown(false);
		if (!isKeyPhysicallyPressed(mc.options.keyLeft)) mc.options.keyLeft.setDown(false);
		if (!isKeyPhysicallyPressed(mc.options.keyRight)) mc.options.keyRight.setDown(false);
	}

	private void releaseAvoidance() {
		avoidanceLateralDir = null;
		if (!avoidanceSteering) return;
		releaseHorizontalKeys();
		avoidanceSteering = false;
	}

	private boolean isMovementKeyPhysicallyPressed() {
		return isKeyPhysicallyPressed(mc.options.keyUp) || isKeyPhysicallyPressed(mc.options.keyDown)
			|| isKeyPhysicallyPressed(mc.options.keyLeft) || isKeyPhysicallyPressed(mc.options.keyRight);
	}

	private boolean isKeyPhysicallyPressed(KeyMapping binding) {
		if (binding == null) return false;
		if (mc.getWindow() == null) return binding.isDown();
		try {
			InputConstants.Key key = ((IMixinKeyMapping) binding).getKey();
			if (key.getType() != InputConstants.Type.KEYSYM) return binding.isDown();
			return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
		} catch (Throwable ignored) {
			return binding.isDown();
		}
	}

	private static double wrapDegrees(double degrees) {
		double wrapped = degrees % 360.0;
		if (wrapped >= 180.0) wrapped -= 360.0;
		if (wrapped < -180.0) wrapped += 360.0;
		return wrapped;
	}

	private enum AssistantSituation {
		CHUNKS, NO_CRASH, UNLOADED_CHUNKS, RUBBERBAND_CAP, AVOIDANCE, BUILDING, LANDING, HEIGHT_LIMIT
	}

	public enum ChestSwapMode {
		Always,
		Never,
		WaitForGround
	}

	public enum Intelligence {
		Low(8, 20, 5.0),
		Medium(64, 8, 3.0),
		High(256, 1, 1.0);

		public final int pathRange;
		public final int repathInterval;
		public final double stuckTimeout;

		Intelligence(int pathRange, int repathInterval, double stuckTimeout) {
			this.pathRange = pathRange;
			this.repathInterval = repathInterval;
			this.stuckTimeout = stuckTimeout;
		}
	}

	public enum HighwayDirection {
		AwayFromCentre,
		TowardsCentre
	}
}
