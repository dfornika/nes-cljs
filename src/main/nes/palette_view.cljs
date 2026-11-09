(ns nes.palette-view
  "The palette viewer: the NES's 64 colors, and the 32 bytes of palette
  RAM that choose which of them the game is using."
  (:require [reagent.core :as r]
            [nes.chr :as chr]
            [nes.console :as console]
            [nes.cpu.disasm :refer [hex]]
            [nes.emulator :as emu]))

(def ^:private muted {:color "#57606a"})
(def ^:private mono {:font-family "ui-monospace, Menlo, Consolas, monospace"})

;; The master palette color under the mouse (0-63), from either section.
(defonce ^:private hover (r/atom nil))

(defn- swatch
  "One color. `index` is its number in the master palette."
  [{:keys [index bgr size label sublabel title highlight? dim?]}]
  [:div {:on-mouse-enter #(reset! hover index)
         :on-mouse-leave #(reset! hover nil)
         :title title
         :style {:display "flex" :flex-direction "column" :align-items "center"
                 :opacity (if dim? 0.35 1)}}
   [:div {:style {:width size :height size :background (chr/bgr->css bgr)
                  :border-radius "3px"
                  :box-sizing "border-box"
                  :border "1px solid rgba(0,0,0,0.25)"
                  :outline (when highlight? "3px solid #fd8c73")
                  :outline-offset "1px"
                  :display "flex" :align-items "center" :justify-content "center"
                  :font-size "10px"
                  :color (if (chr/light? bgr) "black" "white")
                  :font-family "ui-monospace, Menlo, Consolas, monospace"}}
    label]
   (when sublabel [:div {:style (merge muted mono {:font-size "9px" :margin-top "2px"})} sublabel])])

(defn- palette-ram [master ram h]
  (let [row (fn [title first-palette note]
              [:div {:style {:margin-bottom "10px"}}
               [:div {:style {:font-size "13px" :margin-bottom "4px"}} [:strong title] " " [:span {:style muted} note]]
               [:div {:style {:display "flex" :gap "14px" :flex-wrap "wrap"}}
                (for [p (range first-palette (+ 4 first-palette))]
                  ^{:key p}
                  [:div
                   [:div {:style (merge muted {:font-size "11px" :margin-bottom "2px"})}
                    (if (< p 4) "BG " "Sprite ") (mod p 4)]
                   [:div {:style {:display "flex" :gap "3px"}}
                    (for [i (range 4)
                          ;; entry 0 of every palette shows the shared
                          ;; background color at $3F00
                          :let [addr (+ 0x3F00 (* 4 p) i)
                                index (nth ram (if (zero? i) 0 (- addr 0x3F00)))]]
                      ^{:key i}
                      [swatch {:index index :bgr (aget master index) :size 34
                               :label (subs (hex index 2) 1)
                               :sublabel (subs (hex addr 4) 2)
                               :highlight? (= h index)
                               :title (str (hex addr 4) " = " (hex index 2)
                                           (when (zero? i)
                                             (if (and (zero? p))
                                               " (the background color, shown wherever nothing else is drawn)"
                                               " (color 0: the shared background color from $3F00; for sprites, transparent)")))}])]])]])]
    [:div
     (row "Background palettes" 0 "$3F00–$3F0F")
     (row "Sprite palettes" 4 "$3F10–$3F1F")
     [:div {:style (merge muted {:font-size "12px"})}
      "Each byte is a color number from the master palette below. Color 0 of every palette "
      "is the same shared background color ($3F00); for sprites it means transparent."]]))

(defn- master-palette [master ram h]
  (let [used (set ram)]
    [:div
     [:div {:style {:display "grid" :grid-template-columns "repeat(16, 30px)" :gap "3px"}}
      (for [index (range 64)]
        ^{:key index}
        [swatch {:index index :bgr (aget master index) :size 30
                 :label (subs (hex index 2) 1)
                 :highlight? (= h index)
                 :dim? (and (some? h) (not= h index))
                 :title (str (hex index 2) (when (used index) " (used in palette RAM)"))}])]
     [:div {:style (merge muted {:font-size "12px" :margin-top "6px"})}
      "The NES can produce these 64 colors (fewer really: several are duplicates or black). "
      "The high digit is brightness, the low digit hue. Colors in use are listed below."]
     [:div {:style {:display "flex" :gap "4px" :flex-wrap "wrap" :margin-top "6px"}}
      (for [index (sort used)]
        ^{:key index}
        [swatch {:index index :bgr (aget master index) :size 22
                 :label (subs (hex index 2) 1) :highlight? (= h index)}])]]))

(defn palette-view []
  @console/inspect-tick
  (if-let [e (console/emulator-instance)]
    (let [master (emu/master-palette e)
          mem (emu/ppu-memory e)
          ram (mapv #(bit-and 0x3F (aget mem (+ 0x3F00 %))) (range 32))
          h @hover]
      [:div
       [:p {:style (merge muted {:margin-top 0 :font-size "14px"})}
        "Palette RAM, inside the PPU, picks which colors the game uses right now: "
        "four palettes for the background and four for sprites, four colors each. "
        "Hover over a color to find it in the master palette."]
       [palette-ram master ram h]
       [:h4 {:style {:margin "16px 0 6px"}} "Master palette"]
       [master-palette master ram h]])
    [:p {:style muted} "Load a ROM to see its palettes."]))
