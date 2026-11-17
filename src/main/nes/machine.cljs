(ns nes.machine
  "A whole NES, in ClojureScript: our 6502 (nes.cpu.core), our PPU
  (nes.ppu.core), 2 KB of RAM, two controllers and a cartridge
  (nes.cartridge), wired together the way the console's circuit board
  wires them. No sound yet: the sound chip's registers are there to be
  written, and do nothing.

  ## The bus

  The CPU reaches everything through addresses:

    $0000-$1FFF  2 KB of RAM, four times over
    $2000-$3FFF  the PPU's 8 registers, over and over
    $4014        sprite DMA: write a page number, and the 256 bytes there
                 are copied into the PPU's sprite memory (513 or 514
                 cycles, with the CPU paused)
    $4016 $4017  the controllers
    $4000-$4017  the sound chip (not yet)
    $4020-$FFFF  the cartridge

  ## Keeping time

  The PPU runs 3 dots for every CPU cycle. Each time the CPU uses the bus
  (nes.cpu.core makes every read and write a real 6502 makes, dummy ones
  included), the PPU runs its 3 dots first, then the access happens. Cycles
  in an instruction that don't use the bus are made up at its end. (jsnes
  does the same.)

  ## NMI

  The PPU asks for an NMI at the start of vertical blank (when PPUCTRL bit
  7 is on). The CPU notices the request rise, checking every cycle, and
  takes the interrupt between instructions: after this one if the rise came
  before its last cycle, after the next one if not. That's the 6502's rule;
  the exact edge cases are for the test ROMs (docs/ppu_plan.md, step 6).

  A machine is a map of its parts, plus `:s`, a JS object of the numbers
  that change every cycle."
  (:require [gamedev-toolkit.palette :as palette]
            [nes.breakpoints :as bp]
            [nes.bytes :as bytes]
            [nes.cartridge :as cart]
            [nes.cpu.core :as cpu]
            [nes.ppu.core :as ppu]
            [nes.ppu.registers :as r]
            [nes.ppu.render :as render]))

(def master-palette
  "The 64 colors, as 32-bit 0x00BBGGRR numbers (the same as jsnes's)."
  (js/Uint32Array.from
   (clj->js (for [[red green blue] palette/rgb]
              (bit-or (bit-shift-left blue 16) (bit-shift-left green 8) red)))))

;; ---------------------------------------------------------------------------
;; Time passing, and NMI
;;
;; `bus` counts the CPU cycles so far in this instruction. The NMI input
;; is watched as the PPU runs: when the PPU's request rises, `raised` is set,
;; with the cycle it rose in and how many dots of that cycle's 3 were left.
;; At the end of the instruction, if at least 5 dots were left after the
;; rise, the NMI is taken before the next instruction; if not, after it.
;; (jsnes's rule: the CPU looks at its NMI input once, near the end of each
;; instruction, and a rise needs a moment to get there.)

(defn- nmi-check!
  "The PPU's NMI request may have changed: watch for it rising (and for it
  falling again too soon to count). `dots-left` is the dots left in this
  advance of the PPU after the change, or nil if a register access caused it."
  [{:keys [ppu]} ^js s dots-left]
  (let [level (r/nmi? ppu)]
    (cond
      (and level (not (.-nmiLevel s)))
      (do (set! (.-raised s) true)
          (set! (.-raisedAt s) (.-bus s))
          (when dots-left (set! (.-dotsLeft s) dots-left)))
      ;; reading PPUSTATUS right as vertical blank starts: the NMI is lost
      (and (not level) (.-nmiLevel s) (.-raised s))
      (let [diff (- (.-bus s) (.-raisedAt s))]
        (when (or (zero? diff) (and (= diff 1) (zero? (.-dotsLeft s))))
          (set! (.-raised s) false))))
    (set! (.-nmiLevel s) level)))

(defn- dots!
  "Run the PPU for `n` dots."
  [{:keys [ppu cart] :as m} ^js s n]
  (dotimes [i n]
    (when-let [event (ppu/tick! ppu cart)]
      (if (keyword-identical? event :vblank)
        (do (set! (.-frameDone s) true)
            (set! (.-frames s) (inc (.-frames s))))
        ;; the pre-render line: where the next picture will start, for the views
        (let [{:keys [x y]} (r/scroll-position (r/reg ppu r/T) (r/reg ppu r/X))]
          (set! (.-scrollX s) x)
          (set! (.-scrollY s) y)))
      (nmi-check! m s (- n i 1)))))

(defn- cycle!
  "The rest of a CPU cycle, after its access: 3 dots of the PPU."
  [m ^js s]
  (dots! m s 3))

;; ---------------------------------------------------------------------------
;; Controllers
;;
;; Writing 1 then 0 to $4016 makes both controllers take a snapshot of
;; their buttons; then each read of $4016 (or $4017, for the second) gives
;; the next button in bit 0: A, B, Select, Start, Up, Down, Left, Right.

(def ^:private button-bits
  {:a 0 :b 1 :select 2 :start 3 :up 4 :down 5 :left 6 :right 7})

(defn button!
  "Press (`down?` true) or let go of `button` on controller `player` (1 or 2)."
  [{:keys [^js s]} player button down?]
  (when-let [b (button-bits button)]
    (let [k (if (= player 2) "held2" "held1")
          held (aget s k)]
      (aset s k (if down? (bit-or held (bit-shift-left 1 b)) (bit-and held (bit-not (bit-shift-left 1 b))))))))

(defn- strobe! [^js s value]
  (when (or (bit-test value 0) (pos? (.-strobe s)))
    (set! (.-shift1 s) (.-held1 s))
    (set! (.-shift2 s) (.-held2 s)))
  (set! (.-strobe s) (bit-and value 1)))

(defn- read-pad! [^js s player]
  (let [k (if (= player 2) "shift2" "shift1")
        held (if (= player 2) (.-held2 s) (.-held1 s))]
    ;; the top bits are whatever was last on the bus: usually $40
    (bit-or 0x40
            (if (pos? (.-strobe s))
              (bit-and held 1)
              (let [sh (aget s k)]
                (aset s k (bit-or (bit-shift-right sh 1) 0x80))   ; after 8 reads: 1s
                (bit-and sh 1))))))

;; ---------------------------------------------------------------------------
;; Breakpoints on reads and writes

(defn- watch! [^js s addr value write?]
  (when-let [flags (.-bpFlags s)]
    (when (and (pos? (bit-and (bytes/at flags (bp/normalize addr)) (if write? bp/flag-write bp/flag-read)))
               (nil? (.-hit s)))
      (let [hit {:kind (if write? :write :read) :addr addr :value value :pc (.-instrPc s)}]
        (when-let [b (bp/matching (.-bps s) hit)]
          (set! (.-hit s) (assoc hit :breakpoint b)))))))

;; ---------------------------------------------------------------------------
;; The bus

(defn peek-cpu
  "The byte at CPU address `addr`, without side effects, or nil where
  reading has them (the PPU's registers, the controllers)."
  [{:keys [ram cart]} addr]
  (cond
    (< addr 0x2000) (bytes/at ram (bit-and addr 0x7FF))
    (< addr 0x4020) nil
    :else (cart/cpu-read cart addr)))

(defn- read! [{:keys [ram ppu cart] :as m} ^js s addr]
  (set! (.-bus s) (inc (.-bus s)))
  (let [v (cond
            (< addr 0x2000) (bytes/at ram (bit-and addr 0x7FF))
            (< addr 0x4000) (let [v (ppu/cpu-read! ppu cart addr)] (nmi-check! m s nil) v)
            (== addr 0x4016) (read-pad! s 1)
            (== addr 0x4017) (read-pad! s 2)
            (< addr 0x4020) (.-dataBus s)                     ; sound: not yet
            :else (or (cart/cpu-read cart addr) (.-dataBus s)))]
    (set! (.-dataBus s) v)
    (cycle! m s)
    (watch! s addr v false)
    v))

(defn- dma!
  "Copy page `page` ($xx00-$xxFF) into the PPU's sprite memory. The copy
  is done at once; the CPU is then held for the 513 cycles it takes (514
  if it started on an even cycle) before its next instruction."
  [{:keys [ppu] :as m} ^js s page]
  (let [oam (:oam ppu)
        start (r/reg ppu r/OAM-ADDR)]
    (dotimes [i 256]
      (bytes/put! oam (bit-and (+ start i) 0xFF) (or (peek-cpu m (+ (* page 256) i)) 0)))
    ;; the cycles so far, not counting this write's
    (set! (.-halt s) (if (even? (+ (.-cycles s) (dec (.-bus s)))) 514 513))))

(defn- write! [{:keys [ram ppu cart] :as m} ^js s addr v]
  (set! (.-bus s) (inc (.-bus s)))
  (set! (.-dataBus s) v)
  (cond
    (< addr 0x2000) (bytes/put! ram (bit-and addr 0x7FF) v)
    (< addr 0x4000) (do (ppu/cpu-write! ppu cart addr v) (nmi-check! m s nil))
    (== addr 0x4014) (dma! m s v)
    (== addr 0x4016) (strobe! s v)
    (< addr 0x4020) nil                                       ; sound: not yet
    :else (cart/cpu-write! cart addr v))
  (cycle! m s)
  (watch! s addr v true))

(defn- bus-for
  "The bus nes.cpu.core runs on (see its namespace docstring)."
  [m ^js s]
  (let [rd (fn [a] (read! m s a))]
    #js {:fetch rd
         :readOperand rd
         :read rd
         :read16 (fn [a] (let [lo (rd a)] (bit-or lo (bit-shift-left (rd (bit-and (inc a) 0xFFFF)) 8))))
         :write (fn [a v] (write! m s a v))
         :push (fn [sp v] (write! m s (bit-or 0x100 sp) v))
         :pull (fn [sp] (rd (bit-or 0x100 (bit-and (inc sp) 0xFF))))
         :readVector rd
         :tick (fn [] (set! (.-bus s) (inc (.-bus s))) (cycle! m s))
         :dmcDuringInstruction (fn [] false)}))

;; ---------------------------------------------------------------------------
;; Power, reset, and running

;; A record, so its parts are quick to get at
(defrecord Machine [cpu ppu ram cart s bus])

(defn create
  "A console with the cartridge in `file` (the bytes of an iNES file),
  switched on. Throws if the cartridge's mapper isn't supported.

  It starts as jsnes does, so the two keep in step: the PPU at the start of
  line 241 (but not starting vertical blank until it has been round once),
  and the CPU at the RESET address, 7 cycles in."
  [file]
  (let [s #js {:bus 0 :cycles 7 :halt 0 :frames 0 :frameDone false
               :nmiLevel false :raised false :raisedAt 0 :dotsLeft 0
               :nmiPending false :nmiNow false
               :dataBus 0 :strobe 0 :held1 0 :held2 0 :shift1 0 :shift2 0
               :scrollX 0 :scrollY 0
               :bpFlags nil :bps [] :hit nil :instrPc 0 :skipExec -1}
        m (map->Machine {:cpu (cpu/create) :ppu (ppu/create) :ram (bytes/make 0x800)
                         :cart (cart/load file) :s s})
        m (assoc m :bus (bus-for m s))]
    (ppu/set-position! (:ppu m) 241 0)
    (cpu/jump-to-reset-vector! (:cpu m) (:bus m))
    m))

(defn press-reset!
  "Press the reset button: the CPU goes to the address at $FFFC, and the
  PPU's settings are cleared."
  [{:keys [cpu bus ppu ^js s]}]
  (cpu/press-reset! cpu bus)
  (set! (.-cycles s) (+ (.-cycles s) 7))
  (doseq [reg [r/CTRL r/MASK r/W]] (r/set-reg! ppu reg 0))
  (doseq [k ["nmiPending" "nmiNow" "raised"]] (aset s k false))
  (set! (.-halt s) 0))

(defn set-breakpoints!
  "Stop at these breakpoints (see nes.breakpoints) from now on."
  [{:keys [^js s]} bps]
  (set! (.-bps s) (vec bps))
  (set! (.-bpFlags s) (bp/flags bps)))

(defn- exec-break? [^js s pc]
  (let [flags (.-bpFlags s)]
    (if (and flags (pos? (bit-and (bytes/at flags pc) bp/flag-exec)) (not= pc (.-skipExec s)))
      (if-let [b (bp/matching (.-bps s) {:kind :exec :addr pc :pc pc})]
        (do (set! (.-hit s) {:kind :exec :addr pc :pc pc :breakpoint b})
            (set! (.-skipExec s) pc)
            true)
        false)
      (do (set! (.-skipExec s) -1) false))))

(defn- result
  "What `step!` returns: :break, true if a picture was finished, or false."
  [^js s]
  (cond
    (.-hit s) :break
    (.-frameDone s) (do (set! (.-frameDone s) false) true)
    :else false))

(defn- nmi! [{:keys [cpu bus]} ^js s]
  (set! (.-bus s) 0)
  (cpu/nmi! cpu bus)
  (set! (.-cycles s) (+ (.-cycles s) 7)))

(defn step!
  "Run one instruction (and take an NMI after it, if one is due). Returns
  true if the PPU finished a picture meanwhile, :break if a breakpoint went
  off (see `take-hit!`), otherwise false. With `breakpoints?` false,
  breakpoints are ignored.

  Two things can take a step instead of an instruction: the CPU being held
  for sprite DMA (the step ends early if a picture is finished meanwhile),
  and an NMI that's due straight away."
  ([m] (step! m true))
  ([{:keys [cpu bus ^js s] :as m} breakpoints?]
   ;; held for DMA
   (loop []
     (when (and (pos? (.-halt s)) (not (.-frameDone s)))
       (set! (.-halt s) (dec (.-halt s)))
       (set! (.-cycles s) (inc (.-cycles s)))
       (set! (.-bus s) (inc (.-bus s)))
       (cycle! m s)
       (recur)))
   (cond
     (.-frameDone s) (result s)

     (.-nmiNow s)
     (do (set! (.-nmiNow s) false)
         (set! (.-nmiPending s) false)
         (set! (.-raised s) false)
         (nmi! m s)
         (result s))

     :else
     (let [pc (.-pc cpu)]
       (if (and breakpoints? (exec-break? s pc))
         :break
         (do
           (set! (.-instrPc s) pc)
           ;; an NMI that rose too late in the last instruction: after this one
           (when (.-raised s)
             (set! (.-nmiPending s) true)
             (set! (.-raised s) false))
           (set! (.-bus s) 0)
           (set! (.-dotsLeft s) 0)
           (let [c (or (cpu/step! cpu bus)
                       (throw (ex-info (str "The CPU has stopped: the instruction at $" (.toString pc 16)
                                            " jams a real 6502. Reset or load a ROM to start again.")
                                       {:pc pc})))]
             ;; cycles that didn't use the bus
             (when (< (.-bus s) c)
               (let [missing (- c (.-bus s))]
                 (set! (.-bus s) c)
                 (dots! m s (* 3 missing))))
             (set! (.-cycles s) (+ (.-cycles s) c))
             ;; an NMI that rose in this instruction, early enough: next
             (when (and (.-raised s) (>= (+ (* 3 (- (.-bus s) (.-raisedAt s))) (.-dotsLeft s)) 5))
               (set! (.-nmiNow s) true)
               (set! (.-raised s) false))
             (when (.-nmiPending s)
               (set! (.-nmiPending s) false)
               (nmi! m s)))
           (when-not breakpoints? (set! (.-hit s) nil))
           (result s)))))))

(defn take-hit!
  "The breakpoint hit that stopped the last `step!`, and forget it."
  [{:keys [^js s]}]
  (let [h (.-hit s)] (set! (.-hit s) nil) h))

(defn frame!
  "Run until the PPU finishes a picture. Returns nil, or a breakpoint hit
  if one stopped it first."
  [m]
  (loop []
    (case (step! m)
      true nil
      :break (take-hit! m)
      (recur))))

(defn picture!
  "The current picture, in colors (0x00BBGGRR), into `out` (a Uint32Array
  of 256x240)."
  [{:keys [ppu]} out]
  (render/colors! out (:picture ppu) master-palette))
