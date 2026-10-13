# Hunting Macro

Client-side Fabric mod (Minecraft Java 26.1.2, Java 25) that hunts the Diamond Reserve miner zombies on
Hypixel SkyBlock:

1. pathfinds to the nearest zombie (background A*/Dijkstra planner, speed-scaled path follower, human-like camera)
2. hits it **once** with the prime hunt axe
3. (optional, off by default) golden axe step: `/hunt golden use|hit`
4. right-clicks the Medium Pocket Black Hole in the air (no aiming), twice per hit by default (`/hunt hole count`) until it vanishes from the hotbar, then swaps back to the Prime Huntaxe
5. keeps walking to the next zombie; a background timer (starts at 5 s and **learns the real time automatically** from how long each hole takes to suck its zombie in, and after every hit it also learns hit->hole, how fast the hole registers, its cooldown and the hit lead, saved in `config/huntingmacro.properties`; `/hunt hole auto on|off|reset`, or set it by hand with `/hunt hole time`) knows when the hole is done, and the next zombie is hit `/hunt hole lead` (0.5 s) before that, then the next hole goes down

Needs in the **hotbar**: the Prime Huntaxe and the Medium Pocket Black Hole (equip the wither skeleton pet: its double hit is passive).

## Commands
`/hunt start | stop | status`, `/hunt scan` (shows zombie/name-tag text around you),
`/hunt mob <text|any>` (optional name filter, default: every zombie in the scan box is hunted), `/hunt axe <text>` (default `Prime Huntaxe`),
`/hunt golden use|hit|off`, `/hunt hole name`, `/hunt range <blocks>`,
`/hunt warp <command|off>`, `/hunt path`, `/hunt highlight`, `/hunt parkour on|off`.

Every 5 kills the greyed-out zombies (already hit) are reset so they can be hit again (`/hunt refresh <n>`). Hits are fast: it aims with a stiff spring and swings the moment the crosshair is on the zombie.

A new black hole is only placed after the previous one has printed its "You caught xN ... Shards!" chat line (`/hunt caught`); that line also gives the exact hole time, so the timer is learned from it.

## Updating
On joining a world the mod checks the `latest-build` release and tells you if a newer jar exists. `/hunt update` downloads it into your mods folder (SHA-256 checked against the release) and the old jar is removed when the game closes; restart the game to run the new code. `/hunt version` shows what you have. If the old jar is still in the mods folder after closing, delete it by hand.

## Build
GitHub Actions builds the jar on every push (artifact + `latest-build` prerelease).

## Caveats
- **Macros are against Hypixel's rules and can get your account banned. Use at your own risk.**
- Lava: if you end up in lava it swims up and walks to the nearest dry spot. After more than 3 lava incidents it runs `/warp deep`, walks to 45 150 15, clicks the Lift Operator and then the diamond (`/hunt lava`, `/hunt lift`). `/hunt lift go [warp]` runs just the lift trip so you can test it without lava; if the lift is far away it walks there in hops and prints progress.
- Untested in-game: item names, mob name tags, black hole radius/duration and the golden-axe behaviour are
  assumptions with sensible defaults; use `/hunt scan`, `/hunt mob`, `/hunt axe`, `/hunt hole ...` to match them.
