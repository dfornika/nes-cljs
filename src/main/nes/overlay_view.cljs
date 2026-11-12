(ns nes.overlay-view
  "Overlays on the page: the shapes, drawn over the game's picture, and
  the checkboxes that turn each overlay on."
  (:require [nes.console :as console]
            [nes.overlay :as overlay]
            [nes.screen :as screen]
            [nes.snapshot :as snapshot]
            [nes.state :refer [db]]))

(def ^:private colors
  {:red "#ff5c5c" :green "#3fd97f" :lime "#b8ff3d" :blue "#5cb8ff" :yellow "#ffe34d"
   :orange "#ffa94d" :pink "#ff8ad8" :cyan "#5cf2ff" :white "#ffffff" :gray "#b0b8c0" :black "#000000"})

(defn- css-color [c]
  (cond (keyword? c) (get colors c (name c))
        (string? c) c
        :else (:yellow colors)))

;; Lines stay the same thickness on the page however big the screen is
;; ("non-scaling-stroke"), so they're sharp on a big screen and visible on
;; a small one.
(defn- stroke [color]
  {:stroke (css-color color) :stroke-width 1.5 :fill "none" :vector-effect "non-scaling-stroke"})

(defn- words [x y color text]
  ;; a dark outline behind the letters, so they read on any background
  [:text {:x x :y y :font-size 6 :font-family "ui-monospace, Menlo, Consolas, monospace"
          :fill (css-color color) :stroke "rgba(0,0,0,0.75)" :stroke-width 1.6
          :paint-order "stroke" :vector-effect "non-scaling-stroke"}
   (str text)])

(defn- shape [i [kind {:keys [x y w h r x1 y1 x2 y2 color fill label text arrow?]}]]
  (case kind
    :rect ^{:key i} [:g
                     [:rect (assoc (stroke color) :x x :y y :width w :height h
                                   :fill (if fill (css-color fill) "none") :fill-opacity 0.25)]
                     (when label [words x (- y 1.5) color label])]
    :circle ^{:key i} [:circle (assoc (stroke color) :cx x :cy y :r r
                                      :fill (if fill (css-color fill) "none") :fill-opacity 0.25)]
    :line ^{:key i} [:g
                     [:line (assoc (stroke color) :x1 x1 :y1 y1 :x2 x2 :y2 y2)]
                     (when arrow?
                       ;; a small arrowhead: two short lines back from the tip
                       (let [dx (- x2 x1) dy (- y2 y1)
                             len (max 0.001 (js/Math.sqrt (+ (* dx dx) (* dy dy))))
                             ux (/ dx len) uy (/ dy len)
                             back (fn [side] [(- x2 (* 3 ux) (* side 2 uy)) (- y2 (* 3 uy) (* side -2 ux))])]
                         (for [side [1 -1] :let [[bx by] (back side)]]
                           ^{:key side} [:line (assoc (stroke color) :x1 x2 :y1 y2 :x2 bx :y2 by)])))]
    :text ^{:key i} [words x y color text]
    nil))

(defn layer
  "The shapes of every overlay that's turned on, over the screen. It's an
  SVG picture the same shape as the NES's (256 x 240), stretched over the
  canvas the same way the canvas is, so shapes line up with pixels
  whatever size the screen is. Clicks go through it."
  []
  ;; redraw every frame while running, and whenever the inspectors refresh
  @console/frame-tick
  @console/inspect-tick
  (let [{:keys [rom overlays]} @db
        emulator (console/emulator-instance)
        on (filter #(contains? overlays (:name %)) (overlay/overlays-for rom))]
    (when (and emulator (seq on))
      (let [s (snapshot/live emulator)]
        [:svg {:class "overlay-layer"
               :viewBox (str "0 0 " screen/width " " screen/height)
               :preserveAspectRatio "xMidYMid meet"
               :style {:position "absolute" :inset 0 :width "100%" :height "100%"
                       :pointer-events "none"}}
         (for [o on]
           ^{:key (:name o)}
           [:g (map-indexed shape (overlay/shapes o s))])]))))

(defn chooser
  "A checkbox for each overlay the loaded game has."
  []
  (let [{:keys [rom overlays]} @db
        available (when rom (overlay/overlays-for rom))]
    (when (seq available)
      [:div {:class "overlay-chooser"
             :style {:display "flex" :gap "4px 12px" :align-items "center" :flex-wrap "wrap" :font-size "14px"}}
       [:span {:title "Shapes drawn over the picture, from the game's memory: see nes.overlay"} "Overlays"]
       (for [{:keys [name doc]} available]
         ^{:key name}
         [:label {:title doc :style {:cursor "pointer"}}
          [:input {:type "checkbox" :checked (contains? overlays name)
                   :on-change #(swap! db update :overlays (fn [on] (if (contains? on name)
                                                                     (disj on name)
                                                                     (conj (or on #{}) name))))}]
          " " name])])))
