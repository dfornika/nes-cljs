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
    (pause!) (step!) (step! 10) (line!) (line! 8) (frame!) (frame! 60) (resume!)
    (speed! 0.25)            ; slow motion (or 2, 4 to fast-forward)
    (press! :start)          ; tap a button
    (dis)                    ; disassemble from the program counter

    (def a (snap))           ; the whole machine as a value
    ;; ...do something in the game...
    (def b (snap))
    (diff a b)               ; which bytes changed?
    (search [a b] (fn [before after] (= after (inc before))))

    (break! :write 0x003F)   ; pause when the game writes $003F
    (break! 0xC123)          ; pause before running the instruction at $C123
    (why) (dis) (continue!)  ; why it stopped, the code there, and carry on
    (breaks) (unbreak!)      ; list, or remove all, breakpoints

    (look)                   ; the game's state through its lenses
    (lenses)                 ; which lenses the loaded game has
    (lens! \"Player\" {:lives [:u8 0x075A]})  ; try out a lens; it shows in the Lens tab
    (lens-source \"Player\")   ; print it as code to paste into a file

    (play! (hello/build))    ; build a game from ClojureScript (src/main/games/hello.cljc) and run it
    (play! (catch/build))    ; a whole game: src/main/games/catch.cljc
    (save! (catch/build))    ; ...downloaded as a .nes file
    (save-edn!)              ; the loaded game's program, as EDN text you can read
    (tile 0x2A)              ; a tile, as text: [\"..1111..\" ...]
    (tile! 0x2A [\"..1111..\" ...])   ; ...and drawn from text (or paint in the Tiles tab)
    (syms)                   ; a game built here has names: its labels and variables
    (rd :player/x)           ; ...which work wherever an address does
    (break! :write :color) (dis :main)
    (asm/assemble [[:lda [:imm 1]] [:rts]])   ; the assembler by itself

  Reading never has side effects: `rd` on an I/O register like $2002
  returns nil instead of disturbing the hardware."
  (:require [clojure.string :as str]
            [gamedev-toolkit.asm :as asm]
            [games.catch :as catch]
            [games.hello :as hello]
            [nes.console :as console]
            [nes.breakpoints :as bp]
            [nes.chr :as chr]
            [nes.code-log :as code-log]
            [nes.cpu.disasm :as disasm]
            [nes.debug-info :as debug-info]
            [nes.emulator :as emu]
            [nes.lens :as lens]
            ;; game-specific lenses register themselves when loaded
            [nes.lenses.croom]
            [nes.lenses.megaman2]
            [nes.lenses.zelda]
            [nes.snapshot :as snapshot]
            [nes.state :refer [db]]
            [nes.text-table :as text-table]
            [nes.workbench :as workbench]))

(defn- emulator []
  (or (console/emulator-instance)
      (throw (ex-info "No ROM loaded yet. Open one in the page first." {}))))

(defn hex
  "`n` as a hex string: (hex 42) => \"$2A\". Widths: 2 digits under 256,
  otherwise 4."
  ([n] (hex n (if (< n 256) 2 4)))
  ([n width] (disasm/hex n width)))

;; ---------------------------------------------------------------------------
;; Names
;;
;; A game built here (see `play!`) has names for its variables and the
;; labels in its code. Wherever these tools take a CPU address, they take
;; a name too: (rd :player/x), (break! :main), (dis :nmi).

(defn- symbol-table []
  (debug-info/table-for (get-in @db [:rom :sha1])))

(defn sym
  "The address of a name in the loaded game: (sym :player/x) => 4.
  Numbers come back as they are."
  [x]
  (if (number? x)
    x
    (or (debug-info/address (symbol-table) x)
        (throw (ex-info (str "The loaded game has nothing called " (pr-str x)
                             (when-not (symbol-table) ". (Only games built here have names: see play!)"))
                        {:name x})))))

(defn syms
  "The loaded game's names and their addresses (as hex), sorted by
  address. nil for a game with none."
  []
  (when-let [t (symbol-table)]
    (mapv (fn [[n a]] [n (hex a 4)]) (sort-by (juxt val key) (:addresses t)))))

;; ---------------------------------------------------------------------------
;; Memory and registers

(defn rd
  "Read the byte at CPU address `addr` (a number or a name), or `n` bytes
  from there. nil for addresses that can't be read without side effects
  (I/O)."
  ([addr] (emu/cpu-peek (emulator) (sym addr)))
  ([addr n] (let [addr (sym addr)] (mapv #(emu/cpu-peek (emulator) (+ addr %)) (range n)))))

(defn rd-ppu
  "Read the byte at PPU address `addr`, or `n` bytes from there."
  ([addr] (emu/ppu-peek (emulator) addr))
  ([addr n] (mapv #(emu/ppu-peek (emulator) (+ addr %)) (range n))))

(defn poke!
  "Write `value` (and any more values, to the following addresses) into
  RAM at CPU address `addr`. Only RAM and cartridge RAM can be poked."
  [addr value & more]
  (let [e (emulator)
        addr (sym addr)]
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
           :next (disasm/text (disasm/decode #(emu/cpu-peek e %) pc)
                              (debug-info/namer (symbol-table))))))

;; ---------------------------------------------------------------------------
;; Running

(defn pause! [] (console/pause!) (:status @db))
(defn resume! [] (console/resume!) (:status @db))

(defn speed!
  "Run at `s` times normal speed: one of 0.25, 0.5, 1, 2, 4."
  [s]
  (console/set-speed! s))

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

(defn break!
  "Pause the game when something happens:

    (break! 0xC123)                  ; before running the instruction at $C123
    (break! :write 0x003F)           ; when $003F is written
    (break! :read 0x2002)            ; when $2002 is read
    (break! :access [0x0300 0x033F]) ; on any read or write in a range
    (break! :write 0x003F {:value 0}) ; only when 0 is written
    (break! :write 0x0055 {:when (fn [{:keys [value]}] (> value 200))})
    (break! :main) (break! :write :player/x)   ; names, in games that have them

  Returns the breakpoint's id. While running, the game pauses there; use
  (why) to see what happened and (continue!) to carry on."
  ([addr] (break! :exec addr))
  ([kind addr] (break! kind addr {}))
  ([kind addr opts]
   (assert (some #{kind} bp/kinds) (str "kind is one of " bp/kinds))
   (let [[lo hi] (map #(some-> % sym) (if (vector? addr) addr [addr nil]))]
     (console/add-breakpoint! (cond-> (merge (select-keys opts [:value :when]) {:kind kind :addr lo})
                                hi (assoc :end hi))))))

(defn breaks
  "The breakpoints, described."
  []
  (mapv (fn [{:keys [id enabled? hits] :as b}]
          (cond-> {:id id :breakpoint (bp/describe b) :hits hits}
            (not enabled?) (assoc :enabled? false)))
        (console/breakpoints)))

(defn unbreak!
  "Remove breakpoint `id`, or with no argument, all of them."
  ([] (console/clear-breakpoints!) [])
  ([id] (console/remove-breakpoint! id) (breaks)))

(defn why
  "Why the game last stopped at a breakpoint: the hit, described, or nil."
  []
  (when-let [hit (:break @db)]
    (-> hit
        (update :breakpoint #(select-keys % [:id :kind :addr :end :value]))
        (assoc :reason (bp/describe-hit hit)))))

(defn continue!
  "Carry on running after a breakpoint (or a pause)."
  []
  (console/resume!)
  :running)

(defn line!
  "Pause, and run until the PPU starts the next scanline, `n` times
  (default 1). The screen shows the picture drawn so far. Returns where
  the PPU is."
  ([] (line! 1))
  ([n]
   (paused! (fn [e] (dotimes [_ n] (console/step-scanline-on! e))))
   (select-keys (emu/ppu-state (emulator)) [:frame :scanline :dot])))

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
  "Print `n` instructions (default 12) disassembled from `addr` (a number
  or a name; default: the program counter). Addresses with names show
  them."
  ([] (dis (:pc (emu/cpu-state (emulator)))))
  ([addr] (dis addr 12))
  ([addr n]
   (let [e (emulator)
         pc (:pc (emu/cpu-state e))
         table (symbol-table)
         name-of (debug-info/namer table)]
     (doseq [{:keys [addr bytes] :as instr} (disasm/disassemble #(emu/cpu-peek e %) (sym addr) n)]
       (when-let [label (debug-info/label-at table addr)]
         (println (str label ":")))
       (println (str (if (= addr pc) "▶ " "  ")
                     (subs (hex addr 4) 1) "  "
                     (.padEnd (str/join " " (map #(if % (subs (hex % 2) 1) "??") bytes)) 9)
                     (disasm/text instr name-of)))))))

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
  (let [{:keys [header name sha1]} (:rom @db)]
    (snapshot/capture (emulator) (assoc header :name name :sha1 sha1))))

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

(defn rom
  "The loaded ROM: its file name, SHA-1 (which lenses are matched by) and
  header."
  []
  (:rom @db))

(defn lenses
  "The names of the lenses for the loaded ROM: its own, then ones for any
  game."
  []
  (mapv :name (lens/lenses-for (rom))))

(defn- find-lens [lens-name]
  (or (some #(when (= lens-name (:name %)) %) (lens/lenses-for (rom)))
      (throw (ex-info (str "No lens called " (pr-str lens-name) " for this ROM. "
                           "There's: " (pr-str (lenses)))
                      {:lens lens-name}))))

(defn register-lens!
  "Add a lens: a map with :rom, :name and :fields (see nes.lens), or the
  older (register-lens! rom-name fields)."
  ([entry] (lens/register! entry))
  ([rom-name fields] (lens/register! rom-name fields)))

(defn lens!
  "Try out a lens on the loaded game: register `fields` (see nes.lens) as
  a lens called `lens-name` (default \"REPL\") for this ROM, by its hash,
  and show it in the Lens tab. Run it again with changes to update it."
  ([fields] (lens! "REPL" fields))
  ([lens-name fields] (lens! lens-name nil fields))
  ([lens-name doc fields]
   (let [{:keys [sha1 name]} (or (rom) (throw (ex-info "No ROM loaded yet." {})))]
     (lens/register! (cond-> {:rom (or sha1 name) :name lens-name :fields fields}
                       doc (assoc :doc doc)))
     (swap! db assoc :lens-name lens-name :inspector-tab :lens)
     lens-name)))

(defn current-lens
  "The fields of the lens shown in the Lens tab (or the loaded ROM's
  first lens)."
  []
  (let [ls (lens/lenses-for (rom))]
    (:fields (or (some #(when (= (:lens-name @db) (:name %)) %) ls)
                 (first ls)))))

(defn look
  "The game's state through its lenses: {lens-name values}. With a lens
  name, just that lens's values. Pass a snapshot as the last argument to
  look at that moment instead of the present."
  ([] (look (snap)))
  ([lens-name-or-snapshot]
   (if (string? lens-name-or-snapshot)
     (look lens-name-or-snapshot (snap))
     (into {} (for [{:keys [name fields]} (lens/lenses-for (rom))]
                [name (lens/interpret fields lens-name-or-snapshot)]))))
  ([lens-name s]
   (lens/interpret (:fields (find-lens lens-name)) s)))

(defn lens-source
  "Print lens `lens-name` (default: the one in the Lens tab) as a
  `register!` form, ready to paste into a file under src/main/nes/lenses."
  ([] (lens-source (or (:lens-name @db) (first (lenses)))))
  ([lens-name]
   (println (lens/source (find-lens lens-name) (:sha1 (rom))))))

(defn screen-text
  "The text on screen now, read through text table `table-name` (one
  registered for this game, see nes.text-table), as a vector of lines.
  With no name, the first table registered for the game."
  ([] (screen-text (:name (first (text-table/tables-for (rom))))))
  ([table-name]
   (let [t (or (some #(when (= table-name (:name %)) (:table %)) (text-table/tables-for (rom)))
               (throw (ex-info (str "No text table called " (pr-str table-name)) {})))
         {:keys [x y]} (:scroll (emu/ppu-state (emulator)))]
     (text-table/visible-lines t #(emu/ppu-peek (emulator) %) x y))))

(defn rom-poke!
  "Change byte `offset` of the cartridge (an offset in the .nes file,
  counting its 16-byte header) in the running game: a live ROM hack."
  [offset value]
  (console/write! [{:space :rom :addr offset :value value}]))

(defn record-code!
  "Start (or with false, stop) recording the code/data log: which ROM bytes
  run as code, which are data, and where subroutines start. See the Code
  tab, and `code-summary`."
  ([] (record-code! true))
  ([on?] (console/log-code! on?) on?))

(defn code-summary
  "What the code/data log has found so far: byte counts, and the `n`
  (default 10) most-called subroutines."
  ([] (code-summary 10))
  ([n]
   (when-let [log (console/code-log)]
     (-> (code-log/summary log)
         (update :routines #(vec (take n %)))))))

(defn tap-snap!
  "Send a snapshot to `tap>` (for Portal or another data browser)."
  []
  (tap> (snap)))

;; ---------------------------------------------------------------------------
;; Games built here

(defn play!
  "Put a cartridge built with gamedev-toolkit into the console and start
  it: (play! (hello/build)). Takes what a game's `build` returns (a map
  with :rom, the .nes file's bytes, and maybe :name), or just the bytes.
  The game's variables become a lens (see `look`), and its names work as
  addresses (see `syms`). Returns the ROM's size in bytes."
  [built]
  (workbench/play! built))

(defn save!
  "Download a built game as a .nes file, to play in any emulator:
  (save! (catch/build))"
  [built]
  (workbench/save-nes! built))

(defn save-edn!
  "Download the loaded cartridge as EDN text (see gamedev-toolkit.rom-edn).
  With no argument: the program, as instructions and tile drawings, if the
  game was built here, otherwise the bytes as hex. Or ask for one:
  (save-edn! :source) or (save-edn! :container)."
  ([] (or (save-edn! :source) (save-edn! :container)))
  ([kind] (workbench/save-edn! (:rom @db) kind)))

;; ---------------------------------------------------------------------------
;; Tiles

(defn tile
  "Tile `n` (0-255) of pattern table `table` (0 or 1; default 0), as the
  PPU sees it now, as 8 strings of . 1 2 3: the way gamedev-toolkit.chr
  draws tiles."
  ([n] (tile 0 n))
  ([table n]
   (chr/tile-text (emu/ppu-memory (emulator)) (chr/tile-address table n))))

(defn tile!
  "Redraw tile `n` of pattern table `table` (default 0) from 8 strings of
  . 1 2 3, in the running game. Returns :rom if the change went into the
  cartridge too (so it's in the ROM you download), or :live if only into
  the PPU's memory (games that keep tiles in RAM or switch banks)."
  ([n rows] (tile! 0 n rows))
  ([table n rows]
   (emulator)
   (console/poke-tiles! (chr/text-writes (chr/tile-address table n) rows))))
