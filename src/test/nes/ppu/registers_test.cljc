(ns nes.ppu.registers-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test #?(:clj :refer :cljs :refer-macros) [defspec]]
            [clojure.test.check.generators :as tc]
            [clojure.test.check.properties :as prop #?@(:cljs [:include-macros true])]
            [nes.bytes :as bytes]
            [nes.ppu.memory :as memory]
            [nes.ppu.registers :as r]))

(defn- fresh []
  {:ppu (r/create) :mem (memory/video-memory {:mirroring :vertical})})

(defn- do-ops!
  "Do `ops` to a PPU: [:write addr value] and [:read addr]. Returns the
  values read."
  [{:keys [ppu mem]} ops]
  (vec (for [[op addr value] ops
             :let [got (case op
                         :write (r/write! ppu mem addr value)
                         :read (r/read! ppu mem addr))]
             :when (= op :read)]
         got)))

(defn- scroll [ppu] (select-keys (r/registers ppu) [:v :t :x :w]))

;; ---------------------------------------------------------------------------
;; The wiki's worked example: https://www.nesdev.org/wiki/PPU_scrolling#Summary

(deftest the-wikis-example
  (let [{:keys [ppu] :as p} (fresh)]
    (do-ops! p [[:write 0x2000 0x00]])
    (is (= 0 (:t (scroll ppu))) "PPUCTRL: the nametable bits of t")
    (do-ops! p [[:read 0x2002]])
    (is (= 0 (:w (scroll ppu))) "reading PPUSTATUS resets w")
    (do-ops! p [[:write 0x2005 0x7D]])                   ; 01111 101
    (is (= {:t 2r000000000001111 :x 2r101 :w 1} (dissoc (scroll ppu) :v)))
    (do-ops! p [[:write 0x2005 0x5E]])                   ; 01011 110
    (is (= {:t 2r110000101101111 :x 2r101 :w 0} (dissoc (scroll ppu) :v)))
    (do-ops! p [[:write 0x2006 0x3D]])                   ; 00111101
    (is (= {:t 2r011110101101111 :w 1} (select-keys (scroll ppu) [:t :w])))
    (do-ops! p [[:write 0x2006 0xF0]])                   ; 11110000
    (is (= {:v 2r011110111110000 :t 2r011110111110000 :x 2r101 :w 0} (scroll ppu)))))

(deftest scroll-position
  (testing "X 125 and Y 94 on the second nametable down"
    (let [{:keys [ppu] :as p} (fresh)]
      (do-ops! p [[:write 0x2000 2] [:write 0x2005 125] [:write 0x2005 94]])
      (let [{:keys [t x]} (r/registers ppu)]
        (is (= {:x 125 :y (+ 240 94)} (r/scroll-position t x)))
        (is (= {:coarse-x 15 :coarse-y 11 :nametable 2 :fine-y 6} (r/fields t)))))))

;; ---------------------------------------------------------------------------
;; The bit twiddling, as properties

(def u8 (tc/choose 0 255))
(def u15 (tc/choose 0 0x7FFF))
(def on-screen
  "A v with its tile row on the screen (0-29)."
  (tc/fmap (fn [[v row]] (bit-or (bit-and v 0x7C1F) (bit-shift-left row 5)))
           (tc/tuple u15 (tc/choose 0 29))))

(defspec v-and-t-stay-15-bits 200
  (prop/for-all [ops (tc/vector (tc/tuple (tc/elements [0 5 6 7]) u8) 0 40)]
    (let [{:keys [ppu] :as p} (fresh)]
      (do-ops! p (for [[reg value] ops] [:write (+ 0x2000 reg) value]))
      (let [{:keys [v t x w]} (r/registers ppu)]
        (and (<= 0 v 0x7FFF) (<= 0 t 0x7FFF) (<= 0 x 7) (<= 0 w 1))))))

(defspec two-address-writes-set-v 200
  (prop/for-all [before (tc/vector (tc/tuple (tc/elements [0 5 6]) u8) 0 10)
                 hi u8, lo u8]
    (let [{:keys [ppu] :as p} (fresh)]
      (do-ops! p (concat (for [[reg value] before] [:write (+ 0x2000 reg) value])
                      [[:read 0x2002] [:write 0x2006 hi] [:write 0x2006 lo]]))
      (= (bit-or (bit-shift-left (bit-and hi 0x3F) 8) lo) (:v (r/registers ppu))))))

(defspec a-row-of-tiles-goes-across-two-nametables 200
  (prop/for-all [v u15]
    (let [after (iterate r/increment-x v)]
      ;; 32 tiles on: the same column of the other nametable; 64: back again
      (and (= (bit-xor v 0x0400) (nth after 32))
           (= v (nth after 64))))))

(defspec a-screen-of-lines-goes-down-two-nametables 200
  (prop/for-all [v on-screen]
    (let [after (iterate r/increment-y v)]
      ;; 240 lines on: the same place in the next nametable down
      (and (= (bit-xor v 0x0800) (nth after 240))
           (= v (nth after 480))))))

(deftest rows-30-and-31-wrap-without-changing-nametable
  (let [v (bit-or 0x7000 (bit-shift-left 31 5))]   ; fine Y 7, coarse Y 31
    (is (= 0 (r/increment-y v))))
  (let [v (bit-or 0x7000 (bit-shift-left 30 5))]
    (is (= (bit-shift-left 31 5) (r/increment-y v)))))

(defspec copying-x-and-y-together-copies-everything 200
  (prop/for-all [v u15, t u15]
    (and (= t (r/copy-y (r/copy-x v t) t))
         ;; copy-y only touches the vertical bits
         (= (r/copy-x v t) (r/copy-y (r/copy-x v t) v)))))

;; ---------------------------------------------------------------------------
;; The registers

(deftest ppudata-reads-are-buffered
  (let [{:keys [ppu] :as p} (fresh)]
    (is (= [0 0x11 0x22]
           (do-ops! p [[:write 0x2006 0x20] [:write 0x2006 0x00]
                    [:write 0x2007 0x11] [:write 0x2007 0x22]
                    [:write 0x2006 0x20] [:write 0x2006 0x00]
                    [:read 0x2007] [:read 0x2007] [:read 0x2007]]))
        "the first read gives the stale buffer, then each byte one read late")
    (is (= 0x2003 (:v (r/registers ppu))))))

(deftest ppudata-steps-by-32
  (let [{:keys [ppu] :as p} (fresh)]
    (do-ops! p [[:write 0x2000 0x04] [:write 0x2006 0x20] [:write 0x2006 0x05]
             [:write 0x2007 1] [:write 0x2007 2]])
    (is (= 0x2045 (:v (r/registers ppu))))
    (is (= [0 1 2 0] (do-ops! p [[:write 0x2000 0] [:write 0x2006 0x20] [:write 0x2006 0x05] [:read 0x2007]
                          [:write 0x2006 0x20] [:write 0x2006 0x25] [:read 0x2007]
                          [:write 0x2006 0x20] [:write 0x2006 0x45] [:read 0x2007] [:read 0x2007]]))
        "1 at $2005, 2 a row down at $2025, nothing at $2045: each read one late")))

(deftest palette-reads-come-straight-away
  (let [{:keys [ppu] :as p} (fresh)]
    (do-ops! p [[:write 0x2006 0x3F] [:write 0x2006 0x01] [:write 0x2007 0xFF]])
    (is (= 0x3F (bytes/at (:palette ppu) 1)) "6 bits of color")
    (testing "no waiting; the top two bits are the last byte on the bus"
      (is (= [0x3F] (do-ops! p [[:write 0x2006 0x3F] [:write 0x2006 0x01] [:read 0x2007]]))
          "the last write was $01")
      (is (= [0xFF] (do-ops! p [[:write 0x2006 0x3F] [:write 0x2006 0xC1] [:read 0x2007]]))
          "$3FC1 is $3F01 again, and the bus held $C1"))))

(deftest sprite-palettes-share-color-0
  (is (= [0 4 8 12] (map r/palette-index [0x3F10 0x3F14 0x3F18 0x3F1C])))
  (is (= [0x11 0x1F 0x01] (map r/palette-index [0x3F11 0x3F1F 0x3F21]))))

(deftest ppustatus
  (let [{:keys [ppu] :as p} (fresh)]
    (r/set-status! ppu r/VBLANK true)
    (r/set-status! ppu r/SPRITE-0-HIT true)
    (do-ops! p [[:write 0x2005 0x1F]])                    ; leaves w = 1 and $1F on the bus
    (is (= [0xDF 0x5F] (do-ops! p [[:read 0x2002] [:read 0x2002]]))
        "the top 3 bits are status, the rest the bus; vertical blank is cleared by reading")
    (is (= 0 (:w (r/registers ppu))))
    (is (= [0x5F 0x5F] (do-ops! p [[:read 0x2000] [:read 0x2005]])) "write-only registers read as the bus")))

(deftest nmi
  (let [{:keys [ppu] :as p} (fresh)]
    (is (not (r/nmi? ppu)))
    (r/set-status! ppu r/VBLANK true)
    (is (not (r/nmi? ppu)) "NMI is off")
    (do-ops! p [[:write 0x2000 0x80]])
    (is (r/nmi? ppu) "turning it on in vertical blank asks for one")
    (do-ops! p [[:read 0x2002]])
    (is (not (r/nmi? ppu)) "reading PPUSTATUS ends that")))

(deftest oam
  (let [{:keys [ppu] :as p} (fresh)]
    (do-ops! p [[:write 0x2003 0xFE] [:write 0x2004 1] [:write 0x2004 2] [:write 0x2004 0xFF]])
    (is (= [1 2 0xFF] (map #(bytes/at (:oam ppu) %) [0xFE 0xFF 0])) "the address goes round")
    (is (= [0xE3 0xFF] (do-ops! p [[:write 0x2003 2] [:write 0x2004 0xFF] [:write 0x2003 2] [:read 0x2004]
                                [:write 0x2003 1] [:write 0x2004 0xFF] [:write 0x2003 1] [:read 0x2004]]))
        "an attribute byte has no bits 2-4")))

(deftest video-memory-mirroring
  (is (= [0 0 0x400 0x400] (map #(memory/nametable-offset :horizontal %) [0x2000 0x2400 0x2800 0x2C00])))
  (is (= [0 0x400 0 0x400] (map #(memory/nametable-offset :vertical %) [0x2000 0x2400 0x2800 0x2C00])))
  (is (= [0 0x400 0x800 0xC00] (map #(memory/nametable-offset :four-screen %) [0x2000 0x2400 0x2800 0x2C00])))
  (is (= 0x05 (memory/nametable-offset :vertical 0x3805)) "$3000-$3EFF repeats $2000-$2EFF")
  (testing "tiles in ROM can't be written"
    (let [rom (memory/video-memory {:chr (bytes/from (repeat 0x2000 7))})
          ram (memory/video-memory {})]
      (memory/video-write! rom 0x10 1)
      (memory/video-write! ram 0x10 1)
      (is (= [7 1] [(memory/video-read rom 0x10) (memory/video-read ram 0x10)])))))

(deftest the-latch-fades
  (let [{:keys [ppu] :as p} (fresh)]
    (do-ops! p [[:write 0x2000 0xFF]])
    (dotimes [_ r/fade-frames] (r/next-frame! ppu))
    (is (= [0xFF] (do-ops! p [[:read 0x2005]])) "still there after 600 ms...")
    (r/next-frame! ppu)
    (is (= [0] (do-ops! p [[:read 0x2005]])) "...gone after")
    (testing "reading PPUSTATUS refreshes only its top 3 bits"
      (do-ops! p [[:write 0x2000 0xFF]])
      (r/set-status! ppu r/VBLANK true)
      (dotimes [_ (inc r/fade-frames)]
        (r/next-frame! ppu)
        (r/set-status! ppu r/VBLANK true)
        (do-ops! p [[:read 0x2002]]))
      (is (= [0x80] (do-ops! p [[:read 0x2005]]))))))
