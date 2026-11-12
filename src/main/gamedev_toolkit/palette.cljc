(ns gamedev-toolkit.palette
  "Colors and palettes, by name.

  The NES can show 64 colors (really about 54: some repeat). A color is a
  number from $00 to $3F: the low digit is the hue, the high digit how
  light it is. `colors` gives them names: :red, :dark-red, :light-red,
  :pale-red, and so on round the color wheel.

  What a game can use at once is far less: a backdrop color, and eight
  palettes of three colors each, four for the background and four for
  sprites. Every tile or sprite is drawn with one palette, its pixels
  numbered 1 to 3 picking from it, and 0 showing the backdrop (or, in a
  sprite, whatever is behind it).

    (palettes {:backdrop   :dark-blue
               :background [[:white :gray :dark-gray]]
               :sprites    [[:black :yellow :white]
                            [:orange :pale-yellow :white]]})

  gives the 32 bytes the PPU wants. Palettes left out are black."
  (:require [clojure.string :as str]))

(def ^:private hues
  ;; the low digit of a color number -> what to call it
  {1 "blue" 2 "indigo" 3 "violet" 4 "magenta" 5 "rose" 6 "red" 7 "orange"
   8 "yellow" 9 "lime" 0xA "green" 0xB "mint" 0xC "cyan"})

(def colors
  "Name -> color number. Each hue comes in four: :dark-red, :red,
  :light-red, :pale-red. Plus :black, :dark-gray, :gray, :light-gray,
  :white."
  (into {:black 0x0F :dark-gray 0x00 :gray 0x10 :light-gray 0x3D :white 0x30}
        (for [[digit hue] hues
              [row shade] [[0 "dark-"] [1 ""] [2 "light-"] [3 "pale-"]]]
          [(keyword (str shade hue)) (+ (* 16 row) digit)])))

(defn color
  "The number of a color: a name from `colors`, or a number 0 to 63."
  [c]
  (cond
    (and (int? c) (<= 0 c 0x3F)) c
    (contains? colors c) (colors c)
    :else (throw (ex-info (str "Unknown color " (pr-str c) ". Colors are numbers 0 to 63, or names like "
                               (str/join ", " (map pr-str [:black :white :gray :red :dark-red :light-red :pale-red :blue :green :yellow]))
                               " (see gamedev-toolkit.palette/colors)")
                          {:color c}))))

(defn- four-palettes [backdrop palettes what]
  (when (> (count palettes) 4)
    (throw (ex-info (str "There are four " what " palettes, not " (count palettes)) {})))
  (mapcat (fn [p]
            (when-not (= 3 (count p))
              (throw (ex-info (str "A palette is three colors (color 0 is the backdrop), not " (pr-str p)) {:palette p})))
            (cons backdrop (map color p)))
          (take 4 (concat palettes (repeat [:black :black :black])))))

(defn palettes
  "The 32 bytes of palette memory, from {:backdrop color, :background
  [[c1 c2 c3] ...], :sprites [[c1 c2 c3] ...]} (up to four palettes of
  each kind)."
  [{:keys [backdrop background sprites] :or {backdrop :black}}]
  (let [backdrop (color backdrop)]
    (vec (concat (four-palettes backdrop background "background")
                 (four-palettes backdrop sprites "sprite")))))
