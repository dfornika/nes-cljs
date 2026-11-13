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

(def kinds
  "What a block is like to bump into, as the numbers in a screen's :solid
  map. :ladder-top isn't drawn: it's worked out (see `kind-bytes`)."
  {:empty 0 :solid 1 :one-way 2 :ladder 3 :ladder-top 4})

(defn- kind-of [m]
  (let [k (cond (:kind m) (:kind m) (:solid? m) :solid :else :empty)]
    (when-not (contains? kinds k)
      (throw (ex-info (str "Unknown kind of block " (pr-str k) ". There are: " (sort (keys (dissoc kinds :ladder-top)))) {:kind k})))
    k))

(defn kind-bytes
  "A grid of kinds (rows of keywords) as the bytes of a :solid map.

  A ladder whose block above isn't ladder is the ladder's top, and becomes
  :ladder-top: climbable, and also a one-way platform, so that whoever
  climbs to the top can step off and stand there. That's worked out here,
  when the ROM is built, so the game never has to look at the block above."
  [grid]
  (vec (for [[r row] (map-indexed vector grid)
             [c k] (map-indexed vector row)]
         (kinds (if (and (= k :ladder)
                         (or (zero? r) (not= :ladder (get-in grid [(dec r) c]))))
                  :ladder-top
                  k)))))

(def columns 16)
(def rows 15)

(defn- write-text
  "`tiles` (960 of them) with each [col row words] of `text` written over."
  [tiles text font-base]
  (reduce (fn [tiles [col row words]]
            (when-not (and (<= 0 col) (<= (+ col (count words)) 32) (< -1 row 30))
              (throw (ex-info (str (pr-str words) " doesn't fit at column " col ", row " row) {})))
            (reduce (fn [tiles [i tile]] (assoc tiles (+ (* 32 row) col i) tile))
                    tiles
                    (map-indexed vector (font/encode words font-base))))
          (vec tiles)
          text))

(defn- attributes
  "The 64 attribute bytes, given (palette-at col row) for each 16x16
  block. One byte per 32x32 area: two bits for each of its four blocks,
  top-left in the lowest bits. (The last row of areas hangs off the bottom
  of the screen, half unused.)"
  [palette-at]
  (let [at (fn [col row] (if (and (< col columns) (< row rows)) (palette-at col row) 0))]
    (vec (for [ay (range 8), ax (range 8)
               :let [c (* 2 ax), r (* 2 ay)]]
           (bit-or (at c r)
                   (bit-shift-left (at (inc c) r) 2)
                   (bit-shift-left (at c (inc r)) 4)
                   (bit-shift-left (at (inc c) (inc r)) 6))))))

(defn screen
  "Build a screen (see the namespace docstring). `:text` is written over
  the metatiles with gamedev-toolkit.font, whose first tile is
  `:font-base` in the cartridge (default 0).

  A metatile can also say what it's like to bump into, its *kind*:

    {:kind :solid}      a wall or a floor (or {:solid? true})
    {:kind :one-way}    a platform you can jump up through and land on
    {:kind :ladder}     climbable; not solid

  That goes in the screen's :solid map, a byte for each metatile (see
  `kinds`), which gamedev-toolkit.runtime's `solid-at` looks things up in.

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
        tiles (write-text tiles text font-base)]
    {:tiles tiles
     :attributes (attributes (fn [col row] (get-in grid [row col :palette] 0)))
     :solid (kind-bytes (mapv #(mapv kind-of %) grid))}))

(defn from-tiles
  "A screen from art that's already tiles (a picture turned into tiles by
  gamedev-toolkit.png, say), rather than drawn with metatiles:

    :grid            960 tile numbers, row by row
    :tile-base       where those tiles start in the cartridge (default 0)
    :block-palettes  240 palette numbers, one per 16x16 block
    :solid           what's solid, drawn as for `screen`: 15 rows of 16
                     characters, one per block
    :kinds           which characters are which kind of block, e.g.
                     {# :solid, = :one-way, H :ladder} (as character
                     literals); others are empty. Default: just # is solid
    :text            as for `screen`; the blocks it covers get palette
                     `:text-palette` (default 0)"
  [{:keys [grid tile-base block-palettes solid kinds text text-palette font-base]
    :or {tile-base 0 kinds {\# :solid} text-palette 0 font-base 0}}]
  (when-not (and (= 960 (count grid)) (= 240 (count block-palettes)))
    (throw (ex-info "A screen is 960 tiles and 240 block palettes" {})))
  (when-not (and (= rows (count solid)) (every? #(= columns (count %)) solid))
    (throw (ex-info (str ":solid is " rows " rows of " columns " characters") {:solid solid})))
  (let [lettered (set (for [[col row words] text, i (range (count words))]
                        [(quot (+ col i) 2) (quot row 2)]))]
    {:tiles (write-text (mapv #(+ tile-base %) grid) text font-base)
     :attributes (attributes (fn [col row]
                               (if (lettered [col row])
                                 text-palette
                                 (nth block-palettes (+ (* 16 row) col) 0))))
     :solid (kind-bytes (mapv (fn [line] (mapv #(kind-of {:kind (get kinds % :empty)}) line)) solid))}))

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
