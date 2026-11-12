(ns games.ladder-test
  "Each rung of the ladder, run on the CPU by itself."
  (:require [cljs.test :refer [deftest is testing]]
            [games.ladder :as ladder]
            [nes.cpu.core :as cpu]
            [nes.cpu.harness :as harness]))

(deftest every-rung-builds
  (let [sizes (mapv #(ladder/program-size (ladder/build (:id %))) ladder/rungs)]
    (is (= [:nil :color :pulse :pad :sprite] (mapv :id ladder/rungs)))
    (is (= 3 (first sizes)) "nil is one instruction")
    (is (apply < sizes) (str "each rung is a bit more program than the last: " sizes))
    (is (< (last sizes) 256) "and the top one still fits in a page"))
  (is (= "pulse.nes" (:name (ladder/build :pulse))))
  (is (thrown-with-msg? js/Error #"no rung called :nope" (ladder/build :nope))))

(deftest nil-does-nothing
  (let [{:keys [prg labels]} (ladder/build :nil)
        {:keys [^js mem ^js s bus] :as m} (harness/machine prg 0xC000)
        before (vec (take 0x800 (array-seq mem)))]
    (is (= [0x4C 0x00 0xC0] (subvec prg 0 3)) "JMP $C000")
    (is (= 0xC000 (:reset labels)))
    (cpu/jump-to-reset-vector! s bus)
    (dotimes [_ 1000] (cpu/step! s bus))
    (is (= 0xC000 (.-pc s)) "still there")
    (is (= before (vec (take 0x800 (array-seq mem)))) "and memory is untouched")))

(deftest color-sets-the-backdrop-and-stops
  (let [{:keys [prg labels]} (ladder/build :color)
        {:keys [^js mem ^js s bus] :as m} (harness/machine prg 0xC000)]
    (aset mem 0x2002 0x80)                  ; PPUSTATUS: in vertical blank
    (cpu/jump-to-reset-vector! s bus)
    (harness/run-until! m (:forever labels))
    (is (= ladder/sky-blue (aget mem 0x2007)) "the color went to PPUDATA")
    (is (= 0 (aget mem 0x2006)) "and PPUADDR was pointed away again")
    (dotimes [_ 100] (cpu/step! s bus))
    (is (= (:forever labels) (.-pc s)))))

(deftest pulse-changes-color-every-16-frames
  (let [m (harness/boot! (harness/console (ladder/build :pulse)))
        colors (atom [])]
    (dotimes [_ 160]
      (harness/frame! m)
      (swap! colors conj (harness/peek-at m 0x2007)))     ; what the NMI handler showed
    (is (= 160 (harness/peek-at m :frame)))
    (is (= [0x21 0x22 0x23 0x24 0x25 0x26 0x27 0x28 0x21 0x22] (map first (partition 16 @colors))))
    (is (every? #(apply = %) (partition 16 @colors)) "steady for 16 frames each")))

(deftest pad-steps-through-colors
  (let [m (harness/boot! (harness/console (ladder/build :pad)))
        tap! (fn [button] (harness/hold! m button) (harness/frame! m) (harness/hold! m) (harness/frame! m))
        shown #(harness/peek-at m 0x2007)]
    (harness/frames! m 2)
    (is (= (first ladder/colors) (shown)))
    (tap! :right)
    (is (= (second ladder/colors) (shown)))
    (testing "holding a button counts once"
      (harness/hold! m :right)
      (harness/frames! m 10)
      (harness/hold! m)
      (harness/frame! m)
      (is (= (nth ladder/colors 2) (shown))))
    (tap! :left) (tap! :left) (tap! :left)
    (is (= (last ladder/colors) (shown)) "left from the first wraps to the last")))

(deftest sprite-moves-a-ball
  (let [m (harness/boot! (harness/console (ladder/build :sprite)))
        sprite (fn [] (mapv #(harness/peek-at m (+ 0x200 %)) (range 4)))]
    (harness/frame! m)
    (is (= [116 1 0 124] (sprite)) "Y, tile, attributes, X")
    (is (= 0xFF (harness/peek-at m 0x204)) "the other 63 are hidden")
    (is (= 0x14 (harness/peek-at m 0x2001)) "the picture is on, sprites only")
    (harness/hold! m :right :up)
    (harness/frames! m 5)
    (is (= [111 1 0 129] (sprite)))
    (let [{:keys [update nmi]} (harness/frame! m)]
      (is (< (+ update nmi 513) 29780)))))
