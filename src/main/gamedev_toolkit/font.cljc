(ns gamedev-toolkit.font
  "A small font, as tiles, so games can put words and numbers on screen.

  The NES has no text mode: letters are tiles like everything else. Put
  `tiles` in the cartridge's graphics (starting at tile 0, or wherever
  you like), and `encode` turns a string into the tile numbers to write
  to the background.

  Each glyph is 5 pixels wide and 7 tall, drawn here as rows of # and .
  and placed in an 8x8 tile with a blank column on the left and a blank
  row underneath, so letters side by side don't touch."
  (:require [clojure.string :as str]
            [gamedev-toolkit.chr :as chr]))

(def ^:private glyph-rows
  "character -> its 7 rows, top to bottom, separated by spaces."
  {\space ". . . . . . ."
   \A ".###. #...# #...# ##### #...# #...# #...#"
   \B "####. #...# #...# ####. #...# #...# ####."
   \C ".###. #...# #.... #.... #.... #...# .###."
   \D "####. #...# #...# #...# #...# #...# ####."
   \E "##### #.... #.... ####. #.... #.... #####"
   \F "##### #.... #.... ####. #.... #.... #...."
   \G ".###. #...# #.... #.### #...# #...# .###."
   \H "#...# #...# #...# ##### #...# #...# #...#"
   \I ".###. ..#.. ..#.. ..#.. ..#.. ..#.. .###."
   \J "..### ...#. ...#. ...#. ...#. #..#. .##.."
   \K "#...# #..#. #.#.. ##... #.#.. #..#. #...#"
   \L "#.... #.... #.... #.... #.... #.... #####"
   \M "#...# ##.## #.#.# #.#.# #...# #...# #...#"
   \N "#...# ##..# #.#.# #..## #...# #...# #...#"
   \O ".###. #...# #...# #...# #...# #...# .###."
   \P "####. #...# #...# ####. #.... #.... #...."
   \Q ".###. #...# #...# #...# #.#.# #..#. .##.#"
   \R "####. #...# #...# ####. #.#.. #..#. #...#"
   \S ".#### #.... #.... .###. ....# ....# ####."
   \T "##### ..#.. ..#.. ..#.. ..#.. ..#.. ..#.."
   \U "#...# #...# #...# #...# #...# #...# .###."
   \V "#...# #...# #...# #...# #...# .#.#. ..#.."
   \W "#...# #...# #...# #.#.# #.#.# ##.## #...#"
   \X "#...# #...# .#.#. ..#.. .#.#. #...# #...#"
   \Y "#...# #...# .#.#. ..#.. ..#.. ..#.. ..#.."
   \Z "##### ....# ...#. ..#.. .#... #.... #####"
   \0 ".###. #...# #..## #.#.# ##..# #...# .###."
   \1 "..#.. .##.. ..#.. ..#.. ..#.. ..#.. .###."
   \2 ".###. #...# ....# ...#. ..#.. .#... #####"
   \3 "####. ....# ....# .###. ....# ....# ####."
   \4 "...#. ..##. .#.#. #..#. ##### ...#. ...#."
   \5 "##### #.... ####. ....# ....# #...# .###."
   \6 ".###. #.... #.... ####. #...# #...# .###."
   \7 "##### ....# ...#. ..#.. .#... .#... .#..."
   \8 ".###. #...# #...# .###. #...# #...# .###."
   \9 ".###. #...# #...# .#### ....# ....# .###."
   \! "..#.. ..#.. ..#.. ..#.. ..#.. ..... ..#.."
   \. "..... ..... ..... ..... ..... .##.. .##.."
   \: "..... .##.. .##.. ..... .##.. .##.. ....."
   \- "..... ..... ..... ##### ..... ..... ....."
   \? ".###. #...# ....# ...#. ..#.. ..... ..#.."})

(def characters
  "The characters the font has, in tile order. The space comes first, so
  a background filled with tile 0 is blank."
  " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!.:-?")

(defn- glyph-tile
  "A glyph's rows as an 8x8 tile, drawn in color 1."
  [ch]
  (let [rows (str/split (glyph-rows ch) #" ")
        rows (if (= ch \space) (repeat 7 ".....") rows)]
    (when-not (and (= 7 (count rows)) (every? #(= 5 (count %)) rows))
      (throw (ex-info (str "The glyph for " (pr-str ch) " isn't 7 rows of 5") {:char ch})))
    (chr/tile (conj (mapv #(str "." (str/replace % "#" "1") "..") rows)
                    "........"))))

(def tiles
  "The font's tiles (16 bytes each), one per character in `characters`."
  (mapv glyph-tile characters))

(def ^:private index-of
  (into {} (map-indexed (fn [i ch] [ch i]) characters)))

(defn tile-of
  "The tile number of character `ch`, with the font's first tile at
  `base` (default 0). Lower-case letters use the capitals."
  ([ch] (tile-of ch 0))
  ([ch base]
   (let [upper (first (str/upper-case (str ch)))]
     (+ base (or (index-of upper)
                 (throw (ex-info (str "The font has no " (pr-str ch) ". It has: " characters) {:char ch})))))))

(defn encode
  "The tile numbers that spell `text`."
  ([text] (encode text 0))
  ([text base] (mapv #(tile-of % base) text)))
