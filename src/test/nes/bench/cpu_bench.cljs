(ns nes.bench.cpu-bench
  "How fast the CPU runs, in an optimized build like the browser's:
  `npm run bench:cpu`. Not a test (the name doesn't end in -test, so
  `npm test` leaves it out). Prints the best of several runs of:

  - nestest's 8,991 instructions on a flat bus: the CPU by itself
  - our whole console running Star Run for 300 frames (mostly the PPU)
  - jsnes running Star Run on our CPU: the CPU inside a fast PPU, the
    number most sensitive to the CPU's speed
  - jsnes by itself, to see how noisy the machine is today

  Compare against main by running it on both."
  (:require [cljs.test :refer [deftest is]]
            [nes.cpu.core :as cpu]
            [nes.cpu.registers :refer [reg set-reg!]]
            [nes.emulator :as emu]
            [nes.emulator.jsnes :as jsnes]
            [nes.emulator.machine :as machine]))

(def ^:private fs (js/require "fs"))

(defn- best-of [n f]
  (apply min (repeatedly n (fn [] (let [t0 (js/performance.now)] (f) (- (js/performance.now) t0))))))

(defn- nestest []
  (let [rom (.readFileSync fs "test-roms/nestest/nestest.nes")
        mem (js/Uint8Array. 0x10000)
        bus (cpu/flat-bus mem)
        s (cpu/create)]
    (.set mem (.subarray rom 16 (+ 16 16384)) 0x8000)
    (.set mem (.subarray rom 16 (+ 16 16384)) 0xC000)
    (set-reg! s :pc 0xC000)
    (dotimes [_ 8991] (cpu/step! s bus))))

(defn- frames [create cpu n]
  (let [e (create {:on-frame (fn [_])})]
    (emu/load-rom! e (js/Uint8Array. (.readFileSync fs "resources/public/roms/cartridj/star-run.nes")))
    (when cpu (emu/use-cpu! e cpu))
    (dotimes [_ n] (emu/frame! e))))

(deftest bench
  (dotimes [_ 3] (nestest) (frames machine/create nil 60))   ; warm up
  (let [a (best-of 20 nestest)
        b (best-of 5 #(frames machine/create nil 300))
        c (best-of 5 #(frames jsnes/create :cljs 300))
        d (best-of 5 #(frames jsnes/create :jsnes 300))]
    (println (str "BENCH nestest-8991-instr-ms " (.toFixed a 2)
                  " | our-console-300-frames-ms " (.toFixed b 0) " (" (.toFixed (/ 300000 b) 1) " fps)"
                  " | jsnes+our-cpu-300-frames-ms " (.toFixed c 0) " (" (.toFixed (/ 300000 c) 1) " fps)"
                  " | jsnes-alone-300-frames-ms " (.toFixed d 0)))
    (is true)))
