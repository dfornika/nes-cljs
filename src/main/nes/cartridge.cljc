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
  made with gamedev-toolkit is."
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

(defn load
  "A cartridge from the bytes of an iNES file. Throws if its mapper isn't
  one we have."
  [file]
  (let [{:keys [mapper prg-size chr-size chr-ram? mirroring trainer?] :as header} (rom/parse-header file)
        _ (when-not header (throw (ex-info "Not an iNES (.nes) file" {})))
        _ (when-not (= 0 mapper)
            (throw (ex-info (str "Mapper " mapper " (" (get rom/mapper-names mapper "unknown")
                                 ") isn't in NES.cljs's console yet: only mapper 0. jsnes can run it.")
                            {:mapper mapper})))
        start (+ 16 (if trainer? 512 0))
        prg-rom (bytes/make prg-size)
        chr-mem (bytes/make (if chr-ram? 0x2000 chr-size))]
    (bytes/copy-into! prg-rom (bytes/slice file start (+ start prg-size)) 0)
    (when-not chr-ram?
      (bytes/copy-into! chr-mem (bytes/slice file (+ start prg-size) (+ start prg-size chr-size)) 0))
    (->Nrom prg-rom (bytes/make 0x2000) chr-mem
            (memory/video-memory {:chr chr-mem :chr-ram? chr-ram? :mirroring mirroring}))))
