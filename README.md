# Hunting Macro

Client-side Fabric mod (Minecraft Java 26.1.2, Java 25) that hunts the Diamond Reserve miner zombies on
Hypixel SkyBlock:

1. pathfinds to the nearest zombie (background A*/Dijkstra planner, speed-scaled path follower, human-like camera)
2. hits it **once** with the prime hunt axe
3. (optional, off by default) golden axe step: `/hunt golden use|hit`
4. right-clicks the Medium Pocket Black Hole in the air (no aiming) until it vanishes from the hotbar, then swaps back to the Prime Huntaxe
5. keeps walking to the next zombie the whole time (no waiting, no black hole timing)

Needs in the **hotbar**: the Prime Huntaxe and the Medium Pocket Black Hole (equip the wither skeleton pet: its double hit is passive).

## Commands
`/hunt start | stop | status`, `/hunt scan` (shows zombie/name-tag text around you),
`/hunt mob <text|any>` (optional name filter, default: every zombie in the scan box is hunted), `/hunt axe <text>` (default `Prime Huntaxe`),
`/hunt golden use|hit|off`, `/hunt hole name`, `/hunt range <blocks>`,
`/hunt warp <command|off>`, `/hunt path`, `/hunt highlight`, `/hunt parkour on|off`.

## Build
GitHub Actions builds the jar on every push (artifact + `latest-build` prerelease).

## Caveats
- **Macros are against Hypixel's rules and can get your account banned. Use at your own risk.**
- Lava: if you end up in lava it drops everything, swims up and walks to the nearest dry spot (gives up after 15 s).
- Untested in-game: item names, mob name tags, black hole radius/duration and the golden-axe behaviour are
  assumptions with sensible defaults; use `/hunt scan`, `/hunt mob`, `/hunt axe`, `/hunt hole ...` to match them.
