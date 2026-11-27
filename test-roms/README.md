# Test ROMs

Programs that check an emulator against a real NES. Each runs on the
console like a game, tests some behaviour the way a game would see it, and
reports whether the emulator got it right. Their authors checked them on
real hardware, so where our console and jsnes disagree, they decide.

| Folder | What it tests | Author | Used by |
|---|---|---|---|
| `nestest/` | every CPU instruction (see its README) | Kevin Horton | `nes.cpu.nestest-test` |
| `ppu_vbl_nmi/` | vertical blank and NMI timing, to the PPU clock (the ten single-test ROMs, and all ten in one) | Shay Green | `nes.rom-suite` |
| `vbl_nmi_timing/` | the same, the 2005 versions | Shay Green | `nes.rom-suite` |
| `sprite_hit_tests_2005.10.05/` | sprite 0 hit | Shay Green | `nes.rom-suite` |
| `sprite_overflow_tests/` | the sprite overflow flag | Shay Green | `nes.rom-suite` |
| `ppu_open_bus/` | reading the PPU's unused bits | Shay Green | `nes.rom-suite` |
| `oam_read/`, `oam_stress/` | sprite memory through $2003 and $2004 | Shay Green | `nes.rom-suite` |
| `blargg_ppu_tests_2005.09.15b/` | palette RAM, sprite RAM, video memory access, vertical blank clear time, power-on palette | Shay Green | `nes.rom-suite` |
| `instr_test-v5/` | every instruction, unofficial ones too (`all_instrs`, the 16 tests in one) | Shay Green | `nes.rom-suite` |
| `instr_misc/` | page wrapping and dummy reads | Shay Green | `nes.rom-suite` |
| `instr_timing/` | how many cycles each instruction takes | Shay Green | `nes.rom-suite` |
| `cpu_interrupts_v2/` | when interrupts are taken | Shay Green | `nes.rom-suite` |
| `ppu_read_buffer/` | the PPU's $2007 read buffer, and much else | Joel Yliluoma | `nes.rom-suite` |

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
| `ppu_vbl_nmi` all-in-one | 9 of 10 (not `10-even_odd_timing`) | 5 of 10 (stops at 6) |
| `instr_test-v5/all_instrs` | passes | — |
| `instr_misc` (4) | 3: not `04-dummy_reads_apu` (no sound chip yet) | 4 |
| `instr_timing` | fails (no sound chip yet) | passes |
| `cpu_interrupts_v2` | fails at test 1 (no sound chip yet) | fails at test 1 |
| `ppu_read_buffer` | fails #67 (sprite DMA from the PPU's registers) | — |

The ones that fail, and why, are in `known-failures` in
`src/test/nes/rom_suite.cljs`. `power_up_palette` passes because ours
starts with blargg's NES's palette (`nes.ppu.registers/power-on-palette`).

## Where they came from

Shay Green's ('blargg') tests are from the collection at
[christopherpow/nes-test-roms](https://github.com/christopherpow/nes-test-roms)
(commit `95d8f62`), the copies most emulator projects use, unchanged.
Their assembly source is there too. The first ones here were only the
ROMs for mapper 0 cartridges (for `ppu_vbl_nmi`, the single-test ROMs from
`rom_singles/`). Once we had mappers 1 and 3 (2026-11-27), the all-in-one
ROMs for mapper 1 and `ppu_read_buffer` (mapper 3) joined them.
`instr_test-v5`'s `official_only.nes` is left out: it's a part of
`all_instrs.nes`, and would add two minutes to every run.
`ppu_read_buffer` is Joel Yliluoma's (built on blargg's framework), from
the same collection.

No license accompanies them, as with nestest. They're shared freely among
emulator authors for exactly this use.
