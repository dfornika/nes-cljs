(ns games.hello-test
  "Runs the hello ROM's program on the ClojureScript CPU by itself. There's
  no PPU here, so the test stands in for it: PPUSTATUS always reads as 'in
  vertical blank', the controller gives all buttons or none, and the test
  raises the NMI itself."
  (:require [cljs.test :refer [deftest is testing]]
            [games.hello :as hello]
            [gamedev-toolkit.hardware :as hw]
            [nes.cpu.core :as cpu]
            [nes.cpu.harness :as harness]
            [nes.rom]))

(def ^:private pad
  "What every read of JOY1 returns: 1 (all buttons held) or 0 (none)."
  (atom 0))

(defn- boot
  "The program, run from RESET up to the start of the main loop."
  [{:keys [prg labels]}]
  (let [{:keys [^js mem s ^js bus] :as m} (harness/machine prg 0xC000)
        read (.-read bus)]
    (aset mem (:ppustatus hw/registers) 0x80)
    ;; The program writes to JOY1 too (the strobe), so the controller
    ;; can't just be a byte of memory.
    (set! (.-read bus) (fn [addr] (if (= addr (:joy1 hw/registers)) @pad (read addr))))
    (cpu/jump-to-reset-vector! s bus)
    (harness/run-until! m (:main labels))
    m))

(defn- frame!
  "One frame: the main loop up to where it waits, then the NMI handler,
  then back to the top of the main loop. Returns the cycles used."
  [{:keys [s bus] :as m} labels]
  (+ (harness/run-until! m (:wait-for-frame labels))
     (cpu/nmi! s bus)
     (harness/run-until! m (:main labels))))

(defn- hold! [all?]
  (reset! pad (if all? 1 0)))

(defn- sprites [{:keys [^js mem]}]
  (mapv (fn [i] (vec (for [j (range 4)] (aget mem (+ 0x200 (* 4 i) j))))) (range 5)))

(deftest builds-a-cartridge
  (let [{:keys [rom name]} (hello/build)]
    (is (= "hello.nes" name))
    (is (= (+ 16 16384 8192) (count rom)))
    (is (= {:mapper 0 :prg-banks 1 :chr-banks 1}
           (select-keys (nes.rom/parse-header (js/Uint8Array.from (clj->js rom)))
                        [:mapper :prg-banks :chr-banks])))
    (is (= rom (:rom (hello/build))) "building twice gives the same bytes")))

(deftest runs
  (let [{:keys [labels] :as built} (hello/build)
        {:keys [^js mem] :as m} (boot built)
        v (:symbols hello/memory)]
    (testing "after RESET"
      (is (= [120 112] [(aget mem (inc (:player/x v))) (aget mem (inc (:player/y v)))]))
      (is (every? #(= 0xFF (aget mem (+ 0x200 %))) (range 256)) "all sprites hidden")
      (is (= 0x80 (aget mem 0x2000)) "NMI is on"))
    (testing "a frame with nothing pressed draws the face where it started"
      (hold! false)
      (let [cycles (frame! m labels)]
        (is (= [[112 1 0 120] [112 2 0 128] [120 3 0 120] [120 4 0 128] [0xFF 0xFF 0xFF 0xFF]]
               (sprites m)))
        (is (= 1 (aget mem (:frame v))))
        (is (= 0x1E (aget mem 0x2001)) "the NMI handler turned the picture on")
        (is (= 2 (aget mem 0x4014)) "...and asked for the sprite copy from page 2")
        ;; The copy itself takes 513 cycles on a real NES, not counted here.
        (is (< (+ cycles 513) 29780) "a frame's work fits in a frame")))
    (testing "A counts as pressed once, however long it's held"
      (hold! true)
      (frame! m labels)
      (frame! m labels)
      (is (= 0xFF (aget mem (:buttons v))))
      (is (= 0 (aget mem (:pressed v))))
      (is (= 1 (aget mem (:color v))))
      ;; left and right together cancel out, as do up and down
      (is (= [120 112] [(aget mem (inc (:player/x v))) (aget mem (inc (:player/y v)))])))))
