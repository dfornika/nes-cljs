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
- [ ] **Step over / step out / run to line** in the disassembly
- [ ] **Symbolic disassembly** – show `STA PPUCTRL` instead of `STA $2000`, and let
  users name addresses
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
- [x] **Page fits the window** (no page scroll), **big-screen mode** with a pull-up
  inspector drawer, **full screen**, Sample ROMs and Controls in pop-ups
- [x] **More sample ROMs** – Thwaite, 240p Test Suite
- [x] **Zipped ROMs** – .zip (pick between several ROMs in one) and .gz
- [ ] **Keyboard shortcuts** for big screen / full screen / pause
- [ ] **Remember the layout** (big screen, drawer height, tab) between visits

## Our own emulator core (Phase 3)

- [x] **6502 CPU in ClojureScript** – `nes.cpu.core`: the 151 official instructions, with
  cycle counts and jsnes's exact bus accesses; swappable for jsnes's CPU inside the jsnes
  emulator (CPU tab, `emu/use-cpu!`). jsnes still takes interrupts and unofficial opcodes
- [x] **Validate against `nestest.nes`** – all 5,003 official-instruction lines of its log
  match (registers, flags, cycles). The test is skipped where nestest isn't present (CI)
- [x] **Side-by-side check** – the CPU tab's Compare runs both CPUs for 600 frames and checks
  picture and RAM every frame: identical on all the games tried
- [ ] **nestest in CI** – the ROM and log aren't in the repo (no clear license); fetch them in
  CI, or add them with a note
- [ ] **Interrupts in ClojureScript** – take NMI and IRQ in `nes.cpu.core` too, matching
  jsnes's timing (when an NMI raised mid-instruction takes effect)
- [ ] **Unofficial opcodes** in the opcode table, disassembler and `nes.cpu.core`
- [ ] **Faster core** – the step function allocates closures per instruction; whole frames
  run 1.0–1.2× jsnes's time now
- [ ] **A ClojureScript PPU**, then a whole console behind the `Emulator` protocol

## Making games (Phases 4 and 5)

- [ ] **Assembler** – 6502 assembly as Clojure data (`[:lda :imm 0x01]`), labels,
  using `nes.cpu.opcodes/opcode-for`
- [ ] **ROM builder** – pack PRG + CHR + iNES header into a `.nes` file in the browser
  and load it straight into the emulator
- [ ] **CHR tile editor** – paint tiles in the Tiles view and write them back into a ROM
- [ ] **Hello world** – a tiny homebrew ROM built entirely from ClojureScript
- [ ] **In-browser editor** – SCI (already a dependency) to write and run the
  assembler from the page
- [ ] **A small DSL and game engine** – functions and macros that generate 6502 code

## Housekeeping

- [ ] Performance check: how much does the bus monitor cost? Consider a Web Worker
  for emulation if the UI gets heavy
- [x] CI: unit tests, release build and a browser smoke test on every push and PR
- [ ] A README with screenshots
