<div align="center">

<img src="docs/img/logo.png" width="160" alt="logo">

# Fake Players

### Player look-alikes that mine, chop, guard, and haul - for you.

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20%E2%80%93%2026.3-62B47A?style=for-the-badge)

[<img alt="curseforge" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/curseforge_vector.svg">](https://www.curseforge.com/minecraft/mc-mods/fake-player)
[<img alt="modrinth" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg">](https://modrinth.com/mod/fake-players)
[<img alt="fabric" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/fabric_vector.svg">](https://fabricmc.net/)
[<img alt="neoforge" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/neoforge_vector.svg">](https://neoforged.net/)
[<img alt="forge" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/forge_vector.svg">](https://files.minecraftforge.net)

</div>

## What is it?

Entities that look **exactly** like real players - auto-grabbed skins, armour, full inventories, the lot. Bond one to yourself, give it a job, and it gets to work.

Install it on the server and on every client that joins.

## Showcase

<div align="center">
<a href="https://www.youtube.com/watch?v=ZVp2m_3AAxg"><img src="https://img.youtube.com/vi/ZVp2m_3AAxg/hqdefault.jpg" width="480" alt="Fake Players showcase video"></a>
</div>

## Looks and acts like a player

- Wears any player's skin by name, from a URL, from your own file or off the trending list, slim or classic.
- Wanders, sits, sleeps, chats, wears armour and carries a full inventory you manage from its menu.
- Fights back with swords, bows, crossbows and tridents, and never hits you, your other fakes or your pets.
- When idle it watches you and goes to bed at night. Its chat shows in speech bubbles, and a mark over its head means it's stuck.

<div align="center">
<img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/skin-example.png" height="280" alt="A skin grabbed from a username and applied to a fake player">
</div>

## Jobs

- Bond a fake, pick a job from its menu and place the markers it hands you. It follows you while you do.
- **Miner** strip-mines ore (or whatever you filter for), **Lumberjack** fells and replants trees, and **Farmer** plants and harvests any seed.
- **Fisherman** casts a real bobber with your enchanted rod and swaps in a fresh one when it breaks.
- **Guard** patrols your waypoints, **Follow** sticks with you and **Idle** waits at its spot.
- **Courier** hauls between chests, **Crafter** makes a recipe you teach it by hand, and **Builder** puts up Create schematics and other structure files.

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

## Storage and requests

- A **Quartermaster** runs your storeroom, and **Runners** bring fakes whatever they run out of, like a new pickaxe.
- Browse the storeroom and order items yourself. A **Crafter** makes anything that's missing.
- Fill in a fake's Stock box and it keeps those supplies topped up.

## Items and riding

- **Chunk Anchor** keeps a fake working while you're away.
- **Possession Link** lets you see through a fake and control it.
- Fakes ride boats, minecarts and seats, and can drive Create trains.

## Controls and commands

- Shift + right-click a fake for its menu, or bind a key if another mod already uses that click.
- Right-click it with an item for quick actions: a chest opens its inventory, paper makes it talk, stairs sit it down, a bed lays it down, a slab swaps the slim model, an observer turns its AI off and an eye of ender hides its name.
- `/players spawn`, `/players url` and `/players chat` spawn a fake, set its skin and make it speak.
- Addons can add their own jobs through the API.

## Works with

- Create seats and trains
- Storage Drawers, Sophisticated Storage and other modded storage
- Fishing rods from other mods, and Aquaculture tackle on Forge/NeoForge
- River Fishing (1.21.1 only)
- Tide and TACZ guns (1.20.1 and 1.21.1 only)

## Get one

Craft a `Robot Shell` and a `Robot AI`, then combine them in a crafting table for a `Player Egg`. Place it and the fake is already yours.

<div align="center">
<img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/recipe-robot-shell.png" height="120" alt="Robot Shell recipe">
&nbsp;&nbsp;
<img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/recipe-robot-ai.png" height="120" alt="Robot AI recipe">
&nbsp;&nbsp;
<img src="https://raw.githubusercontent.com/Duzos/fakeplayer/master/docs/img/recipe-spawn-egg.png" height="120" alt="Player Egg recipe">
</div>

## Links

- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fake-player)
- [Modrinth](https://modrinth.com/mod/fake-players)
- [Discord](https://discord.gg/ZgssqpUMHS)
- [Showcase video](https://www.youtube.com/watch?v=ZVp2m_3AAxg)

## Credits

- [Jeryn](https://modrinth.com/user/Jeryn/) - skin API and downloading code.
