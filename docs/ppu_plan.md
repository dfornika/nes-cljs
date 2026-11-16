# Our own PPU: a plan

A plan for a PPU (the NES's picture chip) written in portable Clojure
(`.cljc`), and for the console around it. Nothing here is built yet. It's
the next item in "Our own emulator core" in `TODO.md`, and it's what
`docs/enhanced_mode.md`, a JVM emulator, and several debugging tools are
waiting on.

## Why, and what changes

Today jsnes does everything except the CPU. Our 6502 (`nes.cpu.core`)
runs inside jsnes, using jsnes's bus, so jsnes's PPU, mappers and sound
see every access (`nes.emulator.jsnes-cljs-cpu`). Our own PPU gives us:

- **A PPU we can open up.** Every register, fetch and pixel is ours to
  show: the scroll registers (v, t, x, w), a log of register writes by
  scanline and dot, and which tile or sprite made each pixel. That suits
  a site whose point is to explain the hardware.
- **Enhanced mode, tier 1.** No sprite limit, other master palettes, extra
  scanlines for the CPU. All of it is a change to the PPU (see
  `docs/enhanced_mode.md`).
- **A whole console in Clojure**, behind the `nes.emulator` protocols,
  that also runs on the JVM.
- **Exact headless tests.** The game harness (`nes.cpu.harness`) fakes
  the PPU: PPUSTATUS always says "in vertical blank". With a real PPU,
  "the NMI handler fits in vertical blank" is measured, not estimated.

## Decisions to make first

Each comes with a recommendation.

### 1. How exact: by the dot, or by the scanline?

The PPU draws one pixel per *dot*. A frame is 262 lines of 341 dots,
which is 89,342 dots, or about 5.4 million a second. The CPU runs one
cycle for every 3 dots.

- **Scanline renderers** draw a whole line at once and patch in timing
  (sprite 0 hit, vertical blank) separately. jsnes is one: it renders
  lines in `renderBgScanline` and checks events per dot in `advanceDots`.
  They're faster, but mid-line effects are approximate, and the code
  follows the emulator's shortcuts rather than the hardware.
- **Dot renderers** do what the hardware does each dot: one memory fetch
  every 2 dots, shift registers, and the pixel picked from background and
  sprites. They're slower, but the model is the hardware's, so the code
  reads like the nesdev wiki's timing diagram, and mid-line tricks just
  work.

**Recommendation:** by the dot. It's the model worth teaching and the one
the debugging views want. Speed comes second (see step 9). Keep a
whole-frame renderer too (step 2), as a test oracle and for the views.

### 2. How to keep the state, portably

The CPU keeps its registers in a JS object for speed, which ties it to
the browser (`TODO.md` has "A portable CPU core" for that). The PPU
should start portable. The options:

- **A `deftype` with mutable fields.** Fast on both platforms, but on the
  JVM its mutable fields can only be set inside the type's own methods.
  All the logic would end up in protocol methods, and the field
  annotations differ between platforms (`^:mutable` against
  `^:unsynchronized-mutable`).
- **Arrays.** The PPU's memories are already arrays: 2 KB of nametable
  RAM, 256 bytes of OAM, 32 bytes of palette RAM, and the picture. Its
  registers can be one more small array of ints, with named indexes
  (`(def ^:const V 0)`, `(def ^:const T 1)` ...), read and written through
  `nes.bytes`'s macros, which cost what a native array access costs.

**Recommendation:** arrays. Every function is then a plain `defn` over a
map of arrays, so the same code runs on both platforms. Saving the state
is copying arrays, which is what save states, rewind and `nes.snapshot`
want anyway.

### 3. What the picture is made of

Output **palette indexes** (6 bits of color plus 3 bits of tint, the
"emphasis" bits), not RGB. Turn them into colors once per frame with a
master palette. That makes "other master palettes" free, keeps the PPU
free of color tables, and lets a view show which palette entry each pixel
came from. The 64 colors themselves are what `master-palette` returns now.

### 4. How the cartridge plugs in

The PPU reads tiles from the cartridge (CHR ROM or RAM, at
$0000-$1FFF). The cartridge also decides how the 2 KB of nametable RAM
fills the 4 KB of nametable addresses: that's mirroring. And some mappers
watch the PPU's address lines. MMC3 counts scanlines by watching address
bit 12 rise. So the PPU talks to the cartridge through a small protocol:

```clojure
(defprotocol Cartridge
  (cpu-read  [cart addr])
  (cpu-write [cart addr value])
  (ppu-read  [cart addr])        ; $0000-$3EFF: tiles, and nametables through mirroring
  (ppu-write [cart addr value])
  (irq?      [cart]))            ; is the mapper asking for an interrupt?
```

For MMC3, `ppu-read` can watch address bit 12 itself, since every PPU
fetch goes through it.

jsnes's mappers can't be reused. They reach into its PPU's internals
(`ptTile`, `vramMem`, `ntable1`, `triggerRendering`: dozens of uses
between them), so ours are written afresh. That's small for mapper 0 and
the common ones.

## The pieces, in order

Each step is a pull request that stands on its own, with tests.

### Step 1: registers and the scroll model (`nes.ppu.registers`)

The CPU sees 8 registers, $2000-$2007, repeated up to $3FFF. Behind
them, the scroll and address state is four internal registers, named by
the nesdev wiki after the person who worked them out ("loopy"):

- `v`: the current VRAM address, 15 bits. While drawing, it's also the
  scroll position: coarse X (5 bits), coarse Y (5 bits), nametable
  (2 bits) and fine Y (3 bits).
- `t`: the same layout, the address being set up for the next frame or
  line.
- `x`: fine X scroll, 3 bits.
- `w`: which write is next for the two-write registers $2005 and $2006.

Pure functions, one per register access, from state to state:

- PPUCTRL ($2000) writes: the nametable bits go into `t`, and turning NMI
  on during vertical blank raises one straight away.
- PPUMASK ($2001) writes.
- PPUSTATUS ($2002) reads: vertical blank, sprite 0 hit and overflow in
  the top bits, stale bus contents in the low 5. A read clears vertical
  blank and `w`.
- OAMADDR and OAMDATA ($2003, $2004).
- PPUSCROLL ($2005) writes: the first sets coarse and fine X, the second
  coarse and fine Y.
- PPUADDR ($2006) writes: the high byte into `t`, then the low byte, and
  `t` is copied to `v`.
- PPUDATA ($2007) reads and writes. Reads are buffered, one read behind,
  except palette reads. Afterwards `v` moves on by 1 or 32 (PPUCTRL
  bit 2: the column-at-a-time trick Star Run's scrolling uses).
- The open-bus latch: reading a write-only register returns what was last
  on the bus.

Tests: the wiki's worked examples of $2005 and $2006 writes; property
tests that any sequence of writes keeps `v` and `t` within 15 bits and
that two $2006 writes always set `v`.

This step pays for itself at once: the PPU tab can show v, t, x and w
(`TODO.md`, "PPU internals").

### Step 2: a whole-frame renderer (`nes.ppu.render`)

A pure function: PPU memory, OAM and registers in, a frame of palette
indexes out. No timing. Each line is drawn from the scroll position as it
was at the start of the frame. Most of it exists already: `nes.chr`
decodes tiles, `nes.nametable` tiles and attributes, and `nes.sprites`
OAM. It applies the real rules:

- 8 sprites per line, the lowest-numbered first.
- Sprites behind the background (attribute bit 5) show only through its
  color 0.
- The left 8 pixels can be hidden (PPUMASK bits 1 and 2).
- 8x16 sprites take their pattern table from the tile number's bit 0.

What it's for:

- **A test oracle.** On any frame with no changes mid-frame, the dot
  renderer of step 3 must produce exactly this picture. That's a property
  test, run over random memory, OAM and scroll.
- **The views.** The Background and Sprites tabs could use it, instead of
  each decoding memory itself.
- **A first comparison with jsnes.** Grab jsnes's memory at the end of a
  frame in a still scene (a title screen), render it, and compare it with
  jsnes's own picture.

### Step 3: the dot-stepped PPU (`nes.ppu.core`)

`(tick! ppu cart)` moves the PPU one dot. Per frame, by line:

| Lines | What happens |
|---|---|
| 0-239 | visible: a pixel out on each of dots 1-256 |
| 240 | idle |
| 241 | dot 1: vertical blank starts (PPUSTATUS bit 7), and an NMI if PPUCTRL bit 7 is on |
| 242-260 | vertical blank: the CPU's time to change video memory |
| 261 | "pre-render": dot 1 clears vertical blank, sprite 0 hit and overflow; dots 280-304 copy the vertical scroll from `t` to `v`; on odd frames, with drawing on, the last dot is skipped |

On visible lines and the pre-render line, while drawing is on:

- **Background fetches**, 8 dots for each tile: the tile number from the
  nametable, its attribute byte, then the two bitplane bytes of its row.
  The bytes are loaded into 16-bit shift registers, which shift once a
  dot. Fine X picks which bit is the current pixel.
- **Scroll increments:**
  - coarse X goes up every 8 dots, across into the next nametable after
    the 32nd tile
  - fine Y (and, after 8, coarse Y) goes up at dot 256, wrapping at row
    29, not 31
  - at dot 257 the horizontal parts of `t` are copied to `v`

  These are where every scrolling bug lives, so they get their own unit
  tests.
- **Dots 321-336** fetch the first two tiles of the next line.
- **Sprites:**
  - dots 1-64 clear the 32-byte "secondary OAM"
  - dots 65-256 find up to 8 sprites on the next line
  - dots 257-320 fetch their tiles into 8 sprite units, each with its
    own shifters, attribute and X counter

  The search can start simple, done all at once at dot 65. The hardware's
  exact pattern of OAM reads only matters for the overflow flag's bug,
  which can come later.
- **The pixel:** background color 0 or 1-3, the first opaque sprite's
  color and priority, and the rules that pick one. Sprite 0 hit is set
  when an opaque sprite 0 pixel lands on an opaque background pixel. That
  never happens at x = 255, nor in the left 8 pixels when they're hidden,
  and needs both layers on.

Output: the palette index of each pixel goes into the frame array. NMI
goes out as a level that the console checks (see step 4).

Tests:

- the property against step 2
- unit tests of the increments
- the timing table above, as tests: what PPUSTATUS reads at line 241,
  dots 0, 1 and 2
- the test ROMs in step 6

### Step 4: a console (`nes.machine`) and mapper 0

The CPU, the PPU, 2 KB of RAM, the controllers and a cartridge, wired
together:

- **The bus:**
  - $0000-$1FFF: RAM, mirrored
  - $2000-$3FFF: the PPU's registers
  - $4014: sprite DMA. 513 or 514 CPU cycles during which the PPU keeps
    running.
  - $4016 and $4017: the controllers
  - $4020 and up: the cartridge

  It implements the CPU's bus methods (`fetch`, `read`, `write`, ...). Each
  access moves the PPU on 3 dots, as jsnes's does, so the CPU core is used
  unchanged.
- **Interrupts:** the CPU checks the PPU's NMI level between
  instructions. jsnes's precise rules (an NMI that arrives late in an
  instruction waits one more) are already worked out in
  `nes.emulator.jsnes-cljs-cpu`: port them.
- **Mapper 0 (NROM):** 16 or 32 KB of program and 8 KB of tiles, with
  fixed mirroring. Every game we've made, and nestest, are mapper 0.
- **Sound:** none at first. The APU is its own project. Until it exists,
  the app's default stays jsnes, and our console is a choice beside it,
  like the CPU choice is now.
- **The `nes.emulator` protocols:** `Emulator`, `Inspect`, `Poke`,
  `Debug` and `CodeLog`. Most methods are now direct reads of our own
  arrays. `picture-so-far!` and `ppu-state` are free, since the PPU knows
  exactly where it is.

At this point **the game harness can use the real console.** Our games'
headless tests (`games/*_test.cljs`) switch from the fake PPUSTATUS to
real vertical blanks. Check that their NMI budget tests still pass and
now measure the real thing.

### Step 5: compare with jsnes in the app

The CPU tab's Compare runs two CPUs side by side for 600 frames and
compares the picture and RAM each frame. Do the same with two consoles:
jsnes, and ours. Any difference means stopping at that frame and showing
both pictures and a diff.

jsnes isn't the referee, though. Where the two disagree, the test ROMs
decide (step 6). jsnes has the scanline renderer's approximations, so
some differences will be ours being right.

### Step 6: test ROMs

What's worth checking, by area:

- **Vertical blank and NMI timing:** blargg's `ppu_vbl_nmi` and
  `vbl_nmi_timing`.
- **Sprite 0 hit:** `sprite_hit_tests_2005`.
- **Sprite overflow:** `sprite_overflow_tests`. The hardware bug, once
  sprite search is exact.
- **The read buffer and open bus:** `ppu_read_buffer` and
  `ppu_open_bus`.
- **OAM:** `oam_read` and `oam_stress`.
- **Many things at once:** AccuracyCoin, a newer collection that reports
  pass or fail per behaviour.

Most report a result on screen and in memory at $6000, so a test can read
the result directly (as `nestest_test` reads nestest's log). Vendor the
ones whose terms allow it in `test-roms/`, as nestest is, with a README.
They'd run in CI, beside nestest.

Also, **picture tests of our own games:** a hash of the frame at given
moments of the headless play-throughs, such as Star Run's title, the
level after 2 seconds, and after scrolling 600 pixels. Checked in, so a
PPU change that alters a picture is noticed. When one changes on purpose,
a script writes the new hashes, and a PNG of each to look at.

### Step 7: more mappers

Mappers, in order of how many games they open up:

- UxROM (mapper 2): switchable 16 KB program banks, CHR RAM.
- CNROM (3): switchable tiles.
- MMC1 (1): a serial register; program banks, tile banks and mirroring
  all switchable.
- MMC3 (4): the scanline counter, the one that tests the PPU's timing,
  since it counts rises of address bit 12 during sprite fetches.

Each one is a `Cartridge`, with tests that write its registers and check
which bank appears where. Engine part four's "second mapper" is the same
work from the other side. Write the mapper here, then teach the toolkit
to build for it.

### Step 8: views the new PPU makes possible

- **The PPU tab:** v, t, x and w, live. The fetch happening at the
  current dot when stepping.
- **An event view:** every PPU register write in a frame, drawn at its
  line and dot on a 341x262 map. Mesen has one. It makes mid-frame
  scroll splits, like a status bar, plain to see.
- **Where a pixel came from:** hover the picture for the tile, nametable
  address, palette entry, or sprite number and OAM bytes.
- **Step one dot or one line**, beside stepping one instruction.

### Step 9: speed

The budget: about 5.4 million PPU dots a second in the browser, beside
the CPU. Ways to get there, in order:

1. **No allocation in `tick!`:** no maps, vectors or closures, only array
   reads and writes, with type hints and `*unchecked-math*` on the JVM.
   Measure first.
2. **Do whole spans at once.** The 20 lines of vertical blank, and the
   dots of a visible line between fetches, have nothing to compute
   dot by dot.
3. **Catch-up.** Don't step the PPU every CPU cycle. Run it only when
   something could notice: the CPU touching a PPU register or the
   mapper, a known event coming due (vertical blank, sprite 0's line), or
   the end of the frame. Most CPU cycles touch neither, so the PPU runs
   in long fast loops. Many emulators do this. It's an optimization with
   bugs of its own, so it's only for after the test ROMs pass with plain
   stepping, and with stepping kept as a setting to compare against.

## Order and size

| Step | Size | Gives |
|---|---|---|
| 1. Registers and scroll | small | the v/t/x/w view |
| 2. Whole-frame renderer | small to medium | the oracle; simpler views |
| 3. Dot-stepped PPU | large | the PPU itself |
| 4. Console and mapper 0 | medium | our games on our own emulator; real headless timing |
| 5. Compare with jsnes | small | finding differences |
| 6. Test ROMs and picture tests | medium | knowing it's right |
| 7. Mappers 2, 3, 1, 4 | medium each | commercial games |
| 8. Views | as wanted | the educational payoff |
| 9. Speed | open-ended | running at full speed everywhere |

Steps 1-4 make a console that runs our own games silently. That's the
first milestone. Sound (the APU) is a separate plan, after this one. Until
then jsnes stays the default in the app.

## Things to watch

- **Line numbering.** jsnes counts lines from the start of vertical blank,
  so its line 0 is the wiki's 241 and its line 20 the pre-render line.
  Use the wiki's numbers (0-239 visible, 241 vertical blank, 261
  pre-render), and convert at the boundary when comparing.
- **Where in the 3 dots a CPU access lands.** It decides whether a
  PPUSTATUS read on the dot vertical blank starts sees it, and whether the
  NMI happens. The test ROMs in step 6 pin this down. Until then, follow
  jsnes's order: dots first, then the access.
- **Power-on state.** Real consoles start with the CPU and PPU at
  different relative positions, and with RAM in no fixed state. Pick one,
  and document it.
- **Speed on the JVM**, which means boxed numbers unless hinted. The
  JVM tests (`npm run test:jvm`) should run the PPU from step 1, so that
  reflection warnings and slow paths show up early.

## References

- nesdev wiki: [PPU](https://www.nesdev.org/wiki/PPU),
  [rendering](https://www.nesdev.org/wiki/PPU_rendering),
  [scrolling](https://www.nesdev.org/wiki/PPU_scrolling) (the v/t/x/w
  model), [frame timing](https://www.nesdev.org/wiki/PPU_frame_timing),
  [sprite evaluation](https://www.nesdev.org/wiki/PPU_sprite_evaluation),
  and the [timing diagram](https://www.nesdev.org/wiki/File:Ntsc_timing.png).
- jsnes's `src/ppu/index.js`, for comparison, especially `advanceDots`
  and the NMI edge rules.
- Mesen's source and its event viewer, for the views in step 8.
