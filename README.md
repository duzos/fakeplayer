# Fake Players Mod

## A Fabric, Forge and NeoForge mod aimed at adding fake players to the game.

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20%E2%80%93%201.21.11-62B47A)

[<img alt="curseforge" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/curseforge_vector.svg">](https://www.curseforge.com/minecraft/mc-mods/fake-player) <!-- SVG version -->
[<img alt="modrinth" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg">](https://modrinth.com/mod/fake-players) <!-- SVG version -->
[<img alt="fabric" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/fabric_vector.svg">](https://fabricmc.net/) <!-- SVG version -->
[<img alt="neoforge" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/neoforge_vector.svg">](https://neoforged.net/) <!-- SVG version -->
[<img alt="forge" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/forge_vector.svg">](https://files.minecraftforge.net) <!-- SVG version -->

## Features
### Fake Player Mob
These mobs look exactly like regular players.

#### What can they do?

- Send chat messages
- Change skin
- Wander around
- Attack when hurt
- Change username from name tag
- Wear armour
- Hold items
- Utilise inventory space

And much more!

#### Interactions

*An interaction is when the player right clicks the mob with an item*

- **Observer**: Toggle the fake player's AI
- **Name Tag**: Change the fake player's name + skin
- **Stairs**: Make the fake player sit
- **Beds**: Make the fake player sleep
- **Slabs**: Toggle slim skin
- **Eye of Ender**: Toggle name tag visibility
- **Paper**: Send a chat message

#### How do I get one?
Craft a `Robot Shell` and a `Robot AI`, then combine them in a crafting table (or grab a Player Spawn Egg).

<p>
  <img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/recipe-robot-shell.png" height="120" alt="Robot Shell recipe">
  <img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/recipe-robot-ai.png" height="120" alt="Robot AI recipe">
  <img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/recipe-spawn-egg.png" height="120" alt="Player Spawn Egg recipe">
</p>

### AI Jobs
Fake players can be given a job and put to work. Open the management GUI (shift + right-click the fake player), then hit the **AI** button to open the AI sub-menu.

From there you can:
- **Bond/unbond** the fake player to yourself (only the owner can change its job or hand out markers).
- Toggle **No AI** to freeze it completely.
- **Cycle** through jobs with the job picker.
- Hand out single-use **marker items** for waypoint, region (two clicks for corners A and B) and deposit/source containers. Hold a marker and right-click the block (or container) to set it; sneak right-click air to cancel.
- Hit **START JOB** to set it running.

While you are holding any marker for a fake (or have its GUI open) the fake follows you, so you can set things up on the move without changing its job.

#### Jobs

| Job | Set up | What it does |
| --- | --- | --- |
| **Idle** | (optional) waypoint | Walks to its waypoint if one is set, otherwise stands still. |
| **Follow** | bond to you | Follows you within 32 blocks, teleporting if it falls too far behind. |
| **Guard** | patrol points | Patrols the points you mark and attacks hostiles within `guardRadius` (default 12). Hold the Waypoint marker to see all points; right-click adds one, sneak + right-click removes one. |
| **Miner** | region + deposit | Scans the region for ore (item-tag filter, default `c:ores`), mines it, and returns the haul to the deposit container. A **Filter** box in the sub-menu changes the tag. |
| **Lumberjack** | region (+ deposit) | Fells whole trees in the region, replants saplings and bonemeals to speed regrowth; drops are collected automatically. |
| **Courier** | source + deposit | Shuttles matching items from the source container to the deposit container. A **Source** marker button appears for this job. |

#### Config
Job tuning lives in `players.json` - `guardRadius`, `minerMaxBlocksPerSecond`, `minerBailY`, `minerLavaCobbleSafety` and `minerNeverMineBlockUnderFeet`.

#### Screenshots

<table>
  <tr>
    <td align="center" valign="top"><img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/ai-submenu.png" height="260" alt="AI sub-menu"><br><sub><b>AI sub-menu</b> - jobs, markers, run</sub></td>
    <td align="center" valign="top"><img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/management-gui.png" height="260" alt="Management GUI"><br><sub><b>Management GUI</b> - skin, pose, AI/SL/TG</sub></td>
  </tr>
  <tr>
    <td align="center" valign="top"><img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/miner-quarry.png" width="340" alt="Miner quarry"><br><sub><b>Miner</b> clearing a quarry</sub></td>
    <td align="center" valign="top"><img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/lumberjack.png" width="340" alt="Lumberjack"><br><sub><b>Lumberjack</b> after felling a tree</sub></td>
  </tr>
</table>

### Skin Grabbing!
This mod is able to get the skin of a player.
This means that the fake player will always have the correct and matching skin to its given username.

To do this you name the fake player with the username of the player.

<img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/skin-example.png" height="260" alt="Skin grabbed from a username and applied to the fake player">

### Skin from URL

You can also set the skin of a fake player to a skin from a URL.

To do this, you run the command ```/players url <entity> <url>```

### Trending Skins

The mod provides a list of trending skins that you can use for your fake players.

To use this feature, shift and right-click the fake player to open the GUI.

### Slim Skin Support!
This mod supports both slim skins and regular skins.

# Thanks

- [Jeryn](https://modrinth.com/user/Jeryn/) - for his API and Skin Downloading code.

# Why am I crashing on dedicated forge servers?

This is a known issue and is being worked on.

It's because forge *( nightmare modloader of doom and despair )* by default tries to load client-only code in server.

Try setting advertiseDedicatedServerToLan to false in your server config.
