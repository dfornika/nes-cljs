(ns nes.machine-test
  "Our games on NES.cljs's own console (nes.machine: our CPU and our PPU),
  side by side with jsnes: the same buttons pressed on the same frames,
  then the same RAM and the same picture after every frame.

  Pictures are compared except where jsnes is known to draw differently
  from a PPU (see nes.ppu.render-jsnes-test): the right-hand pixels when
  scrolled by part of a tile, the left 8 when PPUMASK hides either layer
  there, and the edges a TV hides, which jsnes blacks out unless told not
  to. And jsnes doesn't skip a dot on odd frames, so for this ours doesn't
  either.

  The two start the same way (the PPU at line 241, the CPU at the RESET
  address), end frames at the same moment (as vertical blank starts), and
  follow the same rules for when an NMI is taken, so they keep in step
  cycle for cycle."
  (:require [cljs.test :refer [deftest is testing]]
            [games.alienscape :as alienscape]
            [games.hello :as hello]
            [games.hopper :as hopper]
            [games.star-run :as star-run]
            [games.tower :as tower]
            [nes.emulator :as emu]
            [nes.emulator.jsnes :as jsnes]
            [nes.emulator.machine :as machine]
            [nes.ppu.core :as ppu]))

(defn- pair
  "jsnes and our console, each with `game` loaded, keeping their last pictures."
  [game]
  (let [rom (js/Uint8Array.from (clj->js (:rom (game))))
        make (fn [create]
               (let [pic (atom nil)
                     e (create {:on-frame #(reset! pic (js/Uint32Array.from %))})]
                 (emu/load-rom! e rom)
                 {:emu e :pic pic}))
        j (make jsnes/create)
        o (make machine/create)]
    (set! (.. ^js (:nes (:emu j)) -ppu -clipToTvSize) false)
    ;; jsnes doesn't skip the odd frames' dot, as a real NES does
    (ppu/skip-odd-dot! (:ppu (.-m ^js (:state (:emu o)))) false)
    ;; RAM starts out different (jsnes fills it with $FF), and our games
    ;; clear it in their first frames
    (dotimes [_ 5] (emu/frame! (:emu j)) (emu/frame! (:emu o)))
    {:jsnes j :ours o}))

(defn- ram [e] (mapv #(emu/cpu-peek e %) (range 0x800)))

(defn- picture-diffs
  "Pixels that differ, as [x y], skipping the ones jsnes gets wrong."
  [{:keys [jsnes ours]}]
  (let [a @(:pic ours), b @(:pic jsnes)
        fine-x (bit-and (:x (:scroll (emu/ppu-state (:emu jsnes)))) 7)
        ;; jsnes blacks out the left 8 pixels when PPUMASK hides either
        ;; layer there (even with drawing off); a PPU shows the backdrop
        left (if (= 6 (bit-and (:mask (emu/ppu-registers (:emu jsnes))) 6)) 0 8)]
    (for [i (range (* 256 240))
          :let [x (mod i 256)]
          :when (<= left x (- 255 fine-x))
          :when (not= (bit-and (aget a i) 0xFFFFFF) (bit-and (aget b i) 0xFFFFFF))]
      [x (quot i 256)])))

(defn- play!
  "Run both for `n` frames holding `buttons`. Returns the first frame where
  they differ, as {:frame :ram-at :pixels}, or nil."
  [{:keys [jsnes ours] :as p} n & buttons]
  (doseq [{e :emu} [jsnes ours] b buttons] (emu/button-down! e 1 b))
  (let [result (loop [k 0]
                 (when (< k n)
                   (emu/frame! (:emu jsnes))
                   (emu/frame! (:emu ours))
                   (let [ra (ram (:emu ours)) rb (ram (:emu jsnes))
                         ram-at (first (filter #(not= (ra %) (rb %)) (range 0x800)))
                         px (take 5 (picture-diffs p))]
                     (if (or ram-at (seq px))
                       {:frame k :ram-at ram-at :pixels (vec px)}
                       (recur (inc k))))))]
    (doseq [{e :emu} [jsnes ours] b buttons] (emu/button-up! e 1 b))
    result))

(deftest hello-world
  (let [p (pair hello/build)]
    (is (nil? (play! p 60)))))

(deftest star-hopper
  (let [p (pair hopper/build)]
    (is (nil? (play! p 30)) "the title")
    (is (nil? (play! p 5 :start)))
    (is (nil? (play! p 60 :right)) "running")
    (is (nil? (play! p 20 :a :left)) "jumping")
    (is (nil? (play! p 120)) "and on")))

(deftest alienscape
  (let [p (pair alienscape/build)]
    (is (nil? (play! p 30)))
    (is (nil? (play! p 5 :start)))
    (is (nil? (play! p 90 :right)))
    (is (nil? (play! p 30 :b)))))

(deftest star-tower
  (let [p (pair tower/build)]
    (is (nil? (play! p 30)))
    (is (nil? (play! p 5 :start)))
    (is (nil? (play! p 60 :right)))
    (is (nil? (play! p 40 :up)))))

(deftest star-run
  (testing "scrolling, with columns streamed in during vertical blank"
    (let [p (pair star-run/build)]
      (is (nil? (play! p 30)))
      (is (nil? (play! p 5 :start)))
      (is (nil? (play! p 300 :right)))
      (is (nil? (play! p 30 :a :right))))))
