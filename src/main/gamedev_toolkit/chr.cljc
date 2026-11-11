(ns gamedev-toolkit.chr
  "Drawing NES graphics as text.

  Everything the NES draws is made of 8x8 pixel tiles. Each pixel is one
  of four values: 0 (transparent, or the background color) or 1-3, which
  pick a color from whichever palette the tile is drawn with. So a tile
  doesn't have colors of its own, only shapes.

  Here a tile is eight strings of eight characters: `.` for 0, and `1`
  `2` `3`:

    (tile [\"..1111..\"
           \".122221.\"
           ...])

  In CHR ROM a tile is 16 bytes: two 'bit planes' of 8 bytes each, one
  row per byte. The first plane has the low bit of each pixel, the second
  the high bit. (nes.chr decodes them, for the Tiles tab.)"
  (:require [clojure.string :as str]))

(def bank-size 8192)

(defn- pixel [c rows]
  (case c
    (\. \0 \space) 0
    \1 1
    \2 2
    \3 3
    (throw (ex-info (str "Pixels are . 1 2 or 3, not " (pr-str c)) {:rows rows}))))

(defn- plane-byte
  "One row of 8 pixels -> the byte holding bit `bit` of each."
  [pixels bit]
  (reduce (fn [acc p] (bit-or (bit-shift-left acc 1) (bit-and (bit-shift-right p bit) 1)))
          0 pixels))

(defn tile
  "One 8x8 tile, from 8 strings of 8 characters, as its 16 bytes."
  [rows]
  (when-not (and (= 8 (count rows)) (every? #(= 8 (count %)) rows))
    (throw (ex-info (str "A tile is 8 rows of 8 pixels:\n" (str/join "\n" rows)) {:rows rows})))
  (let [pixels (mapv (fn [row] (mapv #(pixel % rows) row)) rows)]
    (into (mapv #(plane-byte % 0) pixels)
          (mapv #(plane-byte % 1) pixels))))

(defn tiles
  "A bigger picture cut into tiles: `rows` are strings, with a width and
  height that are multiples of 8. Returns the tiles (16 bytes each) in
  reading order: left to right, then top to bottom."
  [rows]
  (let [w (count (first rows))]
    (when-not (and (pos? w) (zero? (mod w 8)) (zero? (mod (count rows) 8))
                   (every? #(= w (count %)) rows))
      (throw (ex-info (str "A picture's width and height must be multiples of 8, and its rows the same length:\n"
                           (str/join "\n" rows))
                      {:rows rows})))
    (vec (for [band (partition 8 rows)
               x (range 0 w 8)]
           (tile (mapv #(subs % x (+ x 8)) band))))))

(def blank
  "An empty tile."
  (vec (repeat 16 0)))

(defn bank
  "8 KB of CHR ROM (512 tiles' worth) holding `tiles` from tile 0 on, the
  rest empty. Tiles 0-255 are the first 'pattern table', 256-511 the
  second."
  [tiles]
  (let [bytes (into [] cat tiles)]
    (when (> (count bytes) bank-size)
      (throw (ex-info (str (count tiles) " tiles don't fit in 8 KB of CHR ROM (512 at most)") {})))
    (into bytes (repeat (- bank-size (count bytes)) 0))))
