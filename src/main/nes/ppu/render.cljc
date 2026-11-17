(ns nes.ppu.render
  "Drawing a whole frame at once, from the PPU's memory and registers.

  A real PPU draws one dot at a time, and a game can change things while
  it does: the scroll halfway down for a status bar, say. This draws the
  frame as if nothing changed while it was drawn: every line from the same
  memory, sprites and settings. For most frames of most games that's the
  same picture. It's here to be a reference: the dot-by-dot PPU (step 3 of
  docs/ppu_plan.md) has to draw exactly this on any frame where nothing
  changes partway, and that's easy to test. It's also handy for showing
  what's in memory.

  It follows the PPU's own rules:

  - The background starts from t (with fine X scroll x), as the PPU's v
    does each frame. Each line copies t's horizontal part to v, steps v
    across a tile at a time, and steps it down a line at the end, using
    the same functions as nes.ppu.registers. So rows 30 and 31 and
    wrapping into the next nametable work as on a real NES.
  - Sprites: a sprite with Y in OAM is on lines Y+1 to Y+8 (or Y+16), so
    none can be on line 0. Only the first 8 on a line are drawn (the
    lowest numbered). Where sprites overlap, the lowest numbered opaque
    pixel wins, even if it's behind the background.
  - A sprite pixel behind the background (attribute bit 5) shows only
    where the background is color 0.
  - PPUMASK can hide the background or sprites in the left 8 pixels, or
    everywhere, and turn the picture gray. Its tint bits (emphasis) go
    out with each pixel, for whatever turns pixels into colors.
  - Sprite 0 hit: where an opaque pixel of sprite 0 first lands on an
    opaque background pixel (never at x 255, nor in the left 8 pixels
    when either is hidden there).

  The picture is palette indexes, not colors: 256x240 numbers, each the
  64-color index (6 bits) with the emphasis bits above it (bits 6-8).
  `colors!` turns them into RGB with a master palette."
  (:require [nes.bytes :as bytes]
            [nes.ppu.memory :as memory]
            [nes.ppu.registers :as r]))

(def ^:const width 256)
(def ^:const height 240)

(defn picture
  "A new, empty picture: 256x240 palette indexes."
  []
  (bytes/make-words (* width height)))

;; ---------------------------------------------------------------------------
;; The background

(defn- background-line!
  "Draw line `y` of the background into `line` (256 bytes: 0 for color 0,
  else palette*4 + color, as in palette RAM), from `v` at the start of the
  line and fine X `fine-x`."
  [line mem v fine-x table]
  (loop [v v, tile 0]
    (when (< tile 33)                     ; 33 tiles: with fine X, 32 aren't enough
      (let [name (memory/video-read mem (bit-or 0x2000 (bit-and v 0x0FFF)))
            attr (memory/video-read mem (bit-or 0x23C0 (bit-and v 0x0C00)
                                                (bit-and (bit-shift-right v 4) 0x38)
                                                (bit-and (bit-shift-right v 2) 0x07)))
            ;; which quarter of the attribute byte's 32x32 area this tile is in
            shift (bit-or (bit-and (bit-shift-right v 4) 4) (bit-and v 2))
            palette (bit-and (bit-shift-right attr shift) 3)
            addr (+ (* table 0x1000) (* name 16) (r/fine-y v))
            lo (memory/video-read mem addr)
            hi (memory/video-read mem (+ addr 8))
            x0 (- (* tile 8) fine-x)]
        (dotimes [i 8]
          (let [x (+ x0 i)]
            (when (< -1 x width)
              (let [bit (- 7 i)
                    color (bit-or (bit-and (bit-shift-right lo bit) 1)
                                  (bit-shift-left (bit-and (bit-shift-right hi bit) 1) 1))]
                (bytes/put! line x (if (zero? color) 0 (bit-or (bit-shift-left palette 2) color)))))))
        (recur (r/increment-x v) (inc tile))))))

;; ---------------------------------------------------------------------------
;; Sprites

(defn- sprite-line!
  "Draw the sprites on line `y` into `line` (256 bytes: 0 where there's no
  sprite, else $80 + (sprite 0? $40) + (behind? $20) + palette*4 + color).
  Returns the number of sprites on the line, before the limit of 8 (more
  than 8 sets the overflow flag)."
  [line mem oam y sprite-height sprite-table]
  (bytes/fill! line 0)
  (loop [i 0, found 0]
    (if (= i 64)
      found
      (let [top (inc (bytes/at oam (* 4 i)))
            row (- y top)]
        (if-not (< -1 row sprite-height)
          (recur (inc i) found)
          (do
            (when (< found 8)
              (let [tile (bytes/at oam (+ 1 (* 4 i)))
                    attr (bytes/at oam (+ 2 (* 4 i)))
                    sx (bytes/at oam (+ 3 (* 4 i)))
                    row (if (bit-test attr 7) (- sprite-height 1 row) row)   ; flipped top to bottom
                    addr (if (= sprite-height 16)
                           (+ (* (bit-and tile 1) 0x1000)
                              (* (+ (bit-and tile 0xFE) (if (< row 8) 0 1)) 16)
                              (bit-and row 7))
                           (+ (* sprite-table 0x1000) (* tile 16) row))
                    lo (memory/video-read mem addr)
                    hi (memory/video-read mem (+ addr 8))
                    tag (bit-or 0x80 (if (zero? i) 0x40 0) (if (bit-test attr 5) 0x20 0)
                                (bit-shift-left (bit-and attr 3) 2))]
                (dotimes [c 8]
                  (let [x (+ sx c)
                        bit (if (bit-test attr 6) c (- 7 c))   ; flipped side to side
                        color (bit-or (bit-and (bit-shift-right lo bit) 1)
                                      (bit-shift-left (bit-and (bit-shift-right hi bit) 1) 1))]
                    ;; the lowest numbered sprite's opaque pixel wins
                    (when (and (< x width) (pos? color) (zero? (bytes/at line x)))
                      (bytes/put! line x (bit-or tag color)))))))
            (recur (inc i) (inc found))))))))

;; ---------------------------------------------------------------------------
;; The frame

(defn inputs
  "What `render!` needs, from a PPU's registers (nes.ppu.registers) and its
  video memory: as at the start of a frame, when the PPU copies t to v."
  [ppu mem]
  (let [{:keys [ctrl mask t x]} (r/registers ppu)]
    {:memory mem :palette (:palette ppu) :oam (:oam ppu)
     :ctrl ctrl :mask mask :t t :x x}))

(defn render!
  "Draw a frame into `out` (from `picture`). `in` is a map:

    :memory   video memory (nes.ppu.memory/VideoMemory): tiles, nametables
    :palette  the 32 bytes of palette RAM
    :oam      the 256 bytes of sprite memory
    :ctrl     PPUCTRL
    :mask     PPUMASK
    :t :x     the scroll: t and fine X, as nes.ppu.registers keeps them

  Returns what the PPU would have noticed on the way:
  {:sprite-0-hit [x y] or nil, :overflow-line the first line with more
  than 8 sprites, or nil}."
  [out {:keys [memory palette oam ctrl mask t x]}]
  (let [show-bg? (bit-test mask 3)
        show-sprites? (bit-test mask 4)
        bg-left? (bit-test mask 1)
        sprites-left? (bit-test mask 2)
        gray (if (bit-test mask 0) 0x30 0x3F)
        emphasis (bit-shift-left (bit-shift-right mask 5) 6)
        bg-table (if (bit-test ctrl 4) 1 0)
        sprite-table (if (bit-test ctrl 3) 1 0)
        sprite-height (if (bit-test ctrl 5) 16 8)
        bg (bytes/make width)
        spr (bytes/make width)
        color-of (fn [i] (bit-or emphasis (bit-and (bytes/at palette (r/palette-index i)) gray)))
        backdrop (color-of 0)]
    (if-not (or show-bg? show-sprites?)
      ;; Drawing off: the backdrop color everywhere. (On a real NES, if v
      ;; points into the palette, that color instead. Some demos use it.)
      (do (dotimes [i (* width height)] (bytes/put-word! out i backdrop))
          {:sprite-0-hit nil :overflow-line nil})
      (loop [y 0, v (r/copy-y 0 t), hit nil, overflow nil]
        (if (= y height)
          {:sprite-0-hit hit :overflow-line overflow}
          (let [v (r/copy-x v t)
                _ (if show-bg? (background-line! bg memory v x bg-table) (bytes/fill! bg 0))
                found (if show-sprites? (sprite-line! spr memory oam y sprite-height sprite-table) (do (bytes/fill! spr 0) 0))
                hit (loop [px 0, hit hit]
                      (if (= px width)
                        hit
                        (let [b (if (or bg-left? (>= px 8)) (bytes/at bg px) 0)
                              s (if (or sprites-left? (>= px 8)) (bytes/at spr px) 0)
                              b-opaque? (pos? (bit-and b 3))
                              s-opaque? (pos? (bit-and s 3))
                              index (cond
                                      (and s-opaque? (or (not b-opaque?) (not (bit-test s 5))))
                                      (bit-or 0x10 (bit-and s 0x0F))
                                      b-opaque? b
                                      :else 0)]
                          (bytes/put-word! out (+ (* y width) px) (color-of index))
                          (recur (inc px)
                                 (or hit
                                     (when (and b-opaque? s-opaque? (bit-test s 6) (< px 255))
                                       [px y]))))))]
            (recur (inc y)
                   (r/increment-y v)
                   hit
                   (or overflow (when (> found 8) y)))))))))

(defn render
  "`render!` into a new picture. Returns {:pixels :sprite-0-hit :overflow-line}."
  [in]
  (let [out (picture)]
    (assoc (render! out in) :pixels out)))

(defn colors!
  "Turn `pixels` (palette indexes, from `render!`) into colors in `out`,
  using `master` (64 colors, 32-bit numbers). The emphasis bits are
  ignored: a master palette with the tints already applied can stand in
  for them, as jsnes's does."
  [out pixels master]
  (dotimes [i (* width height)]
    (bytes/put-word! out i (bytes/word-at master (bit-and (bytes/word-at pixels i) 0x3F))))
  out)
