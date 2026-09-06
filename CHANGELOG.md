# v2.1.3

- The Miner no longer deletes what it digs (#54). Anything past the first stack of building material used to be discarded, and anything that did not match the filter was destroyed the moment it was mined. Nothing is thrown away now: a new `minerSpoil` config option decides where spoil goes - `ground` (the default) drops it at the fake's feet, `chest` banks it like ore, and `void` restores the old delete-everything behaviour for tidy quarries.
- The Miner no longer thinks its bag is full when every slot merely holds one item (#54). Fullness now counts room in part-filled stacks, in every job.
- Fakes no longer get stuck opening and closing a full deposit chest forever (#54). The Miner, Lumberjack, Farmer and Courier all say the container is full, wait, and carry on by themselves once you empty it.
- The Miner no longer jams on a worn pickaxe (#54). It looks at everything it is carrying rather than whatever happens to be in its hand, hands the worn one over at the chest, and upgrades itself to the best pickaxe it has.
- The Miner no longer claims a pickaxe is the wrong tool for stone while holding a working pickaxe (#54).
- The Miner no longer abandons the block it was working on every time it goes to deposit (#54), which used to leave one hole per trip, and no longer leaves gaps in the quarry cap without saying so.
- The Miner accepts any solid full block as stair and cap material (#54), not just cobblestone, stone, dirt and deepslate, and places whatever it actually used. Ores, metal and gem blocks, obsidian, ancient debris, TNT and redstone parts are never spent as building material. Stairs and slabs still are not accepted.
- The Miner no longer takes back out of the deposit chest what it just put in (#54).
- Fakes no longer report that a deposit container is unreachable when a path exists (#54). A route that cannot actually get there is now recognised as a failure instead of being mistaken for success.
- The Crafter no longer destroys whatever the fake was holding when it starts work (#54).
- The Courier now understands the whole filter syntax (#54) instead of treating the entry as a single item tag.
- Filters can be switched off (#55). There is a toggle beside the filter box, an empty box means no filter at all, and `*` matches everything. The filter row is available for the Courier as well as the Miner, and each job shows its real default.
- The `E` key no longer closes the management screen while you are typing a name (#51), so names with an `E` in them can be typed straight in.
- Session markers now disappear when used in creative mode (#55), and the region preview box goes with them.
- The filter syntax and every config option are now written up in the README (#55).
- The Miner now recognises ore on Forge 1.20 (#54). Minecraft only moved the shared item tags to the `c:` namespace in 1.20.5, so on this version the default `c:ores` filter matched nothing at all and the fake collected no ore; the default now covers `forge:ores` too, and metal and gem blocks are correctly kept out of the building material.
- Fakes no longer lose their gear when they die (#54). The held tool and any armour were being destroyed most of the time on a player kill and always to lava or a fall; they now always drop. A fake also no longer swaps its own pickaxe for a weapon it happens to walk over.
- Closed an item duplication exploit in the Crafter (#54). While it was laying out a recipe the ingredient in its hand could be taken repeatedly by anyone who opened its inventory, and the fake lost nothing in exchange.
