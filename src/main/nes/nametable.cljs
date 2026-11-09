(ns nes.nametable
  "Decoding the background: nametables and attribute tables.

  The background is a grid of 8x8 tiles, 32 across and 30 down (256x240
  pixels). A 'nametable' is 1 KB of PPU memory describing one screen of it:

    960 bytes   one tile number per grid square, row by row
     64 bytes   the 'attribute table': which of the 4 background palettes
                each area uses

  Attribute bytes are coarse. Each covers a 32x32 pixel block (4x4 tiles),
  split into four 16x16 quarters, each with a 2-bit palette number:

    bits 1-0  top-left      bits 3-2  top-right
    bits 5-4  bottom-left   bits 7-6  bottom-right

  The PPU has room for four nametables, at $2000, $2400, $2800 and $2C00,
  laid out as a 2x2 square (512x480 pixels) that the screen scrolls
  around in. But the console only has 2 KB of video RAM, so two of the
  four are copies of the other two. The cartridge decides which
  ('mirroring'):

    vertical:   $2000 = $2800, $2400 = $2C00  (room to scroll sideways)
    horizontal: $2000 = $2400, $2800 = $2C00  (room to scroll up and down)

  See https://www.nesdev.org/wiki/PPU_nametables and
  https://www.nesdev.org/wiki/PPU_attribute_tables"
  (:require [nes.chr :as chr]))

(def addresses
  "PPU addresses of the four nametables: top-left, top-right, bottom-left,
  bottom-right."
  [0x2000 0x2400 0x2800 0x2C00])

(def cols 32)
(def rows 30)

(defn tile-address
  "PPU address of the tile number for (col, row) in the nametable at `base`."
  [base col row]
  (+ base (* row cols) col))

(defn attribute-address
  "PPU address of the attribute byte covering tile (col, row)."
  [base col row]
  (+ base 0x3C0 (* 8 (quot row 4)) (quot col 4)))

(defn attribute-shift
  "Which 2 bits of the attribute byte belong to tile (col, row)."
  [col row]
  (+ (* 4 (quot (mod row 4) 2))
     (* 2 (quot (mod col 4) 2))))

(defn palette-of
  "Background palette (0-3) used by tile (col, row) of the nametable at
  `base`. `peek` reads PPU memory."
  [peek base col row]
  (bit-and 3 (bit-shift-right (peek (attribute-address base col row))
                              (attribute-shift col row))))

(defn tile-info
  "Everything about the tile at (col, row) of nametable `n` (0-3)."
  [peek n col row bg-table]
  (let [base (nth addresses n)
        tile (peek (tile-address base col row))]
    {:nametable n
     :base base
     :col col
     :row row
     :address (tile-address base col row)
     :tile tile
     :pattern-address (chr/tile-address bg-table tile)
     :attribute-address (attribute-address base col row)
     :attribute (peek (attribute-address base col row))
     :palette (palette-of peek base col row)}))

(defn draw-all!
  "Paint all four nametables into `pixels`, a Uint32Array for a 512x480
  image. `peek` reads PPU memory (with mirroring), `mem` is PPU memory for
  the pattern tables, `bg-table` is 0 or 1, and `palettes` is the four
  background palettes as vectors of 4 colors (0x00BBGGRR)."
  [^js pixels peek ^js mem bg-table palettes]
  (let [table-base (* bg-table 0x1000)]
    (dotimes [n 4]
      (let [base (nth addresses n)
            ox (* 256 (mod n 2))
            oy (* 240 (quot n 2))]
        (dotimes [row rows]
          (dotimes [col cols]
            (let [tile (peek (tile-address base col row))
                  colors (nth palettes (palette-of peek base col row))
                  c0 (bit-or 0xFF000000 (nth colors 0))
                  c1 (bit-or 0xFF000000 (nth colors 1))
                  c2 (bit-or 0xFF000000 (nth colors 2))
                  c3 (bit-or 0xFF000000 (nth colors 3))
                  paddr (+ table-base (* 16 tile))]
              (dotimes [y 8]
                (let [lo (aget mem (+ paddr y))
                      hi (aget mem (+ paddr y 8))
                      line (+ (* 512 (+ oy (* 8 row) y)) ox (* 8 col))]
                  (dotimes [x 8]
                    (let [bit (- 7 x)
                          v (bit-or (bit-and (bit-shift-right lo bit) 1)
                                    (bit-shift-left (bit-and (bit-shift-right hi bit) 1) 1))]
                      (aset pixels (+ line x) (case v 0 c0 1 c1 2 c2 c3)))))))))))))

(defn camera-rects
  "The screen's 256x240 view of the 512x480 nametable space, starting at
  (`x`, `y`), as rectangles {:x :y :w :h}. The view wraps around the
  edges, so it can be split into up to four pieces."
  [x y]
  (let [x (mod x 512) y (mod y 480)
        xs (if (> (+ x 256) 512) [[x (- 512 x)] [0 (- (+ x 256) 512)]] [[x 256]])
        ys (if (> (+ y 240) 480) [[y (- 480 y)] [0 (- (+ y 240) 480)]] [[y 240]])]
    (vec (for [[ry rh] ys [rx rw] xs] {:x rx :y ry :w rw :h rh}))))
