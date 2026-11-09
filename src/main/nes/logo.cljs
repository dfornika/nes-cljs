(ns nes.logo
  "The site's title, drawn in the style of the Nintendo Power masthead
  (1988-1990s): heavy slanted yellow letters, a red outline, a red
  extruded shadow down and to the left, and 'speed lines' trailing off the
  words. The top word has its lines on the right, the bottom word on the
  left, like NINTENDO / POWER.

  Everything is one SVG. Text uses a heavy font from the system, and
  `textLength` stretches it to an exact width, so the speed lines line up
  whichever font the browser picks.")

(def ^:private yellow "#ffe600")
(def ^:private red "#e2001a")

(defn- stripes
  "Three speed lines between x0 and x1, filling the height of a word whose
  baseline is at y=0 (so the lines sit between -cap and 0)."
  [x0 x1 cap]
  (let [h (/ cap 5)
        gap (/ (- cap (* 3 h)) 2)]
    (for [i (range 3)]
      ^{:key i} [:rect {:x x0 :y (- (* i (+ h gap)) cap) :width (- x1 x0) :height h}])))

(defn- word
  "A word with its baseline at (x, y), slanted, `width` wide, plus its
  speed lines (`[x0 x1]` relative to x, or nil)."
  [{:keys [x y text width lines]}]
  (let [size 30
        cap (* 0.72 size)]
    [:g {:transform (str "translate(" x "," y ") skewX(-16)")}
     [:text {:x 0 :y 0
             :font-size size
             :text-length width :length-adjust "spacingAndGlyphs"
             :style {:font-family "'Arial Black', 'Archivo Black', Impact, 'Helvetica Neue', sans-serif"
                     :font-weight 900}}
      text]
     (when-let [[x0 x1] lines] (stripes x0 x1 cap))]))

(defn- shapes []
  [:g
   [word {:x 22 :y 30 :text "NES" :width 72 :lines [80 176]}]
   [word {:x 74 :y 62 :text ".cljs" :width 104 :lines [-70 -6]}]])

(defn logo
  "The title. `:height` in CSS pixels (default 52)."
  [{:keys [height] :or {height 52}}]
  [:svg {:view-box "-6 -2 208 72"
         :height height
         :role "img"
         :aria-label "NES.cljs"
         :style {:display "block" :overflow "visible"}}
   [:title "NES.cljs"]
   ;; the extruded shadow: the same shapes, stepped down and to the left
   (for [i (range 1 5)]
     ^{:key i}
     [:g {:transform (str "translate(" (- i) "," i ")")
          :fill red :stroke red :stroke-width 1.5 :stroke-linejoin "round"}
      [shapes]])
   ;; the face: yellow with a thin red outline
   [:g {:fill yellow :stroke red :stroke-width 1.2 :stroke-linejoin "round"
        :paint-order "stroke"}
    [shapes]]])
