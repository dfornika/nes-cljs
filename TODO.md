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

- [ ] **6502 CPU in ClojureScript** behind the `Emulator` protocol, reusing
  `nes.cpu.opcodes`
- [ ] **Validate against `nestest.nes`** and its reference log (in `jsnes/roms/nestest`)
- [ ] **Side-by-side mode** – run jsnes and our CPU in lockstep and stop at the first
  difference
- [ ] **Unofficial opcodes** in the opcode table and disassembler

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
