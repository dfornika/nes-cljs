(ns nes.machine-test
  "Our games on NES.cljs's own console (nes.machine: our CPU and our PPU),
  side by side with jsnes: the same buttons pressed on the same frames,
  then the same RAM and the same picture after every frame, from power on.

  This is the CPU tab's \"Check against jsnes\" (nes.app.console/compare!),
  with the same rules (nes.inspect.compare): what's set up first so the two keep
  in step, and which pixels are left out because jsnes draws them
  differently from a real PPU."
  (:require [cljs.test :refer [deftest is testing]]
            [nes.sample-roms :as sample]
            [nes.inspect.compare :as compare]
            [nes.emulator :as emu]
            [nes.emulator.jsnes :as jsnes]
            [nes.emulator.machine :as machine]))

(defn- pair
  "jsnes and our console, each with `game` (a sample game's name, or a
  .nes file's bytes) loaded, keeping their last
  pictures, set up to keep in step (as nes.app.console/compare! does)."
  [game]
  (let [rom (if (string? game) (sample/rom game) game)
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

(defn- play-ram!
  "As `play!`, but comparing only RAM: the first frame where it differs,
  or nil."
  [{:keys [jsnes ours]} n & buttons]
  (doseq [{e :emu} [jsnes ours] b buttons] (emu/button-down! e 1 b))
  (let [result (loop [k 0]
                 (when (< k n)
                   (emu/frame! (:emu jsnes))
                   (emu/frame! (:emu ours))
                   (let [ram (compare/ram-diffs #(emu/cpu-peek (:emu ours) %) #(emu/cpu-peek (:emu jsnes) %))]
                     (if (seq ram)
                       {:frame k :ram (vec (take 5 ram))}
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

(def ^:private fs (js/require "fs"))

(deftest the-240p-test-suite
  ;; Mapper 2 (UxROM): 64 KB of program in four 16 KB banks, switched as
  ;; it moves between its menu and its tests. RAM is compared every frame,
  ;; which is what the mapper decides: the wrong bank would run the wrong
  ;; code. The pictures aren't compared here, because jsnes draws two
  ;; things in this ROM differently from our PPU, and which of them is
  ;; right is a separate question (TODO.md, under step 7): line 0, which
  ;; jsnes leaves black, and the 8x16 sprites from tile RAM that make up
  ;; the title screen's character, which jsnes draws scrambled.
  (let [p (pair (js/Uint8Array. (.readFileSync fs "resources/public/roms/240p/240pee.nes")))
        ^js m (.-m ^js (.-state (:emu (:ours p))))
        banks (atom #{})
        same-ram? (fn [n & buttons]
                    (let [d (apply play-ram! p n buttons)]
                      (swap! banks conj (.-bank ^js (:cart m)))
                      (nil? d)))]
    (is (same-ram? 90) "the title and the menu")
    (doseq [[n test] [[1 "the first test"] [2 "the second"] [4 "the fourth"]]]
      (dotimes [_ n] (is (same-ram? 4 :down)) (is (same-ram? 4)))
      (is (same-ram? 4 :a))
      (is (same-ram? 120) test)
      (is (same-ram? 4 :b) "and back to the menu")
      (is (same-ram? 30)))
    (is (< 1 (count @banks)) (str "it switched banks: " (sort @banks)))))
