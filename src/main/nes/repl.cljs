(ns nes.repl
  "Tools for exploring the running NES from a ClojureScript REPL.

  Connect a REPL to the browser tab (with the page open):

    npx shadow-cljs cljs-repl app     ; or, from a Clojure REPL:
    (shadow.cljs.devtools.api/repl :app)

  The REPL starts in this namespace. A tour:

    (regs)                   ; CPU registers
    (rd 0x0010)              ; read one byte  -> 43
    (rd 0x0010 8)            ; read 8 bytes   -> [43 0 0 ...]
    (hexdump 0x0000)         ; print 128 bytes as hex
    (poke! 0x0010 0x2A)      ; write RAM
    (pause!) (step!) (step! 10) (frame!) (frame! 60) (resume!)
    (press! :start)          ; tap a button
    (dis)                    ; disassemble from the program counter

    (def a (snap))           ; the whole machine as a value
    ;; ...do something in the game...
    (def b (snap))
    (diff a b)               ; which bytes changed?
    (search [a b] (fn [before after] (= after (inc before))))

    (look)                   ; the game's state through its lens

  Reading never has side effects: `rd` on an I/O register like $2002
  returns nil instead of disturbing the hardware."
  (:require [clojure.string :as str]
            [nes.console :as console]
            [nes.cpu.disasm :as disasm]
            [nes.emulator :as emu]
            [nes.lens :as lens]
            ;; game-specific lenses register themselves when loaded
            [nes.lenses.croom]
            [nes.snapshot :as snapshot]
            [nes.state :refer [db]]))

(defn- emulator []
  (or (console/emulator-instance)
      (throw (ex-info "No ROM loaded yet. Open one in the page first." {}))))

(defn hex
  "`n` as a hex string: (hex 42) => \"$2A\". Widths: 2 digits under 256,
  otherwise 4."
  ([n] (hex n (if (< n 256) 2 4)))
  ([n width] (disasm/hex n width)))

;; ---------------------------------------------------------------------------
;; Memory and registers

(defn rd
  "Read the byte at CPU address `addr`, or `n` bytes from there.
  nil for addresses that can't be read without side effects (I/O)."
  ([addr] (emu/cpu-peek (emulator) addr))
  ([addr n] (mapv #(emu/cpu-peek (emulator) (+ addr %)) (range n))))

(defn rd-ppu
  "Read the byte at PPU address `addr`, or `n` bytes from there."
  ([addr] (emu/ppu-peek (emulator) addr))
  ([addr n] (mapv #(emu/ppu-peek (emulator) (+ addr %)) (range n))))

(defn poke!
  "Write `value` (and any more values, to the following addresses) into
  RAM at CPU address `addr`. Only RAM and cartridge RAM can be poked."
  [addr value & more]
  (let [e (emulator)]
    (doseq [[i v] (map-indexed vector (cons value more))
            :let [a (+ addr i)]]
      (when-not (emu/cpu-poke! e a v)
        (throw (ex-info (str "Can't poke " (hex a 4) ": only RAM ($0000-$1FFF) "
                             "and cartridge RAM ($6000-$7FFF) can be written")
                        {:addr a}))))
    (console/refresh-inspectors!)
    (rd addr (inc (count more)))))

(defn regs
  "The CPU's registers, with the status flags spelled out, and the next
  instruction."
  []
  (let [e (emulator)
        {:keys [p pc] :as cpu} (emu/cpu-state e)]
    (assoc cpu
           :flags (snapshot/decode-flags p)
           :next (disasm/text (disasm/decode #(emu/cpu-peek e %) pc)))))

;; ---------------------------------------------------------------------------
;; Running

(defn pause! [] (console/pause!) (:status @db))
(defn resume! [] (console/resume!) (:status @db))

(defn- paused!
  "Pause (if running) and run `f` on the emulator."
  [f]
  (when (= :running (:status @db)) (console/pause!))
  (when-not (console/while-paused! f)
    (throw (ex-info "Can't step: no ROM loaded, or the game has crashed." {:status (:status @db)}))))

(defn step!
  "Pause, and run `n` CPU instructions (default 1). Returns the registers."
  ([] (step! 1))
  ([n]
   (paused! (fn [e] (dotimes [_ n] (emu/step-instruction! e))))
   (regs)))

(defn frame!
  "Pause, and run until the end of the frame, `n` times (default 1).
  Returns the frame number."
  ([] (frame! 1))
  ([n]
   (paused! (fn [e] (dotimes [_ n] (emu/frame! e))))
   (:frame (emu/ppu-state (emulator)))))

(defn press!
  "Press `button` (:a :b :select :start :up :down :left :right) for
  `:frames` frames (default 4), then let go.

  If paused, runs those frames and stays paused (plus one frame after
  letting go, so the game sees the release). If running, lets go after
  that much time has passed."
  [button & {:keys [frames player] :or {frames 4 player 1}}]
  (let [e (emulator)]
    (emu/button-down! e player button)
    (if (= :running (:status @db))
      (js/setTimeout #(emu/button-up! e player button) (* frames console/frame-ms))
      (paused! (fn [e]
                 (dotimes [_ frames] (emu/frame! e))
                 (emu/button-up! e player button)
                 (emu/frame! e))))
    button))

;; ---------------------------------------------------------------------------
;; Printing

(defn dis
  "Print `n` instructions (default 12) disassembled from `addr` (default:
  the program counter)."
  ([] (dis (:pc (emu/cpu-state (emulator)))))
  ([addr] (dis addr 12))
  ([addr n]
   (let [e (emulator)
         pc (:pc (emu/cpu-state e))]
     (doseq [{:keys [addr bytes] :as instr} (disasm/disassemble #(emu/cpu-peek e %) addr n)]
       (println (str (if (= addr pc) "▶ " "  ")
                     (subs (hex addr 4) 1) "  "
                     (.padEnd (str/join " " (map #(if % (subs (hex % 2) 1) "??") bytes)) 9)
                     (disasm/text instr)))))))

(defn hexdump
  "Print `n` bytes (default 128) of CPU memory from `addr`, 16 per line.
  `--` marks I/O addresses that aren't read."
  ([addr] (hexdump addr 128))
  ([addr n]
   (doseq [row (range (quot addr 16) (quot (+ addr n 15) 16))
           :let [base (* 16 row)]]
     (println (str (subs (hex base 4) 1) "  "
                   (str/join " " (for [v (rd base 16)] (if v (subs (hex v 2) 1) "--"))))))))

;; ---------------------------------------------------------------------------
;; Snapshots and lenses

(defn snap
  "The whole machine's state right now, as an immutable value.
  See nes.snapshot."
  []
  (snapshot/capture (emulator) (assoc (:header (:rom @db)) :name (:name (:rom @db)))))

(def diff
  "Bytes that differ between two snapshots. See nes.snapshot/diff."
  snapshot/diff)

(def search
  "Addresses that changed as `pred` says across snapshots. See nes.snapshot/search."
  snapshot/search)

(defn print-diff
  "Print the bytes that differ between snapshots `a` and `b` as a table."
  [a b & opts]
  (doseq [{:keys [space addr before after delta]} (apply diff a b opts)]
    (println (str (name space) " " (hex addr 4) ": "
                  (hex before 2) " -> " (hex after 2)
                  "  (" (if (pos? delta) "+" "") delta ")"))))

(defn register-lens!
  "Use `lens` (see nes.lens) for the ROM called `rom-name`. Handy for
  trying out a lens from the REPL before putting it in a file."
  [rom-name lens]
  (lens/register! rom-name lens))

(defn current-lens
  "The lens registered for the loaded ROM, if any."
  []
  (lens/lens-for (get-in @db [:rom :name])))

(defn look
  "The game's state, read through its lens (or the default lens if none is
  registered). Pass a snapshot to look at that instead of the present."
  ([] (look (snap)))
  ([s]
   (if-let [l (current-lens)]
     (lens/interpret l s)
     (lens/default-lens s))))

(defn tap-snap!
  "Send a snapshot to `tap>` (for Portal or another data browser)."
  []
  (tap> (snap)))
