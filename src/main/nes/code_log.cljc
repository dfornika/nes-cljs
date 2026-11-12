(ns nes.code-log
  "The code/data log: which bytes of a game's program ROM ran as code, and
  which were read as data, recorded while the game runs. (Emulators call
  this a 'code/data logger', CDL.)

  You can't reliably tell code from data by looking at a ROM: the same
  byte could be an instruction or a number in a table. Running the game
  and watching settles it, for the parts of the game you play. It's also
  where subroutines show up: every JSR the CPU executes says 'a subroutine
  starts here'. See nes.emulator.jsnes-code-log for the recording itself.

  A log is two byte arrays the size of the PRG ROM, plus call counts:

  `flags`, one byte per ROM byte, in FCEUX's .cdl format
  (https://fceux.com/web/help/CodeDataLogger.html):
    bit 0  ran as code
    bit 1  read as data
    bits 2-3  which 8 KB CPU window it was in when last used
              (0 = $8000, 1 = $A000, 2 = $C000, 3 = $E000)
    bit 4  reached as code indirectly (a jump table: JMP ($nnnn), or an
           address pushed and 'returned' to with RTS)
    bit 5  read as data indirectly (LDA ($nn),Y and friends; not recorded
           yet)

  `extra`, our own bits:
    bit 0  an instruction starts here
    bit 1  a subroutine starts here (a JSR went to it)
    bit 2  ran during the NMI handler (once a frame, in vertical blank)
    bit 3  ran outside it (the main loop)

  `calls`, how many times a JSR went to each byte."
  (:require [nes.bytes :as bytes]))

(def flag-code 0x01)
(def flag-data 0x02)
(def flag-indirect-code 0x10)
(def flag-indirect-data 0x20)

(def extra-instruction 0x01)
(def extra-subroutine 0x02)
(def extra-nmi 0x04)
(def extra-main 0x08)

(def window-bases [0x8000 0xA000 0xC000 0xE000])

(defn window-of
  "Which 8 KB CPU window (0-3) a flags byte says its ROM byte was in."
  [flag]
  (bit-and 3 (bit-shift-right flag 2)))

(defn cpu-address
  "The CPU address PRG offset `prg-offset` was last seen at, from its flags."
  [flag prg-offset]
  (+ (window-bases (window-of flag)) (bit-and prg-offset 0x1FFF)))

(defn- count-where [arr pred]
  (let [n (bytes/size arr)]
    (loop [i 0 c 0]
      (if (< i n) (recur (inc i) (if (pred (bytes/at arr i)) (inc c) c)) c))))

(defn summary
  "What a log shows: byte counts, and the subroutines found, most called
  first. `log` is {:flags :extra :calls :frames :header-size}, where
  `header-size` is the iNES header (and trainer) before the PRG ROM in the
  file, so routines can be given file offsets like ROM lenses use."
  [{:keys [flags extra calls frames header-size]}]
  (let [size (bytes/size flags)
        code (count-where flags #(pos? (bit-and % flag-code)))
        data (count-where flags #(pos? (bit-and % flag-data)))
        both (count-where flags #(= 3 (bit-and % 3)))
        entries (loop [i 0 out (transient [])]
                  (if (< i size)
                    (recur (inc i) (if (pos? (bit-and (bytes/at extra i) extra-subroutine)) (conj! out i) out))
                    (persistent! out)))
        routines (->> entries
                      (map (fn [i]
                             (let [e (bytes/at extra i)
                                   n (bytes/word-at calls i)]
                               {:prg-offset i
                                :offset (+ header-size i)
                                :bank (quot i 16384)
                                :cpu-addr (cpu-address (bytes/at flags i) i)
                                :calls n
                                :per-frame (if (pos? frames) (/ n frames) 0)
                                :runs-in (cond
                                           (and (pos? (bit-and e extra-nmi)) (pos? (bit-and e extra-main))) :both
                                           (pos? (bit-and e extra-nmi)) :nmi
                                           :else :main)})))
                      (sort-by (comp - :calls))
                      vec)]
    {:prg-size size
     :frames frames
     :code-bytes code
     :data-bytes data
     :code-and-data-bytes both
     :unseen-bytes (- size (count-where flags #(pos? (bit-and % 3))))
     :instructions (count-where extra #(pos? (bit-and % extra-instruction)))
     :jump-targets (count-where flags #(pos? (bit-and % flag-indirect-code)))
     :routines routines}))

(defn cdl-file
  "The log as a .cdl file for FCEUX: the PRG ROM's flags, then one byte per
  CHR ROM byte (left 0: CHR isn't recorded)."
  [flags chr-size]
  (let [out (bytes/make (+ (bytes/size flags) chr-size))]
    (bytes/copy-into! out flags 0)
    out))
