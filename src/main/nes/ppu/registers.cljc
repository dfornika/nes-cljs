(ns nes.ppu.registers
  "The PPU's registers: the eight the CPU sees, and the four behind them
  that hold the scroll position.

  The CPU talks to the PPU through eight addresses, $2000-$2007 (repeated
  all the way up to $3FFF):

    $2000 PPUCTRL    write: NMI on/off, sprite size, which tiles, address step
    $2001 PPUMASK    write: show the background, the sprites, the left edge
    $2002 PPUSTATUS  read:  in vertical blank? sprite 0 hit? too many sprites?
    $2003 OAMADDR    write: where in sprite memory (OAM) to read or write next
    $2004 OAMDATA    read/write a byte of sprite memory there
    $2005 PPUSCROLL  write twice: X scroll, then Y
    $2006 PPUADDR    write twice: high byte, then low, of a video memory address
    $2007 PPUDATA    read/write video memory there; the address then moves on

  ## The scroll registers: v, t, x and w

  Scrolling and video-memory addresses share four registers inside the
  PPU. The nesdev wiki names them after loopy, who worked them out
  (https://www.nesdev.org/wiki/PPU_scrolling):

    v  the current address, 15 bits
    t  the address being set up, 15 bits: copied into v at the right moments
    x  the fine X scroll, 3 bits: which pixel of a tile is first on the left
    w  which write is next, for $2005 and $2006 (they take two)

  While the PPU draws, v is where it's reading the background from, so its
  bits are a scroll position:

    yyy NN YYYYY XXXXX
    ||| || ||||| +++++-- coarse X: the tile column, 0-31
    ||| || +++++-------- coarse Y: the tile row, 0-29
    ||| ++-------------- which of the four nametables
    +++----------------- fine Y: the pixel row within the tile, 0-7

  That's why $2005 and $2006 interfere with each other: both write into t.
  A game sets the scroll with $2005 (and the nametable bits of $2000)
  during vertical blank, and the PPU copies t to v when the next frame
  starts. Writing $2006 sets v straight away, which is also how a game
  changes the scroll halfway down the screen.

  ## What's here

  Pure functions on v and t (numbers in, numbers out), which are all of
  the bit twiddling, and are what the drawing code will use too:
  `after-ctrl`, `after-scroll-x`, `after-scroll-y`, `after-address-high`,
  `after-address-low`, `increment-x`, `increment-y`, `copy-x`, `copy-y`.

  Then a PPU's register state, kept in arrays so it runs the same in the
  browser and on the JVM, and copies easily (for save states): `create`,
  and `read!` and `write!`, what happens when the CPU reads or writes one
  of the eight registers.

  Not here yet: anything that depends on where the PPU is in the frame.
  Vertical blank and sprite 0 hit are set from outside (`set-status!`).
  A $2007 access while the PPU is drawing moves v differently, and so do
  $2004 accesses; that comes with the PPU that draws (see
  docs/ppu_plan.md)."
  (:require [nes.bytes :as bytes]
            [nes.ppu.memory :as memory]))

;; ---------------------------------------------------------------------------
;; v and t: the bits

(defn coarse-x "The tile column in `v`, 0-31." [v] (bit-and v 0x1F))
(defn coarse-y "The tile row in `v`, 0-31 (30 and 31 are past the bottom)." [v] (bit-and (bit-shift-right v 5) 0x1F))
(defn nametable "Which of the four nametables `v` is in, 0-3." [v] (bit-and (bit-shift-right v 10) 3))
(defn fine-y "The pixel row within the tile in `v`, 0-7." [v] (bit-and (bit-shift-right v 12) 7))

(def v-fields
  "The parts of v (and t), high bits first, for showing them."
  [{:key :fine-y :name "fine Y" :bits [14 13 12] :about "the pixel row within the tile, 0-7"}
   {:key :nametable :name "nametable" :bits [11 10] :about "which of the four: 0 top left, 1 top right, 2 bottom left, 3 bottom right"}
   {:key :coarse-y :name "coarse Y" :bits [9 8 7 6 5] :about "the tile row, 0-29"}
   {:key :coarse-x :name "coarse X" :bits [4 3 2 1 0] :about "the tile column, 0-31"}])

(defn fields
  "`v` (or `t`) taken apart: {:coarse-x :coarse-y :nametable :fine-y}."
  [v]
  {:coarse-x (coarse-x v) :coarse-y (coarse-y v) :nametable (nametable v) :fine-y (fine-y v)})

(defn scroll-position
  "The scroll position that `v` and fine X `x` stand for, in pixels, as
  {:x :y} in the 512x480 space of the four nametables (two across, two
  down)."
  [v x]
  {:x (+ (* 256 (bit-and (nametable v) 1)) (* 8 (coarse-x v)) x)
   :y (+ (* 240 (bit-shift-right (nametable v) 1)) (* 8 (coarse-y v)) (fine-y v))})

(defn after-ctrl
  "t after a write of `value` to PPUCTRL: its low 2 bits pick the nametable."
  [t value]
  (bit-or (bit-and t 0x73FF) (bit-shift-left (bit-and value 3) 10)))

(defn after-scroll-x
  "t after the first write to PPUSCROLL: the top 5 bits of `value` are the
  coarse X. (Its low 3 bits are the fine X, which goes into x.)"
  [t value]
  (bit-or (bit-and t 0x7FE0) (bit-shift-right value 3)))

(defn after-scroll-y
  "t after the second write to PPUSCROLL: the top 5 bits of `value` are the
  coarse Y, the low 3 the fine Y."
  [t value]
  (bit-or (bit-and t 0x0C1F)
          (bit-shift-left (bit-and value 7) 12)
          (bit-shift-left (bit-and value 0xF8) 2)))

(defn after-address-high
  "t after the first write to PPUADDR: the high 6 bits of the address. Bit
  14 is cleared: a PPU address is 14 bits, though v and t have 15."
  [t value]
  (bit-or (bit-and t 0x00FF) (bit-shift-left (bit-and value 0x3F) 8)))

(defn after-address-low
  "t after the second write to PPUADDR: the low 8 bits. (Then t is copied to v.)"
  [t value]
  (bit-or (bit-and t 0x7F00) value))

(defn increment-x
  "v moved one tile right, into the next nametable across after column 31.
  The PPU does this every 8 dots while drawing."
  [v]
  (if (= (coarse-x v) 31)
    (bit-xor (bit-and v 0x7FE0) 0x0400)
    (inc v)))

(defn increment-y
  "v moved one pixel down: the next row of the tile, or the next row of
  tiles after 8. After row 29 (the last on the screen) it goes into the
  next nametable down. Rows 30 and 31 aren't on the screen, but a game can
  set them: then it wraps to 0 in the same nametable (and the attribute
  bytes get drawn as tiles). The PPU does this at the end of each line."
  [v]
  (if (< (fine-y v) 7)
    (+ v 0x1000)
    (let [v (bit-and v 0x0FFF)                  ; fine Y back to 0
          y (coarse-y v)]
      (case y
        29 (bit-xor (bit-and v 0x7C1F) 0x0800)  ; row 0 of the next nametable down
        31 (bit-and v 0x7C1F)                   ; row 0, same nametable
        (+ v 0x20)))))

(defn copy-x
  "v with the horizontal parts of t (coarse X, and which nametable across).
  The PPU does this at the start of each line, at dot 257."
  [v t]
  (bit-or (bit-and v 0x7BE0) (bit-and t 0x041F)))

(defn copy-y
  "v with the vertical parts of t (fine Y, coarse Y, which nametable down).
  The PPU does this just before each frame, on the pre-render line."
  [v t]
  (bit-or (bit-and v 0x041F) (bit-and t 0x7BE0)))

;; ---------------------------------------------------------------------------
;; A PPU's registers, as arrays

;; The `regs` array: one 32-bit number per register
(def ^:const CTRL 0)
(def ^:const MASK 1)
(def ^:const STATUS 2)
(def ^:const OAM-ADDR 3)
(def ^:const V 4)
(def ^:const T 5)
(def ^:const X 6)
(def ^:const W 7)
(def ^:const BUFFER 8)   ; PPUDATA's read buffer
(def ^:const LATCH 9)    ; the last byte on the PPU's data bus: what a write-only register reads as

;; PPUSTATUS bits
(def ^:const VBLANK 0x80)
(def ^:const SPRITE-0-HIT 0x40)
(def ^:const OVERFLOW 0x20)

(defn create
  "A PPU's registers and its own memories, as at power on (all zero):

    :regs     the registers (CTRL, MASK, STATUS ... above)
    :oam      sprite memory: 64 sprites of 4 bytes
    :palette  palette RAM: 32 bytes of color numbers"
  []
  {:regs (bytes/make-words 10)
   :oam (bytes/make 256)
   :palette (bytes/make 32)})

(defn reg "The register at index `i` of `ppu`'s registers." [ppu i] (bytes/word-at (:regs ppu) i))
(defn- reg! [ppu i value] (bytes/put-word! (:regs ppu) i value))

(defn registers
  "All the registers as a map, for showing and testing:
  {:ctrl :mask :status :oam-addr :v :t :x :w :buffer :latch}."
  [ppu]
  (zipmap [:ctrl :mask :status :oam-addr :v :t :x :w :buffer :latch]
          (map #(reg ppu %) (range 10))))

(defn set-status!
  "Turn a PPUSTATUS bit (VBLANK, SPRITE-0-HIT, OVERFLOW) on or off."
  [ppu bit on?]
  (let [s (reg ppu STATUS)]
    (reg! ppu STATUS (if on? (bit-or s bit) (bit-and s (bit-xor 0xFF bit))))))

(defn nmi?
  "Is the PPU asking for an NMI? It is while it's in vertical blank with NMI
  turned on (PPUCTRL bit 7). The CPU takes one when this becomes true:
  turning NMI on during vertical blank makes one straight away. (The
  console watches for that; see docs/ppu_plan.md, step 4.)"
  [ppu]
  (and (bit-test (reg ppu CTRL) 7) (bit-test (reg ppu STATUS) 7)))

(defn palette-index
  "Where palette address `addr` ($3F00-$3FFF) is in the 32 bytes of palette
  RAM. They repeat every 32 bytes, and $3F10, $3F14, $3F18 and $3F1C are
  the same bytes as $3F00, $3F04, $3F08 and $3F0C: color 0 of each sprite
  palette is the background's."
  [addr]
  (let [i (bit-and addr 0x1F)]
    (if (= (bit-and i 0x13) 0x10) (bit-and i 0x0F) i)))

(defn- step-address!
  "After a PPUDATA access: v moves on by 1, or by 32 (a row of tiles down)
  if PPUCTRL bit 2 is on."
  [ppu]
  (reg! ppu V (bit-and (+ (reg ppu V) (if (bit-test (reg ppu CTRL) 2) 32 1)) 0x7FFF)))

(defn- read-data!
  "A read of PPUDATA. Reads of video memory come through a buffer: each
  returns what the previous one fetched, so the first read after setting
  the address returns a stale byte. Palette reads don't wait: the palette
  is inside the PPU. (The buffer is still refilled, with the nametable byte
  'underneath' the palette.)"
  [ppu mem]
  (let [addr (bit-and (reg ppu V) 0x3FFF)
        value (if (< addr 0x3F00)
                (let [old (reg ppu BUFFER)]
                  (reg! ppu BUFFER (memory/video-read mem addr))
                  old)
                (do
                  (reg! ppu BUFFER (memory/video-read mem (bit-and addr 0x2FFF)))
                  ;; 6 bits of color; the top 2 are whatever was on the bus
                  (bit-or (bit-and (bytes/at (:palette ppu) (palette-index addr)) 0x3F)
                          (bit-and (reg ppu LATCH) 0xC0))))]
    (step-address! ppu)
    value))

(defn- write-data! [ppu mem value]
  (let [addr (bit-and (reg ppu V) 0x3FFF)]
    (if (< addr 0x3F00)
      (memory/video-write! mem addr value)
      (bytes/put! (:palette ppu) (palette-index addr) (bit-and value 0x3F)))
    (step-address! ppu)))

(defn read!
  "The CPU reads PPU register `addr` ($2000-$3FFF; only the low 3 bits
  matter). `mem` is the video memory (see nes.ppu.memory). Returns the byte
  read. Reading has effects: PPUSTATUS clears the vertical blank bit and
  w, PPUDATA moves the address on.

  The write-only registers read as the last byte on the PPU's data bus.
  (On a real NES that fades after a while; not here.)"
  [ppu mem addr]
  (let [value (case (int (bit-and addr 7))
                2 (let [v (bit-or (bit-and (reg ppu STATUS) 0xE0) (bit-and (reg ppu LATCH) 0x1F))]
                    (set-status! ppu VBLANK false)
                    (reg! ppu W 0)
                    v)
                4 (let [i (reg ppu OAM-ADDR)
                        v (bytes/at (:oam ppu) i)]
                    ;; a sprite's attribute byte has no bits 2-4: they read as 0
                    (if (= 2 (bit-and i 3)) (bit-and v 0xE3) v))
                7 (read-data! ppu mem)
                (reg ppu LATCH))]
    (reg! ppu LATCH value)
    value))

(defn write!
  "The CPU writes `value` to PPU register `addr` ($2000-$3FFF; only the low
  3 bits matter). `mem` is the video memory (see nes.ppu.memory)."
  [ppu mem addr value]
  (reg! ppu LATCH value)
  (case (int (bit-and addr 7))
    0 (do (reg! ppu CTRL value)
          (reg! ppu T (after-ctrl (reg ppu T) value)))
    1 (reg! ppu MASK value)
    2 nil                                  ; PPUSTATUS can't be written
    3 (reg! ppu OAM-ADDR value)
    4 (let [i (reg ppu OAM-ADDR)]
        (bytes/put! (:oam ppu) i value)
        (reg! ppu OAM-ADDR (bit-and (inc i) 0xFF)))
    5 (if (zero? (reg ppu W))
        (do (reg! ppu T (after-scroll-x (reg ppu T) value))
            (reg! ppu X (bit-and value 7))
            (reg! ppu W 1))
        (do (reg! ppu T (after-scroll-y (reg ppu T) value))
            (reg! ppu W 0)))
    6 (if (zero? (reg ppu W))
        (do (reg! ppu T (after-address-high (reg ppu T) value))
            (reg! ppu W 1))
        (let [t (after-address-low (reg ppu T) value)]
          (reg! ppu T t)
          (reg! ppu V t)
          (reg! ppu W 0)))
    7 (write-data! ppu mem value))
  nil)
