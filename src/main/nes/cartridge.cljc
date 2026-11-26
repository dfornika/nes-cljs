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
  made with cartridj is so far, mapper 2 (UxROM: one switchable bank of
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

(def mappers
  "The mappers this console has, by iNES number: the functions that make
  each one's cartridge from its parts."
  {0 (fn [{:keys [prg-rom prg-ram chr-mem video]}] (->Nrom prg-rom prg-ram chr-mem video))
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
           :video (memory/video-memory {:chr chr-mem :chr-ram? chr-ram? :mirroring mirroring})})))
