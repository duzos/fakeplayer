# v2.0.8

- Fix skin select crash and broken trending downloads (#41, #42). Trending skin downloads no longer assert on the wrong-thread RenderSystem check; the texture register now hops back onto the render thread via `Minecraft.execute`. The skin select screen also bails cleanly when the trending list is still empty instead of indexing out of bounds.
