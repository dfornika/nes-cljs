(ns nes.cpu.nestest-test
  "Runs nes.cpu.core through nestest, kevtris's CPU test ROM, comparing the
  CPU's registers and cycle count before every instruction with the
  reference log from a known-good emulator (Nintendulator).

  nestest is in test-roms/nestest (see the README there). NESTEST_DIR can
  point somewhere else."
  (:require [cljs.test :refer [deftest is]]
            [nes.cpu.core :as cpu]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- find-dir []
  (some #(when (and % (.existsSync fs (.join path % "nestest.nes"))
                    (.existsSync fs (.join path % "nestest.log")))
           %)
        [(.. js/process -env -NESTEST_DIR)
         "test-roms/nestest"]))

(defn- parse-line
  "One line of nestest.log: the state before the instruction."
  [line]
  (let [field (fn [k] (js/parseInt (second (re-find (re-pattern (str k ":([0-9A-F]+)")) line)) 16))]
    {:pc (js/parseInt (subs line 0 4) 16)
     :a (field "A") :x (field "X") :y (field "Y") :p (field "P") :sp (field "SP")
     :cycles (js/parseInt (second (re-find #"CYC:(\d+)" line)))
     :unofficial? (= "*" (subs line 15 16))
     :line line}))

(deftest nestest
  (if-let [dir (find-dir)]
    (let [rom (.readFileSync fs (.join path dir "nestest.nes"))
          expected (->> (.split (str (.readFileSync fs (.join path dir "nestest.log"))) "\n")
                        (remove #(< (count %) 20))
                        (map parse-line)
                        vec)
          mem (js/Uint8Array. 0x10000)
          bus (cpu/flat-bus mem)
          s (cpu/create)]
      ;; NROM-128: the 16 KB program appears at both $8000 and $C000
      (.set mem (.subarray rom 16 (+ 16 16384)) 0x8000)
      (.set mem (.subarray rom 16 (+ 16 16384)) 0xC000)
      ;; nestest's automatic mode starts at $C000, after RESET's 7 cycles
      (set! (.-pc s) 0xC000)
      (set! (.-cycles s) 7)
      (let [mismatch (loop [[e & more] expected n 0]
                       (if-not e
                         nil
                         (let [actual {:pc (.-pc s) :a (.-a s) :x (.-x s) :y (.-y s)
                                       :p (.-p s) :sp (.-sp s) :cycles (.-cycles s)}]
                           (if (= actual (dissoc e :unofficial? :line))
                             (do (cpu/step! s bus) (recur more (inc n)))
                             {:line (inc n) :expected (:line e) :actual actual}))))]
        (is (nil? mismatch) (str "differs from nestest.log: " (pr-str mismatch)))
        ;; nestest's own verdict: an error code at $02 and $03, 0 if all passed
        (is (= [0 0] [(aget mem 2) (aget mem 3)]) "nestest reports no errors")
        (is (= 8991 (count expected)) "every line was compared")
        (is (= 197 (count (filter :unofficial? expected))) "including the unofficial instructions")))
    (is false "nestest not found in test-roms/nestest (or NESTEST_DIR)")))
