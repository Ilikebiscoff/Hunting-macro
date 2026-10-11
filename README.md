# Hunting Macro

Client-side Fabric mod (Minecraft Java 26.1.2, Java 25) that hunts the Diamond Reserve miner zombies on
Hypixel SkyBlock:

1. pathfinds to the nearest zombie (background A*/Dijkstra planner, speed-scaled path follower, human-like camera)
2. hits it **once** with the prime hunt axe
3. swaps to the golden axe and uses it (right-click by default; `/hunt golden hit|off` to change)
4. looks at the ground and places the black hole, swaps back to the prime axe
5. zombies standing inside the fresh black hole are left alone; it paths to the next one

Needs in the **hotbar**: the prime hunt axe, a golden axe, the black hole item.

## Commands
`/hunt start | stop | status`, `/hunt scan` (shows zombie/name-tag text around you),
`/hunt mob <text|any>` (default `Miner`), `/hunt axe <text>` (default `Prime`),
`/hunt golden use|hit|off`, `/hunt hole name|radius|seconds`, `/hunt range <blocks>`,
`/hunt warp <command|off>`, `/hunt path`, `/hunt highlight`, `/hunt parkour on|off`.

## Build
GitHub Actions builds the jar on every push (artifact + `latest-build` prerelease).

## Caveats
- **Macros are against Hypixel's rules and can get your account banned. Use at your own risk.**
- Untested in-game: item names, mob name tags, black hole radius/duration and the golden-axe behaviour are
  assumptions with sensible defaults; use `/hunt scan`, `/hunt mob`, `/hunt axe`, `/hunt hole ...` to match them.
