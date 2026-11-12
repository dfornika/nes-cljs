# TODO

Ideas for where to take NES.cljs next, roughly grouped. Nothing here is
committed to; pick whatever looks fun.

## Done so far

- [x] **Phase 0** – load and run ROMs (jsnes via interop), canvas screen, keyboard input
- [x] **Phase 1** – inspectors: cartridge header, CHR tile viewer, CPU registers and
  disassembly, memory watch; pause / step instruction / step frame
- [x] **Phase 2** – system schematic lit by live bus traffic, bus log of I/O register
  accesses, per-step bus accesses; sprite (OAM) viewer; Power Data Box
- [x] **REPL tools** – `nes.repl` (rd / poke! / regs / step! / frame! / press! / dis /
  hexdump), immutable snapshots with diff and search, datafy/nav, declarative lenses,
  a first lens for Concentration Room
- [x] **More views** – Lens tab (live lens fields), Background (nametables + scroll),
  Palettes; Portal in dev builds

## Seeing more of the hardware

- [ ] **Schematic follow-ups** – filter the bus log (PPU / APU / controllers / mapper);
  click a log line to jump to that register; show the mapper's current banks on the chip;
  draw the CIRAM A10 (mirroring) wire from the cartridge to video RAM
- [ ] **Sprite viewer follow-ups** – show the 8-sprites-per-scanline limit and sprite 0
  hit; highlight sprites drawn behind the background
- [ ] **Background viewer follow-ups** – show mid-frame scroll changes (status bars) as
  separate bands; mark which nametables are mirror copies
- [x] **Register decoder** – PPUCTRL / PPUMASK / PPUSTATUS shown as named bits (PPU tab),
  with a diagram of where the PPU is in the frame
- [x] **Partial frames while stepping** – show the picture as far as the PPU has drawn
  it, with a marker at the current scanline and dot; **Step line** button, `(line!)`
- [ ] **PPU internals** – the v / t / x / w scroll registers, and a log of PPU register
  writes with the scanline they happened on
- [ ] **Mapper / bank view** – which PRG and CHR banks are switched in right now

## REPL-driven development

- [x] **Match lenses by ROM hash** (SHA-1 without the header, as No-Intro lists it)
- [x] **Several lenses per game**, plus lenses for any game; picker in the Lens tab;
  `lens!` / `lenses` / `look` / `lens-source` at the REPL, Copy as code in the tab
- [ ] **Lens fields with notes** (e.g. "approximate") and units, shown in the Lens tab
- [x] **Edit a lens value from the Lens tab** (RAM, or ROM: a live ROM hack; download the
  changed ROM from the Cartridge tab)
- [ ] **Freeze a lens value** (write it every frame, like a Game Genie code)
- [ ] **Lenses for the other sample ROMs** (Thwaite, 240p Test Suite)
- [x] **Mega Man 2 lenses** from Data Crystal's RAM map, checked against the game
- [x] **Legend of Zelda lenses** – Link, inventory, screen/enemies, save files; ROM: weapon
  power, shops, world, cave and ending text (editable)
- [x] **ROM lenses** – `[:rom spec]` reads the cartridge by file offset; Mega Man 2's boss
  weaknesses, game rules and credits
- [x] **Text tables** – .tbl files; `:text` / `:screen-text` lens fields; Screen as text in the
  Background tab (load any .tbl)
- [ ] **Patch files** – save ROM changes as an IPS patch instead of a whole ROM
- [ ] **CHR ROM editing** (ROM lenses can only change PRG ROM)
- [ ] **Snapshot history** – keep the last few seconds of per-frame snapshots; ask
  "when did $0075 last change?"
- [ ] **Restore a snapshot** – needs full state (jsnes `toJSON`), not just memory
- [ ] **Finish the croom lens** – which card the pointer actually selects; whether
  poking tries-left shows on screen after the next miss

## Debugging tools

- [x] **Breakpoints** – execute / read / write / access, address ranges, a value to match,
  conditions from the REPL; gutter dots in the CPU tab, "on write" buttons in the Memory
  and Lens tabs; `break!` / `breaks` / `unbreak!` / `why` / `continue!`
- [ ] **Breakpoint follow-ups** – remember them per ROM; stop on NMI / IRQ; stop on a PPU
  register write at a given scanline; log instead of stopping (tracepoints)
- [x] **CPU tab: column headings** (with tooltips), **the last 3 instructions stepped through**
  stay above the highlighted one, and the stack shows where SP points and how full it is
- [ ] **Know what each stack byte is** – track JSR / PHA / PHP / interrupts as they push, so the
  stack view can say "A, saved by PHA at $C135" or "interrupted at $C12E" instead of guessing
- [ ] **A longer history** – the last few instructions before a breakpoint or pause too (needs
  the emulator to keep a short trace while running)
- [ ] **Step over / step out / run to line** in the disassembly
- [x] **Symbolic disassembly** – `STA PPUCTRL` instead of `STA $2000` for every game (hover
  for the number); games built here also show their own variables and labels
- [ ] **Name addresses yourself** – in any game, from the page or the REPL; kept per ROM
- [ ] **Trace log** – record every instruction for N frames; search and diff it
- [ ] **Memory editing and freezing** – poke values, lock a byte (like a Game Genie)
- [ ] **Save states and rewind** – jsnes has `toJSON`/`fromJSON`; a timeline of
  snapshots enables time travel and diffing two moments
- [ ] **Persist the watch list** per ROM in localStorage

## Code lenses: finding and naming subroutines

Lenses for code: name a game's subroutines, and find them automatically.
In an experiment (scripts in the conversation, not committed), following
code statically from the interrupt vectors found 17 subroutines in
Concentration Room and 7 in Mega Man 2's fixed bank. A minute of play,
recording what ran, found 40 and 97. Jump tables, data after `JSR`, and
bank switching defeat static analysis; recording only sees what was
played. So combine them.

- [x] **Code/data logger** – the Code tab (and `record-code!` / `code-summary` at the REPL):
  code vs data per PRG ROM byte, instruction starts, `JSR` targets with call counts, jump-table
  targets, NMI vs main loop; a map of the ROM; download in FCEUX's .cdl format
- [ ] **Code/data logger follow-ups** – remember logs per ROM (they're lost on reload); CHR ROM
  (which tiles get drawn); indirect data reads (`LDA ($nn),Y`); IRQ handlers; check the .cdl
  file in FCEUX itself
- [ ] **Routine discovery** – a pure function: logged entry points plus static traversal from
  them (and from the vectors) into a list of routines with their extent, calls and callers
- [ ] **Routine metadata** – RAM read and written (static and observed); hardware touched,
  as tags (PPU, controller, sound, bank switching); runs from the main loop or NMI
- [ ] **Code tab** – the routines, with calls and CPU cycles per frame (a profiler); click
  one to see it disassembled
- [ ] **Code lenses** – `{:rom sha1 :name "play sound" :at 0x3C061 :doc ...}`, registered like
  lenses (by ROM file offset, so banks don't matter), shown in the disassembly and usable
  for breakpoints by name
- [ ] **Call stack** while paused, from tracking `JSR`/`RTS` (the push-an-address-and-`RTS`
  trick will occasionally confuse it)
- [ ] **"Written by" on lens fields** – which routine changed this value (e.g. Mega Man's
  HP), from watching writes and mapping the instruction to its routine
- [ ] **Import/export labels** – FCEUX and Mesen label files; names (not code) from public
  disassemblies (Zelda 1 by Aldo Nunez, Super Mario Bros.) and Data Crystal's ROM maps
- [ ] **Recognizing routines by their behavior** – suggest names: writes `$4016` then reads it
  8 times → reads the controller; writes `$2007` in a loop → copies to video memory
- [ ] **Our own games' compiler** emits code lenses, so the same tools debug them
- [ ] **Annotation by homology** – the gene annotation idea: recognize a routine because it
  resembles one already named in another game (shared sound drivers, libraries), by comparing
  instruction sequences with the operands masked out, the way BLAST transfers gene names

## Playing games properly

- [x] **Sound** – Web Audio (AudioWorklet), volume, remembered on/off
- [x] **Gamepad support** and **player 2** (keyboard and second gamepad)
- [x] **Speed control** – 0.25× to 4× (sound muted away from 1×)
- [ ] **Remappable keys** and gamepad buttons
- [ ] **Sound at other speeds** – resample instead of muting
- [ ] **Turbo key** – hold to fast-forward
- [x] **Touch controller** – D-pad, A/B, Select/Start for phones and tablets: under the screen in
  portrait, beside it in big screen landscape; multi-touch, sliding, diagonals
- [x] **Page fits the window** (no page scroll), **big-screen mode** with a pull-up
  inspector drawer, **full screen**, Sample ROMs and Controls in pop-ups
- [x] **More sample ROMs** – Thwaite, 240p Test Suite
- [x] **Zipped ROMs** – .zip (pick between several ROMs in one) and .gz
- [ ] **Keyboard shortcuts** for big screen / full screen / pause
- [x] **A divider between the screen and the inspectors** – drag it sideways (or arrow keys);
  double-click to reset; remembered between visits. With room, the CPU tab's stack sits
  beside the instructions
- [ ] **Remember the rest of the layout** (big screen, drawer height, tab) between visits

## Our own emulator core (Phase 3)

- [x] **6502 CPU in ClojureScript** – `nes.cpu.core`: the 151 official instructions, with
  cycle counts and jsnes's exact bus accesses; swappable for jsnes's CPU inside the jsnes
  emulator (CPU tab, `emu/use-cpu!`). jsnes still takes interrupts and unofficial opcodes
- [x] **Validate against `nestest.nes`** – every line of its log matches (registers, flags,
  cycles), official and unofficial instructions
- [x] **Side-by-side check** – the CPU tab's Compare runs both CPUs for 600 frames and checks
  picture and RAM every frame: identical on all the games tried
- [x] **nestest in CI** – vendored in `test-roms/nestest`, as nescore does
- [x] **Interrupts in ClojureScript** – NMI, IRQ and RESET in `nes.cpu.core`, following jsnes's
  timing rules; jsnes now runs nothing but the 12 opcodes that jam the CPU
- [x] **Unofficial opcodes** – all 93, in the opcode table (`*` in the disassembly), the
  Instructions tab and `nes.cpu.core`; nestest's whole log (8,991 lines) matches
- [ ] **Faster core** – the step function allocates closures per instruction; whole frames
  run 1.0–1.2× jsnes's time now
- [ ] **A ClojureScript PPU**, then a whole console behind the `Emulator` protocol

## Making games (Phases 4 and 5)

The plan is in `docs/clojurescript_game_dev_notes.md`. The game-making code
is its own library, `gamedev-toolkit` (in `src/main/gamedev_toolkit`, all
`.cljc`): it depends on nothing in `nes.*` but the opcode table, so it can
move to its own repository once its API settles. Games are in
`src/main/games`.

- [x] **Assembler** – `gamedev-toolkit.asm`: 6502 assembly as Clojure data
  (`[:lda [:imm 1]]`, `[:sta :player-x]`, `[:lda :table :x]`), labels, expressions,
  `:db` / `:dw` / `:org`, a listing; errors that name the form
- [x] **ROM builder** – `gamedev-toolkit.rom`: PRG + CHR + iNES header into a `.nes`
  file (NROM), loaded straight into the emulator with `(play! ...)`
- [x] **Tiles as text** – `gamedev-toolkit.chr`: draw tiles as strings of `. 1 2 3`
- [x] **Hello world** – `games.hello`: a face to move around, built entirely from
  ClojureScript; in the Sample ROMs menu, assembled in the page
- [x] **Run routines without a console** – `nes.cpu.harness/run-routine`: assembled code
  on the ClojureScript CPU in `npm test`, with cycle counts
- [x] **Memory and symbols** – `gamedev-toolkit.memory/allocate`: declare variables as data
  (`:u8`, `:u16`, enums, bits, arrays, structs, structures of arrays) and it places them, zero
  page first. A built game brings a "Variables" lens and its names (`nes.debug-info`,
  `nes.workbench`): `STA player.x` and `main:` in the CPU tab, breakpoints by name,
  `(rd :player/x)` `(break! :main)` `(dis :nmi)` `(syms)` at the REPL
- [ ] **Memory follow-ups** – put the most-used variables in the zero page
  (count uses in the code); show `:doc` in the Lens tab; a memory map of what's allocated
- [x] **Generators** – `gamedev-toolkit.gen`: `add16` `sub16` `inc16` `compare16` `move`
  `fill` `copy`; `when-flag` `if-flag` `do-while` `times` `forever`; `preserving`. Checked with
  test.check: the generated code runs on the CPU and is compared with Clojure arithmetic
- [x] **What code clobbers, worked out from the code** – `gamedev-toolkit.analysis/clobbers`
  reads the instructions (and follows calls); `times` refuses a body that changes its counter
- [x] **Subroutines** – `gamedev-toolkit.sub`: `sub`, `call`, `inline`; `link` includes only the
  ones reached; their descriptions show as tooltips on labels in the CPU tab
- [x] **More generators** – `multiply`, `divide`, `multiply-a` / `divide-a` (by constants, with
  shifts); `compare-signed`, `compare16-signed`, `add-signed-byte`; 8.8 fixed point (`:u8.8` /
  `:s8.8` variables, shown and edited as decimals in the Lens tab; `gen/fixed`, `gen/whole`);
  `fill` / `copy` up to 4 KB and `times` up to 65536; loops and conditions switch to a `JMP`
  by themselves when the body is too big for a branch; `analysis/size` counts bytes
- [ ] **Simple optimizations** – e.g. drop a `LDA x` straight after `STA x`
- [ ] **Cycle counts without running** – for straight-line code, from the opcode table (for
  now, `nes.cpu.harness/run-routine` runs the code and reports the cycles it took)
- [x] **Runtime** – `gamedev-toolkit.runtime/program`: a game is a finite state machine (states
  with `:enter`, `:update` and `:exit` actions; `transition` between them) and the runtime is
  the rest: reset, the NMI handler, the controller
  (`when-held`, `when-pressed`), sprites (`sprite`, `metasprite`, hidden when not drawn), a queue
  of background writes (`queue`), whole screens (`draw-screen`, `text`), `random`
- [x] **A font** – `gamedev-toolkit.font`: A-Z, 0-9 and some punctuation, as tiles
- [x] **A first game** – `games.catch`, Catch the Stars: title, playing and game over states,
  fixed-point movement, a bouncing enemy that speeds up, a score. In the Sample ROMs menu
- [x] **Games run without a console in tests** – `nes.cpu.harness/console`, `boot!`, `frame!`,
  `hold!`: play a game on the CPU alone, press buttons, check its variables and that each
  frame's work fits in a frame (and the NMI handler in vertical blank)
- [x] **Cartridges as EDN** – `gamedev-toolkit.rom-edn`: any ROM as a container (its bytes as
  hex), a game built here as its source (every instruction, tiles as drawings); both go back to
  the same `.nes` file. Save from the Cartridge tab, or `(save-edn!)`
- [x] **Download the built ROM** as a `.nes` file – Cartridge tab, or `(save! (catch/build))`
- [ ] **Runtime follow-ups** – check the queue for overflow; sound; a second controller;
  scrolling; more than 64 sprites' worth of flicker handling; pausing
- [ ] **EDN for ROMs not built here** – bytes the Code tab has seen run become instructions,
  the rest stay as `[:db ...]`: lossless always, more readable the more you play
- [ ] **Build from the command line** (the toolkit runs on JVM Clojure too): a `clj -X` entry
  that writes the `.nes` and `.edn` files
- [ ] **CHR tile editor** – paint tiles in the Tiles view and write them back into a ROM
- [ ] **Assets as data** – palettes, screens and levels as EDN
- [ ] **Engine pieces** – metatiles, collision with the background, scrolling, sound, object
  slots with spawn and despawn, animation, a second mapper
- [ ] **In-browser editor** – SCI (already a dependency) to write and run the
  assembler from the page
- [ ] **A small language** of expressions and typed variables that compiles to generators

## Running outside the browser

Non-UI code is written as `.cljc` from here on (the toolkit and games already are), so that
a JVM emulator could reuse it one day.

- [ ] **Portability, stage one** – a small shared namespace for hex formatting and number
  parsing, a byte-array wrapper (JVM bytes are signed), and the easy namespaces moved to
  `.cljc`: `emulator` (protocols), `debug-info`, `memory-map`, `registers`, `disasm`,
  `cpu.reference`, `lens` and the lenses, `text-table`, `breakpoints`, `nametable`, `snapshot`,
  `sprites`; then `rom`, `chr`, `code-log`, `routines`. Their tests run on the JVM in CI
- [ ] **A portable CPU core** – `nes.cpu.core` keeps its registers in a JS object for speed;
  a `deftype` with mutable fields and a bus protocol would work on both. nestest guards it
- [ ] **Split `nes.console`** into a portable controller (run, step, breakpoints) and the
  browser's driver (animation frames, audio, Reagent state)
- [ ] **Views: what to show, separately from how** – so another front end (HumbleUI?) can
  reuse the rows of the disassembly, the stack, the lens values, the schematic's layout
- [ ] A JVM emulator also needs our own PPU, sound and mappers: those are jsnes today

## Housekeeping

- [ ] Performance check: how much does the bus monitor cost? Consider a Web Worker
  for emulation if the UI gets heavy
- [x] CI: unit tests, release build and a browser smoke test on every push and PR
- [ ] A README with screenshots
