(ns nes.ppu.memory
  "What the PPU's addresses lead to.

  The PPU has its own 16 KB address space, separate from the CPU's:

    $0000-$1FFF  the two pattern tables: tile graphics, on the cartridge
                 (CHR ROM, or CHR RAM the game fills in itself)
    $2000-$2FFF  four nametables of 1 KB each: the background
    $3000-$3EFF  the nametables again (a repeat of $2000-$2EFF)
    $3F00-$3FFF  palette RAM, which is inside the PPU (see nes.ppu.registers)

  There's only 2 KB of nametable RAM in the console, enough for two
  nametables, not four. The cartridge decides which two of the four
  addresses show the same RAM. That's 'mirroring':

    :horizontal  $2000 = $2400 and $2800 = $2C00 (for scrolling up and down)
    :vertical    $2000 = $2800 and $2400 = $2C00 (for scrolling sideways)
    :single-screen, :single-screen-b   all four show the same 1 KB
    :four-screen the cartridge brings 2 KB more, so all four are different

  The PPU reaches all of this through the `VideoMemory` protocol, so a
  cartridge with bank switching (a mapper) can decide for itself what's
  where. `video-memory` is the simplest kind: the tiles fixed, mirroring
  fixed, which is what mapper 0 cartridges have."
  (:require [nes.bytes :as bytes]))

(defprotocol VideoMemory
  (video-read [m addr]
    "The byte at PPU address `addr` ($0000-$3EFF).")
  (video-write! [m addr value]
    "Store `value` at PPU address `addr` ($0000-$3EFF). Writes to tile
    graphics in ROM are ignored, as on a real cartridge."))

(defn nametable-offset
  "Where nametable address `addr` ($2000-$3EFF) is in nametable RAM, given
  the cartridge's `mirroring`. Bits 10 and 11 of the address pick one of the
  four nametables; mirroring says which of the RAM's 1 KB halves that is."
  [mirroring addr]
  (let [within (bit-and addr 0x3FF)              ; where in the 1 KB nametable
        table (bit-and (bit-shift-right addr 10) 3)] ; which of the four: 0-3
    (case mirroring
      :horizontal    (bit-or within (bit-shift-left (bit-shift-right table 1) 10))
      :vertical      (bit-or within (bit-shift-left (bit-and table 1) 10))
      :single-screen within
      :single-screen-b (bit-or within 0x400)
      :four-screen   (bit-or within (bit-shift-left table 10)))))

(defn nametable-ram-size
  "Bytes of nametable RAM needed for `mirroring`: 2 KB, or 4 KB with four screens."
  [mirroring]
  (if (= mirroring :four-screen) 4096 2048))

(deftype FixedVideoMemory [chr chr-ram? mirroring nametables]
  VideoMemory
  (video-read [_ addr]
    (if (< addr 0x2000)
      (bytes/at chr addr)
      (bytes/at nametables (nametable-offset mirroring addr))))
  (video-write! [_ addr value]
    (if (< addr 0x2000)
      (when chr-ram? (bytes/put! chr addr value))
      (bytes/put! nametables (nametable-offset mirroring addr) value))))

(defn video-memory
  "Video memory with the tiles and mirroring fixed (as on a mapper 0
  cartridge). Options:

    :chr        8 KB of tile graphics (a byte array). Without it, 8 KB of CHR RAM.
    :chr-ram?   whether the tiles can be written (default: true without :chr)
    :mirroring  as above (default :horizontal)"
  [{:keys [chr chr-ram? mirroring] :or {mirroring :horizontal}}]
  (->FixedVideoMemory (or chr (bytes/make 0x2000))
                      (if (some? chr-ram?) chr-ram? (nil? chr))
                      mirroring
                      (bytes/make (nametable-ram-size mirroring))))
