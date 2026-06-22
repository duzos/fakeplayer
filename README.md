# Fake Players Mod

## A Forge/Fabric mod aimed at adding fake players to the game.

[<img alt="curseforge" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/curseforge_vector.svg">](https://www.curseforge.com/minecraft/mc-mods/fake-player) <!-- SVG version -->
[<img alt="modrinth" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg">](https://modrinth.com/mod/fake-players) <!-- SVG version -->
[<img alt="fabric" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/fabric_vector.svg">](https://fabricmc.net/) <!-- SVG version -->
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
You need to craft a ```Robot Shell``` and a ```Robot AI``` and combine them in a crafting table

Recipes are on the gallery

### AI Jobs
Fake players can be given a job and put to work. Open the management GUI (shift + right-click the fake player), then hit the **AI** button to open the AI sub-menu.

From there you can:
- **Bond/unbond** the fake player to yourself (only the owner can change its job or hand out markers).
- Toggle **No AI** to freeze it completely.
- **Cycle** through jobs with the job picker.
- Hand out single-use **marker items** for waypoint, region (two clicks for corners A and B) and deposit/source containers. Hold a marker and right-click the block (or container) to set it; sneak right-click air to cancel.
- Hit **START JOB** to set it running.

#### Jobs

- **Idle** - walks to its waypoint if one is set, otherwise stands still.
- **Follow** - follows the owner around (within 32 blocks), teleporting if it falls too far behind.
- **Guard** - patrols between the waypoints you mark (the Waypoint marker is additive in this mode) and attacks hostile mobs that come within `guardRadius` (default 12). The sub-menu shows a Patrol row with a Clear button.
- **Miner** - scans the marked region for ore (filtered by an item tag, default `c:ores`), mines it, and dumps the haul in the deposit container. The sub-menu shows a **Filter** box where you can change the tag. Set a region and a deposit container first.
- **Lumberjack** - finds logs in the marked region, fells whole trees, and the drops are picked up automatically. Set a region (and a deposit container if you want it to stash the wood).
- **Courier** - shuttles items from a source container to the deposit container. The sub-menu shows an extra **Source** marker button while this job is selected; mark both containers.

#### Config
Job tuning lives in `players.json` - `guardRadius`, `minerMaxBlocksPerSecond`, `minerBailY`, `minerLavaCobbleSafety` and `minerNeverMineBlockUnderFeet`.

### Skin Grabbing!
This mod is able to get the skin of a player.
This means that the fake player will always have the correct and matching skin to its given username.

To do this you name the fake player with the username of the player.

Image of this in gallery!

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

# Handy Dandy Links
### [Showcase](https://www.youtube.com/watch?v=O5BO6fA41n0)
### [Curseforge](https://www.curseforge.com/minecraft/mc-mods/fake-player)
### [Modrinth](https://modrinth.com/mod/fake-players)
### [Discord](https://discord.gg/ZgssqpUMHS)
