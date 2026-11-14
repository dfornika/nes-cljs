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
- [x] **CHR ROM editing** – the Tiles tab's editor and `emu/tile-poke!` (ROM lenses still only
  change PRG ROM)
- [ ] **Snapshot history** – keep the last few seconds of per-frame snapshots; ask
  "when did $0075 last change?"
- [ ] **Restore a snapshot** – needs full state (jsnes `toJSON`), not just memory
- [ ] **Finish the croom lens** – which card the pointer actually selects; whether
  poking tries-left shows on screen after the next miss

## Debugging tools

- [x] **Overlays** – `nes.overlay`: shapes (boxes, lines, circles, text) drawn over the game's
  picture from lens values, as data; checkboxes under the screen; `(overlay! name fields draw)`
  at the REPL. "Sprites" boxes every sprite in any game; a game built here can bring its own
  (Catch the Stars shows the squares it compares to decide what's touching)
- [ ] **Overlay follow-ups** – overlays for the lenses we have (Mega Man 2 and Zelda keep
  positions in known places); the 8-sprites-per-line limit and sprite 0 hit as overlays; a
  trail of where something has been; click a shape to open its lens field or set a breakpoint
- [ ] **A way out to the host** – let a game call ClojureScript while it runs, for logging,
  assertions in tests, and trying out logic before writing it in 6502. Two ways, both worth having:
  (1) a debug port: a write to an unused address ($4018-$401F do nothing on a real NES), which
  the emulator notices. The ROM still runs anywhere else, the hook just doesn't fire.
  (2) a host-call opcode: one of the 12 that jam a real 6502 ($02, $12, $22...), followed by a
  byte saying which function. The emulator runs the function and carries on. More powerful
  (it can stand in for a whole routine) but the ROM only runs here, so builds must be able to
  refuse or strip it. Start with (1) in `nes.cpu.harness`, where it's a few lines. (Both
  belong with the enhanced mode: `docs/enhanced_mode.md`)

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
  instruction sequences with the operands masked out, the way BLAST transfers gene names.
  Notes, with genome formats for all of it (FASTA, GFF3, GenBank, alignments, Mash), in
  `docs/rom_annotation.md`
- [ ] **A GFF3 file from every build** (and a Mesen `.mlb`): where each subroutine and table
  is, from the linker. The true answer to test homology search against; see
  `docs/rom_annotation.md`, "Where to start"

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
- [ ] **An enhanced mode** – after our own PPU: options for any ROM (no sprite limit,
  overclocking without changing what's on screen, other master palettes), then a "meta-NES
  mapper" (per-tile palettes, a multiplier, more sprites, extra sound), and perhaps one day a
  different machine (16:9, more colors, extended opcodes). See `docs/enhanced_mode.md`

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
- [x] **Tail calls** – a subroutine that ends by calling another jumps to it instead (`JSR` +
  `RTS` becomes `JMP`): a byte smaller, 9 cycles quicker, and no stack used, so chains of them
  can be any length. Done by `sub/link`
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
- [x] **Tile editor** – Tiles tab: click a tile and paint on it. The game's picture changes as
  you paint; the change goes into the cartridge's CHR ROM when the tiles can be traced back to
  it (so it's in the ROM and EDN you download), and the tile is shown as text to paste into a
  game's source. `(tile n)` and `(tile! n rows)` at the REPL
- [x] **Assets as data** – `gamedev-toolkit.tileset` (tiles by name instead of number),
  `gamedev-toolkit.palette` (colors and palettes by name), `gamedev-toolkit.screen` (a
  background drawn as text: 16 by 15 blocks with a legend, text over the top, packed as runs).
  The runtime takes `:screens` and draws one with `(background :title)`
- [ ] **Asset follow-ups** – read drawings and screens from `.edn` files; import a PNG as tiles;
  a screen editor in the page (paint with blocks, as the tile editor paints with pixels); share
  identical tiles; better packing for screens with patterns; levels bigger than a screen
- [ ] **Tile editor follow-ups** – undo; copy and paste tiles; flip and shift; paint with the
  palette the tile is drawn with on screen; games whose mappers switch 1 KB or 2 KB of tiles
  at a time (changes there stay in the PPU's memory only)
- [x] **The ladder** – `games.ladder`: five tiny programs, each a rung above the last, from
  `nil` (one instruction, 3 bytes: a valid cartridge that does nothing) through `color`,
  `pulse` (the smallest game loop), `pad` and `sprite`, each adding one idea and one piece of the
  toolkit. In the Sample ROMs menu; `(play! (ladder/build :nil))`
- [ ] **Ladder follow-ups** – a page that shows a rung's source beside it running; more rungs
  (a background, sound, scrolling); the same for the toolkit's layers (assembler → generators →
  subroutines → runtime)
- [x] **Engine, part one: a platform game on one screen** – `games.hopper`, Star Hopper, and
  what it needed:
  - **Solid blocks** – `:solid? true` on a metatile; each screen carries a map of them, and
    `rt/solid-at` / `rt/probe` ask "is the background solid at this pixel?"
  - **Platformer physics** – `gamedev-toolkit.platformer`: `move-body` (gravity, landing on
    things, bumping heads, walls) and `jump`, generated for a body's own variables
  - **Objects** – `gamedev-toolkit.objects`: a pool of slots (a structure of arrays; type 0 is
    free) with `spawn`, `each`, `by-type`, `remove-it`; a level's objects are drawn in its text
    with its blocks (`in-drawing`) and spawned from a table (`spawn-all`)
  - **Sound effects** – `gamedev-toolkit.sound`: an effect is four register writes, and the
    sound chip's own fade and pitch-bend do the rest
  - **Facing both ways, and walking** – `tileset/sprites-flipped`; two frames, chosen by the
    frame count
  - `gen/indexed` operands (an entry in a table, by X or Y), so the one-byte generators and
    `metasprite` work on objects in a pool
- [x] **Art from a pack** – `resources/assets/hexanys-alienscape` (Hexany's Alienscape, CC0);
  `gamedev-toolkit.png` (JVM) turns pictures into NES tiles: nearest NES colors, palettes that
  cover the tiles, one palette per 16x16 area (repainting the few pixels that don't fit),
  shared tiles, animation frames. `games.alienscape.import` writes the result as data
  (`games.alienscape.art`), so the game builds anywhere; a JVM test checks it's up to date
- [x] **One set of rules, two games** – `games.platform-kit`: Star Hopper's rules with the looks,
  level and numbers passed in. `games.hopper` and `games.alienscape` are each a page of data
- [x] `rt/animated` (frames that take turns), `screen/from-tiles` (a screen from imported tiles,
  with what's solid drawn separately), `palette/rgb` and `palette/nearest`
- [ ] **Art follow-ups** – the pack's other three rooms (needs scrolling or doors between
  screens), its animated tiles (the liquid, the doors: needs CHR RAM or bank switching), the
  leech; reading what's solid from the art (most solid blocks look alike); an "import a PNG"
  button in the page
- [x] **Engine, part two** – `games.tower`, Star Tower, and what it needed:
  - **Kinds of block** – `:kind :solid / :one-way / :ladder` on a metatile; a ladder's top is
    worked out when the ROM is built (`screen/kind-bytes`) and is both climbable and stood on;
    `rt/on-kinds` tests what `probe` found
  - **One-way platforms and ladders** in `gamedev-toolkit.platformer`: `move-body` lands on a
    one-way platform only from above; `ladders` climbs with Up and Down
  - **Feel** – `platformer/speed-up` and `slow-down` (acceleration and friction), `cut-jump`
    (let go of A to cut a jump short)
  - **Music** – `sound/song` (notes by name, in frames), played on the triangle channel by
    the runtime (`:songs`, `rt/play-song`, `rt/stop-music`)
  - **Enemies that fall** – a walker with `:falls? true` walks off edges and drops
  - **Flicker instead of vanishing** – `objects/each-rotating` draws objects in a different
    order each frame
  - `games.platform-kit` takes all of these as options; Star Hopper and Alienscape are unchanged
- [x] **Engine, part three: scrolling** – `games.star-run`, Star Run, and what it needed:
  - **Levels wider than the screen** – `screen/level`: 15 rows of text up to 240 blocks long;
    its tiles, palettes and what's solid worked out a column at a time when the ROM is built
  - **Drawing as the camera moves** – `rt/level` draws the 32 columns around the camera; the
    main loop prepares each column that comes into view and the NMI handler copies it in
    (vertical mirroring: two screens side by side, as a ring); `rt/follow` moves the camera
  - **16-bit positions** – `:u16.8` variables, `gen/whole16`, `gen/add-wide`; 16-bit object
    fields (`objects/field16`); `solid-at` looks up a level position; `rt/on-screen` turns a
    level position into a screen one; `gen/when-close` across 16 bits
  - **One way to write operands** – every generator takes addresses, `[:imm n]`, table entries
    (`gen/indexed`) and 16-bit pairs (`gen/pair`, `gen/wide`)
  - `probe` and `solid-at` no longer change X, so code looking at objects needn't keep
    reloading the object's slot; `rt/restart` re-enters the current state; `rt/sprite-text`
  - The kit runs every game on 16-bit positions; Star Hopper, Alienscape and Star Tower are
    unchanged, and their tests pass as they were
- [ ] **Engine, part four** – a second mapper (UxROM or MMC1), for games that outgrow 16 KB
  (Star Run uses 9 of its 16); platforms that move (and carry the player); objects that appear
  as the camera nears them, instead of all being active all the time; vertical scrolling
- [ ] **Toolkit ergonomics** (from a review after part three) – the layers below are sound
  (the assembler, linker, allocator and generators have property tests, and every game is played
  headlessly in `npm test`), but some things are still kept by convention:
  - the runtime's scratch bytes (`:rt/temp`, `:rt/temp2`, `:rt/old`, `:rt/pointer`) belong to
    whichever routine is running: nothing checks two don't overlap. Declare them per routine,
    and have the linker check
  - which registers a routine changes is checked only by `gen/times`. `analysis/clobbers` could
    check `objects/each` bodies, and calls generally
  - a struct's fields are `:player/x` in code but `:player.x` in an overlay's values (when the
    lens flattens a struct of more than four fields); one spelling would be better
  - `games.platform-kit` is game code that's grown options; if a third kind of game wants it,
    split it into pieces instead of adding more
- [ ] **Engine follow-ups** – animation as data (frames and timings in a table) rather than
  code; a second voice for the music (on a pulse channel, giving way to sound effects);
  `move-body` for objects in a pool, so enemies can jump; the player climbing onto a ladder
  from beside it without lining up exactly
- [ ] **In-browser editor** – SCI (already a dependency) to write and run the
  assembler from the page
- [ ] **A small language** of expressions and typed variables that compiles to generators

## Ideas from SICP

Things that came up reading *Structure and Interpretation of Computer Programs* beside this
project. The rule for when one gets built: it helps make or debug the next thing on the engine
plan, or it's an afternoon. Otherwise it waits here.

- [ ] **A state as an address** – SICP's register machines keep 'where to go next' in a
  `continue` register. The 6502 version: a state is the address of its code, a transition
  stores a new address, and the dispatch is `JMP (state)`. It replaces the runtime's chain of
  compares. Worth doing when a game has more than a handful of states; the debugger can still
  show the state by name, from the symbol table
- [ ] **Scripts that pause** (coroutines) – the useful form of continuations on this machine.
  An object's behaviour written as a sequence ("walk left for 30 frames, wait, shoot, repeat"),
  with a `yield` that saves where to resume next frame in the object's slot. No heap: one
  address per object. Do it with object slots, in the engine work
- [ ] **Full continuations** (`call/cc`) would mean copying the stack. With 256 bytes of it
  and no heap, not on the 6502; the two items above get the useful parts
- [ ] **Calling conventions** – arguments here go in registers or variables, so there's
  nothing for a callee to clean up. It would matter with a software stack for arguments (as
  cc65 has), which costs enough cycles that games avoid it
- [x] **A pool of objects** – what games use where other programs use a heap: a fixed number
  of slots (a structure of arrays). Spawn and despawn, no fragmentation: `gamedev-toolkit.objects`.
  (It looks for a free slot each time; a free list would be quicker with many slots)
- [ ] **A priority queue** – MagerValp's AsmHeap is this (a binary min-heap of bytes in 128
  bytes, push and pop), not a memory allocator. As a generator it could order sprites by
  depth, or schedule "do this in N frames"
- [ ] **Pairs and garbage collection** – SICP 5.3 builds lists from two arrays, `the-cars` and
  `the-cdrs`, and collects garbage by copying. That's `[:soa n {:car :u8 :cdr :u8}]` here. A
  list memory with a stop-and-copy collector you can watch in the Memory tab would be a fine
  thing to learn from, and no use at all to a game
- [ ] **An evaluator on the NES** – SICP 5.4's explicit-control evaluator, written with the
  toolkit: a tiny Lisp running on the 6502, with the debugger showing `exp`, `env`, `val`
  and `continue`. Needs the item above. The far end of this road
- [ ] **A small language that compiles to generators** (layer 7 in the notes) – SICP 5.5's
  compiler is the model: functions returning instruction sequences, combined with
  `preserving`, which `gamedev-toolkit.gen` already has

## Running outside the browser

Non-UI code is written as `.cljc` from here on (the toolkit and games already are), so that
a JVM emulator could reuse it one day.

- [x] **Portability, stage one** – `nes.util` (hex, reading numbers) and `nes.bytes` (byte and
  32-bit arrays that read the same in the browser and on the JVM, where bytes are signed); 21
  namespaces moved to `.cljc`: `emulator` (the protocols), `debug-info`, `memory-map`, `gamepad`,
  `registers`, `disasm`, `cpu.reference`, `lens` and the lenses, `text-table`, `breakpoints`,
  `nametable`, `snapshot`, `sprites`, `rom`, `chr`, `code-log`, `routines`, `sha1`. Their tests
  are `.cljc` too and run on the JVM as well (`npm run test:jvm`, and in CI)
- [ ] **More tests on the JVM** – the assembler's and games' tests run code on `nes.cpu.core`,
  so they wait for the portable CPU core
- [ ] **A portable CPU core** – `nes.cpu.core` keeps its registers in a JS object for speed;
  a `deftype` with mutable fields and a bus protocol would work on both. nestest guards it
- [ ] **`nes.zip`** – unzipping uses the browser's `DecompressionStream`; the JVM has `java.util.zip`
- [ ] **Split `nes.console`** into a portable controller (run, step, breakpoints) and the
  browser's driver (animation frames, audio, Reagent state)
- [ ] **Views: what to show, separately from how** – so another front end (HumbleUI?) can
  reuse the rows of the disassembly, the stack, the lens values, the schematic's layout
- [ ] A JVM emulator also needs our own PPU, sound and mappers: those are jsnes today

## Housekeeping

- [ ] **A frame-budget meter** in the CPU tab: how much of each frame the game used (see
  `docs/enhanced_mode.md`). Doesn't need our own PPU
- [ ] **The breakpoint form sometimes empties itself** – in the smoke test, about one run in
  ten, the address typed into the CPU tab's "Stop on" form is gone before Add is clicked (the
  test now fills it in again). Find out why: something re-creating the form, or the way
  Reagent handles typing into inputs
- [ ] **A name** – "meta-nes" (after the Super NES, and Lisp's metacircular evaluator) is the
  candidate. Renaming moves the site's address, so do it once, perhaps when the toolkit
  becomes its own library and needs a name too
- [ ] Performance check: how much does the bus monitor cost? Consider a Web Worker
  for emulation if the UI gets heavy
- [x] CI: unit tests, release build and a browser smoke test on every push and PR
- [ ] A README with screenshots
