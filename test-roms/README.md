# Test ROMs

Programs that check an emulator against a real NES. Each runs on the
console like a game, tests some behaviour the way a game would see it, and
reports whether the emulator got it right. Their authors checked them on
real hardware, so where our console and jsnes disagree, they decide.

| Folder | What it tests | Author | Used by |
|---|---|---|---|
| `nestest/` | every CPU instruction (see its README) | Kevin Horton | `nes.cpu.nestest-test` |
| `ppu_vbl_nmi/` | vertical blank and NMI timing, to the PPU clock (the ten single-test ROMs) | Shay Green | `nes.rom-suite` |
| `vbl_nmi_timing/` | the same, the 2005 versions | Shay Green | `nes.rom-suite` |
| `sprite_hit_tests_2005.10.05/` | sprite 0 hit | Shay Green | `nes.rom-suite` |
| `sprite_overflow_tests/` | the sprite overflow flag | Shay Green | `nes.rom-suite` |
| `ppu_open_bus/` | reading the PPU's unused bits | Shay Green | `nes.rom-suite` |
| `oam_read/`, `oam_stress/` | sprite memory through $2003 and $2004 | Shay Green | `nes.rom-suite` |
| `blargg_ppu_tests_2005.09.15b/` | palette RAM, sprite RAM, video memory access, vertical blank clear time, power-on palette | Shay Green | `nes.rom-suite` |

Each folder's `readme.txt` is the author's: what the tests check, and
what each failure number means. `nes.test-roms` (in `src/test`) explains
how they report their results.

## How we do

`npm run test:roms` runs blargg's tests on NES.cljs's own console
(`nes.rom-suite`), as CI does. The jsnes column is from the same runner (`nes.test-roms`), for comparison.

| Tests | NES.cljs | jsnes |
|---|---|---|
| `ppu_vbl_nmi` (10) | 9: all but `10-even_odd_timing` | 6 |
| `vbl_nmi_timing` (7) | 7 | 3 |
| `sprite_hit_tests_2005.10.05` (11) | 11 | 7 |
| `sprite_overflow_tests` (5) | 5 | 3 |
| `ppu_open_bus` | passes | fails (#4) |
| `oam_read`, `oam_stress` | pass | pass |
| `blargg_ppu_tests_2005.09.15b` (5) | 5 | 3 |
| **All 41** | **40** | **24** |

The one that fails, and why, is in `known-failures` in
`src/test/nes/rom_suite.cljs`. `power_up_palette` passes because ours
starts with blargg's NES's palette (`nes.ppu.registers/power-on-palette`).

## Where they came from

Shay Green's ('blargg') tests are from the collection at
[christopherpow/nes-test-roms](https://github.com/christopherpow/nes-test-roms)
(commit `95d8f62`), the copies most emulator projects use, unchanged.
Their assembly source is there too. Only the ROMs for mapper 0 cartridges
are here, the only kind our console has so far; for `ppu_vbl_nmi` that's
the single-test ROMs (`rom_singles/`), as the all-in-one ROM is mapper 1.

No license accompanies them, as with nestest. They're shared freely among
emulator authors for exactly this use.
