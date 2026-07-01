<div align="center">

# Fake Players

### Player look-alikes that mine, chop, guard, and haul - for you.

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20%E2%80%93%201.21.11-62B47A?style=for-the-badge)

[<img alt="curseforge" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/curseforge_vector.svg">](https://www.curseforge.com/minecraft/mc-mods/fake-player)
[<img alt="modrinth" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg">](https://modrinth.com/mod/fake-players)
[<img alt="fabric" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/fabric_vector.svg">](https://fabricmc.net/)
[<img alt="neoforge" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/neoforge_vector.svg">](https://neoforged.net/)
[<img alt="forge" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/forge_vector.svg">](https://files.minecraftforge.net)

</div>

## 🤖 What is it?

Entities that look **exactly** like real players - auto-grabbed skins, armour, full inventories, the lot. Bond one to yourself, give it a job, and it gets to work.

## 🎬 Showcase

<div align="center">
<a href="https://www.youtube.com/watch?v=ZVp2m_3AAxg"><img src="https://img.youtube.com/vi/ZVp2m_3AAxg/hqdefault.jpg" width="480" alt="Fake Players showcase video"></a>
</div>

## 🧍 The Fake Player

- **Looks like a player** - real model with a skin grabbed from any username (or URL / the trending list); slim & classic.
- **Acts like one** - wanders, sits, sleeps, wears armour, holds items, chats, fights back.
- **Carries an inventory** - managed through its GUI.

## 🧠 AI Jobs

Shift + right-click a fake → **AI** → **Bond**, then pick a job. The GUI hands you markers (waypoint, region, chests); right-click to place them - the fake follows you while you do. Each job's menu shows only the markers it needs, and fakes visibly open the chests and barrels they work from.

| Job | Needs | Does |
| --- | --- | --- |
| **Idle** | waypoint (optional) | Walks to its waypoint, else waits. |
| **Follow** | bond | Sticks within 32 blocks, teleports if it lags behind. |
| **Guard** | patrol points | Patrols your points and attacks hostiles in range. Hold the Waypoint marker to edit points - right-click adds, sneak + right-click removes. |
| **Miner** | region + deposit | Strip-mines ore (`c:ores` by default) and banks the haul. |
| **Lumberjack** | region (+ deposit) | Fells whole trees, replants, bonemeals; auto-collects drops. |
| **Courier** | source + deposit | Hauls matching items from one chest to another. |
| **Fisherman** | waypoint + deposit | Sits at the water and casts a real bobber; banks the catch, swaps a fresh rod when one breaks, and uses your rod's enchantments. |
| **Farmer** | region + deposit | Tills a plot, waters it, plants any seed (modded too), bonemeals, then harvests and replants on a loop. |
| **Crafter** | table + source + deposit | Walks to a crafting table and lays out a recipe you teach it by hand; chain it onto another job's chest for a pipeline. |

Tuning lives in `players.json` (`guardRadius`, `minerMaxBlocksPerSecond`, `minerBailY`).

<div align="center">
<table>
  <tr>
    <td align="center" valign="top"><img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/ai-submenu.png" height="250" alt="AI sub-menu"><br><sub><b>AI sub-menu</b></sub></td>
    <td align="center" valign="top"><img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/management-gui.png" height="250" alt="Management GUI"><br><sub><b>Management GUI</b></sub></td>
  </tr>
  <tr>
    <td align="center" valign="top"><img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/miner-quarry.png" width="330" alt="Miner quarry"><br><sub><b>Miner</b> clearing a quarry</sub></td>
    <td align="center" valign="top"><img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/lumberjack.png" width="330" alt="Lumberjack"><br><sub><b>Lumberjack</b> after felling a tree</sub></td>
  </tr>
  <tr>
    <td align="center" valign="top"><img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/farmer.png" width="330" alt="Farmer"><br><sub><b>Farmer</b> tending a watered plot</sub></td>
    <td align="center" valign="top"><img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/fisherman.png" width="330" alt="Fisherman"><br><sub><b>Fisherman</b> casting at the water's edge</sub></td>
  </tr>
  <tr>
    <td align="center" valign="top"><img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/crafter.png" width="330" alt="Crafter"><br><sub><b>Crafter</b> crafting by hand at its table</sub></td>
  </tr>
</table>
</div>

## 🎨 Skins

Name a fake after a player and it wears their skin - always matching. Also:

- **URL** - `/players url <entity> <url>`
- **Trending** - browse and apply from the in-game list (shift + right-click).
- **Slim & classic** both supported.

<div align="center">
<img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/skin-example.png" height="280" alt="A skin grabbed from a username and applied to a fake player">
</div>

## 🛠️ Get one

Craft a `Robot Shell` and a `Robot AI` and combine them in a crafting table (or use a Player Spawn Egg).

<div align="center">
<img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/recipe-robot-shell.png" height="120" alt="Robot Shell recipe">
&nbsp;&nbsp;
<img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/recipe-robot-ai.png" height="120" alt="Robot AI recipe">
&nbsp;&nbsp;
<img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/recipe-spawn-egg.png" height="120" alt="Player Spawn Egg recipe">
</div>

## 🔗 Links

- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fake-player)
- [Modrinth](https://modrinth.com/mod/fake-players)
- [Discord](https://discord.gg/ZgssqpUMHS)
- [Showcase video](https://www.youtube.com/watch?v=ZVp2m_3AAxg)

## 🙏 Credits

- [Jeryn](https://modrinth.com/user/Jeryn/) - skin API and downloading code.
