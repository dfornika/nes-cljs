(ns gamedev-toolkit.hardware
  "Names for the NES's hardware, to use as assembler :symbols.

  A program controls the console by reading and writing 'registers':
  addresses that aren't memory but connections to the picture chip (PPU),
  the sound chip (APU) and the controllers. The names are the usual ones
  from the NESdev wiki (https://www.nesdev.org/wiki/PPU_registers), in
  lower case.")

(def registers
  {;; PPU
   :ppuctrl   0x2000   ; settings: NMI on/off, which tiles, sprite size
   :ppumask   0x2001   ; what to show: background, sprites, tints
   :ppustatus 0x2002   ; bit 7: in vertical blank. Reading it clears that bit
   :oamaddr   0x2003   ; where in sprite memory to write
   :oamdata   0x2004
   :ppuscroll 0x2005   ; write twice: X scroll, then Y
   :ppuaddr   0x2006   ; write twice: high byte, then low, of a video memory address
   :ppudata   0x2007   ; read/write video memory there; the address then moves on
   ;; APU and I/O
   :dmc-freq  0x4010   ; sample channel settings (bit 7: its interrupt)
   :oamdma    0x4014   ; write a page number: copy that page to sprite memory
   :snd-chn   0x4015   ; sound channels on/off
   :joy1      0x4016   ; controller 1 (write: strobe both controllers)
   :joy2      0x4017   ; controller 2 when read
   :apu-frame 0x4017}) ; sound frame counter settings when written

(def buttons
  "A bit for each controller button, in a byte read with the usual loop:
  eight reads of $4016, each shifted in from the right, so the first
  button read (A) ends up in bit 7."
  {:a 0x80 :b 0x40 :select 0x20 :start 0x10
   :up 0x08 :down 0x04 :left 0x02 :right 0x01})

(def button-bits
  "The same, as bit numbers: for a [:bits ...] variable."
  {:a 7 :b 6 :select 5 :start 4 :up 3 :down 2 :left 1 :right 0})

(def video
  "Addresses in the PPU's own memory (reached through :ppuaddr/:ppudata)."
  {:nametable-0 0x2000   ; the background: 30 rows of 32 tile numbers...
   :attributes-0 0x23C0  ; ...then 64 bytes choosing palettes for 16x16 areas
   :palettes 0x3F00      ; 4 background palettes, then 4 sprite palettes
   :sprite-palettes 0x3F10})
