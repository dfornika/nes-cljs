(ns nes.ppu.core
  "The PPU, one dot at a time.

  The PPU draws the picture one pixel per clock tick ('dot'). A frame is
  262 lines of 341 dots:

    lines 0-239   the picture: a pixel out on each of dots 1-256
    line 240      idle
    line 241      dot 1: vertical blank starts (PPUSTATUS bit 7; an NMI if
                  PPUCTRL bit 7 is on)
    lines 242-260 vertical blank: the CPU's time to change video memory
    line 261      'pre-render': dot 1 clears vertical blank, sprite 0 hit
                  and overflow; it fetches like a visible line, to get the
                  first two tiles of line 0 ready, and copies the vertical
                  scroll from t to v (dots 280-304). On odd frames, with
                  drawing on, its last dot is skipped.

  While drawing is on (PPUMASK bit 3 or 4), on lines 0-239 and 261:

  The background. Each tile takes 8 dots, a fetch every 2: its number from
  the nametable, its attribute byte, then the two bytes of its row of
  pixels. Every 8th dot v moves a tile right (nes.ppu.registers/
  increment-x). The bytes go into the low half of 16-bit shift registers,
  which move one bit a dot, so the pixel coming out is always the top
  bit, give or take the fine X scroll (which bit is read). Dot 256 moves v
  a line down (increment-y), dot 257 copies the horizontal scroll from t
  (copy-x), and dots 321-336 fetch the next line's first two tiles.

  Sprites. At dot 65 the PPU finds the sprites on the next line, up to 8
  ('secondary OAM'; a 9th sets the overflow flag). Dots 257-320 fetch
  their rows of pixels, 8 dots each, into 8 'sprite units'. (A real PPU
  spreads the search over dots 65-256; doing it at once gives the same
  sprites, though not the overflow flag's hardware bug.)

  The pixel. The background's color (0-3) and palette, and the first
  sprite unit with an opaque pixel here. A sprite pixel wins unless it's
  marked behind the background and the background is opaque. Where an
  opaque pixel of sprite 0 meets an opaque background pixel, sprite 0 hit.

  The state is arrays, like nes.ppu.registers': a PPU here is the map from
  nes.ppu.registers/create with these added:

    :core       where the PPU is, and its latches and shift registers
    :secondary  the sprites found for the next line (8 x 4 bytes)
    :units      the sprites being drawn on this line (8 x x, attr, lo, hi)
    :picture    the frame, as palette indexes (see nes.ppu.render)

  The CPU reads and writes registers through `cpu-read!` and `cpu-write!`
  here, not nes.ppu.registers', since some behave differently while the
  PPU draws."
  (:require [nes.bytes :as bytes]
            [nes.ppu.memory :as memory]
            [nes.ppu.registers :as r]))

(def ^:const width 256)
(def ^:const dots-per-line 341)
(def ^:const lines-per-frame 262)
(def ^:const vblank-line 241)
(def ^:const pre-render 261)

;; The `:core` array
(def ^:const LINE 0)
(def ^:const DOT 1)
(def ^:const FRAME 2)
(def ^:const NT 3)        ; the fetched tile number
(def ^:const AT 4)        ; the fetched tile's palette (0-3)
(def ^:const LO 5)        ; the fetched row's two bytes
(def ^:const HI 6)
(def ^:const BG-LO 7)     ; 16-bit shift registers: the tiles' pixels...
(def ^:const BG-HI 8)
(def ^:const AT-LO 9)     ; ...and their palettes, a bit per pixel
(def ^:const AT-HI 10)
(def ^:const FOUND 11)    ; sprites found for the next line (0-8)
(def ^:const FOUND-0 12)  ; 1 if sprite 0 is one of them
(def ^:const UNITS 13)    ; sprites loaded for this line
(def ^:const UNITS-0 14)  ; 1 if the first is sprite 0

(defn create
  "A PPU at power on. It starts on the pre-render line, so the first `tick!`s
  get the first frame ready. (A real one starts somewhere else each time.)"
  []
  (let [core (bytes/make-words 15)]
    (bytes/put-word! core LINE pre-render)
    (assoc (r/create)
           :core core
           :secondary (bytes/make 32)
           :units (bytes/make 32)
           :picture (bytes/make-words (* width 240)))))

(defn- c [ppu i] (bytes/word-at (:core ppu) i))
(defn- c! [ppu i v] (bytes/put-word! (:core ppu) i v))

(defn position
  "Where the PPU is: {:scanline 0-261 :dot 0-340 :frame}. The dot is the
  next one `tick!` will do."
  [ppu]
  {:scanline (c ppu LINE) :dot (c ppu DOT) :frame (c ppu FRAME)})

(defn drawing?
  "Is drawing on (the background, the sprites, or both)?"
  [ppu]
  (pos? (bit-and (r/reg ppu r/MASK) 0x18)))

(defn- drawing-now?
  "Is the PPU drawing, or getting ready to, right now? Then v is busy, and
  some registers behave differently."
  [ppu]
  (and (drawing? ppu) (let [line (c ppu LINE)] (or (< line 240) (= line pre-render)))))

;; ---------------------------------------------------------------------------
;; The background

(defn- reload!
  "Put the fetched tile into the low halves of the shift registers."
  [ppu]
  (let [at (c ppu AT)]
    (c! ppu BG-LO (bit-or (bit-and (c ppu BG-LO) 0xFF00) (c ppu LO)))
    (c! ppu BG-HI (bit-or (bit-and (c ppu BG-HI) 0xFF00) (c ppu HI)))
    (c! ppu AT-LO (bit-or (bit-and (c ppu AT-LO) 0xFF00) (if (bit-test at 0) 0xFF 0)))
    (c! ppu AT-HI (bit-or (bit-and (c ppu AT-HI) 0xFF00) (if (bit-test at 1) 0xFF 0)))))

(defn- shift! [ppu]
  (doseq [i [BG-LO BG-HI AT-LO AT-HI]]
    (c! ppu i (bit-and (bit-shift-left (c ppu i) 1) 0xFFFF))))

(defn- background-dot!
  "This dot's work on the background, while drawing."
  [ppu mem line dot]
  (let [fetching? (or (<= 1 dot 256) (<= 321 dot 336))]
    (when (or (<= 2 dot 257) (<= 322 dot 337))
      (shift! ppu)
      (when (and (= 1 (bit-and dot 7)) (>= dot 9))   ; 9, 17 ... 257, 329, 337
        (reload! ppu)))
    (when fetching?
      (let [v (r/reg ppu r/V)]
        (case (int (bit-and dot 7))
          2 (c! ppu NT (memory/video-read mem (bit-or 0x2000 (bit-and v 0x0FFF))))
          4 (let [attr (memory/video-read mem (bit-or 0x23C0 (bit-and v 0x0C00)
                                                      (bit-and (bit-shift-right v 4) 0x38)
                                                      (bit-and (bit-shift-right v 2) 0x07)))
                  shift (bit-or (bit-and (bit-shift-right v 4) 4) (bit-and v 2))]
              (c! ppu AT (bit-and (bit-shift-right attr shift) 3)))
          6 (c! ppu LO (memory/video-read mem (+ (if (bit-test (r/reg ppu r/CTRL) 4) 0x1000 0)
                                                 (* 16 (c ppu NT)) (r/fine-y v))))
          0 (do (c! ppu HI (memory/video-read mem (+ (if (bit-test (r/reg ppu r/CTRL) 4) 0x1000 0)
                                                     (* 16 (c ppu NT)) (r/fine-y v) 8)))
                (r/set-reg! ppu r/V (r/increment-x v)))
          nil)))
    (when (= dot 256) (r/set-reg! ppu r/V (r/increment-y (r/reg ppu r/V))))
    (when (= dot 257) (r/set-reg! ppu r/V (r/copy-x (r/reg ppu r/V) (r/reg ppu r/T))))
    (when (and (= line pre-render) (<= 280 dot 304))
      (r/set-reg! ppu r/V (r/copy-y (r/reg ppu r/V) (r/reg ppu r/T))))))

;; ---------------------------------------------------------------------------
;; Sprites

(defn- sprite-height [ppu] (if (bit-test (r/reg ppu r/CTRL) 5) 16 8))

(defn- find-sprites!
  "Find the sprites on the line after `line`: the first 8 into secondary
  OAM. A 9th sets the overflow flag."
  [ppu line]
  (let [oam (:oam ppu), secondary (:secondary ppu), h (sprite-height ppu)]
    (bytes/fill! secondary 0xFF)
    (loop [i 0, found 0, zero 0]
      (if (= i 64)
        (do (c! ppu FOUND (min found 8)) (c! ppu FOUND-0 zero))
        (if (< -1 (- line (bytes/at oam (* 4 i))) h)
          (do (when (< found 8)
                (dotimes [k 4] (bytes/put! secondary (+ (* 4 found) k) (bytes/at oam (+ (* 4 i) k)))))
              (when (= found 8) (r/set-status! ppu r/OVERFLOW true))
              (recur (inc i) (inc found) (if (zero? i) 1 zero)))
          (recur (inc i) found zero))))))

(defn- fetch-sprite!
  "Fetch sprite unit `k`'s row of pixels for the next line. A unit with no
  sprite still fetches (tile $FF), as the hardware does; some cartridges
  count those fetches."
  [ppu mem line k]
  (let [secondary (:secondary ppu), units (:units ppu)
        used? (< k (c ppu UNITS))
        h (sprite-height ppu)
        y (if used? (bytes/at secondary (* 4 k)) 0)
        tile (if used? (bytes/at secondary (+ 1 (* 4 k))) 0xFF)
        attr (if used? (bytes/at secondary (+ 2 (* 4 k))) 0)
        row (if used? (- line y) 0)
        row (if (bit-test attr 7) (- h 1 row) row)
        addr (if (= h 16)
               (+ (* (bit-and tile 1) 0x1000) (* (+ (bit-and tile 0xFE) (if (< row 8) 0 1)) 16) (bit-and row 7))
               (+ (if (bit-test (r/reg ppu r/CTRL) 3) 0x1000 0) (* tile 16) row))
        lo (memory/video-read mem addr)
        hi (memory/video-read mem (+ addr 8))]
    (when used?
      (bytes/put! units (* 4 k) (bytes/at secondary (+ 3 (* 4 k))))
      (bytes/put! units (+ 1 (* 4 k)) attr)
      (bytes/put! units (+ 2 (* 4 k)) lo)
      (bytes/put! units (+ 3 (* 4 k)) hi))))

(defn- sprite-dot!
  "This dot's work on sprites, while drawing."
  [ppu mem line dot]
  (when (and (= dot 65) (< line 240)) (find-sprites! ppu line))
  (when (<= 257 dot 320)
    (r/set-reg! ppu r/OAM-ADDR 0)
    (when (= dot 257)
      ;; nothing is searched on the pre-render line, so nothing is on line 0
      (c! ppu UNITS (if (= line pre-render) 0 (c ppu FOUND)))
      (c! ppu UNITS-0 (if (= line pre-render) 0 (c ppu FOUND-0))))
    (when (zero? (bit-and dot 7))   ; 264, 272 ... 320: the end of each unit's 8 dots
      (fetch-sprite! ppu mem line (bit-shift-right (- dot 264) 3)))))

;; ---------------------------------------------------------------------------
;; The pixel

(defn- pixel!
  "Pick the color of pixel `x` on `line`."
  [ppu line x]
  (let [mask (r/reg ppu r/MASK)
        palette (:palette ppu)
        index
        (if-not (pos? (bit-and mask 0x18))
          ;; drawing off: the backdrop, or the palette entry v points to
          (let [v (bit-and (r/reg ppu r/V) 0x3FFF)]
            (if (>= v 0x3F00) (bit-and v 0x1F) 0))
          (let [bit (- 15 (r/reg ppu r/X))
                bg (if (and (bit-test mask 3) (or (bit-test mask 1) (>= x 8)))
                     (bit-or (bit-and (bit-shift-right (c ppu BG-LO) bit) 1)
                             (bit-shift-left (bit-and (bit-shift-right (c ppu BG-HI) bit) 1) 1))
                     0)
                bg-palette (bit-or (bit-and (bit-shift-right (c ppu AT-LO) bit) 1)
                                   (bit-shift-left (bit-and (bit-shift-right (c ppu AT-HI) bit) 1) 1))
                units (:units ppu)
                ;; the first unit with an opaque pixel here: [unit color attr], or nil
                sprite (when (and (bit-test mask 4) (or (bit-test mask 2) (>= x 8)))
                         (loop [k 0]
                           (when (< k (c ppu UNITS))
                             (let [col (- x (bytes/at units (* 4 k)))]
                               (if (< -1 col 8)
                                 (let [attr (bytes/at units (+ 1 (* 4 k)))
                                       b (if (bit-test attr 6) col (- 7 col))
                                       color (bit-or (bit-and (bit-shift-right (bytes/at units (+ 2 (* 4 k))) b) 1)
                                                     (bit-shift-left (bit-and (bit-shift-right (bytes/at units (+ 3 (* 4 k))) b) 1) 1))]
                                   (if (pos? color) [k color attr] (recur (inc k))))
                                 (recur (inc k)))))))]
            (when (and sprite (pos? bg) (zero? (first sprite)) (= 1 (c ppu UNITS-0)) (< x 255))
              (r/set-status! ppu r/SPRITE-0-HIT true))
            (cond
              (and sprite (or (zero? bg) (not (bit-test (nth sprite 2) 5))))
              (bit-or 0x10 (bit-shift-left (bit-and (nth sprite 2) 3) 2) (nth sprite 1))
              (pos? bg) (bit-or (bit-shift-left bg-palette 2) bg)
              :else 0)))]
    (bytes/put-word! (:picture ppu) (+ (* line width) x)
                     (bit-or (bit-shift-left (bit-shift-right mask 5) 6)
                             (bit-and (bytes/at palette (r/palette-index index))
                                      (if (bit-test mask 0) 0x30 0x3F))))))

;; ---------------------------------------------------------------------------
;; Ticking

(defn tick!
  "Run the PPU for one dot. `mem` is its video memory (nes.ppu.memory)."
  [ppu mem]
  (let [line (c ppu LINE)
        dot (c ppu DOT)
        drawing (drawing? ppu)]
    (when (= dot 1)
      (cond
        (= line vblank-line) (r/set-status! ppu r/VBLANK true)
        (= line pre-render) (doseq [bit [r/VBLANK r/SPRITE-0-HIT r/OVERFLOW]] (r/set-status! ppu bit false))))
    (when (and drawing (or (< line 240) (= line pre-render)))
      (background-dot! ppu mem line dot)
      (sprite-dot! ppu mem line dot))
    (when (and (< line 240) (<= 1 dot 256))
      (pixel! ppu line (dec dot)))
    ;; on to the next dot
    (cond
      ;; odd frames are a dot short when drawing: the last of the pre-render line is skipped
      (and (= line pre-render) (= dot 339) drawing (odd? (c ppu FRAME)))
      (do (c! ppu LINE 0) (c! ppu DOT 0) (c! ppu FRAME (inc (c ppu FRAME))))
      (< dot 340) (c! ppu DOT (inc dot))
      (< line pre-render) (do (c! ppu DOT 0) (c! ppu LINE (inc line)))
      :else (do (c! ppu DOT 0) (c! ppu LINE 0) (c! ppu FRAME (inc (c ppu FRAME)))))
    nil))

(defn run-to!
  "Tick until the PPU is at `line`, `dot` (the next dot to do). Returns the
  number of dots run."
  [ppu mem line dot]
  (loop [n 0]
    (if (and (= line (c ppu LINE)) (= dot (c ppu DOT)))
      n
      (do (tick! ppu mem) (recur (inc n))))))

(defn frame!
  "Run until the picture of the current or next frame is finished (the
  start of line 240). Returns the picture."
  [ppu mem]
  (when (and (= 240 (c ppu LINE)) (zero? (c ppu DOT))) (tick! ppu mem))
  (run-to! ppu mem 240 0)
  (:picture ppu))

;; ---------------------------------------------------------------------------
;; The CPU's side

(defn cpu-read!
  "The CPU reads PPU register `addr`. As nes.ppu.registers/read!, except
  while the PPU is drawing:

  - OAMDATA ($2004) reads what the sprite search is looking at: $FF while
    it clears secondary OAM and while it fetches, OAM at OAMADDR between.
  - PPUDATA ($2007) moves v the way drawing does (a tile right and a line
    down) instead of by 1 or 32."
  [ppu mem addr]
  (if-not (drawing-now? ppu)
    (r/read! ppu mem addr)
    (case (int (bit-and addr 7))
      4 (let [dot (c ppu DOT)
              i (r/reg ppu r/OAM-ADDR)
              v (if (<= 65 dot 256)
                  (let [b (bytes/at (:oam ppu) i)] (if (= 2 (bit-and i 3)) (bit-and b 0xE3) b))
                  0xFF)]
          (r/set-reg! ppu r/LATCH v)
          v)
      7 (let [v (r/reg ppu r/V)
              value (r/read! ppu mem addr)]
          (r/set-reg! ppu r/V (r/increment-y (r/increment-x v)))
          value)
      (r/read! ppu mem addr))))

(defn cpu-write!
  "The CPU writes `value` to PPU register `addr`. As nes.ppu.registers/
  write!, except while the PPU is drawing:

  - OAMDATA ($2004) writes are lost, and OAMADDR goes up by 4 (to the next
    sprite).
  - PPUDATA ($2007) moves v the way drawing does."
  [ppu mem addr value]
  (if-not (drawing-now? ppu)
    (r/write! ppu mem addr value)
    (case (int (bit-and addr 7))
      4 (do (r/set-reg! ppu r/LATCH value)
            (r/set-reg! ppu r/OAM-ADDR (bit-and (+ (r/reg ppu r/OAM-ADDR) 4) 0xFC)))
      7 (let [v (r/reg ppu r/V)]
          (r/write! ppu mem addr value)
          (r/set-reg! ppu r/V (r/increment-y (r/increment-x v))))
      (r/write! ppu mem addr value)))
  nil)
