(ns nes.machine-test
  "Our games on NES.cljs's own console (nes.machine: our CPU and our PPU),
  side by side with jsnes: the same buttons pressed on the same frames,
  then the same RAM and the same picture after every frame, from power on.

  This is the CPU tab's \"Check against jsnes\" (nes.console/compare!),
  with the same rules (nes.compare): what's set up first so the two keep
  in step, and which pixels are left out because jsnes draws them
  differently from a real PPU."
  (:require [cljs.test :refer [deftest is testing]]
            [nes.sample-roms :as sample]
            [nes.compare :as compare]
            [nes.emulator :as emu]
            [nes.emulator.jsnes :as jsnes]
            [nes.emulator.machine :as machine]))

(defn- pair
  "jsnes and our console, each with `game` loaded, keeping their last
  pictures, set up to keep in step (as nes.console/compare! does)."
  [game]
  (let [rom (sample/rom game)
        make (fn [create]
               (let [pic (atom nil)
                     e (create {:on-frame #(reset! pic (js/Uint32Array.from %))})]
                 (emu/load-rom! e rom)
                 {:emu e :pic pic}))
        j (make jsnes/create)
        o (make machine/create)]
    (set! (.. ^js (:nes (:emu j)) -ppu -clipToTvSize) false)
    (machine/match-jsnes! (:emu o))
    (dotimes [a 0x800] (emu/cpu-poke! (:emu o) a (emu/cpu-peek (:emu j) a)))
    {:jsnes j :ours o}))

(defn- play!
  "Run both for `n` frames holding `buttons`. Returns the first frame where
  they differ, as {:frame :ram :pixels}, or nil."
  [{:keys [jsnes ours]} n & buttons]
  (doseq [{e :emu} [jsnes ours] b buttons] (emu/button-down! e 1 b))
  (let [result (loop [k 0]
                 (when (< k n)
                   (emu/frame! (:emu jsnes))
                   (emu/frame! (:emu ours))
                   (let [j (:emu jsnes), o (:emu ours)
                         ram (compare/ram-diffs #(emu/cpu-peek o %) #(emu/cpu-peek j %))
                         px (compare/picture-diffs @(:pic ours) @(:pic jsnes)
                                                   (:mask (emu/ppu-registers j))
                                                   (bit-and (:x (:scroll (emu/ppu-state j))) 7)
                                                   (machine/lines-drawn o))]
                     (if (or (seq ram) (seq px))
                       {:frame k :ram (vec (take 5 ram))
                        :pixels (mapv (fn [i] [(mod i 256) (quot i 256)]) (take 5 px))}
                       (recur (inc k))))))]
    (doseq [{e :emu} [jsnes ours] b buttons] (emu/button-up! e 1 b))
    result))

(deftest hello-world
  (let [p (pair "hello")]
    (is (nil? (play! p 60)))))

(deftest star-hopper
  (let [p (pair "hopper")]
    (is (nil? (play! p 30)) "the title")
    (is (nil? (play! p 5 :start)))
    (is (nil? (play! p 60 :right)) "running")
    (is (nil? (play! p 20 :a :left)) "jumping")
    (is (nil? (play! p 120)) "and on")))

(deftest alienscape
  (let [p (pair "alienscape")]
    (is (nil? (play! p 30)))
    (is (nil? (play! p 5 :start)))
    (is (nil? (play! p 90 :right)))
    (is (nil? (play! p 30 :b)))))

(deftest star-tower
  (let [p (pair "tower")]
    (is (nil? (play! p 30)))
    (is (nil? (play! p 5 :start)))
    (is (nil? (play! p 60 :right)))
    (is (nil? (play! p 40 :up)))))

(deftest star-run
  (testing "scrolling, with columns streamed in during vertical blank"
    (let [p (pair "star-run")]
      (is (nil? (play! p 30)))
      (is (nil? (play! p 5 :start)))
      (is (nil? (play! p 300 :right)))
      (is (nil? (play! p 30 :a :right))))))
