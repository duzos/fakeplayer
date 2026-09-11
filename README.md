<div align="center">

<img src="docs/img/logo.png" width="160" alt="logo">

# Fake Players

### Player look-alikes that mine, chop, guard, and haul - for you.

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20%E2%80%93%2026.2-62B47A?style=for-the-badge)

[<img alt="curseforge" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/curseforge_vector.svg">](https://www.curseforge.com/minecraft/mc-mods/fake-player)
[<img alt="modrinth" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg">](https://modrinth.com/mod/fake-players)
[<img alt="fabric" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/fabric_vector.svg">](https://fabricmc.net/)
[<img alt="neoforge" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/neoforge_vector.svg">](https://neoforged.net/)
[<img alt="forge" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/forge_vector.svg">](https://files.minecraftforge.net)

</div>

## 🤖 What is it?

Entities that look **exactly** like real players - auto-grabbed skins, armour, full inventories, the lot. Bond one to yourself, give it a job, and it gets to work.

## 🧍 The Fake Player

- **Looks like a player** - real model with a skin grabbed from any username (or URL / the trending list); slim & classic.
- **Acts like one** - wanders, sits, sleeps, wears armour, holds items, chats, fights back.
- **Fights at range** - give it a bow, crossbow or trident and it keeps its distance, strafes and fires like a skeleton, shooting arrows from its own inventory.
- **Carries an inventory** - managed through its GUI.

## 🧠 AI Jobs

Shift + right-click a fake (or press your **Open Fake Player Menu** key, see below) → **AI** → **Bond**, then pick a job. The GUI hands you markers (waypoint, region, chests); right-click to place them - the fake follows you while you do. Each job's menu shows only the markers it needs, and fakes visibly open the chests and barrels they work from.

| Job | Needs | Does |
| --- | --- | --- |
| **Idle** | waypoint (optional) | Walks to its waypoint, else waits. |
| **Follow** | bond | Sticks within 32 blocks, teleports if it lags behind. |
| **Guard** | patrol points | Patrols your points and attacks hostiles in range, at range if it is carrying a bow, crossbow or trident. Hold the Waypoint marker to edit points - right-click adds, sneak + right-click removes. |
| **Miner** | region + deposit | Strip-mines ore (`c:ores` by default) and banks the haul. Filter grammar and the on/off toggle are covered below. |
| **Lumberjack** | region (+ deposit) | Fells whole trees, replants, bonemeals; auto-collects drops. |
| **Courier** | source + deposit | Hauls matching items from one chest to another. Shares the Miner's filter. |
| **Fisherman** | waypoint + deposit | Sits at the water and casts a real bobber; banks the catch, swaps a fresh rod when one breaks, and uses your rod's enchantments. |
| **Farmer** | region + deposit | Tills a plot, waters it, plants any seed (modded too), bonemeals, then harvests and replants on a loop. |
| **Crafter** | table + source + deposit | Walks to a crafting table and lays out a recipe you teach it by hand; chain it onto another job's chest for a pipeline. |
| **Quartermaster** | storage pool | Owns a pool of marked containers, answers requests from your other fakes and from you, and sends Runners. Never leaves the storeroom. |
| **Runner** | none | Fetches a requested item from a Quartermaster's pool and delivers it. No fixed route and nothing to configure. |

### Requests: Quartermaster and Runner

A fake that runs out of something asks for more instead of just stopping. A **Quartermaster** owns a
storeroom and works out who can fill the request; a **Runner** does the carrying. Set one of each up:

- Set a fake to **Quartermaster** and press Mark on its Pool row. Right-click each chest or barrel to
  add it to the pool, and right-click a pooled one again to remove it. The marker is not used up, so
  one marker marks the whole storeroom. Furnaces and other sided containers cannot be pooled, and
  marking only one half of a double chest is enough.
- Bond at least one fake as a **Runner**, owned by you and within `requestRadius` blocks (256 by
  default, about 16 chunks). The Quartermaster never walks, so with no Runner nearby nothing is
  delivered and it tells you so. The same radius decides which Quartermasters a waiting fake can
  reach, so a storeroom further than that is invisible to it.
- Start both. A Fisherman with no rod now asks for one and waits, and the rod arrives if the pool has
  one. If nothing can fill the request you are told **once** and the fake keeps waiting rather than
  unbonding itself.
- Ask for something yourself from the **Request** row on a Quartermaster. Press Browse to see
  everything the storeroom holds, then click an item: a click asks for a stack, sneak-click asks for
  one, ctrl-click asks for all of it. Asking again for more tops the request up instead of queuing a
  second one. Under the grid is what you currently have on order, with an x to call one off. The
  whole thing updates while you watch it.

Worth knowing:

- Only **your own** Quartermasters serve you: browsing a teammate's storeroom is refused.
- The nearest Quartermaster that actually **has stock** wins, so an empty storeroom standing closer
  does not shadow a full one.
- Items are matched by id, so a damaged or enchanted one already in the Runner's own inventory can be
  handed over in place of a fresh one from the pool. For the same reason Browse merges variants:
  three tools of different durability show as one icon with a count of three.
- A Runner holds what it is carrying, so you can see which ones are loaded from across the base.
  With nothing to carry it walks back to its Quartermaster rather than standing where it stopped.
- Re-jobbing a Runner mid-delivery leaves the goods in its inventory. Open it to take them back, or
  set it back to Runner and it returns them to the pool itself.

### Miner / Courier filter

The Miner and Courier both filter what they collect against one stored string, edited from the AI menu's Filter row:

- `<namespace>:<id>` - a specific item or block, e.g. `minecraft:diamond`.
- `#<namespace>:<tag>` - an item or block tag, e.g. `#c:ores`.
- Comma-separated for multiple tokens: `minecraft:diamond,#c:ores`.
- `*`, or a blank box - matches everything. The ON/OFF button next to Apply toggles this directly, and a blank box always means the filter is off (reopening the menu shows it that way too).

Miner defaults to `c:ores` when never set; Courier defaults to matching everything when never set, and the Filter row reflects that per job. Applying a filter does **not** reset quarry progress.

## ⌨️ Controls

Shift + right-click a fake opens its management menu. If another mod claims right-click on mobs, bind
**Options → Controls → Fake Players → Open Fake Player Menu** to a key instead: while that key is bound the menu
opens from it whenever you are looking at a fake, and shift + right-click no longer opens it. Leave it unbound
and nothing changes.

Holding an item and right-clicking a fake still runs that item's interaction either way (chest opens its
inventory, paper makes it speak, stairs sit it down, and so on).

## ⚙️ Config

Tuning lives in `players.json`:

| Key | Default | Effect |
| --- | --- | --- |
| `defaultSkin` | `duzo` | Skin applied to a freshly-placed fake before you rename it. |
| `maxHealth` | `25.0` | Fake player max health. |
| `movementSpeed` | `0.2` | Fake player walk speed. |
| `attackDamage` | `1.0` | Fake player melee damage. |
| `persistFakePlayers` | `true` | Whether fakes survive a server restart. |
| `allowLocalSkinUploadOpOnly` | `true` | Restricts uploading a local skin file to server operators; disable to let any player upload one. |
| `guardRadius` | `12` | How far a Guard chases from its patrol point. |
| `requestRadius` | `256` | How far a waiting fake looks for a Quartermaster, and how far a Quartermaster looks for a Runner. Roughly 16 chunks. Configs written by an older version are moved up to this once, unless you had already changed the value yourself. |
| `minerMaxBlocksPerSecond` | `2.5` | Caps how fast the Miner can break blocks. |
| `minerBailY` | `-58` | Y level the Miner refuses to dig below. |
| `minerSpoil` | `ground` | What the Miner does with a drop its filter doesn't ask for once the build reserve (up to 64 build blocks) is full: `ground` (dropped and left at the bot's feet - most despawns uncollected), `chest` (deposited like ore), or `void` (deleted). Anything the filter matches is always kept, build-suitable or not. An unrecognised value falls back to `ground` with a logged warning. |

<div align="center">
<table>
  <tr>
    <td align="center" valign="top"><img src="docs/img/ai-submenu.png" height="250" alt="AI sub-menu"><br><sub><b>AI sub-menu</b></sub></td>
    <td align="center" valign="top"><img src="docs/img/management-gui.png" height="250" alt="Management GUI"><br><sub><b>Management GUI</b></sub></td>
  </tr>
  <tr>
    <td align="center" valign="top"><img src="docs/img/miner-quarry.png" width="330" alt="Miner quarry"><br><sub><b>Miner</b> clearing a quarry</sub></td>
    <td align="center" valign="top"><img src="docs/img/lumberjack.png" width="330" alt="Lumberjack"><br><sub><b>Lumberjack</b> after felling a tree</sub></td>
  </tr>
  <tr>
    <td align="center" valign="top"><img src="docs/img/farmer.png" width="330" alt="Farmer"><br><sub><b>Farmer</b> tending a watered plot</sub></td>
    <td align="center" valign="top"><img src="docs/img/fisherman.png" width="330" alt="Fisherman"><br><sub><b>Fisherman</b> casting at the water's edge</sub></td>
  </tr>
  <tr>
    <td align="center" valign="top"><img src="docs/img/crafter.png" width="330" alt="Crafter"><br><sub><b>Crafter</b> crafting by hand at its table</sub></td>
  </tr>
</table>
</div>

## 🎨 Skins

Name a fake after a player and it wears their skin - always matching. Also:

- **URL** - `/players url <entity> <url>`
- **Trending** - browse and apply from the in-game list (shift + right-click).
- **Slim & classic** both supported.

<div align="center">
<img src="docs/img/skin-example.png" height="280" alt="A skin grabbed from a username and applied to a fake player">
</div>

## 🛠️ Get one

Craft a `Robot Shell` and a `Robot AI` and combine them in a crafting table (or use a Player Spawn Egg).

<div align="center">
<img src="docs/img/recipe-robot-shell.png" height="120" alt="Robot Shell recipe">
&nbsp;&nbsp;
<img src="docs/img/recipe-robot-ai.png" height="120" alt="Robot AI recipe">
&nbsp;&nbsp;
<img src="docs/img/recipe-spawn-egg.png" height="120" alt="Player Spawn Egg recipe">
</div>

## 🔗 Links

- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fake-player)
- [Modrinth](https://modrinth.com/mod/fake-players)
- [Discord](https://discord.gg/ZgssqpUMHS)
- [Showcase video](https://www.youtube.com/watch?v=O5BO6fA41n0)

## 🙏 Credits

- [Jeryn](https://modrinth.com/user/Jeryn/) - skin API and downloading code.
