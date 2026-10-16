# Handoff for a new Claude session (read this first)

Owner: Nablzz (Ilikebiscoff). Project: **Nablzz Addons**, a client-side Fabric mod with several Hypixel SkyBlock macros and one settings GUI.
Stack: Minecraft Java 26.1.2, Java 25, Fabric Loader 0.18+, Fabric API 0.145.4+26.1.2, Mojang mappings, Loom 1.15, Gradle 9.4.
Branch: `claude/wizardly-franklin-t55462` (PR #1). Sources: `src/main/java/com/nablzz/addons/`. See README.md ("Adding a new feature").

## Features
- Miner Zombie Hunting (`minerzombie/`): hits zombies twice with Prime Huntaxe, one Medium Pocket Black Hole per hit, pathing, lava/stuck rescue via `/warp deep` + lift.
- Glowing Mushrooms (`mushroom/`), Fusion Machine (`fusion/`).
- Galatea Mudworm (`mudworm/`): ESP on green-sparkle spots (particle packets via `mushroom/mixin/ClientPacketListenerMixin` -> `MudwormTracker`), pathfinding walk (`minerzombie/pathing/Planner`), Aspect of the Void shift+right-click etherwarp (aims at block middle, teleports when target is >1 block higher), profit tracker top right, soft route (follow waypoints, detour to spots), round loop: new lobby -> route -> all spots -> `/hub` -> `/warp galatea` -> repeat. Route file `nablz_mudworm_route.json`; the "Load the video's route" button holds approximate, UNVERIFIED coordinates.
- All features implement `Feature` and register in `NablzzAddons`. GUI: `NablzzScreen` (Right Shift or `/nablzz`).

## Current bug (unconfirmed)
User reported the Mudworm macro "just looks to the left". Build 1.0.64 (commit f90af15) fixes two suspected causes in `MudwormMacro.walk()`: no aim target when there is no path, and a fixed yaw when the target equals the player position. NOT tested in-game. Ask the user what the status line says (No path / Searching / Walking the route) if it persists. Consider adding a debug log of stage/goal/yaw/path length.

## Rules the user set
- Never claim success before CI/local build is green; say honestly that nothing has been tested in-game unless it was.
- Warn that macros on Hypixel risk a ban. NEVER run macros on Hypixel yourself; singleplayer/main menu only.
- Updating the mod in-game: `/nablzz update`, then restart (Modrinth App: Stop then Play). Game launcher is the Modrinth App, instance "Fabulously Optimized" (Fabric 26.1.2).
- When delivering a jar, give only the newest passing jar. CI (`.github/workflows/build.yml`) publishes prerelease `latest-build` with `nablzz-addons-1.0.<run>.jar` + sha256 digest.
- Commit messages end with: `Co-Authored-By: Claude <noreply@anthropic.com>`. Don't open PRs unless asked.
- The earlier cloud chat is at https://claude.ai/code/session_01FvZzfk8K7ApNNPzkmBdHyr if more history is needed.

## Local build
`./gradlew build` (gradlew.bat on Windows); jar in `build/libs/`. The cloud sandbox could not reach maven.fabricmc.net, so CI was the only builder there; locally this should just work.
