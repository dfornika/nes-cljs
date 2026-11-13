# An enhanced mode: notes

Ideas for letting the emulator do more than a real NES: more colors, more
sprites, a wider screen, new instructions. None of it is built yet, and
all of it comes after our own ClojureScript PPU (see "Our own emulator
core" in `TODO.md`): jsnes's PPU isn't ours to change.

## Three tiers

The ideas sort by what they cost in compatibility, and that decides the
order to build them in.

| Tier | What changes | Who it's for | Runs elsewhere? |
|---|---|---|---|
| 1. Options | how the emulator runs a ROM | any game, existing or ours | the ROM is unchanged |
| 2. A mapper | what's on the cartridge | games made for it | a real iNES file; other emulators refuse it cleanly |
| 3. A new machine | the console itself | games made for it | only here |

## Tier 1: options for any ROM

Emulator settings, per game, off by default. Mesen has all of these.

- **No sprite limit.** The PPU draws only 8 sprites on a line; more
  flicker or vanish. Easy to lift in our own PPU. A per-game toggle,
  because a few games rely on the limit: they stack 8 blank sprites on a
  line to hide others behind them (The Legend of Zelda's dungeon doorways
  are the usual example).
- **Overclocking.** Copy Mesen's approach: add scanlines in vertical blank
  that only the CPU runs. Games get more time per frame, so slowdown goes,
  but what's on screen keeps its timing. Running the CPU faster outright
  breaks games that count cycles.
- **A different master palette.** The 64 colors' RGB values are someone's
  measurement of a TV signal, and emulators differ. Loading another table
  (many emulators read `.pal` files; we have `gamedev-toolkit.palette/rgb`)
  changes how colors look, not how many are on screen. This one doesn't
  even need our own PPU.
- **WideNES.** The ANESE emulator's trick for widescreen with unchanged
  games: remember what scrolled off the edges and keep drawing it beside
  the real picture. Imperfect (things off screen don't move), but needs
  nothing from the ROM.

## Tier 2: a mapper

How the real NES was extended: chips on the cartridge, reached through
registers. Nintendo's MMC5 is the model. It has a hardware multiplier
(write two numbers to `$5205` and `$5206`, read the product), extra RAM,
a palette for every 8x8 tile rather than every 16x16 area (from its
'ExRAM'), and on the Famicom extra sound channels. Konami's VRC6 added
three sound channels.

A "meta-NES mapper" could offer, through registers in the unused
`$4020`-`$5FFF` area:

- **Per-tile palettes.** Of everything here, the biggest help to art: the
  16x16 rule is why `gamedev-toolkit.png` has to repaint pixels.
- **Multiply and divide**, so the toolkit's `gen/multiply` can be one
  write and one read.
- **More sprites** than 64.
- **More RAM.** `$6000`-`$7FFF` is already 8 KB of standard cartridge RAM;
  banking gives more.
- **Extra sound channels.**
- **A debug port**: a write that hands something to ClojureScript (see "A
  way out to the host" in `TODO.md`).

The ROM stays a genuine iNES file with its own mapper number, so other
emulators refuse it cleanly instead of crashing, and an FPGA cartridge
could in principle implement it. The CPU stays a real 6502, so nestest,
the disassembler, breakpoints and the code logger all stay right.

What a mapper can't change is the stack: it's fixed at $0100-$01FF by the
CPU itself, because the stack pointer is 8 bits. A bigger one means a
different CPU (the 65816 has a 16-bit stack pointer) or a software stack
in RAM.

## Tier 3: a different machine

A wider screen, more colors at once, new instructions: these change what
the console *is*. That's a fantasy console in the PICO-8 sense, and only
this emulator runs its ROMs. Legitimate, and perhaps what the name
"meta-nes" would best describe, but a separate target: the toolkit would
need to know which machine it's building for, with an opcode table and a
runtime for each, and the header would have to mark these ROMs so other
emulators refuse them.

- **16:9.** At 240 lines that's about 426 pixels across: a wider
  nametable, a new scroll range, wider sprite X positions.
- **More colors on screen**: more palettes, or palettes of real RGB colors
  (as the SNES has) instead of picks from the 64.
- **New instructions.** See below.

## Extended instructions

A prefix byte that switches to a second opcode table is an old idea:

- **Zilog Z80:** prefixes `CB`, `DD`, `ED` and `FD`
- **Motorola 6809:** pages 2 and 3, via `$10` and `$11`
- **x86:** two-byte opcodes behind `0F`
- **65816** (the SNES's CPU): keeps `$42` reserved as `WDM`, for future
  expansion
- **MEGA65's 45GS02**, an extended 6502: existing opcodes act as prefixes
  (`$42 $42` for 32-bit operations, `$EA` for 32-bit addressing)

On the NES's 6502 the free bytes are the 12 that jam the CPU: `$02 $12
$22 $32 $42 $52 $62 $72 $92 $B2 $D2 $F2`. `$42` is one of them, so a
prefix there would match the 65816's convention.

(UTF-8 is a little different: its first byte says how long the sequence
is. A single prefix byte is simpler, and is what CPUs do.)

But most of what new instructions would be for (multiplying, 16-bit
arithmetic, calling the host) can be done through Tier 2 registers or the
toolkit's generators, without changing the CPU. Keep a prefix for what
registers can't do well, as part of Tier 3.

## Smaller ideas

- **A frame-budget meter** in the debugger: how much of each frame a game
  used. For our own games it prevents slowdown rather than hiding it.
  Possible now, with jsnes.
- **Rewind and save states** (in `TODO.md` already): easier with our own
  PPU, since then the whole machine is a value.

## Order

After the ClojureScript PPU:

1. Tier 1 options: they help every game
2. The meta-NES mapper, per-tile palettes and the multiplier first
3. Tier 3 only as a deliberate second target, if the project grows that way
