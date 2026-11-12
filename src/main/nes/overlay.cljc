(ns nes.overlay
  "Overlays: drawing what a game is thinking on top of what it's showing.

  A lens (see nes.lens) turns a game's bytes into named values. An overlay
  takes those values and turns them into shapes, which are drawn over the
  game's picture as it runs: a box round what the game thinks the player
  is, a line for a speed, a number beside an enemy.

    (register!
     {:rom    \"a1b2...\"            ; which game, as for lenses; or :any
      :name   \"Hitboxes\"
      :doc    \"What touches what\"
      :fields {:x [:u8 0x0086] :y [:u8 0x00CE]}      ; a lens
      :draw   (fn [{:keys [x y]}]                    ; its values -> shapes
                [[:rect {:x x :y y :w 16 :h 16 :color :red :label \"player\"}]])})

  Shapes are data, in the screen's own coordinates (256 across, 240 down,
  from the top left):

    [:rect   {:x :y :w :h}]         a box
    [:line   {:x1 :y1 :x2 :y2}]     a line (:arrow? true for an arrowhead)
    [:circle {:x :y :r}]            a circle, centre x y
    [:text   {:x :y :text}]         words or a number
  each with optional :color (a keyword like :red, or any CSS color),
  :fill (for :rect and :circle), and for :rect a :label written above it.

  A :draw function is ordinary Clojure: it runs on your computer, not on
  the NES, so it can do anything with the values it's given."
  (:require [nes.lens :as lens]))

;; {[rom name] overlay}
(defonce registry (atom {}))

(defonce ^:private counter (atom 0))

(defn register!
  "Add an overlay (a map, see above), replacing any with the same :rom
  and :name. Returns its [rom name] key."
  [{:keys [rom name draw] :as overlay}]
  (assert (and rom name (ifn? draw)) "An overlay needs :rom, :name and :draw")
  (let [k [rom name]]
    (swap! registry update k
           (fn [old] (assoc overlay :order (or (:order old) (swap! counter inc)))))
    k))

(defn unregister! [rom name]
  (swap! registry dissoc [rom name])
  nil)

(defn overlays-for
  "The overlays that apply to `rom` ({:sha1 ... :name ...}, as in the app
  db): the game's own first, then ones for every game."
  [rom]
  (->> (vals @registry)
       (keep #(when-let [how (lens/matches? % rom)] (assoc % :matched-by how)))
       (sort-by (juxt #(= :any (:matched-by %)) :order))
       vec))

(def shape-kinds #{:rect :line :circle :text})

(defn shapes
  "The shapes `overlay` draws for snapshot `s` (see nes.snapshot): its
  :fields read as a lens, and the values given to its :draw. (An overlay
  with no :fields is given the snapshot itself.) Anything that isn't a
  shape is left out. If :draw throws, there's one shape: the
  error, as text, so a mistake shows instead of vanishing."
  [{:keys [fields draw]} s]
  (try
    (->> (draw (if fields (lens/interpret fields s) s))
         (filter #(and (vector? %) (shape-kinds (first %)) (map? (second %))))
         vec)
    (catch #?(:clj Exception :cljs :default) e
      [[:text {:x 4 :y 12 :color :red :text (str "overlay error: " (ex-message e))}]])))

;; ---------------------------------------------------------------------------
;; An overlay for every game

(defn sprite-boxes
  "A box round every sprite on screen, from sprite memory: [y tile
  attributes x] for each of the 64. `height` is 8 or 16."
  [oam height]
  (for [[i [y _tile attributes x]] (map-indexed vector (partition 4 oam))
        :when (< y 0xEF)]
    [:rect {:x x :y (inc y) :w 8 :h height   ; sprites are drawn one line lower than their Y
            :color (if (bit-test attributes 5) :gray :lime)   ; behind the background?
            :label (when (zero? i) "0")}]))

(register!
 {:rom :any
  :name "Sprites"
  :doc "A box round each of the PPU's 64 sprites that's on screen (gray if it's behind the background). Sprite 0 is numbered."
  ;; no :fields, so :draw is given the snapshot
  :draw (fn [s]
          (sprite-boxes (get-in s [:ppu :oam])
                        (get-in s [:ppu :flags :sprite-height] 8)))})
