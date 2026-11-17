(ns nes.ppu.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test #?(:clj :refer :cljs :refer-macros) [defspec]]
            [clojure.test.check.generators :as tc]
            [clojure.test.check.properties :as prop #?@(:cljs [:include-macros true])]
            [nes.bytes :as bytes]
            [nes.ppu.core :as core]
            [nes.ppu.memory :as memory]
            [nes.ppu.registers :as r]
            [nes.ppu.render :as render]))

;; ---------------------------------------------------------------------------
;; Random machines, from a seed (so a failure shrinks to a seed)

(defn- random-bytes
  "`n` bytes from a small random number generator (a 32-bit xorshift)."
  [seed n]
  (loop [s (bit-or 1 (bit-and seed 0x7FFFFFFF)), i 0, out (transient [])]
    (if (= i n)
      (persistent! out)
      (let [s (bit-and (bit-xor s (bit-shift-left s 13)) 0xFFFFFFFF)
            s (bit-xor s (unsigned-bit-shift-right s 17))
            s (bit-and (bit-xor s (bit-shift-left s 5)) 0xFFFFFFFF)]
        (recur s (inc i) (conj! out (bit-and s 0xFF)))))))

(defn- machine
  "A PPU on its pre-render line, with video memory, palette and OAM filled
  from `seed`, and these settings."
  [seed {:keys [ctrl mask t x mirroring]}]
  (let [[chr names palette oam] (let [b (random-bytes seed (+ 8192 2048 32 256))]
                                  [(subvec b 0 8192) (subvec b 8192 10240)
                                   (subvec b 10240 10272) (subvec b 10272)])
        mem (memory/video-memory {:mirroring mirroring})
        ppu (core/create)]
    (dotimes [i 8192] (memory/video-write! mem i (nth chr i)))
    (dotimes [i 2048] (memory/video-write! mem (+ 0x2000 (if (= mirroring :vertical) i (+ (mod i 1024) (* 2048 (quot i 1024))))) (nth names i)))
    (dotimes [i 32] (bytes/put! (:palette ppu) i (bit-and (nth palette i) 0x3F)))
    (dotimes [i 256] (bytes/put! (:oam ppu) i (nth oam i)))
    (doseq [[reg v] [[r/CTRL ctrl] [r/MASK mask] [r/T t] [r/X x]]] (r/set-reg! ppu reg v))
    {:ppu ppu :mem mem}))

(defn- picture [ppu] (bytes/words->vec (:picture ppu)))

(def settings
  (tc/hash-map :ctrl (tc/choose 0 0x7F)
               :mask (tc/choose 0 255)
               :t (tc/choose 0 0x7FFF)
               :x (tc/choose 0 7)
               :mirroring (tc/elements [:vertical :horizontal])))

;; ---------------------------------------------------------------------------
;; The same picture as nes.ppu.render

(defspec draws-what-the-whole-frame-renderer-draws 25
  (prop/for-all [seed (tc/choose 0 0x7FFFFFFF), s settings]
    (let [{:keys [ppu mem]} (machine seed s)
          expected (render/render (render/inputs ppu mem))]
      (core/frame! ppu mem)
      (let [status (r/reg ppu r/STATUS)]
        (and (= (bytes/words->vec (:pixels expected)) (picture ppu))
             (= (some? (:sprite-0-hit expected)) (bit-test status 6))
             (= (some? (:overflow-line expected)) (bit-test status 5)))))))

(deftest a-frame-with-everything-on
  (testing "a fixed case of the property above, to see the differences if it fails"
    (let [s {:ctrl 0x10 :mask 0x1E :t 0x1234 :x 3 :mirroring :vertical}
          {:keys [ppu mem]} (machine 42 s)
          expected (bytes/words->vec (:pixels (render/render (render/inputs ppu mem))))
          got (picture (do (core/frame! ppu mem) ppu))
          diffs (for [i (range (count expected)) :when (not= (expected i) (got i))] [(mod i 256) (quot i 256)])]
      (is (= [] (vec (take 10 diffs)))))))

;; ---------------------------------------------------------------------------
;; Timing

(defn- quiet [] {:ppu (core/create) :mem (memory/video-memory {})})

(deftest vertical-blank
  (let [{:keys [ppu mem]} (quiet)]
    (r/set-reg! ppu r/CTRL 0x80)
    (core/run-to! ppu mem 241 1)
    (is (not (bit-test (r/reg ppu r/STATUS) 7)) "not yet: dot 1 hasn't happened")
    (is (not (r/nmi? ppu)))
    (core/tick! ppu mem)
    (is (bit-test (r/reg ppu r/STATUS) 7) "line 241, dot 1")
    (is (r/nmi? ppu) "and an NMI, with PPUCTRL bit 7 on")
    (core/run-to! ppu mem 261 2)
    (is (not (bit-test (r/reg ppu r/STATUS) 7)) "cleared on the pre-render line, dot 1")))

(deftest frame-lengths
  (testing "drawing off: always 262 x 341 dots"
    (let [{:keys [ppu mem]} (quiet)]
      (core/run-to! ppu mem 241 1)
      (is (= [89342 89342] (repeatedly 2 #(do (core/tick! ppu mem) (inc (core/run-to! ppu mem 241 1))))))))
  (testing "drawing on: every other frame is a dot short"
    (let [{:keys [ppu mem]} (quiet)]
      (r/set-reg! ppu r/MASK 0x08)
      (core/run-to! ppu mem 241 1)
      (is (= #{89341 89342} (set (repeatedly 4 #(do (core/tick! ppu mem) (inc (core/run-to! ppu mem 241 1))))))))))

(deftest sprite-0-hit-happens-on-its-dot
  (let [s {:ctrl 0 :mask 0x1E :t 0 :x 0 :mirroring :vertical}
        seed (first (filter #(let [{:keys [ppu mem]} (machine % s)]
                               (:sprite-0-hit (render/render (render/inputs ppu mem))))
                            (range)))
        {:keys [ppu mem]} (machine seed s)
        [x y] (:sprite-0-hit (render/render (render/inputs ppu mem)))]
    ;; pixel x is drawn on dot x + 1
    (core/run-to! ppu mem y (inc x))
    (is (not (bit-test (r/reg ppu r/STATUS) 6)))
    (core/tick! ppu mem)
    (is (bit-test (r/reg ppu r/STATUS) 6) (str "at x " x ", line " y))))

;; ---------------------------------------------------------------------------
;; Changes partway through a frame: what the whole-frame renderer can't do

(deftest a-scroll-split
  (testing "a new X scroll written on line 99 takes effect on line 101"
    (let [s {:ctrl 0 :mask 0x1E :t 0 :x 0 :mirroring :vertical}
          top (bytes/words->vec (:pixels (let [{:keys [ppu mem]} (machine 7 s)] (render/render (render/inputs ppu mem)))))
          bottom (bytes/words->vec (:pixels (let [{:keys [ppu mem]} (machine 7 (assoc s :t 13 :x 5))] (render/render (render/inputs ppu mem)))))
          {:keys [ppu mem]} (machine 7 s)
          lines (fn [pic from to] (subvec pic (* 256 from) (* 256 to)))]
      (core/run-to! ppu mem 99 300)
      (core/cpu-write! ppu mem 0x2005 (+ (* 13 8) 5))   ; coarse X 13, fine X 5
      (core/frame! ppu mem)
      (let [pic (picture ppu)]
        (is (= (lines top 0 99) (lines pic 0 99)) "above: the old scroll")
        (is (= (lines bottom 101 240) (lines pic 101 240)) "below: the new one")))))

(deftest ppudata-while-drawing
  (let [{:keys [ppu mem]} (quiet)]
    (r/set-reg! ppu r/MASK 0x08)
    (core/run-to! ppu mem 10 300)
    (let [v (r/reg ppu r/V)]
      (core/cpu-write! ppu mem 0x2007 0)
      (is (= (r/increment-y (r/increment-x v)) (r/reg ppu r/V)) "a tile right and a line down, not +1"))))

(deftest oamdata-while-drawing
  (let [{:keys [ppu mem]} (quiet)]
    (r/set-reg! ppu r/MASK 0x08)
    (core/run-to! ppu mem 10 30)
    (is (= 0xFF (core/cpu-read! ppu mem 0x2004)) "secondary OAM being cleared reads $FF")
    (r/set-reg! ppu r/OAM-ADDR 5)
    (core/cpu-write! ppu mem 0x2004 0x77)
    (is (= 8 (r/reg ppu r/OAM-ADDR)) "a write moves on to the next sprite...")
    (is (zero? (bytes/at (:oam ppu) 5)) "...and is lost")))
