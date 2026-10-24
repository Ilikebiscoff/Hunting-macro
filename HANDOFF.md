# Handoff for a new Claude session (read this first)

Owner: Nablzz (Ilikebiscoff). Project: **Nablzz Addons**, a client-side Fabric mod with several Hypixel SkyBlock macros and one settings GUI.
Stack: Minecraft Java 26.1.2, Java 25, Fabric Loader 0.18+, Fabric API 0.145.4+26.1.2, Mojang mappings, Loom 1.15, Gradle 9.4.
Branch: `claude/wizardly-franklin-t55462` (PR #1). Sources: `src/main/java/com/nablzz/addons/`. See README.md ("Adding a new feature").

## Features
- Miner Zombie Hunting (`minerzombie/`): hits zombies twice with Prime Huntaxe, one Medium Pocket Black Hole per hit, pathing, lava/stuck rescue via `/warp deep` + lift.
- Glowing Mushrooms (`mushroom/`), Fusion Machine (`fusion/`).
- All features implement `Feature` and register in `NablzzAddons`. GUI: `NablzzScreen` (Right Shift or `/nablzz`).

## Current bug (unconfirmed)

## Rules the user set
- Never claim success before CI/local build is green; say honestly that nothing has been tested in-game unless it was.
- Warn that macros on Hypixel risk a ban. NEVER run macros on Hypixel yourself; singleplayer/main menu only.
- Updating the mod in-game: `/nablzz update`, then restart (Modrinth App: Stop then Play). Game launcher is the Modrinth App, instance "Fabulously Optimized" (Fabric 26.1.2).
- When delivering a jar, give only the newest passing jar. CI (`.github/workflows/build.yml`) publishes prerelease `latest-build` with `nablzz-addons-<VERSION>.<run>.jar` + sha256 digest.
- Commit messages end with: `Co-Authored-By: Claude <noreply@anthropic.com>`. Don't open PRs unless asked.
- The earlier cloud chat is at https://claude.ai/code/session_01FvZzfk8K7ApNNPzkmBdHyr if more history is needed.

## Local build
`./gradlew build` (gradlew.bat on Windows); jar in `build/libs/`. The cloud sandbox could not reach maven.fabricmc.net, so CI was the only builder there; locally this should just work.


NOTE: the Galatea Mudworm macro was removed from the mod on request (the user restarts it in a new chat). Old code is in git history (commit 0d55069 and earlier).

VERSIONING RULE (user request): version = MAJOR.MINOR.PATCH. `VERSION` holds MAJOR.MINOR (now `2.3`), `BUILD_BASE` holds a CI run number, and CI builds `<VERSION>.<run number - BUILD_BASE>`, so PATCH counts the builds since the last bump. When the MAJOR number goes up, MINOR and PATCH start again at 0; when MINOR goes up (every NEW FEATURE: 2.2, 2.3 ...), PATCH starts again at 0. Fixes only raise PATCH. To bump: in ONE commit set `VERSION` (e.g. `2.2` or `3.0`) and set `BUILD_BASE` to the number the NEXT CI run will get (= number of the latest run + 1; look it up with the Actions API/tool), so that this build is x.y.0. The updater compares the whole version (major, then minor, then patch), so a bump is always seen as newer, and the patch dropping back to 0 is fine.
