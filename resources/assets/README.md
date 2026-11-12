# Art assets

Art made by other people, kept here as they published it, for games in
`src/main/games` to use.

These aren't served with the site: an import script turns them into
Clojure data (tiles drawn as text) once, and the game is built from that.

| Folder | What | By | License |
|---|---|---|---|
| `hexanys-alienscape/` | Hexany's Alienscape 0.1.0: a fleshy, biomechanical tileset and creatures, drawn to the NES's limits | Hexany Ives, <https://hexany-ives.itch.io/hexanys-alienscape> | CC0 1.0 (public domain): see its `LICENSE.txt` and `COPYING.txt` |

Used by `games.alienscape` (imported by `games.alienscape.import`:
`npm run import:alienscape`).
