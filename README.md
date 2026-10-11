# Hunting Macro

Client-side Fabric mod (Minecraft Java 26.1.2, Java 25) that hunts the Diamond Reserve miner zombies on
Hypixel SkyBlock:

1. pathfinds to the nearest zombie (background A*/Dijkstra planner, speed-scaled path follower, human-like camera)
2. hits it **once** with the prime hunt axe
3. (optional, off by default) golden axe step: `/hunt golden use|hit`
4. immediately places/uses the Medium Pocket Black Hole where the zombie stood, swaps back to the Prime Huntaxe
5. zombies standing inside the fresh black hole are left alone; it paths to the next one

Needs in the **hotbar**: the Prime Huntaxe and the Medium Pocket Black Hole (equip the wither skeleton pet: its double hit is passive).

## Commands
`/hunt start | stop | status`, `/hunt scan` (shows zombie/name-tag text around you),
`/hunt mob <text|any>` (default `Miner`), `/hunt axe <text>` (default `Prime Huntaxe`),
`/hunt golden use|hit|off`, `/hunt hole name|radius|seconds`, `/hunt range <blocks>`,
`/hunt warp <command|off>`, `/hunt path`, `/hunt highlight`, `/hunt parkour on|off`.

## Build
GitHub Actions builds the jar on every push (artifact + `latest-build` prerelease).

## Caveats
- **Macros are against Hypixel's rules and can get your account banned. Use at your own risk.**
- Untested in-game: item names, mob name tags, black hole radius/duration and the golden-axe behaviour are
  assumptions with sensible defaults; use `/hunt scan`, `/hunt mob`, `/hunt axe`, `/hunt hole ...` to match them.
