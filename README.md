# VolytraFly for RusherHack

RusherHack plugin that ports [VolytraFly-Addon](https://github.com/Volizray/VolytraFly-Addon) 0.1.1 by Volizray. That addon is a Meteor Client module for maximising elytra flight. It is dedicated to the public domain under [CC0 1.0](LICENSE). This repository keeps that dedication and reimplements the module with RusherHack's plugin, module, setting, and event APIs. The Gradle layout follows [RusherDevelopment/example-plugin](https://github.com/RusherDevelopment/example-plugin). See [NOTICE](NOTICE) for the attribution notes carried over from the addon.

## What was ported

One movement module, `VolytraFly`, registered from `com.volytrafly.rusher.VolytraFlyPlugin`, plus four HUD elements (speedometer, elytra fuel gauge, mode switches, and the Volytra assistant). Future Sight is drawn from the same plugin listener during 3D render. The original addon has no custom mixins and no command; this plugin does not add either.

Settings use the same names and groups as the Meteor module (General, Mapping, Building Mode, Bypass Mode, Player Avoidance, Landing, Inventory, Autopilot, Highway Mode, Visuals). Groups are `NullSetting` parents, which is how the RusherHack API nests settings. Mode keybinds are `BindSetting`s. Toggles listen for key and mouse presses while no screen is open.

Flight behaviour follows the addon:

- Plateau acceleration curve, including the 15-tick rewind after a position correction while gliding.
- Rubberband speed cap (three qualifying corrections within three seconds, speed within 5% above the threshold, cap held for 10 seconds).
- Bypass mode scales the player's real horizontal velocity with that same curve.
- Mapping mode waits until chunks in radius are loaded, then resets the horizontal ramp.
- Building mode eases to its minimum speed over five ticks.
- Anti-slam samples a 3×3 footprint under the player. No-crash looks ahead. Unloaded chunks can block horizontal flight. Water can cancel flight and resend the start-fall-flying packet. Max height is optional. Fall multiplier clears upward vanilla velocity before the vertical keys add to it.
- Auto takeoff after jump is held for eight ticks, then a jump and a start-fall-flying packet.
- Insta-drop and the WaitForGround chest swap keep running after the module is turned off. RusherHack only delivers module events while the module is toggled, so those two, plus Future Sight, live on a listener the plugin subscribes for the whole session.
- Highway mode snaps to the nearest axis or diagonal through the origin and pathfinds with a client-side parrot. Route repair uses the same detour radius and cell budget as the addon.
- Player avoidance uses WASD octants without changing yaw, skips friends, and can sidestep players, wither skulls, arrows, and blocks. The vertical step still happens after three ticks of horizontal collision (the 0.1.1 addon removed the setting and left the behaviour).
- Fireworks come from the offhand, otherwise a hotbar swap, on the configured delay. Elytra below the durability threshold are replaced from storage. Chest swap is an inventory click sequence: glider on the chest slot swaps toward chest armour, and the other way equips an elytra.
- The assistant uses the original situation strings, hold times, and priority.

## Build

Java 21. From this directory:

```bash
./gradlew build
```

The plugin jar is `build/libs/volytrafly-0.1.1.jar`. Install it the way other RusherHack plugins are installed: copy that jar into the client's `rusherhack/plugins` folder. The [rusherhack-plugins readme](https://github.com/RusherDevelopment/rusherhack-plugins?tab=readme-ov-file#installation) describes that folder.

`./gradlew runPlugin` is the template's client launch task. It refuses to start until `lib/rusherhack-loader.jar` is present, and that loader is not part of this repository. The jar above was produced with `./gradlew build`. The client was not launched from here, so packet timing and HUD drawing were not checked in-game.

### Toolchain

VolytraFly-Addon targets Minecraft 1.21.11. The example plugin targets 1.21.4, and that API snapshot does not match the 1.21.11 game classes this port compiles against. This project therefore uses:

| | Example plugin | This port |
| --- | --- | --- |
| Minecraft / `rusherhack-api` | 1.21.4 | 1.21.11-SNAPSHOT |
| Fabric Loom | 1.9-SNAPSHOT | 1.14.10 |
| Gradle | 8.11 | 9.2.0 |
| Parchment | 1.21.4:2025.03.23 | 1.21.11:2025.12.20 |
| Fabric loader | 0.17.2 | 0.17.2 |
| Java | 21 | 21 |

Gradle 9 dropped `archivesBaseName`, so the jar name is set with `base.archivesName`. Mappings are Mojmap plus Parchment, same as the template. `updateAccessWidener` still copies `rusherhack.accesswidener` out of the API jar before launch configs are generated.

## Not one-to-one

These pieces follow the addon's behaviour as far as the RusherHack API goes, and then stop:

- **CRT phosphor.** Meteor's HUD renderer draws arbitrary triangles, and the original CRT effect is a triangle phosphor sink. `IRenderer2D` draws lines, circles, rectangles, rounded rectangles, and scanline rectangles. The dials are stroked arcs, the scanlines use the rectangle scanline call, and power-on / power-off follows the original timeline (0.25 s, line phase through 0.38, 1.25× speed on the way off).
- **Future Sight projection.** The addon projects a screen-space ring with Meteor `NametagUtils`. This port draws a camera-facing ring in the world with `IRenderer3D` lines and depth test off. The prediction (smoothing 0.4, 20 ticks, gravity 0.08, drag 0.98, collision stepping) is the same math.
- **HUD chrome.** RusherHack's HUD handler draws a background rectangle before `renderContent`. `shouldDrawBackground()` returning false does not stop that, so each element has a panel the Meteor HUD does not.
- **Per-string font scale.** `IFontRenderer.drawString` has no scale argument. Speedometer and fuel size come from the element width and height, multiplied by the module's scale settings.
- **Default scale.** The addon computes the speedometer's default scale from the framebuffer height when the setting is created. The window may not exist then, so the defaults are the 1080p-equivalent constants from that function: speedometer `2.0`, fuel gauge `1.25`.
- **Auto-arrange.** Elements are moved by their screen positions (fuel to the left of the dial, switches above it, assistant above the switches). Meteor anchors are not available.
- **Flashback.** The addon hides its HUD while the Flashback replay mod (`com.moulberry.flashback`) is playing. That check is omitted. The HUD is shown while the module and the matching visual setting are on, then plays the power-off and turns the element off.
- **Chest swap.** Meteor calls its own `ChestSwap` module. There is no equivalent hook here. The click sequence is local: hotbar swap onto chest slot 6 when the elytra is already on the hotbar, otherwise a pickup onto that slot, and the reverse when a chestplate should replace the glider.
- **Movement event.** Meteor writes the player's movement vector for the tick. This port calls `EventMove.setMotion` in the same place and assumes RusherHack applies that vector the same way. That was not confirmed in a running client.
- **Physical key state.** Whether a movement key is held is read through RusherHack's `IMixinKeyMapping`. If that mixin is not applied, the code uses `KeyMapping.isDown()`, which can disagree with a key the player is holding while a screen is closed.
- **Highway pathfinder.** A client-side parrot supplies `BirdNavigation`, matching the addon. Whether that entity can query the current world the same way depends on the client; the build only proves the constructor and navigation calls compile on 1.21.11.

The original icon is at `src/main/resources/assets/volytrafly/icon.png`.
