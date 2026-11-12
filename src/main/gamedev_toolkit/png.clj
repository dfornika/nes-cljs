(ns gamedev-toolkit.png
  "Turning pictures (PNG files) into NES graphics. JVM only: it's for
  scripts that run when you prepare a game's art, and write the result out
  as Clojure data for the game to use (which then builds anywhere,
  including in the browser).

  A picture has to be squeezed into what the NES can show:

  - every pixel becomes one of the NES's 64 colors (`to-nes-colors`)
  - the picture is cut into 8x8 tiles, and each tile may use three colors
    besides the backdrop: those three are a palette, and a game has four
    palettes for its background (`palettes-for`)
  - worse, the background's palettes are chosen per 16x16 area, not per
    tile. Art drawn without that in mind has areas that want two
    palettes; `screen` gives each area the palette that suits most of it
    and repaints the rest in that palette's nearest colors.

  Then tiles become drawings (strings of . 1 2 3, as gamedev-toolkit.chr
  reads them), and identical ones are shared."
  (:require [gamedev-toolkit.palette :as palette])
  (:import [javax.imageio ImageIO]
           [java.io File]))

(defn read-image
  "A PNG file as {:width :height :pixels}, where :pixels is a vector of
  rows of [red green blue alpha], each 0 to 255."
  [path]
  (let [image (ImageIO/read (File. (str path)))
        w (.getWidth image)
        h (.getHeight image)]
    {:width w
     :height h
     :pixels (vec (for [y (range h)]
                    (vec (for [x (range w)
                               :let [argb (.getRGB image x y)]]
                           [(bit-and (bit-shift-right argb 16) 0xFF)
                            (bit-and (bit-shift-right argb 8) 0xFF)
                            (bit-and argb 0xFF)
                            (bit-and (bit-shift-right argb 24) 0xFF)]))))}))

(defn to-nes-colors
  "The picture's pixels as NES color numbers: a vector of rows. See-through
  pixels and black become `backdrop`. `choices` is {[r g b] color-number},
  for colors whose nearest match isn't the one you want."
  ([image backdrop] (to-nes-colors image backdrop {}))
  ([{:keys [pixels]} backdrop choices]
   (let [match (memoize (fn [rgb] (or (choices rgb) (palette/nearest rgb))))]
     (mapv (fn [row]
             (mapv (fn [[r g b a]]
                     (if (or (zero? a) (= [0 0 0] [r g b]))
                       backdrop
                       (match [r g b])))
                   row))
           pixels))))

(defn- area
  "The colors in the w x h rectangle at x, y, as a vector of rows."
  [colors x y w h]
  (mapv #(subvec % x (+ x w)) (subvec colors y (+ y h))))

(defn- brightness [color]
  (let [[r g b] (palette/rgb color)]
    (+ (* 0.299 r) (* 0.587 g) (* 0.114 b))))

(defn palettes-for
  "Palettes (each a vector of up to three color numbers, darkest first)
  that between them cover the 8x8 tiles of some pictures (each as from
  `to-nes-colors`), where possible. Colors used together in a tile are
  kept together; the most common combinations get their palettes first."
  [pictures backdrop]
  (let [tile-colors (for [colors pictures
                          y (range 0 (count colors) 8)
                          x (range 0 (count (first colors)) 8)
                          :let [used (disj (set (apply concat (area colors x y 8 8))) backdrop)]
                          :when (seq used)]
                      used)
        by-need (sort-by (fn [[used n]] [(- (count used)) (- n)]) (frequencies tile-colors))]
    (->> by-need
         (reduce (fn [palettes [used _]]
                   (cond
                     (some #(every? % used) palettes) palettes
                     :else
                     (if-let [[i merged] (->> (map-indexed (fn [i p] [i (into p used)]) palettes)
                                              (filter #(<= (count (second %)) 3))
                                              (sort-by #(count (second %)))
                                              first)]
                       (assoc palettes i merged)
                       (conj palettes (set used)))))
                 [])
         (mapv #(vec (sort-by brightness %))))))

(defn- nearest-in
  "The color in `allowed` that looks most like `color`."
  [color allowed]
  (let [[r g b] (palette/rgb color)]
    (apply min-key (fn [c] (let [[r2 g2 b2] (palette/rgb c)]
                             (+ (* (- r r2) (- r r2)) (* (- g g2) (- g g2)) (* (- b b2) (- b b2)))))
           allowed)))

(defn- drawing
  "An 8x8 area of colors as a tile drawing: `.` for the backdrop, 1 2 3
  for the palette's colors in order."
  [tile-colors backdrop palette]
  (let [digit (into {backdrop \.} (map vector palette "123"))]
    (mapv #(apply str (map digit %)) tile-colors)))

(defn screen
  "A 256x240 picture (as from `to-nes-colors`) as a background:

    :tiles           the drawings of its distinct tiles
    :grid            960 numbers: which of :tiles goes where, row by row
    :block-palettes  240 numbers: the palette for each 16x16 area
    :repainted       how many pixels had to change color to fit

  `tiles` is the drawings found so far (in other screens), which this
  adds to, so screens share tiles."
  [colors backdrop palettes tiles]
  (let [blocks (for [by (range 15), bx (range 16)]
                 (let [block (area colors (* 16 bx) (* 16 by) 16 16)
                       pixels (apply concat block)
                       fits (fn [p] (count (filter #(or (= % backdrop) (some #{%} p)) pixels)))
                       best (apply max-key #(fits (palettes %)) (reverse (range (count palettes))))
                       allowed (conj (palettes best) backdrop)]
                   {:palette best
                    :repainted (- 256 (fits (palettes best)))
                    :colors (mapv (fn [row] (mapv #(if (some #{%} allowed) % (nearest-in % allowed)) row)) block)}))
        ;; each block is four tiles; go through them in the nametable's order
        placed (for [ty (range 30), tx (range 32)
                     :let [{:keys [colors palette]} (nth blocks (+ (* 16 (quot ty 2)) (quot tx 2)))]]
                 (drawing (area colors (* 8 (mod tx 2)) (* 8 (mod ty 2)) 8 8) backdrop (palettes palette)))
        all-tiles (reduce (fn [known d] (if (some #{d} known) known (conj known d))) (vec tiles) placed)
        number (zipmap all-tiles (range))]
    {:tiles all-tiles
     :grid (mapv number placed)
     :block-palettes (mapv :palette blocks)
     :repainted (reduce + (map :repainted blocks))}))

(defn frames
  "A strip of animation frames, each `width` pixels wide, as drawings
  (vectors of strings), using the colors in `palette`."
  [colors backdrop palette width]
  (let [h (count colors)]
    (vec (for [x (range 0 (count (first colors)) width)]
           (vec (mapcat (fn [ty]
                          ;; a row of tiles' drawings, joined side by side
                          (apply map str (for [tx (range (quot width 8))]
                                           (drawing (area colors (+ x (* 8 tx)) (* 8 ty) 8 8) backdrop palette))))
                        (range (quot h 8))))))))
