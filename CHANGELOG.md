# v2.0.7

- Upload local skin PNGs from disk via the skin select gui (#25). Click `UPLOAD` in the gallery, pick a 64x64 or 64x32 png up to 32 kb; the bytes go to the server, get persisted under `./fakeplayer/local-skins/`, and stream out to other clients on demand. Op-only by default; toggle `allowLocalSkinUploadOpOnly` in `config/players.json` to let everyone upload.
