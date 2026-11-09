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
            [nes.emulator :as emu]
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
    (set! (.-frames state) (inc (.-frames state)))
    (when-let [m (.-monitor state)] (monitor/frame-done! m ppu))
    true))

(defn- step!
  "Run one CPU instruction (first finishing any DMA transfer that has the
  CPU paused). Returns true if the PPU finished a frame meanwhile.
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
          ;; The PPU runs alongside, inside the CPU's memory accesses.
          (let [cycles (.emulate cpu)]
            (.clockFrameCounter papu cycles (.-apuCatchupCycles cpu))
            (set! (.-apuCatchupCycles cpu) 0)
            (boolean (frame-ended? ppu state)))))
      (catch :default e
        (set! (.-crashed nes) true)
        (throw e)))))

(defn- reinstall-monitor!
  "jsnes builds a new CPU, PPU and mapper on reset and ROM load, so the
  bus monitor has to be put back on the new ones."
  [^js nes ^js state]
  (when-let [m (.-monitor state)]
    (monitor/install! nes m)))

(defrecord JsnesEmulator [^js nes ^js state]
  emu/Emulator
  (load-rom! [_ bytes]
    (.loadROM nes bytes)
    (set! (.-frameStarted state) false)
    (set! (.-frames state) 0)
    (reinstall-monitor! nes state))
  (press-reset! [_]
    (.reset nes)
    (set! (.-frameStarted state) false)
    (reinstall-monitor! nes state))
  (frame! [_]
    (loop [] (when-not (step! nes state) (recur))))
  (step-instruction! [_] (step! nes state))
  (button-down! [_ player button]
    (when-let [code (button-codes button)]
      (.buttonDown nes player code)))
  (button-up! [_ player button]
    (when-let [code (button-codes button)]
      (.buttonUp nes player code)))

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
       :frame (.-frames state)}))
  (oam [_] (.. nes -ppu -spriteMem))
  (ppu-flags [_]
    (let [^js ppu (.-ppu nes)]
      {:nmi? (= 1 (.-f_nmiOnVblank ppu))
       :sprite-height (if (= 1 (.-f_spriteSize ppu)) 16 8)
       :bg-table (.-f_bgPatternTable ppu)
       :sprite-table (.-f_spPatternTable ppu)
       :show-bg? (= 1 (.-f_bgVisibility ppu))
       :show-sprites? (= 1 (.-f_spVisibility ppu))}))

  emu/Poke
  (cpu-poke! [_ addr value]
    (let [mem (.. nes -cpu -mem)
          v (bit-and value 0xFF)]
      (cond
        (<= 0 addr 0x1FFF) (do (aset mem (bit-and addr 0x07FF) v) true)
        (<= 0x6000 addr 0x7FFF) (do (aset mem addr v) true)
        :else false)))

  emu/BusMonitor
  (monitor-bus! [_ on?]
    (cond
      (and on? (not (.-monitor state)))
      (let [m (monitor/create)]
        (set! (.-monitor state) m)
        (monitor/install! nes m))
      (and (not on?) (.-monitor state))
      (do (monitor/uninstall! nes)
          (set! (.-monitor state) nil))))
  (take-bus-activity! [_]
    (when-let [m (.-monitor state)]
      (monitor/take! m))))

(defn create
  "Make a jsnes-backed emulator.

  `on-frame` is called with a Uint32Array of 256x240 pixels each time a
  frame finishes. Each pixel is 0x00BBGGRR (blue in the high byte)."
  [{:keys [on-frame]}]
  (->JsnesEmulator
   (NES. #js {:onFrame on-frame
              ;; No sound yet - it needs an audio pipeline of its own.
              :emulateSound false})
   #js {:frameStarted false :frames 0 :monitor nil}))
