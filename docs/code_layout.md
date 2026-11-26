# How the code is laid out

The namespaces under `src/main/nes` are in five groups. Each group may
require the groups above it, never the ones below. So the hardware never
depends on the page, and nothing above the line marked "browser" needs
one. Most of that part is `.cljc` and runs on the JVM too, including our
6502 (`nes.cpu.core`). The exceptions, written for speed in the browser
for now, are `nes.machine` and the jsnes adapters.

| Group | Namespaces | What's there |
|---|---|---|
| **The hardware** | `nes.cpu.*`, `nes.ppu.*`, `nes.machine`, `nes.cartridge`, `nes.rom`, `nes.io-registers`, `nes.memory-map`, `nes.gamepad`, `nes.breakpoints`, `nes.code-log`, plus `nes.bytes`, `nes.util`, `nes.sha1` | The NES itself: our 6502, our PPU, cartridges and mappers, the whole console (`nes.machine`), and facts about the hardware (what each I/O register is, what's where in memory, which gamepad button is which). Also the two things the emulators check as they run, both about the CPU's bus: breakpoints, and the code/data log. |
| **Emulators** | `nes.emulator`, `nes.emulator.*` | One interface for "an NES the app can run" (`nes.emulator`), and its two implementations: jsnes, and our own console (`nes.emulator.machine`). |
| **Inspection** | `nes.inspect.*` | Tools for looking inside a running game, as data: the routines found in the code/data log, names from a names file (`debug-info`), lenses and the game-specific ones (`lenses.*`), overlays, snapshots, text tables, comparing with jsnes, and decoding tiles, sprites and nametables. |
| *browser below* | | |
| **The app** | `nes.app.*` | The running page: the emulator running now and the counters that tell views to redraw (`current`, which most views need and nothing more), the run loop and controls (`console`), getting games from files and URLs (`loading`), the breakpoint list (`breakpoints`), checking against jsnes (`checking`), watching the bus (`bus-monitor`), the app's state atom (`state`), keyboard and gamepad input, sound, the screen canvas, reading zip files. |
| **The views** | `nes.ui.*` | What's on the page, in Reagent: one namespace per tab or panel (`ui.cpu`, `ui.ppu`, `ui.lens`...), the page itself (`ui.app`), and building blocks (`ui.components`, `ui.canvas`, `ui.drawer`...). |

`nes.main` starts the page, and `nes.repl` is the REPL's toolbox. Both
sit on top of everything.

The tests in `src/test/nes` follow the same layout.

## Namespaces other projects use

The toolkit repo (<https://github.com/dfornika/nes-gamedev-toolkit-clj>)
requires these. Renaming one, or changing its public functions, needs
a matching change there:

- `nes.app.console`: putting a built game into the console (`load-bytes!`)
- `nes.app.current`: the emulator running now (`emulator-instance`)
- `nes.inspect.debug-info`: names for a game (`install!`, `lens-fields`)
- `nes.inspect.overlay`: a game's own overlays (`register!`)
- `nes.emulator`: reading the loaded ROM (`rom-file`)
- `nes.rom`: iNES headers and ROM hashes
- `nes.repl`: the REPL's tools
- `nes.cpu.zeropage`: our CPU behind zeropage's CPU interface
- `nes.main` and `nes.dev.portal`: the toolkit's `:app` build starts the
  page with `nes.main/init`, and preloads `nes.dev.portal` in development
