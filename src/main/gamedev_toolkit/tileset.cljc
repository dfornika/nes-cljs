(ns gamedev-toolkit.tileset
  "A cartridge's tiles, with names instead of numbers.

  The PPU knows tiles by number: their position in the cartridge's
  graphics. A tileset is where a game's drawings get those numbers, in the
  order they're listed, so the rest of the game can say :wall or :face
  and never count tiles:

    (def tiles
      (tileset [[:font   font/tiles]     ; tiles that are already made
                [:face   face-drawing]   ; a drawing: rows of . 1 2 3
                [:wall   wall-drawing]]))

    (tile tiles :wall)          ; => its first tile's number
    (block tiles :wall)         ; => a 16x16 drawing's four tiles
    (sprites tiles :face)       ; => the pieces to draw it as sprites

  A drawing can be any size that's a multiple of 8 each way; it's cut
  into tiles in reading order (see gamedev-toolkit.chr/tiles)."
  (:require [gamedev-toolkit.chr :as chr]))

(defn tileset
  "Number the tiles of `entries`, [[name drawing-or-tiles] ...], from 0.
  Returns {:tiles [16 bytes each ...] :entries {name {:first :width
  :height}}} (width and height in tiles; ready-made tiles count as one row)."
  [entries]
  (reduce (fn [{:keys [tiles] :as acc} [name art]]
            (when (contains? (:entries acc) name)
              (throw (ex-info (str "Two entries in the tileset are both called " name) {:name name})))
            (let [drawing? (string? (first art))
                  made (if drawing? (chr/tiles art) (vec art))]
              (-> acc
                  (update :tiles into made)
                  (assoc-in [:entries name]
                            {:first (count tiles)
                             :width (if drawing? (quot (count (first art)) 8) (count made))
                             :height (if drawing? (quot (count art) 8) 1)}))))
          {:tiles [] :entries {}}
          entries))

(defn- entry [tileset name]
  (or (get-in tileset [:entries name])
      (throw (ex-info (str "The tileset has nothing called " name ". It has: " (sort (keys (:entries tileset))))
                      {:name name}))))

(defn tile
  "The number of the tile called `name`: its first, or the one at column
  `col`, row `row` of its drawing."
  ([tileset name] (tile tileset name 0 0))
  ([tileset name col row]
   (let [{:keys [first width height]} (entry tileset name)]
     (when-not (and (< -1 col width) (< -1 row height))
       (throw (ex-info (str name " is " width " by " height " tiles; there's no tile at column " col ", row " row) {})))
     (+ first (* row width) col))))

(defn block
  "The four tiles of a 16x16 drawing, as [top-left top-right bottom-left
  bottom-right]: what a metatile wants (see gamedev-toolkit.screen)."
  [tileset name]
  (let [{:keys [width height]} (entry tileset name)]
    (when-not (= [2 2] [width height])
      (throw (ex-info (str "A block is 16x16 pixels (2 by 2 tiles); " name " is " width " by " height " tiles") {})))
    [(tile tileset name 0 0) (tile tileset name 1 0) (tile tileset name 0 1) (tile tileset name 1 1)]))

(defn sprites
  "The pieces to draw `name` as sprites, for gamedev-toolkit.runtime's
  metasprite: [[tile dx dy attributes] ...]. `attributes` is the sprite
  palette (0 to 3), plus 0x40 to flip left-right and 0x80 upside down."
  ([tileset name] (sprites tileset name 0))
  ([tileset name attributes]
   (let [{:keys [first width height]} (entry tileset name)]
     (vec (for [row (range height), col (range width)]
            [(+ first (* row width) col) (* 8 col) (* 8 row) attributes])))))
