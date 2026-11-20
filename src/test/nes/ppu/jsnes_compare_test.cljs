(ns nes.ppu.jsnes-compare-test
  "Our PPU registers (nes.ppu.registers) against jsnes's: the same random
  reads and writes of $2000-$2007 to both, then the same bytes read back,
  and the same v, t, x and w.

  Where jsnes is known to differ from a real PPU, the test says so and
  puts jsnes right, so the rest can still be compared.

  Drawing stays off (PPUMASK bits 3 and 4), and no frames run: while the
  PPU draws, some registers behave differently, and that isn't modelled
  yet."
  (:require [cljs.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer-macros [defspec]]
            [clojure.test.check.generators :as tc]
            [clojure.test.check.properties :as prop :include-macros true]
            [games.hello :as hello]
            [nes.bytes :as bytes]
            [nes.emulator :as emu]
            [nes.emulator.jsnes :as jsnes]
            [nes.ppu.memory :as memory]
            [nes.ppu.registers :as r]
            [nes.rom :as rom]))

(def ^:private cartridge
  (js/Uint8Array.from (clj->js (:rom (hello/build)))))

(defn- ours
  "Our registers, with video memory like the cartridge's."
  []
  (let [{:keys [prg-size chr-size mirroring]} (rom/parse-header cartridge)
        chr (when (pos? chr-size) (bytes/slice cartridge (+ 16 prg-size) (+ 16 prg-size chr-size)))]
    ;; palette RAM starts as jsnes's does (zeros), not as on blargg's NES
    {:ppu (assoc (r/create) :palette (bytes/make 32))
     :mem (memory/video-memory {:chr chr :mirroring mirroring})}))

(defn- theirs []
  (doto (jsnes/create {:on-frame (fn [_])})
    (emu/load-rom! cartridge)))

(def ^:private op
  (tc/one-of
   [(tc/fmap (fn [[reg value]]
               [:write (+ 0x2000 reg)
                (case reg
                  0 (bit-and value 0x7F)    ; NMI stays off
                  1 (bit-and value 0xE7)    ; and drawing
                  value)])
             (tc/tuple (tc/elements [0 1 3 4 5 6 7]) (tc/choose 0 255)))
    ;; $2006 twice, at addresses worth reading and writing: nametables and palette
    (tc/fmap (fn [[hi lo]] [:address hi lo])
             (tc/tuple (tc/elements [0x20 0x23 0x27 0x2B 0x2F 0x3F]) (tc/choose 0 255)))
    (tc/fmap (fn [reg] [:read (+ 0x2000 reg)]) (tc/elements [0 2 4 5 7]))]))

(defn- jsnes-wrong-here?
  "Would a $2007 access at our `v` go wrong in jsnes? Two places where it
  does, so the comparison skips them:

  - v with bit 14 on (fine Y 4-7). The PPU's address is v's low 14 bits,
    but jsnes uses all 15, so a read meant for $0020 lands in its palette
    code.
  - $3F20-$3FFF: the palette repeats every 32 bytes, with $3F10 the same
    as $3F00 each time, and jsnes's table of repeats doesn't do that.
  - $3000-$3EFF: a repeat of $2000-$2EFF, mirroring and all, but jsnes's
    table sends it to $2000-$2EFF without the mirroring."
  [v]
  (or (bit-test v 14) (<= 0x3F20 v 0x3FFF) (<= 0x3000 v 0x3EFF)))

(defn- run-both!
  "Do `ops` to both. Returns [what ours read, what jsnes read]."
  [ops]
  (let [{:keys [ppu mem]} (ours)
        e (theirs)
        ^js mmap (.-mmap (:nes e))
        ours-read (atom []) theirs-read (atom [])]
    (doseq [[kind a b] ops
            :when (not (and (#{:read :write} kind) (= 7 (bit-and a 7))
                            (jsnes-wrong-here? (:v (r/registers ppu)))))]
      (case kind
        :write (do (r/write! ppu mem a b) (.write mmap a b))
        :address (doseq [v [a b]] (r/write! ppu mem 0x2006 v) (.write mmap 0x2006 v))
        :read (let [v (.load mmap a)]
                (swap! ours-read conj (r/read! ppu mem a))
                (swap! theirs-read conj v)
                ;; jsnes doesn't put what $2004 and $2007 read on the PPU's
                ;; bus, as a real PPU does ("Reading any readable port also
                ;; fills the latch": https://www.nesdev.org/wiki/PPU_registers)
                (when (#{4 7} (bit-and a 7))
                  (set! (.. ^js (:nes e) -ppu -openBusLatch) v)))))
    ;; jsnes keeps 2 bits of v's fine Y, not 3, so when stepping v on past
    ;; $3FFF carries into bit 14, jsnes loses it. Compare the 14 bits that
    ;; make the address.
    (let [address-bits #(update % :v bit-and 0x3FFF)]
      {:ours {:read @ours-read :scroll (address-bits (select-keys (r/registers ppu) [:v :t :x :w]))}
       :theirs {:read @theirs-read :scroll (address-bits (emu/ppu-scroll e))}})))

(deftest the-same-example
  (let [{:keys [ours theirs]} (run-both! [[:write 0x2000 0] [:read 0x2002] [:write 0x2005 0x7D]
                                          [:write 0x2005 0x5E] [:write 0x2006 0x3D] [:write 0x2006 0xF0]])]
    (is (= ours theirs))
    (is (= 0x3DF0 (get-in ours [:scroll :v])))))

(defspec same-as-jsnes 1000
  (prop/for-all [ops (tc/vector op 1 60)]
    (let [{:keys [ours theirs]} (run-both! ops)]
      (= ours theirs))))
