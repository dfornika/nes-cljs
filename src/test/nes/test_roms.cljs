(ns nes.test-roms
  "Running the test ROMs in test-roms/ (blargg's, see its README) and
  reading their results.

  A test ROM checks some behaviour of the hardware, the way a game would
  see it, and reports whether the emulator got it right. Shay Green
  ('blargg') wrote them against a real NES, so they're the referee when our
  console and jsnes disagree.

  They report in one of two ways:

  - The 2010 ones (ppu_vbl_nmi, ppu_open_bus, oam_read, oam_stress) write
    their status to $6000: $80 while running, $81 to ask for the Reset
    button, then a result: 0 for passed, anything else the number of the
    check that failed. $6001-$6003 hold $DE $B0 $61 once that's valid, and
    text (what you'd see on screen) goes from $6004, ending with a zero.
  - The 2005 ones keep the result in zero page, at $F8 (or $F0, for the
    earliest, blargg_ppu_tests_2005.09.15b): 1 for passed, 2 or more the
    number of the check that failed (the readme says what each means).
    When done, they turn NMI off, print it, and wait forever on a JMP to
    itself. (Some wait on a JMP to itself for an NMI while running, too.)

  `run` runs one, on any emulator (nes.emulator), and returns
  {:result :passed / :failed / :timeout, :code, :text, :frames}."
  (:require [clojure.string :as str]
            [nes.emulator :as emu]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(def dir
  "Where the test ROMs are: test-roms/, or TEST_ROMS_DIR."
  (or (.. js/process -env -TEST_ROMS_DIR) (.resolve path "test-roms")))

(defn rom-bytes
  "The bytes of test ROM `file` (relative to `dir`)."
  [file]
  (js/Uint8Array. (.readFileSync fs (.join path dir file))))

(defn- peek-byte [e addr] (or (emu/cpu-peek e addr) 0))

(defn- text-at-6000
  "The text the test wrote from $6004."
  [e]
  (apply str (map char (take-while pos? (map #(peek-byte e %) (range 0x6004 0x7000))))))

(defn- reports-at-6000? [e]
  (= [0xDE 0xB0 0x61] (mapv #(peek-byte e %) [0x6001 0x6002 0x6003])))

(defn- waiting-forever?
  "Is the CPU on a JMP to itself, with NMI off?"
  [e]
  (let [pc (:pc (emu/cpu-state e))]
    (and (not (bit-test (:ctrl (emu/ppu-registers e)) 7))
         (= 0x4C (peek-byte e pc))
         (= pc (bit-or (peek-byte e (inc pc)) (bit-shift-left (peek-byte e (+ pc 2)) 8))))))

(defn result-address
  "Where a 2005 test ROM in `folder` keeps its result."
  [folder]
  (if (= folder "blargg_ppu_tests_2005.09.15b") 0xF0 0xF8))

(defn run
  "Run the test ROM in `bytes` on emulator `e` (made, not yet loaded) for up
  to `max-frames` frames (default 3600: a minute), and read its result.
  `result-at` is where a 2005 test keeps its result (see `result-address`)."
  ([e bytes] (run e bytes 3600 0xF8))
  ([e bytes max-frames result-at]
   (emu/load-rom! e bytes)
   (loop [frame 0, reset-at nil]
     (emu/frame! e)
     (let [status (peek-byte e 0x6000)
           new-style? (reports-at-6000? e)]
       (cond
         (and new-style? (< status 0x80))
         {:result (if (zero? status) :passed :failed) :code status :text (str/trim (text-at-6000 e)) :frames frame}

         ;; it asks for Reset: wait a moment (as a person would), then press it
         (and new-style? (= status 0x81))
         (if (and reset-at (>= frame reset-at))
           (do (emu/press-reset! e) (recur (inc frame) nil))
           (recur (inc frame) (or reset-at (+ frame 10))))

         (and (not new-style?) (waiting-forever? e))
         (let [code (peek-byte e result-at)]
           {:result (if (= 1 code) :passed :failed) :code code :frames frame})

         (>= frame max-frames)
         {:result :timeout :code status :text (when new-style? (str/trim (text-at-6000 e))) :frames frame}

         :else (recur (inc frame) reset-at))))))

(def roms
  "The test ROMs, by folder."
  (into (sorted-map)
        (for [d (.readdirSync fs dir)
              :when (.isDirectory (.statSync fs (.join path dir d)))
              :let [files (sort (filter #(str/ends-with? % ".nes") (.readdirSync fs (.join path dir d))))]
              :when (and (seq files) (not= d "nestest"))]
          [d (vec files)])))
