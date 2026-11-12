(ns nes.emulator.jsnes
  "An `Emulator` backed by jsnes (https://github.com/bfirsh/jsnes).

  jsnes does the emulating; this namespace translates between it and our
  protocols. The underlying jsnes object is kept on the record as `:nes`,
  so you can poke at its internals from the REPL, e.g.
  `(.. (:nes emu) -cpu -mem)` is the CPU's memory.

  jsnes's own `frame()` always runs a whole frame from the start, so to be
  able to stop after any single instruction we drive the CPU ourselves,
  with a copy of jsnes's frame loop (`NES.frame` in jsnes/src/nes.js)
  split into one-instruction steps."
  (:require ["jsnes" :refer [NES Controller]]
            [nes.breakpoints :as bp]
            [nes.emulator :as emu]
            [nes.emulator.jsnes-cljs-cpu :as cljs-cpu]
            [nes.emulator.jsnes-code-log :as code-log]
            [nes.emulator.jsnes-monitor :as monitor]))

(def ^:private button-codes
  {:a      (.-BUTTON_A Controller)
   :b      (.-BUTTON_B Controller)
   :select (.-BUTTON_SELECT Controller)
   :start  (.-BUTTON_START Controller)
   :up     (.-BUTTON_UP Controller)
   :down   (.-BUTTON_DOWN Controller)
   :left   (.-BUTTON_LEFT Controller)
   :right  (.-BUTTON_RIGHT Controller)})

(defn- start-frame!
  "What jsnes does at the start of each frame."
  [^js nes]
  (let [^js controllers (.-controllers nes)
        ^js c1 (aget controllers 1)
        ^js c2 (aget controllers 2)]
    (.clock c1)
    (.clock c2))
  (.startFrame (.-ppu nes)))

(defn- frame-ended?
  "True (once) if the PPU has just finished a frame."
  [^js ppu ^js state]
  (when (.-frameEnded ppu)
    (set! (.-frameEnded ppu) false)
    (set! (.-frameStarted state) false)
    (set! (.-scrollCaptured state) false)
    (set! (.-frames state) (inc (.-frames state)))
    (when-let [m (.-monitor state)] (monitor/frame-done! m ppu))
    (when-let [l (.-codeLog state)] (code-log/frame! l))
    true))

(defn- capture-scroll!
  "Once per frame, when the PPU starts drawing the first visible line,
  record the scroll position it's drawing with. (Games often change the
  scroll partway down the screen, e.g. for a status bar; this is the
  position for the top of the picture.)

  jsnes keeps the scroll in the PPU's internal 't' register, split into
  pieces: which nametable (regH, regV), which tile (regHT, regVT), and
  which pixel within the tile (regFH, regFV)."
  [^js ppu ^js state]
  ;; jsnes's scanline 21 is the first visible line
  (when (and (not (.-scrollCaptured state)) (>= (.-scanline ppu) 21))
    (set! (.-scrollCaptured state) true)
    (set! (.-scrollX state) (+ (* 256 (.-regH ppu)) (* 8 (.-regHT ppu)) (.-regFH ppu)))
    (set! (.-scrollY state) (+ (* 240 (.-regV ppu)) (* 8 (.-regVT ppu)) (.-regFV ppu)))))

(defn- exec-break?
  "Should we stop before running the instruction at `pc`? If so, records
  the hit. An instruction we just stopped at runs on the next try,
  rather than stopping again."
  [^js state pc]
  (let [^js flags (.-bpFlags state)]
    (if (and flags
             (pos? (bit-and (aget flags pc) bp/flag-exec))
             (not= pc (.-skipExec state)))
      (if-let [b (bp/matching (.-bps state) {:kind :exec :addr pc :pc pc})]
        (do (set! (.-hit state) {:kind :exec :addr pc :pc pc :breakpoint b})
            (set! (.-skipExec state) pc)
            true)
        false)
      (do (set! (.-skipExec state) -1)
          false))))

(defn- watch-fn
  "The function the memory-access wrappers call on every CPU read and
  write while read/write breakpoints are set: it records the first access
  in an instruction that sets one off, and `step!` stops afterwards."
  [^js state]
  (fn [addr v write?]
    (let [^js flags (.-bpFlags state)
          f (if write? bp/flag-write bp/flag-read)]
      (when (and flags
                 (pos? (bit-and (aget flags (bp/normalize addr)) f))
                 (nil? (.-hit state)))
        (let [hit {:kind (if write? :write :read) :addr addr :value v
                   :pc (.-instrPc state)}]
          (when-let [b (bp/matching (.-bps state) hit)]
            (set! (.-hit state) (assoc hit :breakpoint b))))))))

(defn- step!
  "Run one CPU instruction (first finishing any DMA transfer that has the
  CPU paused). Returns true if the PPU finished a frame meanwhile, or
  :break if a breakpoint went off (before the instruction, for execute
  breakpoints; after it, for reads and writes), with the hit in
  `state.hit`.
  `state` is a JS object holding `frameStarted` and `frames`."
  [^js nes ^js state]
  (when (.-crashed nes)
    (throw (js/Error. "Game has crashed. Reset or load a ROM to restart.")))
  (when-not (.-frameStarted state)
    (start-frame! nes)
    (set! (.-frameStarted state) true))
  (let [^js cpu (.-cpu nes)
        ^js ppu (.-ppu nes)
        ^js papu (.-papu nes)]
    (try
      (loop []
        (if (pos? (.-cyclesToHalt cpu))
          ;; DMA: the CPU is paused while sprite data is copied to the PPU.
          (let [chunk (min (.-cyclesToHalt cpu) 8)]
            (dotimes [_ chunk] (.advanceDots ppu 3))
            (.clockFrameCounter papu chunk)
            (set! (.-cyclesToHalt cpu) (- (.-cyclesToHalt cpu) chunk))
            (set! (.-_cpuCycleBase cpu) (+ (.-_cpuCycleBase cpu) chunk))
            (if (frame-ended? ppu state) true (recur)))
          (let [pc (bit-and (inc (.-REG_PC cpu)) 0xFFFF)]
            (if (exec-break? state pc)
              :break
              ;; The PPU runs alongside, inside the CPU's memory accesses.
              (let [_ (set! (.-instrPc state) pc)
                    cycles (.emulate cpu)]
                (.clockFrameCounter papu cycles (.-apuCatchupCycles cpu))
                (set! (.-apuCatchupCycles cpu) 0)
                (capture-scroll! ppu state)
                (let [ended? (frame-ended? ppu state)]
                  (if (.-hit state) :break (boolean ended?))))))))
      (catch :default e
        (set! (.-crashed nes) true)
        (throw e)))))

(defn- reinstall-monitor!
  "Put the memory-access wrappers on jsnes's CPU and mapper if the bus
  monitor or a read/write breakpoint needs them, and take them off if
  not. (jsnes builds a new CPU, PPU and mapper on reset and ROM load, so
  this is also called then.)"
  [^js nes ^js state]
  (if (or (.-monitor state) (.-watch state) (.-codeLog state))
    (monitor/install! nes state)
    (monitor/uninstall! nes))
  (if-let [l (.-codeLog state)]
    (code-log/install! l nes)
    (code-log/uninstall! nes))
  (if (.-cljsCpu state)
    (cljs-cpu/install! nes (.-cpuStats state))
    (cljs-cpu/uninstall! nes)))

(defrecord JsnesEmulator [^js nes ^js state]
  emu/Emulator
  (load-rom! [_ bytes]
    (.loadROM nes bytes)
    ;; our own copy, so patches don't touch the caller's array
    (set! (.-romFile state) (js/Uint8Array.from bytes))
    (set! (.-frameStarted state) false)
    (set! (.-frames state) 0)
    (reinstall-monitor! nes state))
  (press-reset! [_]
    (.reset nes)
    (set! (.-frameStarted state) false)
    (reinstall-monitor! nes state))
  (frame! [_]
    (set! (.-hit state) nil)
    (loop []
      (let [r (step! nes state)]
        (cond
          (= r :break) (let [hit (.-hit state)]
                         (set! (.-hit state) nil)
                         hit)
          r nil
          :else (recur)))))
  (step-instruction! [_]
    ;; single steps ignore breakpoints
    (let [flags (.-bpFlags state)]
      (set! (.-bpFlags state) nil)
      (try
        (true? (step! nes state))
        (finally
          (set! (.-bpFlags state) flags)
          (set! (.-skipExec state) -1)
          (set! (.-hit state) nil)))))
  (button-down! [_ player button]
    (when-let [code (button-codes button)]
      (.buttonDown nes player code)))
  (button-up! [_ player button]
    (when-let [code (button-codes button)]
      (.buttonUp nes player code)))
  (set-audio-sink! [_ f]
    ;; jsnes looks this up for every sample, so it can change at any time
    (set! (.. nes -opts -onAudioSample) f))

  (picture-so-far! [_]
    (let [^js ppu (.-ppu nes)
          ;; jsnes's scanline 21 is the first line of the picture
          line (- (.-scanline ppu) 21)]
      (when (<= 0 line 239)
        ;; jsnes puts the picture together lazily: it composes lines into
        ;; its buffer when the game changes a PPU setting, and at the end
        ;; of the frame. Compose the lines that are finished so far, the
        ;; same way jsnes's own triggerRendering does, but stopping before
        ;; the line in progress (triggerRendering would include it, half
        ;; drawn, and not draw it again).
        (let [done (dec line)
              last-done (.-lastRenderedScanline ppu)]
          (when (and (> done last-done) (not (.-_inRendering ppu)))
            (.renderFramePartially ppu (inc last-done) (- done last-done))
            (set! (.-lastRenderedScanline ppu) done)))
        {:pixels (.-buffer ppu) :line line})))

  emu/Inspect
  (ppu-memory [_] (.. nes -ppu -vramMem))
  (ppu-peek [_ addr]
    (let [^js ppu (.-ppu nes)
          addr (bit-and addr 0x3FFF)]
      (aget (.-vramMem ppu)
            (if (>= addr 0x3F00)
              ;; the 32 palette bytes repeat all the way up to $3FFF
              ;; (jsnes's mirror table only covers some of the repeats)
              (+ 0x3F00 (bit-and addr 0x1F))
              ;; nametables are mirrored according to the cartridge
              (aget (.-vramMirrorTable ppu) addr)))))
  (master-palette [_] (.. nes -ppu -palTable -curTable))
  (cpu-peek [_ addr]
    (let [mem (.. nes -cpu -mem)]
      (cond
        (< addr 0x2000) (aget mem (bit-and addr 0x07FF)) ; 2 KB RAM, mirrored 4x
        (< addr 0x4020) nil                               ; I/O registers
        (< addr 0x6000) nil                               ; expansion (open bus)
        :else (aget mem addr))))                          ; cartridge
  (cpu-state [_]
    (let [^js cpu (.-cpu nes)]
      {:a (.-REG_ACC cpu)
       :x (.-REG_X cpu)
       :y (.-REG_Y cpu)
       :sp (bit-and (.-REG_SP cpu) 0xFF)
       ;; jsnes keeps PC one less than the next instruction's address
       :pc (bit-and (inc (.-REG_PC cpu)) 0xFFFF)
       :p (.getStatus cpu)
       :cycles (.-_cpuCycleBase cpu)}))
  (ppu-state [_]
    (let [^js ppu (.-ppu nes)
          s (.-scanline ppu)]
      ;; jsnes numbers scanlines starting from vertical blank: its 0 is the
      ;; usual 241, its 20 the 'pre-render' line -1, its 21 visible line 0.
      {:scanline (if (= s 20) -1 (mod (+ s 241) 262))
       :dot (.-curX ppu)
       :frame (.-frames state)
       :scroll {:x (.-scrollX state) :y (.-scrollY state)}}))
  (oam [_] (.. nes -ppu -spriteMem))
  (ppu-flags [_]
    (let [^js ppu (.-ppu nes)]
      {:nmi? (= 1 (.-f_nmiOnVblank ppu))
       :sprite-height (if (= 1 (.-f_spriteSize ppu)) 16 8)
       :bg-table (.-f_bgPatternTable ppu)
       :sprite-table (.-f_spPatternTable ppu)
       :show-bg? (= 1 (.-f_bgVisibility ppu))
       :show-sprites? (= 1 (.-f_spVisibility ppu))}))
  (ppu-registers [_]
    ;; jsnes keeps PPUCTRL and PPUMASK as separate flags; put them back
    ;; together. (PPUCTRL bit 6 isn't kept, but it does nothing on an NES.)
    (let [^js ppu (.-ppu nes)]
      {:ctrl (bit-or (bit-shift-left (.-f_nmiOnVblank ppu) 7)
                     (bit-shift-left (.-f_spriteSize ppu) 5)
                     (bit-shift-left (.-f_bgPatternTable ppu) 4)
                     (bit-shift-left (.-f_spPatternTable ppu) 3)
                     (bit-shift-left (.-f_addrInc ppu) 2)
                     (.-f_nTblAddress ppu))
       :mask (bit-or (bit-shift-left (.-f_color ppu) 5)
                     (bit-shift-left (.-f_spVisibility ppu) 4)
                     (bit-shift-left (.-f_bgVisibility ppu) 3)
                     (bit-shift-left (.-f_spClipping ppu) 2)
                     (bit-shift-left (.-f_bgClipping ppu) 1)
                     (.-f_dispType ppu))
       ;; jsnes keeps the status register's bits in CPU memory at $2002
       :status (aget (.. nes -cpu -mem) 0x2002)}))
  (rom-file [_] (.-romFile state))
  (mirroring [_]
    ;; jsnes's numbering, from ROM.VERTICAL_MIRRORING etc. in jsnes/src/rom.js
    (case (.. nes -ppu -currentMirroring)
      0 :vertical
      1 :horizontal
      2 :four-screen
      (3 4 5 6) :single-screen
      :unknown))

  emu/Poke
  (cpu-poke! [_ addr value]
    (let [mem (.. nes -cpu -mem)
          v (bit-and value 0xFF)]
      (cond
        (<= 0 addr 0x1FFF) (do (aset mem (bit-and addr 0x07FF) v) true)
        (<= 0x6000 addr 0x7FFF) (do (aset mem addr v) true)
        :else false)))
  (rom-poke! [_ offset value]
    (let [^js rom (.-rom nes)
          prg-start (+ 16 (if (.-trainer rom) 512 0))
          p (- offset prg-start)]
      (if (and (<= 0 p) (< p (* 16384 (.-romCount rom))))
        (let [v (bit-and value 0xFF)
              ^js bank (aget (.-rom rom) (quot p 16384))
              in-bank (mod p 16384)
              chunk (- in-bank (mod in-bank 8192))
              ^js mem (.. nes -cpu -mem)]
          ;; jsnes copies a bank into the CPU's memory when the mapper
          ;; switches it in, so a bank that's switched in now has to be
          ;; changed there too. Find where by comparing contents, 8 KB at a
          ;; time (the smallest bank size jsnes's mappers use).
          (doseq [w [0x8000 0xA000 0xC000 0xE000]
                  :when (loop [i 0]
                          (cond (= i 8192) true
                                (not= (aget mem (+ w i)) (aget bank (+ chunk i))) false
                                :else (recur (inc i))))]
            (aset mem (+ w (mod in-bank 8192)) v))
          (aset bank in-bank v)
          (aset (.-romFile state) offset v)
          true)
        false)))

  (tile-poke! [_ addr value]
    (if-not (<= 0 addr 0x1FFF)
      false
      (let [^js ppu (.-ppu nes)
            ^js rom (.-rom nes)
            ^js vram (.-vramMem ppu)
            v (bit-and value 0xFF)
            half (bit-and addr 0x1000)      ; which pattern table
            in-half (bit-and addr 0x0FFF)
            ;; jsnes copies a 4 KB bank of CHR ROM into the PPU's memory
            ;; when the mapper switches it in. Find which bank is showing
            ;; in this pattern table by comparing contents (before the
            ;; change), as rom-poke! does for program ROM. Mappers that
            ;; switch smaller pieces won't match any, and that's fine.
            bank (first (filter (fn [b]
                                  (let [^js data (aget (.-vrom rom) b)]
                                    (loop [i 0]
                                      (cond (= i 4096) true
                                            (not= (aget vram (+ half i)) (aget data i)) false
                                            :else (recur (inc i))))))
                                (range (.-vromCount rom))))]
        ;; the PPU's memory, and the decoded copy of the tile it draws from
        (aset vram addr v)
        (.patternWrite ppu addr v)
        (if (nil? bank)
          :live
          (let [^js data (aget (.-vrom rom) bank)
                ^js tile (aget (aget (.-vromTile rom) bank) (bit-shift-right in-half 4))
                row (bit-and in-half 15)
                chr-start (+ 16 (if (.-trainer rom) 512 0) (* 16384 (.-romCount rom)))]
            (aset data in-half v)
            (if (< row 8)
              (.setScanline tile row v (aget data (+ in-half 8)))
              (.setScanline tile (- row 8) (aget data (- in-half 8)) v))
            (aset (.-romFile state) (+ chr-start (* 4096 bank) in-half) v)
            :rom)))))

  emu/BusMonitor
  (monitor-bus! [_ on?]
    (cond
      (and on? (not (.-monitor state)))
      (do (set! (.-monitor state) (monitor/create))
          (reinstall-monitor! nes state))
      (and (not on?) (.-monitor state))
      (do (set! (.-monitor state) nil)
          (reinstall-monitor! nes state))))
  (take-bus-activity! [_]
    (when-let [m (.-monitor state)]
      (monitor/take! m)))

  emu/CodeLog
  (log-code! [_ on?]
    (cond
      (and on? (not (.-codeLog state)))
      (do (set! (.-codeLog state) (or (.-codeLogKept state)
                                      (code-log/create (* 16384 (.. nes -rom -romCount)))))
          (reinstall-monitor! nes state))
      (and (not on?) (.-codeLog state))
      (do (set! (.-codeLogKept state) (.-codeLog state))
          (set! (.-codeLog state) nil)
          (reinstall-monitor! nes state))))
  (clear-code-log! [_]
    (set! (.-codeLogKept state) nil)
    (when (.-codeLog state)
      (set! (.-codeLog state) (code-log/create (* 16384 (.. nes -rom -romCount))))
      (reinstall-monitor! nes state)))
  (code-log [_]
    (when-let [^js l (or (.-codeLog state) (.-codeLogKept state))]
      (let [^js rom (.-rom nes)]
        {:flags (.-flags l)
         :extra (.-extra l)
         :calls (.-calls l)
         :frames (.-frames l)
         :header-size (+ 16 (if (.-trainer rom) 512 0))
         :chr-size (* 4096 (.-vromCount rom))
         :recording? (some? (.-codeLog state))})))

  emu/CpuChoice
  (use-cpu! [_ which]
    (set! (.-cljsCpu state) (= which :cljs))
    (set! (.-cpuStats state) #js {:cljs 0 :jsnes 0})
    (reinstall-monitor! nes state))
  (cpu-in-use [_] (if (.-cljsCpu state) :cljs :jsnes))
  (cpu-stats [_]
    (let [^js st (.-cpuStats state)] {:cljs (.-cljs st) :jsnes (.-jsnes st)}))

  emu/Debug
  (set-breakpoints! [_ bps]
    (let [bps (vec bps)
          flags (bp/flags bps)]
      (set! (.-bps state) bps)
      (set! (.-bpFlags state) flags)
      ;; only wrap memory accesses if a read/write breakpoint needs it
      (set! (.-watch state)
            (when (some #(and (:enabled? %) (not= :exec (:kind %))) bps)
              (watch-fn state)))
      (reinstall-monitor! nes state))))

(defn create
  "Make a jsnes-backed emulator.

  `on-frame` is called with a Uint32Array of 256x240 pixels each time a
  frame finishes. Each pixel is 0x00BBGGRR (blue in the high byte)."
  [{:keys [on-frame]}]
  (->JsnesEmulator
   (NES. #js {:onFrame on-frame
              :sampleRate emu/sample-rate})
   #js {:frameStarted false :frames 0 :monitor nil
        :scrollCaptured false :scrollX 0 :scrollY 0
        ;; breakpoints: see emu/Debug
        :bps [] :bpFlags nil :watch nil :hit nil :skipExec -1 :instrPc 0
        :romFile nil
        ;; the code/data log while recording, and kept after stopping
        :codeLog nil :codeLogKept nil
        ;; true to run the CPU with nes.cpu.core (see emu/CpuChoice)
        :cljsCpu false :cpuStats #js {:cljs 0 :jsnes 0}}))
