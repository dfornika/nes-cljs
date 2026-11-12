(ns games.hello-test
  "Runs the hello ROM's program on the ClojureScript CPU by itself, with
  nes.cpu.harness standing in for the rest of the console."
  (:require [cljs.test :refer [deftest is testing]]
            [games.hello :as hello]
            [nes.cpu.harness :as harness]
            [nes.rom]))

(defn- at* [m name] (get (:symbols m) name))

(defn- sprites [m]
  (mapv (fn [i] (vec (for [j (range 4)] (harness/peek-at m (+ 0x200 (* 4 i) j))))) (range 5)))

(deftest builds-a-cartridge
  (let [{:keys [rom name]} (hello/build)]
    (is (= "hello.nes" name))
    (is (= (+ 16 16384 8192) (count rom)))
    (is (= {:mapper 0 :prg-banks 1 :chr-banks 1}
           (select-keys (nes.rom/parse-header (js/Uint8Array.from (clj->js rom)))
                        [:mapper :prg-banks :chr-banks])))
    (is (= rom (:rom (hello/build))) "building twice gives the same bytes")))

(deftest runs
  (let [m (harness/boot! (harness/console (hello/build)))
        at #(harness/peek-at m %)
        where (fn [] [(at (inc (at* m :player/x))) (at (inc (at* m :player/y)))])]
    (testing "after RESET"
      (is (= [120 112] (where)))
      (is (every? #(= 0xFF (at (+ 0x200 %))) (range 256)) "all sprites hidden")
      (is (= 0x80 (at 0x2000)) "NMI is on"))
    (testing "a frame with nothing pressed draws the face where it started"
      (let [{:keys [update nmi]} (harness/frame! m)]
        (is (= [[112 1 0 120] [112 2 0 128] [120 3 0 120] [120 4 0 128] [0xFF 0xFF 0xFF 0xFF]]
               (sprites m)))
        (is (= 1 (at :frame)))
        (is (= 0x1E (at 0x2001)) "the NMI handler turned the picture on")
        (is (= 2 (at 0x4014)) "...and asked for the sprite copy from page 2")
        (is (< (+ update nmi 513) 29780) "a frame's work fits in a frame")
        (is (< (+ nmi 513) 2270) "and the NMI handler in vertical blank")))
    (testing "the D-pad moves it a pixel and a half a frame"
      (harness/hold! m :right :down)
      (harness/frames! m 2)
      (is (= [123 115] (where)))
      (harness/hold! m :left)
      (harness/frames! m 4)
      (is (= [117 115] (where))))
    (testing "A counts as pressed once, however long it's held"
      (harness/hold! m :a)
      (harness/frames! m 3)
      (is (= 0x80 (at :buttons)))
      (is (= 0 (at :pressed)))
      (is (= 1 (at :color)))
      (harness/hold! m)
      (harness/frame! m)
      (harness/hold! m :a)
      (harness/frame! m)
      (is (= 2 (at :color))))))
