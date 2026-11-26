(ns nes.cpu.nestest-test
  "Runs nes.cpu.core through nestest, kevtris's CPU test ROM, comparing the
  CPU's registers and cycle count before every instruction with the
  reference log from a known-good emulator (Nintendulator). In the browser
  build's tests and on the JVM.

  nestest is in test-roms/nestest (see the README there). NESTEST_DIR can
  point somewhere else."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [nes.cpu.core :as cpu]
            [nes.cpu.registers :refer [set-reg!]]
            #?(:clj [clojure.java.io :as io])))

#?(:cljs (def ^:private fs (js/require "fs")))
#?(:cljs (def ^:private path (js/require "path")))

(defn- file [dir name]
  #?(:clj (str (io/file dir name)) :cljs (.join path dir name)))

(defn- file-exists? [f]
  #?(:clj (.exists (io/file f)) :cljs (.existsSync fs f)))

(defn- find-dir []
  (some #(when (and % (file-exists? (file % "nestest.nes")) (file-exists? (file % "nestest.log")))
           %)
        [#?(:clj (System/getenv "NESTEST_DIR") :cljs (.. js/process -env -NESTEST_DIR))
         "test-roms/nestest"]))

(defn- parse-int [s radix]
  #?(:clj (Long/parseLong s radix) :cljs (js/parseInt s radix)))

(defn- parse-line
  "One line of nestest.log: the state before the instruction."
  [line]
  (let [field (fn [k] (parse-int (second (re-find (re-pattern (str k ":([0-9A-F]+)")) line)) 16))]
    {:pc (parse-int (subs line 0 4) 16)
     :a (field "A") :x (field "X") :y (field "Y") :p (field "P") :sp (field "SP")
     :cycles (parse-int (second (re-find #"CYC:(\d+)" line)) 10)
     :unofficial? (= "*" (subs line 15 16))
     :line line}))

(defn- memory
  "64 KB of memory with nestest's program in it. It's NROM-128: the 16 KB
  program appears at both $8000 and $C000."
  [rom-file]
  #?(:clj (let [^bytes rom (with-open [in (io/input-stream rom-file)] (.readAllBytes in))
                mem (int-array 0x10000)]
            (dotimes [i 16384]
              (let [b (bit-and (aget rom (+ 16 i)) 0xFF)]
                (aset mem (+ 0x8000 i) b)
                (aset mem (+ 0xC000 i) b)))
            mem)
     :cljs (let [rom (.readFileSync fs rom-file)
                 mem (js/Uint8Array. 0x10000)]
             (.set mem (.subarray rom 16 (+ 16 16384)) 0x8000)
             (.set mem (.subarray rom 16 (+ 16 16384)) 0xC000)
             mem)))

(deftest nestest
  (if-let [dir (find-dir)]
    (let [expected (->> (str/split-lines (#?(:clj slurp :cljs #(str (.readFileSync fs %))) (file dir "nestest.log")))
                        (remove #(< (count %) 20))
                        (map parse-line)
                        vec)
          mem (memory (file dir "nestest.nes"))
          bus (cpu/flat-bus mem)
          s (cpu/create)]
      ;; nestest's automatic mode starts at $C000, after RESET's 7 cycles
      (set-reg! s :pc 0xC000)
      (let [mismatch (loop [[e & more] expected n 0 cycles 7]
                       (if-not e
                         nil
                         (let [actual (assoc (cpu/registers s) :cycles cycles)]
                           (if (= actual (dissoc e :unofficial? :line))
                             (let [c (cpu/step! s bus)] (recur more (inc n) (+ cycles c)))
                             {:line (inc n) :expected (:line e) :actual actual}))))]
        (is (nil? mismatch) (str "differs from nestest.log: " (pr-str mismatch)))
        ;; nestest's own verdict: an error code at $02 and $03, 0 if all passed
        (is (= [0 0] [(aget #?(:clj ^ints mem :cljs mem) 2) (aget #?(:clj ^ints mem :cljs mem) 3)])
            "nestest reports no errors")
        (is (= 8991 (count expected)) "every line was compared")
        (is (= 197 (count (filter :unofficial? expected))) "including the unofficial instructions")))
    (is false "nestest not found in test-roms/nestest (or NESTEST_DIR)")))
