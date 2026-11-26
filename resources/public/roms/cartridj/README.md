# Sample games made with cartridj

Games written in Clojure with cartridj and zeropage
(<https://github.com/dfornika/nes-gamedev-toolkit-clj>). They're built
there and copied here as finished files. nes-cljs doesn't build games: it
plays them and lets you look inside.

Each game is two files:

- `<game>.nes`: the cartridge, as any emulator reads it.
- `<game>.debug.edn`: its **names file**. It holds the names from the
  source that the assembler threw away: labels in the code, variables in
  RAM, and what each one is for. The Samples menu loads it alongside the
  ROM, so the CPU tab can say `STA player.x` instead of `STA $04` and the
  Lens tab can show the game's variables. The format is described in
  `nes.debug-info`.

These files are also the test cartridges for `nes.machine-test`,
`nes.ppu.render-jsnes-test`, `nes.ppu.jsnes-compare-test` and
`nes.debug-info-test` (read by `nes.sample-roms`). If you rebuild them,
run `npm test`.

| Files | Source in the toolkit repo |
|---|---|
| `hello` | `games/hello` |
| `catch` | `games/catch` |
| `hopper` | `games/hopper` |
| `tower` | `games/tower` |
| `star-run` | `games/star-run` |
| `alienscape` | `games/alienscape`. Art by Hexany Ives, from *Hexany's Alienscape*, CC0 1.0: <https://hexany-ives.itch.io/hexanys-alienscape> |
| `ladder-nil`, `-color`, `-pulse`, `-pad`, `-sprite` | `games/ladder` |

To rebuild one, in the toolkit repo:

```bash
cd games/hopper && clojure -T:build rom
```

Then copy `target/roms/hopper-alpha.nes` and `hopper-alpha.debug.edn` here
as `hopper.nes` and `hopper.debug.edn`. For the ladder, `clojure -T:build rom`
in `games/ladder` writes `ladder-*.nes` and `ladder-*.debug.edn`.
