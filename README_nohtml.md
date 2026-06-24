# Fake Players

### Player look-alikes that mine, chop, guard, and haul - for you.

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20%E2%80%93%201.21.11-62B47A?style=for-the-badge)

[![curseforge](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/curseforge_vector.svg)](https://www.curseforge.com/minecraft/mc-mods/fake-player)
[![modrinth](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg)](https://modrinth.com/mod/fake-players)
[![fabric](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/fabric_vector.svg)](https://fabricmc.net/)
[![neoforge](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/neoforge_vector.svg)](https://neoforged.net/)
[![forge](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/forge_vector.svg)](https://files.minecraftforge.net)

## 🤖 What is it?

Entities that look **exactly** like real players - auto-grabbed skins, armour, full inventories, the lot. Bond one to yourself, give it a job, and it gets to work.

## 🎬 Showcase

<a href="https://www.youtube.com/watch?v=ZVp2m_3AAxg">![Fake Players showcase video](https://img.youtube.com/vi/ZVp2m_3AAxg/hqdefault.jpg)</a>

## 🧍 The Fake Player

- **Looks like a player** - real model with a skin grabbed from any username (or URL / the trending list); slim & classic.
- **Acts like one** - wanders, sits, sleeps, wears armour, holds items, chats, fights back.
- **Carries an inventory** - managed through its GUI.

## 🧠 AI Jobs

Shift + right-click a fake → **AI** → **Bond**, then pick a job. The GUI hands you markers (waypoint, region, chests); right-click to place them - the fake follows you while you do.

| Job | Needs | Does |
| --- | --- | --- |
| **Idle** | waypoint (optional) | Walks to its waypoint, else waits. |
| **Follow** | bond | Sticks within 32 blocks, teleports if it lags behind. |
| **Guard** | patrol points | Patrols your points and attacks hostiles in range. Hold the Waypoint marker to edit points - right-click adds, sneak + right-click removes. |
| **Miner** | region + deposit | Strip-mines ore (`c:ores` by default) and banks the haul. |
| **Lumberjack** | region (+ deposit) | Fells whole trees, replants, bonemeals; auto-collects drops. |
| **Courier** | source + deposit | Hauls matching items from one chest to another. |

Tuning lives in `players.json` (`guardRadius`, `minerMaxBlocksPerSecond`, `minerBailY`, `minerLavaCobbleSafety`, `minerNeverMineBlockUnderFeet`).

![AI sub-menu](https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/ai-submenu.png)
**AI sub-menu**

![Management GUI](https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/management-gui.png)
**Management GUI**

![Miner quarry](https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/miner-quarry.png)
**Miner** clearing a quarry

![Lumberjack](https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/lumberjack.png)
**Lumberjack** after felling a tree

## 🎨 Skins

Name a fake after a player and it wears their skin - always matching. Also:

- **URL** - `/players url <entity> <url>`
- **Trending** - browse and apply from the in-game list (shift + right-click).
- **Slim & classic** both supported.

![A skin grabbed from a username and applied to a fake player](https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/skin-example.png)

## 🛠️ Get one

Craft a `Robot Shell` and a `Robot AI` and combine them in a crafting table (or use a Player Spawn Egg).

![Robot Shell recipe](https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/recipe-robot-shell.png)

![Robot AI recipe](https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/recipe-robot-ai.png)

![Player Spawn Egg recipe](https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/recipe-spawn-egg.png)

## 🔗 Links

- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fake-player)
- [Modrinth](https://modrinth.com/mod/fake-players)
- [Discord](https://discord.gg/ZgssqpUMHS)
- [Showcase video](https://www.youtube.com/watch?v=ZVp2m_3AAxg)

## 🙏 Credits

- [Jeryn](https://modrinth.com/user/Jeryn/) - skin API and downloading code.
