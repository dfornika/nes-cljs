(ns gamedev-toolkit.screen
  "A screen's background, drawn as text.

  The background is a grid of 32 by 30 tiles (a 'nametable'), plus 64
  bytes (the 'attribute table') saying which of the four background
  palettes each 16x16-pixel area uses. Palettes can't be chosen per tile,
  only per 16x16 area, so backgrounds are naturally made of 16x16 blocks
  of four tiles: 'metatiles'. A screen is 16 metatiles across and 15 down,
  which is small enough to type:

    (def metatiles
      {:sky  {:tiles [0 0 0 0]              :palette 0}
       :wall {:tiles (block tiles :wall)    :palette 1}})   ; see gamedev-toolkit.tileset

    (screen {:metatiles metatiles
             :legend    {\\. :sky, \\# :wall}
             :rows      [\"################\"
                         \"#..............#\"
                         ...]                               ; 15 rows of 16
             :text      [[11 2 \"SCORE 000\"]]})             ; column, row (in tiles), words

  A whole screen is 1024 bytes, mostly repeats, so it's stored in the
  cartridge packed (`pack`): 'this many of this byte', over and over.
  gamedev-toolkit.runtime unpacks it straight into video memory."
  (:require [gamedev-toolkit.font :as font]))

(def columns 16)
(def rows 15)

(defn screen
  "Build a screen (see the namespace docstring). `:text` is written over
  the metatiles with gamedev-toolkit.font, whose first tile is
  `:font-base` in the cartridge (default 0).

  A metatile can also say what it's like to bump into: {:solid? true}
  for a wall or a floor. That goes in the screen's :solid map, a byte for
  each metatile (1 for solid), which gamedev-toolkit.runtime's `solid-at`
  looks things up in.

  Returns {:tiles [960 tile numbers, row by row] :attributes [64 bytes]
  :solid [240 bytes, a row of 16 at a time]}."
  [{:keys [metatiles legend text font-base] drawn :rows :or {font-base 0}}]
  (when-not (and (= rows (count drawn)) (every? #(= columns (count %)) drawn))
    (throw (ex-info (str "A screen is " rows " rows of " columns " characters; this has "
                         (count drawn) " rows of " (vec (distinct (map count drawn))))
                    {:rows drawn})))
  (let [grid (mapv (fn [row]
                     (mapv (fn [ch]
                             (let [k (or (get legend ch)
                                         (throw (ex-info (str "The character " (pr-str ch) " isn't in the :legend") {:char ch})))]
                               (or (get metatiles k)
                                   (throw (ex-info (str "The :legend says " (pr-str ch) " is " k ", which isn't one of the :metatiles") {:metatile k})))))
                           row))
                   drawn)
        ;; each metatile is two tiles on each of two tile rows
        tiles (vec (mapcat (fn [row]
                             (concat (mapcat #(subvec (vec (:tiles %)) 0 2) row)
                                     (mapcat #(subvec (vec (:tiles %)) 2 4) row)))
                           grid))
        tiles (reduce (fn [tiles [col row words]]
                        (when-not (and (<= 0 col) (<= (+ col (count words)) 32) (< -1 row 30))
                          (throw (ex-info (str (pr-str words) " doesn't fit at column " col ", row " row) {})))
                        (reduce (fn [tiles [i tile]] (assoc tiles (+ (* 32 row) col i) tile))
                                tiles
                                (map-indexed vector (font/encode words font-base))))
                      tiles
                      text)
        palette-at (fn [col row] (get-in grid [row col :palette] 0))
        ;; One byte per 32x32 area: two bits for each of its four metatiles,
        ;; top-left in the lowest bits. (The last row of areas hangs off
        ;; the bottom of the screen, half unused.)
        attributes (vec (for [ay (range 8), ax (range 8)
                              :let [c (* 2 ax), r (* 2 ay)]]
                          (bit-or (palette-at c r)
                                  (bit-shift-left (palette-at (inc c) r) 2)
                                  (bit-shift-left (palette-at c (inc r)) 4)
                                  (bit-shift-left (palette-at (inc c) (inc r)) 6))))]
    {:tiles tiles
     :attributes attributes
     :solid (vec (for [row grid, m row] (if (:solid? m) 1 0)))}))

(defn nametable
  "A screen as the 1024 bytes of video memory it fills: tiles, then
  attributes."
  [{:keys [tiles attributes]}]
  (into (vec tiles) attributes))

(defn pack
  "Bytes, packed as runs: [count value count value ... 0]. A count is 1
  to 255, and a count of 0 marks the end."
  [bytes]
  (conj (vec (mapcat (fn [run]
                       (mapcat (fn [part] [(count part) (first part)])
                               (partition-all 255 run)))
                     (partition-by identity bytes)))
        0))

(defn unpack
  "The reverse of `pack`."
  [packed]
  (vec (mapcat (fn [[n value]] (repeat n value))
               (take-while #(pos? (first %)) (partition 2 packed)))))
