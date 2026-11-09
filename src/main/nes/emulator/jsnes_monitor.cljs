(ns nes.emulator.jsnes-monitor
  "Watching the CPU's bus inside jsnes.

  jsnes has no hooks for observing memory accesses, so while monitoring is
  on we wrap the methods the CPU uses to reach memory (on the live jsnes
  objects, not on the classes) with versions that also count and record
  each access. Turning monitoring off deletes the wrappers, so the
  original methods take over again.

  What we can see:
  - every CPU read and write, classified by which chip answered
    (RAM, PPU registers, sound/IO, controllers, cartridge ROM/RAM, mapper)
  - CPU reads/writes of PPU memory through PPUDATA ($2007)
  - NMI interrupts and sprite DMA

  What we can't see: jsnes draws the picture without going through its PPU
  memory-access code, so the PPU's own fetches of tiles and nametables
  while drawing are ESTIMATED from whether rendering was switched on.")

(def ring-size
  "How many of the most recent bus accesses to remember."
  64)

(def ^:private max-events 400)

(defn create
  "A fresh monitor: counters, an event log, and a ring of recent accesses."
  []
  #js {:counts #js {}
       :events #js []
       :accA (js/Uint16Array. ring-size)
       :accV (js/Uint8Array. ring-size)
       :accW (js/Uint8Array. ring-size)
       :accN 0
       :accTaken 0})

(defn- bump! [^js m k n]
  (let [^js c (.-counts m)]
    (aset c k (+ (or (aget c k) 0) n))))

(defn- remember! [^js m addr v write?]
  (let [i (mod (.-accN m) ring-size)]
    (aset (.-accA m) i addr)
    (aset (.-accV m) i v)
    (aset (.-accW m) i (if write? 1 0))
    (set! (.-accN m) (inc (.-accN m)))))

(defn- log-event! [^js m ^js ppu addr v write?]
  (let [^js events (.-events m)
        ^js last-event (when (pos? (.-length events)) (aget events (dec (.-length events))))]
    (if (and last-event
             (= addr (.-addr last-event))
             (= write? (.-write last-event))
             (= v (.-value last-event)))
      ;; The same access again (like a loop polling PPUSTATUS): count it
      ;; instead of logging it again.
      (set! (.-repeat last-event) (inc (.-repeat last-event)))
      (do
        (.push events #js {:addr addr :value v :write write? :repeat 1
                           :scanline (.-scanline ppu)})
        (when (> (.-length events) max-events) (.shift events))))))

(defn- ram-access! [^js m addr v write?]
  (bump! m (if write? "ramW" "ramR") 1)
  (remember! m addr v write?))

(defn- ppudata-target
  "Which part of PPU memory a PPUDATA ($2007) access is about to touch."
  [^js ppu]
  (let [a (bit-and (.-vramAddress ppu) 0x3FFF)]
    (cond (< a 0x2000) "chr"
          (< a 0x3F00) "vram"
          :else "pal")))

(defn- bus-access!
  "Count and record an access that went through the cartridge connector
  or to an I/O register (anything at $2000 and up)."
  [^js m ^js ppu addr v write?]
  (remember! m addr v write?)
  (cond
    (< addr 0x2000) (bump! m (if write? "ramW" "ramR") 1)
    (< addr 0x4000) (do (bump! m (if write? "ppuW" "ppuR") 1)
                        (log-event! m ppu addr v write?))
    (= addr 0x4014) (do (bump! m "dma" 256)
                        (log-event! m ppu addr v write?))
    (or (= addr 0x4016) (and (= addr 0x4017) (not write?)))
    (do (bump! m (if write? "padW" "padR") 1)
        (log-event! m ppu addr v write?))
    (< addr 0x4020) (do (bump! m (if write? "apuW" "apuR") 1)
                        (log-event! m ppu addr v write?))
    (< addr 0x6000) nil
    (< addr 0x8000) (bump! m (if write? "cartW" "cartR") 1)
    write? (do (bump! m "mapperW" 1)
               (log-event! m ppu addr v write?))
    :else (bump! m "prgR" 1)))

(defn- ppudata? [addr]
  (and (<= 0x2000 addr 0x3FFF) (= 7 (bit-and addr 7))))

(defn install!
  "Wrap `nes`'s (a jsnes NES) memory-access methods so that every access
  is reported to whichever of these `state` (a JS object) holds:

  - `state.monitor`: a monitor from `create`, which counts and records
    accesses
  - `state.watch`: a function (watch addr value write?), called for every
    CPU read and write (used for read/write breakpoints)

  Either can be added or removed later without reinstalling. Call this
  again after jsnes replaces its CPU or mapper (on reset and ROM load)."
  [^js nes ^js state]
  (let [^js cpu (.-cpu nes)
        ^js ppu (.-ppu nes)
        ^js mmap (.-mmap nes)
        proto (.getPrototypeOf js/Object cpu)
        orig (fn [k] (aget proto k))
        load (orig "load")
        load-direct (orig "loadDirect")
        load16 (orig "load16bit")
        write (orig "write")
        push (orig "push")
        pull (orig "pull")
        nmi (orig "doNonMaskableInterrupt")
        ram! (fn [addr v write?]
               (when-let [m (.-monitor state)]
                 (when (< addr 0x2000) (ram-access! m addr v write?))))
        watch! (fn [addr v write?]
                 (when-let [f (.-watch state)] (f addr v write?)))]
    ;; Every CPU access goes through these. (RAM accesses are counted here;
    ;; the monitor counts everything else in the mapper's methods, below.)
    (set! (.-load cpu)
          (fn [addr]
            (let [v (.call load cpu addr)]
              (ram! addr v false)
              (watch! addr v false)
              v)))
    (set! (.-loadDirect cpu)
          (fn [addr]
            (let [v (.call load-direct cpu addr)]
              (ram! addr v false)
              (watch! addr v false)
              v)))
    (set! (.-load16bit cpu)
          (fn [addr]
            (let [v (.call load16 cpu addr)
                  lo (bit-and v 0xFF)
                  hi (bit-shift-right v 8)]
              (when (< addr 0x1FFF)
                (ram! addr lo false)
                (ram! (inc addr) hi false))
              (watch! addr lo false)
              (watch! (bit-and (inc addr) 0xFFFF) hi false)
              v)))
    (set! (.-write cpu)
          (fn [addr v]
            (ram! addr v true)
            (.call write cpu addr v)
            (watch! addr v true)))
    (set! (.-push cpu)
          (fn [v]
            (let [addr (bit-or 0x100 (bit-and (.-REG_SP cpu) 0xFF))]
              (ram! addr v true)
              (.call push cpu v)
              (watch! addr v true))))
    (set! (.-pull cpu)
          (fn []
            (let [v (.call pull cpu)
                  addr (bit-or 0x100 (bit-and (.-REG_SP cpu) 0xFF))]
              (ram! addr v false)
              (watch! addr v false)
              v)))
    (set! (.-doNonMaskableInterrupt cpu)
          (fn [status]
            (when-let [m (.-monitor state)] (bump! m "nmi" 1))
            (.call nmi cpu status)))
    (when mmap
      (let [mproto (.getPrototypeOf js/Object mmap)
            mload (aget mproto "load")
            mwrite (aget mproto "write")]
        (set! (.-load mmap)
              (fn [addr]
                (if-let [m (.-monitor state)]
                  (let [target (when (ppudata? addr) (ppudata-target ppu))
                        v (.call mload mmap addr)]
                    (when target (bump! m target 1))
                    (bus-access! m ppu addr v false)
                    v)
                  (.call mload mmap addr))))
        (set! (.-write mmap)
              (fn [addr v]
                (when-let [m (.-monitor state)]
                  (when (ppudata? addr) (bump! m (ppudata-target ppu) 1))
                  (bus-access! m ppu addr v true))
                (.call mwrite mmap addr v)))))))

(defn uninstall!
  "Remove the wrappers so jsnes's own methods take over."
  [^js nes]
  (let [^js cpu (.-cpu nes)
        ^js mmap (.-mmap nes)]
    (doseq [k ["load" "loadDirect" "load16bit" "write" "push" "pull" "doNonMaskableInterrupt"]]
      (js-delete cpu k))
    (when mmap
      (js-delete mmap "load")
      (js-delete mmap "write"))))

(defn frame-done!
  "Called at the end of each frame: estimate the PPU's own memory fetches
  for the frame, from whether the background and sprites were switched on.
  Per visible line the PPU fetches, for each of 34 background tiles, a
  nametable byte, an attribute byte and two pattern bytes; and for 8
  sprites, two pattern bytes each."
  [^js m ^js ppu]
  (bump! m "frames" 1)
  (when (= 1 (.-f_bgVisibility ppu))
    (bump! m "vramRender" (* 240 34 2))
    (bump! m "chrRender" (* 240 34 2)))
  (when (= 1 (.-f_spVisibility ppu))
    (bump! m "chrRender" (* 240 8 2))))

(defn- jsnes-scanline->scanline [s]
  (if (= s 20) -1 (mod (+ s 241) 262)))

(defn take!
  "Everything recorded since the last call, as ClojureScript data, and
  reset the counters:
  `:counts` - map of counter name (keyword) to count
  `:events` - I/O register accesses, oldest first
  `:accesses` - the most recent bus accesses (at most `ring-size`), oldest first
  `:dropped` - how many more accesses happened than `:accesses` holds"
  [^js m]
  (let [counts (into {} (for [k (js-keys (.-counts m))]
                          [(keyword k) (aget (.-counts m) k)]))
        events (vec (for [^js e (.-events m)]
                      {:addr (.-addr e) :value (.-value e) :write? (.-write e)
                       :repeat (.-repeat e)
                       :scanline (jsnes-scanline->scanline (.-scanline e))}))
        n (.-accN m)
        new-count (- n (.-accTaken m))
        kept (min new-count ring-size)
        accesses (vec (for [k (range (- n kept) n)
                            :let [i (mod k ring-size)]]
                        {:addr (aget (.-accA m) i)
                         :value (aget (.-accV m) i)
                         :write? (= 1 (aget (.-accW m) i))}))]
    (set! (.-counts m) #js {})
    (set! (.-events m) #js [])
    (set! (.-accTaken m) n)
    {:counts counts :events events :accesses accesses
     :dropped (- new-count kept)}))
