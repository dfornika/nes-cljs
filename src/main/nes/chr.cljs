(ns nes.chr
  "Decoding NES tile graphics ('CHR' data).

  Everything the NES draws is made of 8x8 pixel tiles. Each pixel is one
  of 4 colors, so it takes 2 bits. A tile is therefore 8 x 8 x 2 = 128
  bits = 16 bytes, stored as two 'bitplanes':

    bytes 0-7   plane 0: the LOW bit of each pixel, one byte per row
    bytes 8-15  plane 1: the HIGH bit of each pixel, one byte per row

  Within a row's byte, the leftmost pixel is bit 7 (the highest bit).
  So the color number of the pixel at (x, y) is:

    (plane-1-bit << 1) | plane-0-bit     ; 0, 1, 2 or 3

  The color number isn't a color yet: it picks one of the 4 colors in
  whichever palette the game chooses for that tile. Number 0 always
  means 'transparent / background color'.

  The PPU sees two 'pattern tables' of 256 tiles each: $0000-$0FFF and
  $1000-$1FFF. A game usually uses one for the background and the other
  for sprites.

  See https://www.nesdev.org/wiki/PPU_pattern_tables")

(def tile-bytes 16)
(def tiles-per-table 256)
(def pattern-table-bytes (* tile-bytes tiles-per-table)) ; $1000

(defn tile-address
  "PPU address of tile number `tile` (0-255) in pattern table `table` (0 or 1)."
  [table tile]
  (+ (* table pattern-table-bytes) (* tile tile-bytes)))

(defn pixel
  "Color number (0-3) of the pixel at column `x`, row `y` of the tile
  starting at `addr` in `mem` (a Uint8Array)."
  [^js mem addr x y]
  (let [lo (aget mem (+ addr y))
        hi (aget mem (+ addr y 8))
        bit (- 7 x)]
    (bit-or (bit-and (bit-shift-right lo bit) 1)
            (bit-shift-left (bit-and (bit-shift-right hi bit) 1) 1))))

(defn tile-pixels
  "The 8x8 color numbers of the tile at `addr`, as 8 rows of 8."
  [mem addr]
  (vec (for [y (range 8)]
         (vec (for [x (range 8)]
                (pixel mem addr x y))))))

(defn tile-planes
  "The tile at `addr` split into its parts, for explaining how it's stored:
  `:bytes` the 16 raw bytes, and `:plane0` / `:plane1` the bits as 8 rows
  of 8."
  [^js mem addr]
  (let [bits (fn [b] (vec (for [x (range 8)] (bit-and (bit-shift-right b (- 7 x)) 1))))]
    {:bytes (vec (for [i (range tile-bytes)] (aget mem (+ addr i))))
     :plane0 (vec (for [y (range 8)] (bits (aget mem (+ addr y)))))
     :plane1 (vec (for [y (range 8)] (bits (aget mem (+ addr y 8)))))}))

;; ---------------------------------------------------------------------------
;; Palettes
;;
;; Palette RAM is 32 bytes at PPU $3F00-$3F1F: eight palettes of 4 colors.
;; Palettes 0-3 are for the background, 4-7 for sprites. Each byte is an
;; index into the NES's fixed set of 64 colors (the 'master palette').
;; Entry 0 of every palette is really the shared background color at $3F00.

(def palette-ram-start 0x3F00)

(defn palette-colors
  "The 4 colors of palette `p` (0-7), as indexes into the master palette."
  [^js ppu-mem p]
  (vec (for [i (range 4)]
         (bit-and 0x3F (aget ppu-mem (+ palette-ram-start
                                        (if (zero? i) 0 (+ (* 4 p) i))))))))

(def grayscale
  "Four shades of gray as 0x00BBGGRR, for viewing tiles without a palette."
  [0x000000 0x666666 0xADADAD 0xFFFFFF])

(defn bgr->css
  "A 0x00BBGGRR color as a CSS color string."
  [bgr]
  (str "rgb(" (bit-and bgr 0xFF) ","
       (bit-and (bit-shift-right bgr 8) 0xFF) ","
       (bit-and (bit-shift-right bgr 16) 0xFF) ")"))

(defn light?
  "True if the 0x00BBGGRR color is light enough that black text shows up
  better on it than white."
  [bgr]
  (let [r (bit-and bgr 0xFF)
        g (bit-and (bit-shift-right bgr 8) 0xFF)
        b (bit-and (bit-shift-right bgr 16) 0xFF)]
    (> (+ (* 0.299 r) (* 0.587 g) (* 0.114 b)) 140)))

;; ---------------------------------------------------------------------------
;; Drawing

(defn draw-pattern-table!
  "Paint pattern table `table` (0 or 1) from `mem` into `pixels`, a
  Uint32Array for a 128x128 image laid out as 16 x 16 tiles.
  `colors` is 4 colors as 0x00BBGGRR."
  [^js pixels ^js mem table colors]
  (let [c0 (nth colors 0) c1 (nth colors 1) c2 (nth colors 2) c3 (nth colors 3)]
    (dotimes [tile tiles-per-table]
      (let [addr (tile-address table tile)
            ox (* 8 (mod tile 16))
            oy (* 8 (quot tile 16))]
        (dotimes [y 8]
          (dotimes [x 8]
            (aset pixels (+ (* 128 (+ oy y)) ox x)
                  (bit-or 0xFF000000
                          (case (pixel mem addr x y)
                            0 c0 1 c1 2 c2 c3)))))))))
