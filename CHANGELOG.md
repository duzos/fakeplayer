# v2.0.8

- Recipes now load on 1.21.2+. Recipe and loot table data moved to the singular `recipe`/`loot_table` directories and use `result.id`, matching the 1.21.2 datapack format.
- Fix skin select crash and broken trending downloads (#41, #42). Trending skin downloads no longer assert on the wrong-thread RenderSystem check; the texture register now hops back onto the render thread via `Minecraft.execute`. The skin select screen also bails cleanly when the trending list is still empty instead of indexing out of bounds.
- Management gui tooltips only refresh on shift-state change, so they no longer flicker every frame.
- Fake players render armor and held items again. The humanoid render state is now populated in the wrapper renderer.
