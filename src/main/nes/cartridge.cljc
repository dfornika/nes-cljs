(ns nes.cartridge
  "Cartridges: what's on the circuit board, and how the console reaches it.

  A cartridge connects to both of the console's buses. The CPU sees its
  program ROM at $8000-$FFFF (and sometimes RAM at $6000-$7FFF); the PPU
  sees its tiles at $0000-$1FFF, and the cartridge also decides how the
  console's 2 KB of nametable RAM fills the PPU's four nametables
  (mirroring, see nes.ppu.memory).

  Simple cartridges are wired straight through. Bigger ones have a
  'mapper' chip that switches which part of a large ROM appears where;
  each kind of mapper has a number in the iNES header. This namespace has
  mapper 0 (NROM, the simplest: no switching), which is what every game
  made with cartridj is so far, mapper 1 (MMC1: switches everything,
  mirroring included), mapper 2 (UxROM: one switchable bank of
  program ROM) and mapper 3 (CNROM: switchable tiles).

  See https://www.nesdev.org/wiki/Mapper"
  (:refer-clojure :exclude [load])
  (:require [nes.bytes :as bytes]
            [nes.ppu.memory :as memory]
            [nes.rom :as rom]))

(defprotocol Cartridge
  (cpu-read [cart addr] "The byte the CPU reads at `addr` ($4020-$FFFF), or nil if nothing answers.")
  (cpu-write! [cart addr value] "The CPU writes `value` to `addr` ($4020-$FFFF).")
  (prg [cart] "The program ROM, as a byte array (for changing it from outside).")
  (chr [cart] "The tile ROM or RAM, as a byte array."))

;; Mapper 0. Program ROM is 16 KB (appearing twice, at $8000 and $C000) or
;; 32 KB. Tiles are 8 KB of ROM, or of RAM if the header says there's no
;; CHR ROM. Some boards have 8 KB of RAM at $6000, so it's here too.
(deftype Nrom [prg-rom prg-ram chr-mem video]
  Cartridge
  (cpu-read [_ addr]
    (cond
      (>= addr 0x8000) (bytes/at prg-rom (bit-and (- addr 0x8000) (dec (bytes/size prg-rom))))
      (>= addr 0x6000) (bytes/at prg-ram (- addr 0x6000))
      :else nil))
  (cpu-write! [_ addr value]
    (when (<= 0x6000 addr 0x7FFF) (bytes/put! prg-ram (- addr 0x6000) value)))
  (prg [_] prg-rom)
  (chr [_] chr-mem)
  memory/VideoMemory
  (video-read [_ addr] (memory/video-read video addr))
  (video-write! [_ addr value] (memory/video-write! video addr value)))

;; Mapper 2. Program ROM is up to 256 KB (sometimes more), in 16 KB banks.
;; $C000-$FFFF always shows the last bank: that's where the reset code and
;; the interrupt vectors are, so they're there whatever is switched in.
;; $8000-$BFFF shows the bank the game picks, by writing its number
;; anywhere from $8000 up. ROM can't be written, so the mapper chip
;; listens for those writes instead. Tiles are 8 KB of RAM, which the game
;; fills itself.
;;
;; (On some UxROM boards the ROM answers the write too, and the chip sees
;; the value ANDed with the ROM's byte there: a 'bus conflict'. Games
;; avoid trouble by writing to a byte that already holds the number. jsnes
;; leaves bus conflicts out, so this does too.)
(deftype Uxrom [prg-rom prg-ram chr-mem video ^:mutable ^:unsynchronized-mutable bank]
  Cartridge
  (cpu-read [_ addr]
    (let [banks (quot (bytes/size prg-rom) 0x4000)]
      (cond
        (>= addr 0xC000) (bytes/at prg-rom (+ (* (dec banks) 0x4000) (- addr 0xC000)))
        (>= addr 0x8000) (bytes/at prg-rom (+ (* (mod bank banks) 0x4000) (- addr 0x8000)))
        (>= addr 0x6000) (bytes/at prg-ram (- addr 0x6000))
        :else nil)))
  (cpu-write! [_ addr value]
    (cond
      (>= addr 0x8000) (set! bank value)
      (>= addr 0x6000) (bytes/put! prg-ram (- addr 0x6000) value)))
  (prg [_] prg-rom)
  (chr [_] chr-mem)
  memory/VideoMemory
  (video-read [_ addr] (memory/video-read video addr))
  (video-write! [_ addr value] (memory/video-write! video addr value)))

;; Mapper 3. The opposite of UxROM: program ROM is fixed (16 or 32 KB, as
;; on NROM), and the tiles switch. Tile ROM is up to 32 KB (sometimes
;; more), in 8 KB banks, and the PPU sees the one the game picks, by
;; writing its number anywhere from $8000 up. A game can switch in a whole
;; new set of tiles at once, to animate the background, say, or for a new
;; screen. The nametables are as on NROM, so they're left to `video`.
;;
;; (Bus conflicts, as for UxROM: real CNROM boards have them, and games
;; write to a byte that already holds the number. jsnes leaves them out,
;; so this does too.)
(deftype Cnrom [prg-rom prg-ram chr-rom video ^:mutable ^:unsynchronized-mutable bank]
  Cartridge
  (cpu-read [_ addr]
    (cond
      (>= addr 0x8000) (bytes/at prg-rom (bit-and (- addr 0x8000) (dec (bytes/size prg-rom))))
      (>= addr 0x6000) (bytes/at prg-ram (- addr 0x6000))
      :else nil))
  (cpu-write! [_ addr value]
    (cond
      (>= addr 0x8000) (set! bank value)
      (>= addr 0x6000) (bytes/put! prg-ram (- addr 0x6000) value)))
  (prg [_] prg-rom)
  (chr [_] chr-rom)
  memory/VideoMemory
  (video-read [_ addr]
    (if (< addr 0x2000)
      (let [banks (max 1 (quot (bytes/size chr-rom) 0x2000))]
        (bytes/at chr-rom (+ (* (mod bank banks) 0x2000) addr)))
      (memory/video-read video addr)))
  ;; (`video` ignores writes to tile ROM)
  (video-write! [_ addr value] (memory/video-write! video addr value)))

(defprotocol Mirroring
  "For cartridges whose mapper changes the mirroring while the game runs.
  (The others keep what the iNES header says.)"
  (mirroring [cart] "How the nametables are arranged right now (see nes.ppu.memory)."))

(defn current-mirroring
  "How `cart` arranges the nametables right now, or `header-mirroring` if
  its mapper never changes that."
  [cart header-mirroring]
  (if (satisfies? Mirroring cart) (mirroring cart) header-mirroring))

;; Mapper 1, the MMC1 chip. It switches everything: program ROM in 16 or
;; 32 KB pieces, tiles in 4 or 8 KB pieces, and the mirroring too.
;;
;; It has four registers, but the game can't write a whole byte to one.
;; It writes one bit at a time, five times: bit 0 of each write to $8000
;; or above goes into a 'shift register', and on the fifth write the five
;; bits go into the register picked by that write's address:
;;
;;   $8000-$9FFF  control:   ---CPPMM
;;                  MM  mirroring: 0 single screen (the first 1 KB of
;;                      nametable RAM), 1 single screen (the second),
;;                      2 vertical, 3 horizontal
;;                  PP  program ROM: 0 or 1 one 32 KB bank at $8000;
;;                      2 the first bank fixed at $8000, switch $C000;
;;                      3 switch $8000, the last bank fixed at $C000
;;                  C   tiles: 0 one 8 KB bank; 1 two 4 KB banks
;;   $A000-$BFFF  tile bank at $0000 (in 4 KB units)
;;   $C000-$DFFF  tile bank at $1000 (only in 4 KB mode)
;;   $E000-$FFFF  program bank (in 16 KB units; bit 4 is for RAM, below)
;;
;; A write with bit 7 set starts over: it empties the shift register and
;; sets PP to 3. A game does that first, since it can't know how many bits
;; are already in there.
;;
;; In 32 KB mode the program bank's lowest bit is ignored, and in 8 KB mode
;; the tile bank's: the banks are counted in 16 and 4 KB either way.
;; Cartridges with 512 KB of program ROM (SUROM: Dragon Warrior III and
;; IV) use bit 4 of the tile bank at $0000 to pick which 256 KB half the
;; program banks are in.
;;
;; Where this differs from a real MMC1:
;; - The chip ignores a write that comes right after another one, on the
;;   next CPU cycle. That happens when a game uses INC or another
;;   read-modify-write instruction on $8000-$FFFF, which writes the old
;;   value back before the new one (see nes.cpu.core). jsnes's MMC1 takes
;;   both writes, and so does this one, as the cartridge doesn't see the
;;   CPU's cycles.
;; - Bit 4 of the program bank turns the RAM at $6000 off on most MMC1s.
;;   Left out here (the RAM is always on), as in jsnes.
;;
;; And where it differs from jsnes's MMC1 (jsnes/src/mappers/mapper1.js),
;; which this follows the hardware on:
;; - jsnes treats mirroring 1 as mirroring 0 (both the first 1 KB), and
;;   keeps the header's mirroring until a game asks for something other
;;   than 0.
;; - In 32 KB mode, jsnes doesn't ignore the bank's lowest bit, so bank 2
;;   there shows 16 KB banks 4 and 5, not 2 and 3.
;; - jsnes switches a bank in when the register is written, so changing
;;   the mode afterwards doesn't move anything until the next bank write.
;;   On the chip (and here) the mode applies straight away.
;;
;; See https://www.nesdev.org/wiki/MMC1
(defprotocol ^:private Mmc1Registers
  (load! [cart register value] "Register 0-3 (by the address's bits 13 and 14) gets `value`.")
  (tile-offset [cart addr] "Where tile address `addr` ($0000-$1FFF) is in the tile ROM or RAM."))

(deftype Mmc1 [prg-rom prg-ram chr-mem chr-ram? nametables
               ^:mutable ^:unsynchronized-mutable shift     ; bits so far, the first in bit 0
               ^:mutable ^:unsynchronized-mutable shifted   ; how many: 0-4
               ^:mutable ^:unsynchronized-mutable control
               ^:mutable ^:unsynchronized-mutable chr0
               ^:mutable ^:unsynchronized-mutable chr1
               ^:mutable ^:unsynchronized-mutable prg-bank
               ^:mutable ^:unsynchronized-mutable mirrors]  ; from control, as a keyword
  Cartridge
  (cpu-read [_ addr]
    (cond
      (>= addr 0x8000)
      (let [banks (quot (bytes/size prg-rom) 0x4000)
            ;; which 256 KB half, on a 512 KB cartridge
            base (if (> banks 16) (bit-and chr0 0x10) 0)
            b (bit-and prg-bank 0x0F)
            high? (>= addr 0xC000)
            bank (case (bit-and (bit-shift-right control 2) 3)
                   (0 1) (+ (bit-and b 0x0E) (if high? 1 0))
                   2 (if high? b 0)
                   3 (if high? 0x0F b))]
        (bytes/at prg-rom (+ (* (mod (+ base bank) banks) 0x4000) (bit-and addr 0x3FFF))))
      (>= addr 0x6000) (bytes/at prg-ram (- addr 0x6000))
      :else nil))
  (cpu-write! [this addr value]
    (cond
      (>= addr 0x8000)
      (if (bit-test value 7)
        (do (set! shift 0) (set! shifted 0)
            (load! this 0 (bit-or control 0x0C)))
        (let [bits (bit-or shift (bit-shift-left (bit-and value 1) shifted))]
          (if (< shifted 4)
            (do (set! shift bits) (set! shifted (inc shifted)))
            (do (set! shift 0) (set! shifted 0)
                (load! this (bit-and (bit-shift-right addr 13) 3) bits)))))
      (>= addr 0x6000) (bytes/put! prg-ram (- addr 0x6000) value)))
  (prg [_] prg-rom)
  (chr [_] chr-mem)
  Mirroring
  (mirroring [_] mirrors)
  memory/VideoMemory
  (video-read [this addr]
    (if (< addr 0x2000)
      (bytes/at chr-mem (tile-offset this addr))
      (bytes/at nametables (memory/nametable-offset mirrors addr))))
  (video-write! [this addr value]
    (if (< addr 0x2000)
      (when chr-ram? (bytes/put! chr-mem (tile-offset this addr) value))
      (bytes/put! nametables (memory/nametable-offset mirrors addr) value)))
  Mmc1Registers
  (load! [_ register value]
    (case register
      0 (do (set! control value)
            (set! mirrors (case (bit-and value 3)
                            0 :single-screen 1 :single-screen-b 2 :vertical 3 :horizontal)))
      1 (set! chr0 value)
      2 (set! chr1 value)
      3 (set! prg-bank value)))
  (tile-offset [_ addr]
    (let [banks (max 1 (quot (bytes/size chr-mem) 0x1000))
          bank (if (bit-test control 4)
                 (if (< addr 0x1000) chr0 chr1)
                 (+ (bit-and chr0 0x1E) (bit-shift-right addr 12)))]
      (+ (* (mod bank banks) 0x1000) (bit-and addr 0x0FFF)))))

(def mappers
  "The mappers this console has, by iNES number: the functions that make
  each one's cartridge from its parts."
  {0 (fn [{:keys [prg-rom prg-ram chr-mem video]}] (->Nrom prg-rom prg-ram chr-mem video))
   1 (fn [{:keys [prg-rom prg-ram chr-mem chr-ram? mirroring]}]
       ;; At power on: program mode 3 (the reset code is in the last bank,
       ;; at $C000, where the CPU looks for it), the rest zero, and the
       ;; header's mirroring until the game sets its own.
       (->Mmc1 prg-rom prg-ram chr-mem chr-ram? (bytes/make (memory/nametable-ram-size mirroring)) 0 0 0x0C 0 0 0 mirroring))
   2 (fn [{:keys [prg-rom prg-ram chr-mem video]}] (->Uxrom prg-rom prg-ram chr-mem video 0))
   3 (fn [{:keys [prg-rom prg-ram chr-mem video]}] (->Cnrom prg-rom prg-ram chr-mem video 0))})

(defn load
  "A cartridge from the bytes of an iNES file. Throws if its mapper isn't
  one we have (see `mappers`)."
  [file]
  (let [{:keys [mapper prg-size chr-size chr-ram? mirroring trainer?] :as header} (rom/parse-header file)
        _ (when-not header (throw (ex-info "Not an iNES (.nes) file" {})))
        make (or (mappers mapper)
                 (throw (ex-info (str "Mapper " mapper " (" (get rom/mapper-names mapper "unknown")
                                      ") isn't in NES.cljs's console yet: only mappers "
                                      (let [[last-one & others] (reverse (sort (keys mappers)))]
                                        (str (apply str (interpose ", " (reverse others))) " and " last-one))
                                      ". jsnes can run it.")
                                 {:mapper mapper})))
        start (+ 16 (if trainer? 512 0))
        prg-rom (bytes/make prg-size)
        chr-mem (bytes/make (if chr-ram? 0x2000 chr-size))]
    (bytes/copy-into! prg-rom (bytes/slice file start (+ start prg-size)) 0)
    (when-not chr-ram?
      (bytes/copy-into! chr-mem (bytes/slice file (+ start prg-size) (+ start prg-size chr-size)) 0))
    (make {:prg-rom prg-rom
           :prg-ram (bytes/make 0x2000)
           :chr-mem chr-mem
           :chr-ram? chr-ram?
           :mirroring mirroring
           :video (memory/video-memory {:chr chr-mem :chr-ram? chr-ram? :mirroring mirroring})})))
