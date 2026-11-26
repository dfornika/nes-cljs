(ns nes.emulator.machine
  "An `Emulator` backed by NES.cljs's own console (nes.machine): our CPU,
  our PPU, mapper 0 cartridges, and no sound yet.

  It does what nes.emulator.jsnes does, through the same protocols, so the
  app can use either. A few things only jsnes has so far: sound, the bus
  monitor, the code/data log, and mappers other than 0."
  (:require [nes.bytes :as bytes]
            [nes.cartridge :as cart]
            [nes.cpu.core :as cpu]
            [nes.emulator :as emu]
            [nes.machine :as machine]
            [nes.ppu.core :as ppu]
            [nes.ppu.memory :as memory]
            [nes.ppu.registers :as r]
            [nes.rom :as rom]))

(defn- ppu-of [m] (:ppu m))

(defrecord MachineEmulator [^js state]
  ;; state: #js {:m the machine, :file the iNES file, :onFrame, :out (a picture buffer)}
  emu/Emulator
  (load-rom! [_ bytes]
    (let [file (js/Uint8Array.from bytes)]
      (set! (.-m state) (machine/create file))
      (set! (.-file state) file)
      (machine/set-breakpoints! (.-m state) (.-bps state))))
  (press-reset! [_] (machine/press-reset! (.-m state)))
  (frame! [_]
    (let [hit (machine/frame! (.-m state))]
      (when-not hit
        (when-let [f (.-onFrame state)] (f (machine/picture! (.-m state) (.-out state)))))
      hit))
  (step-instruction! [_]
    (let [done? (true? (machine/step! (.-m state) false))]
      (when done?
        (when-let [f (.-onFrame state)] (f (machine/picture! (.-m state) (.-out state)))))
      done?))
  (button-down! [_ player button] (machine/button! (.-m state) player button true))
  (button-up! [_ player button] (machine/button! (.-m state) player button false))
  (set-audio-sink! [_ _] nil)                ; no sound yet
  (picture-so-far! [_]
    (let [m (.-m state)
          line (ppu/line (ppu-of m))]
      (when (< line 240)
        {:pixels (machine/picture! m (.-out state)) :line line})))

  emu/Inspect
  (ppu-memory [this]
    (let [out (js/Uint8Array. 0x4000)]
      (dotimes [a 0x4000] (aset out a (emu/ppu-peek this a)))
      out))
  (ppu-peek [_ addr]
    (let [m (.-m state)
          addr (bit-and addr 0x3FFF)]
      (if (>= addr 0x3F00)
        (bytes/at (:palette (ppu-of m)) (r/palette-index addr))
        (memory/video-read (:cart m) addr))))
  (master-palette [_] machine/master-palette)
  (cpu-peek [_ addr] (machine/peek-cpu (.-m state) addr))
  (cpu-state [_]
    ;; the cycles the whole console has run (DMA included), not just the CPU's
    (assoc (cpu/registers (:cpu (.-m state)))
           :cycles (.-cycles ^js (:s (.-m state)))))
  (ppu-state [_]
    (let [m (.-m state)
          ^js s (:s m)
          {:keys [scanline dot]} (ppu/position (ppu-of m))]
      {:scanline (if (= scanline 261) -1 scanline)
       :dot dot
       :frame (.-frames s)
       :scroll {:x (.-scrollX s) :y (.-scrollY s)}}))
  (ppu-scroll [_]
    (select-keys (r/registers (ppu-of (.-m state))) [:v :t :x :w]))
  (oam [_] (:oam (ppu-of (.-m state))))
  (ppu-flags [_]
    (let [{:keys [ctrl mask]} (r/registers (ppu-of (.-m state)))]
      {:nmi? (bit-test ctrl 7)
       :sprite-height (if (bit-test ctrl 5) 16 8)
       :bg-table (if (bit-test ctrl 4) 1 0)
       :sprite-table (if (bit-test ctrl 3) 1 0)
       :show-bg? (bit-test mask 3)
       :show-sprites? (bit-test mask 4)}))
  (ppu-registers [_]
    (select-keys (r/registers (ppu-of (.-m state))) [:ctrl :mask :status]))
  (rom-file [_] (.-file state))
  (mirroring [_] (:mirroring (rom/parse-header (.-file state))))

  emu/Poke
  (cpu-poke! [_ addr value]
    (let [m (.-m state), v (bit-and value 0xFF)]
      (cond
        (<= 0 addr 0x1FFF) (do (bytes/put! (:ram m) (bit-and addr 0x7FF) v) true)
        (<= 0x6000 addr 0x7FFF) (do (cart/cpu-write! (:cart m) addr v) true)
        :else false)))
  (rom-poke! [_ offset value]
    (let [{:keys [prg-size trainer?]} (rom/parse-header (.-file state))
          p (- offset 16 (if trainer? 512 0))]
      (if (< -1 p prg-size)
        (do (bytes/put! (cart/prg (:cart (.-m state))) p (bit-and value 0xFF))
            (aset (.-file state) offset (bit-and value 0xFF))
            true)
        false)))
  (tile-poke! [_ addr value]
    (if-not (<= 0 addr 0x1FFF)
      false
      (let [{:keys [prg-size chr-ram? trainer?]} (rom/parse-header (.-file state))]
        (bytes/put! (cart/chr (:cart (.-m state))) addr (bit-and value 0xFF))
        (if chr-ram?
          :live
          (do (aset (.-file state) (+ 16 (if trainer? 512 0) prg-size addr) (bit-and value 0xFF))
              :rom)))))

  emu/BusMonitor
  (monitor-bus! [_ _] nil)                   ; not yet
  (take-bus-activity! [_] nil)

  emu/CodeLog
  (log-code! [_ _] nil)                      ; not yet
  (clear-code-log! [_] nil)
  (code-log [_] nil)

  emu/CpuChoice
  (use-cpu! [_ _] nil)                       ; always ours
  (cpu-in-use [_] :cljs)
  (cpu-stats [_] nil)

  emu/Debug
  (set-breakpoints! [_ bps]
    (set! (.-bps state) bps)
    (when-let [m (.-m state)] (machine/set-breakpoints! m bps))))

(defn create
  "Make an emulator backed by nes.machine. `on-frame` is called with a
  Uint32Array of 256x240 pixels (0x00BBGGRR) each time a frame finishes."
  [{:keys [on-frame]}]
  (->MachineEmulator #js {:m nil :file nil :onFrame on-frame :bps []
                          :out (js/Uint32Array. (* 256 240))}))

(defn match-jsnes!
  "Run as jsnes does where it differs from a real NES in timing, so the two
  keep in step for comparing: don't skip the odd frames' dot."
  [e]
  (let [^js st (:state e)]
    (ppu/skip-odd-dot! (:ppu (.-m st)) false)))

(defn lines-drawn
  "For each line of the last picture, 1 if drawing was on as it began, else
  0 (a byte array of 240)."
  [e]
  (let [^js st (:state e)]
    (:drawn (:ppu (.-m st)))))
