(ns nes.ppu.core
  "The PPU, one dot at a time.

  The PPU draws the picture one pixel per clock tick ('dot'). A frame is
  262 lines of 341 dots:

    lines 0-239   the picture: a pixel out on each of dots 1-256
    line 240      idle
    line 241      vertical blank starts (PPUSTATUS bit 7; an NMI if PPUCTRL
                  bit 7 is on), as dot 1 begins: a read on dot 1 sees it,
                  and a read just before it means it isn't set this frame
    lines 242-260 vertical blank: the CPU's time to change video memory
    line 261      'pre-render': as dot 1 begins, vertical blank, sprite 0
                  hit and overflow are cleared; it fetches like a visible
                  line, to get the
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
    :drawn      for each line of the picture, 1 if drawing was on as it began

  The CPU reads and writes registers through `cpu-read!` and `cpu-write!`
  here, not nes.ppu.registers', since some behave differently while the
  PPU draws."
  (:require [nes.bytes :as bytes]
            [nes.ppu.memory :as memory]
            [nes.ppu.registers :as r]
            [nes.ppu.sprite-search :as sprite-search]))

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
(def ^:const ARMED 15)    ; 1 once line 240 has passed: vertical blank comes next
(def ^:const SUPPRESS 16) ; 1 if PPUSTATUS was read just before vertical blank
(def ^:const SKIP-ODD 17) ; 1 (as on a real NES) to skip a dot on odd frames
(def ^:const OVERFLOW-DOT 18) ; the dot on this line the overflow flag goes up, or `never`
(def ^:const never 0xFFFF)    ; a dot that never comes

(defn create
  "A PPU at power on. It starts on the pre-render line, so the first `tick!`s
  get the first frame ready. (A real one starts somewhere else each time;
  see `set-position!`.)"
  []
  (let [core (bytes/make-words 19)]
    (bytes/put-word! core SKIP-ODD 1)
    (bytes/put-word! core OVERFLOW-DOT never)
    (bytes/put-word! core LINE pre-render)
    (assoc (r/create)
           :core core
           :secondary (bytes/make 32)
           :units (bytes/make 32)
           :picture (bytes/make-words (* width 240))
           :drawn (bytes/make 240))))

(defn- c [ppu i] (bytes/word-at (:core ppu) i))
(defn- c! [ppu i v] (bytes/put-word! (:core ppu) i v))

(defn position
  "Where the PPU is: {:scanline 0-261 :dot 0-340 :frame}. The dot is the
  next one `tick!` will do."
  [ppu]
  {:scanline (c ppu LINE) :dot (c ppu DOT) :frame (c ppu FRAME)})

(defn set-position!
  "Move the PPU to `line`, `dot`, as if it had started there. It won't
  start vertical blank until it has been through line 240."
  [ppu line dot]
  (c! ppu LINE line)
  (c! ppu DOT dot)
  (c! ppu ARMED 0))

(defn skip-odd-dot!
  "Skip the last dot of the pre-render line on odd frames while drawing,
  as a real NES does (`on?` true, the default), or not."
  [ppu on?]
  (c! ppu SKIP-ODD (if on? 1 0)))

(defn line "The line the PPU is on, 0-261." [ppu] (c ppu LINE))
(defn dot "The next dot `tick!` will do, 0-340." [ppu] (c ppu DOT))

(defn drawing?
  "Is drawing on (the background, the sprites, or both)?"
  [ppu]
  (pos? (bit-and (r/reg ppu r/MASK) 0x18)))

(defn- drawing-now?
  "Is the PPU drawing, or getting ready to, right now? Then v is busy, and
  some registers behave differently."
  [ppu]
  (and (drawing? ppu) (let [line (c ppu LINE)] (or (< line 240) (== line pre-render)))))

;; ---------------------------------------------------------------------------
;; The background

(defn- reload!
  "Put the fetched tile into the low halves of the shift registers."
  [core]
  (let [at (bytes/word-at core AT)]
    (bytes/put-word! core BG-LO (bit-or (bit-and (bytes/word-at core BG-LO) 0xFF00) (bytes/word-at core LO)))
    (bytes/put-word! core BG-HI (bit-or (bit-and (bytes/word-at core BG-HI) 0xFF00) (bytes/word-at core HI)))
    (bytes/put-word! core AT-LO (bit-or (bit-and (bytes/word-at core AT-LO) 0xFF00) (if (bit-test at 0) 0xFF 0)))
    (bytes/put-word! core AT-HI (bit-or (bit-and (bytes/word-at core AT-HI) 0xFF00) (if (bit-test at 1) 0xFF 0)))))

(defn- shift! [core]
  (bytes/put-word! core BG-LO (bit-and (bit-shift-left (bytes/word-at core BG-LO) 1) 0xFFFF))
  (bytes/put-word! core BG-HI (bit-and (bit-shift-left (bytes/word-at core BG-HI) 1) 0xFFFF))
  (bytes/put-word! core AT-LO (bit-and (bit-shift-left (bytes/word-at core AT-LO) 1) 0xFFFF))
  (bytes/put-word! core AT-HI (bit-and (bit-shift-left (bytes/word-at core AT-HI) 1) 0xFFFF)))

(defn- background-dot!
  "This dot's work on the background, while drawing. `core` and `regs` are
  the PPU's arrays (passed in, as this runs every dot)."
  [core regs mem line dot]
  (when (or (<= 2 dot 257) (<= 322 dot 337))
    (shift! core)
    (when (and (== 1 (bit-and dot 7)) (>= dot 9))   ; 9, 17 ... 257, 329, 337
      (reload! core)))
  (when (or (<= 1 dot 256) (<= 321 dot 336))
    (let [v (bytes/word-at regs r/V)
          table (if (bit-test (bytes/word-at regs r/CTRL) 4) 0x1000 0)]
      (case (int (bit-and dot 7))
        2 (bytes/put-word! core NT (memory/video-read mem (bit-or 0x2000 (bit-and v 0x0FFF))))
        4 (let [attr (memory/video-read mem (bit-or 0x23C0 (bit-and v 0x0C00)
                                                    (bit-and (bit-shift-right v 4) 0x38)
                                                    (bit-and (bit-shift-right v 2) 0x07)))
                shift (bit-or (bit-and (bit-shift-right v 4) 4) (bit-and v 2))]
            (bytes/put-word! core AT (bit-and (bit-shift-right attr shift) 3)))
        6 (bytes/put-word! core LO (memory/video-read mem (+ table (* 16 (bytes/word-at core NT)) (r/fine-y v))))
        0 (do (bytes/put-word! core HI (memory/video-read mem (+ table (* 16 (bytes/word-at core NT)) (r/fine-y v) 8)))
              (bytes/put-word! regs r/V (r/increment-x v)))
        nil)))
  (when (== dot 256) (bytes/put-word! regs r/V (r/increment-y (bytes/word-at regs r/V))))
  (when (== dot 257) (bytes/put-word! regs r/V (r/copy-x (bytes/word-at regs r/V) (bytes/word-at regs r/T))))
  (when (and (== line pre-render) (<= 280 dot 304))
    (bytes/put-word! regs r/V (r/copy-y (bytes/word-at regs r/V) (bytes/word-at regs r/T)))))

;; ---------------------------------------------------------------------------
;; Sprites

(defn- sprite-height [ppu] (if (bit-test (r/reg ppu r/CTRL) 5) 16 8))

(defn- find-sprites!
  "Find the sprites on the line after `line`, the first 8 into secondary
  OAM, and when the overflow flag goes up (see nes.ppu.sprite-search). The
  search takes dots 65-256 on a real PPU; this does it at dot 65, and the
  flag goes up on its dot."
  [ppu line]
  (let [oam (:oam ppu), secondary (:secondary ppu)
        {:keys [found overflow-dot]} (sprite-search/search oam line (sprite-height ppu))]
    (bytes/fill! secondary 0xFF)
    (doseq [[slot n] (map-indexed vector found)]
      (dotimes [k 4] (bytes/put! secondary (+ (* 4 slot) k) (bytes/at oam (+ (* 4 n) k)))))
    (c! ppu FOUND (count found))
    (c! ppu FOUND-0 (if (= 0 (first found)) 1 0))
    (c! ppu OVERFLOW-DOT (or overflow-dot never))))

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
        addr (if (== h 16)
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
  (when (and (== dot 65) (< line 240)) (find-sprites! ppu line))
  (when (<= 257 dot 320)
    (r/set-reg! ppu r/OAM-ADDR 0)
    (when (== dot 257)
      ;; nothing is searched on the pre-render line, so nothing is on line 0
      (c! ppu UNITS (if (== line pre-render) 0 (c ppu FOUND)))
      (c! ppu UNITS-0 (if (== line pre-render) 0 (c ppu FOUND-0))))
    (when (zero? (bit-and dot 7))   ; 264, 272 ... 320: the end of each unit's 8 dots
      (fetch-sprite! ppu mem line (bit-shift-right (- dot 264) 3)))))

;; ---------------------------------------------------------------------------
;; The pixel

(defn- sprite-at
  "The first sprite unit with an opaque pixel at `x`, as unit*8 + color,
  or -1. (Units are in sprite order, so the first one wins.)"
  [core units x]
  (loop [k 0]
    (if (< k (bytes/word-at core UNITS))
      (let [col (- x (bytes/at units (* 4 k)))]
        (if (< -1 col 8)
          (let [attr (bytes/at units (+ 1 (* 4 k)))
                b (if (bit-test attr 6) col (- 7 col))
                color (bit-or (bit-and (bit-shift-right (bytes/at units (+ 2 (* 4 k))) b) 1)
                              (bit-shift-left (bit-and (bit-shift-right (bytes/at units (+ 3 (* 4 k))) b) 1) 1))]
            (if (pos? color) (+ (* k 8) color) (recur (inc k))))
          (recur (inc k))))
      -1)))

(defn- pixel!
  "Pick the color of pixel `x` on `line`."
  [ppu core regs line x]
  (let [mask (bytes/word-at regs r/MASK)
        palette (:palette ppu)
        index
        (if-not (pos? (bit-and mask 0x18))
          ;; drawing off: the backdrop, or the palette entry v points to
          (let [v (bit-and (bytes/word-at regs r/V) 0x3FFF)]
            (if (>= v 0x3F00) (bit-and v 0x1F) 0))
          (let [bit (- 15 (bytes/word-at regs r/X))
                bg (if (and (bit-test mask 3) (or (bit-test mask 1) (>= x 8)))
                     (bit-or (bit-and (bit-shift-right (bytes/word-at core BG-LO) bit) 1)
                             (bit-shift-left (bit-and (bit-shift-right (bytes/word-at core BG-HI) bit) 1) 1))
                     0)
                bg-palette (bit-or (bit-and (bit-shift-right (bytes/word-at core AT-LO) bit) 1)
                                   (bit-shift-left (bit-and (bit-shift-right (bytes/word-at core AT-HI) bit) 1) 1))
                units (:units ppu)
                found (if (and (bit-test mask 4) (or (bit-test mask 2) (>= x 8)))
                        (sprite-at core units x)
                        -1)
                unit (bit-shift-right found 3)
                color (bit-and found 3)
                attr (when (>= found 0) (bytes/at units (+ 1 (* 4 unit))))]
            (when (and (>= found 0) (pos? bg) (zero? unit) (== 1 (bytes/word-at core UNITS-0)) (< x 255))
              (bytes/put-word! regs r/STATUS (bit-or (bytes/word-at regs r/STATUS) r/SPRITE-0-HIT)))
            (cond
              (and (>= found 0) (or (zero? bg) (not (bit-test attr 5))))
              (bit-or 0x10 (bit-shift-left (bit-and attr 3) 2) color)
              (pos? bg) (bit-or (bit-shift-left bg-palette 2) bg)
              :else 0)))]
    (bytes/put-word! (:picture ppu) (+ (* line width) x)
                     (bit-or (bit-shift-left (bit-shift-right mask 5) 6)
                             (bit-and (bytes/at palette (r/palette-index index))
                                      (if (bit-test mask 0) 0x30 0x3F))))))

;; ---------------------------------------------------------------------------
;; Ticking

(defn tick!
  "Run the PPU for one dot. `mem` is its video memory (nes.ppu.memory).
  Returns :vblank when vertical blank starts (the picture is finished),
  :pre-render when it ends, otherwise nil."
  [ppu mem]
  (let [core (:core ppu)
        regs (:regs ppu)
        line (bytes/word-at core LINE)
        dot (bytes/word-at core DOT)
        drawing (pos? (bit-and (bytes/word-at regs r/MASK) 0x18))
        event (when (zero? dot)
                (cond
                  (== line 240) (do (c! ppu ARMED 1) nil)
                  ;; at the end of dot 0, so it's there as dot 1 begins
                  (and (== line vblank-line) (== 1 (c ppu ARMED)))
                  (do (c! ppu ARMED 0)
                      (r/next-frame! ppu)
                      (when (zero? (c ppu SUPPRESS)) (r/set-status! ppu r/VBLANK true))
                      (c! ppu SUPPRESS 0)
                      :vblank)
                  (== line pre-render)
                  (do (r/set-status! ppu r/VBLANK false)
                      (r/set-status! ppu r/SPRITE-0-HIT false)
                      (r/set-status! ppu r/OVERFLOW false)
                      :pre-render)))]
    (when (and drawing (or (< line 240) (== line pre-render)))
      (when (and (== dot (bytes/word-at core OVERFLOW-DOT)) (< line 240))
        (r/set-status! ppu r/OVERFLOW true))
      (background-dot! core regs mem line dot)
      (when (or (== dot 65) (<= 257 dot 320))
        (sprite-dot! ppu mem line dot)))
    (when (and (< line 240) (<= 1 dot 256))
      (when (== dot 1) (bytes/put! (:drawn ppu) line (if drawing 1 0)))
      (pixel! ppu core regs line (dec dot)))
    ;; on to the next dot
    (cond
      ;; Odd frames are a dot short when drawing: the last of the pre-render
      ;; line is skipped. (Not exact yet: on a real PPU, turning drawing on
      ;; and turning it off take effect after different delays; blargg's
      ;; ppu_vbl_nmi 10 checks it, and fails here.)
      (and (== line pre-render) (== dot 339) drawing (odd? (bytes/word-at core FRAME)) (== 1 (bytes/word-at core SKIP-ODD)))
      (do (bytes/put-word! core LINE 0) (bytes/put-word! core DOT 0) (bytes/put-word! core FRAME (inc (bytes/word-at core FRAME))))
      (< dot 340) (bytes/put-word! core DOT (inc dot))
      (< line pre-render) (do (bytes/put-word! core DOT 0) (bytes/put-word! core LINE (inc line)))
      :else (do (bytes/put-word! core DOT 0) (bytes/put-word! core LINE 0) (bytes/put-word! core FRAME (inc (bytes/word-at core FRAME)))))
    event))

(defn run-to!
  "Tick until the PPU is at `line`, `dot` (the next dot to do). Returns the
  number of dots run."
  [ppu mem line dot]
  (loop [n 0]
    (if (and (== line (c ppu LINE)) (== dot (c ppu DOT)))
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
  ;; reading PPUSTATUS just before vertical blank starts: it doesn't, this frame
  (when (and (= 2 (bit-and addr 7)) (= vblank-line (c ppu LINE)) (zero? (c ppu DOT)))
    (c! ppu SUPPRESS 1))
  (if-not (drawing-now? ppu)
    (r/read! ppu mem addr)
    (case (int (bit-and addr 7))
      4 (let [dot (c ppu DOT)
              i (r/reg ppu r/OAM-ADDR)
              v (if (<= 65 dot 256)
                  (let [b (bytes/at (:oam ppu) i)] (if (= 2 (bit-and i 3)) (bit-and b 0xE3) b))
                  0xFF)]
          (r/refresh-latch! ppu 0xFF v)
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
      4 (do (r/refresh-latch! ppu 0xFF value)
            (r/set-reg! ppu r/OAM-ADDR (bit-and (+ (r/reg ppu r/OAM-ADDR) 4) 0xFC)))
      7 (let [v (r/reg ppu r/V)]
          (r/write! ppu mem addr value)
          (r/set-reg! ppu r/V (r/increment-y (r/increment-x v))))
      (r/write! ppu mem addr value)))
  nil)
