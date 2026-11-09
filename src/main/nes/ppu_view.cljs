(ns nes.ppu-view
  "The PPU tab: the PPU's settings and status registers decoded bit by
  bit, and where it is in drawing the picture."
  (:require [nes.console :as console]
            [nes.cpu.disasm :refer [hex]]
            [nes.emulator :as emu]
            [nes.registers :as registers]
            [nes.state :refer [db]]))

(def ^:private mono {:font-family "ui-monospace, Menlo, Consolas, monospace"})
(def ^:private muted {:color "#57606a"})

;; One color per field, so the bit boxes and the table rows match up
(def ^:private field-colors
  ["#ddf4ff" "#fbefff" "#dafbe1" "#fff8c5" "#ffebe9" "#fff1e5" "#eaeef2" "#f6f8fa"])

(defn- bit-boxes
  "The byte as 8 boxes, bit 7 first, tinted by which field each bit
  belongs to."
  [fields v]
  (let [color-of (into {} (for [[i {:keys [bits]}] (map-indexed vector fields)
                                b bits]
                            [b (nth field-colors i)]))]
    [:div {:style {:display "flex" :gap "2px"}}
     (for [b (range 7 -1 -1)
           :let [on? (bit-test v b)]]
       ^{:key b}
       [:div {:style {:text-align "center"}}
        [:div {:style (merge muted mono {:font-size "10px"})} b]
        [:div {:style (merge mono {:width "24px" :height "26px" :line-height "26px"
                                   :border "1px solid #d0d7de" :border-radius "3px"
                                   :background (color-of b)
                                   :font-weight (when on? "bold")
                                   :color (if on? "#24292f" "#8c959f")})}
         (if on? 1 0)]])]))

(defn- register-card [{:keys [name addr write-only? fields]} v]
  (let [decoded (registers/decode-bits fields v)]
    [:div {:style {:border "1px solid #d0d7de" :border-radius "6px" :padding "10px 12px"
                   :min-width "300px" :flex "1"}}
     [:div {:style {:display "flex" :justify-content "space-between" :align-items "baseline"
                    :gap "8px" :margin-bottom "8px"}}
      [:div [:strong name] " " [:span {:style (merge muted mono)} (hex addr 4)]]
      [:div {:style mono} (hex v 2)]]
     [bit-boxes fields v]
     [:table {:style {:border-collapse "collapse" :font-size "13px" :margin-top "8px" :width "100%"}}
      [:tbody
       (for [[i {:keys [bits name value about]}] (map-indexed vector decoded)]
         ^{:key name}
         [:tr {:title about :style {:border-top "1px solid #eaeef2"}}
          [:td {:style {:padding "3px 6px" :white-space "nowrap" :background (nth field-colors i)}}
           [:span {:style (merge muted mono {:font-size "11px"})}
            (if (= 1 (count bits)) (first bits) (str (first bits) "–" (last bits)))]]
          [:td {:style {:padding "3px 8px"}} name]
          [:td {:style (merge mono {:padding "3px 0"})} (str value)]])]]
     [:div {:style (merge muted {:font-size "12px" :margin-top "6px"})}
      (if write-only?
        "Write-only: the CPU can't read it back, so this is what it last wrote."
        "Read here without side effects (a real read clears the vblank bit).")]]))

;; ---------------------------------------------------------------------------
;; Where the PPU is in the frame

(defn- beam-diagram
  "One frame of NTSC video as the PPU sees it: 262 lines of 341 dots,
  of which 240 lines of 256 dots are the picture, with a marker where the
  PPU is now."
  [scanline dot]
  (let [y (fn [line] (if (= line -1) 261 line))   ; the pre-render line, drawn last
        band (fn [x0 y0 w h fill label]
               [:g
                [:rect {:x x0 :y y0 :width w :height h :fill fill}]
                (when label
                  [:text {:x (+ x0 (/ w 2)) :y (+ y0 (/ h 2) 4) :text-anchor "middle"
                          :font-size 11 :fill "#57606a"} label])])]
    [:svg {:view-box "-2 -2 345 266" :style {:width "100%" :max-width "420px" :display "block"}}
     [band 0 0 256 240 "#dafbe1" "picture: 256 × 240"]
     [band 256 0 85 240 "#eaeef2" "hblank"]
     [band 0 240 341 1 "#d0d7de" nil]
     [band 0 241 341 20 "#ddf4ff" "vblank: 20 lines"]
     [band 0 261 341 1 "#ffebe9" nil]
     [:rect {:x 0 :y 0 :width 341 :height 262 :fill "none" :stroke "#8c959f"}]
     ;; where the PPU is
     [:line {:x1 0 :x2 341 :y1 (+ 0.5 (y scanline)) :y2 (+ 0.5 (y scanline))
             :stroke "#cf222e" :stroke-width 1}]
     [:circle {:cx dot :cy (+ 0.5 (y scanline)) :r 4 :fill "#cf222e"}]]))

(defn- where-am-i [scanline]
  (cond
    (<= 0 scanline 239) (str "drawing line " scanline " of the picture")
    (= scanline 240) "on the idle line after the picture"
    (<= 241 scanline 260) "in vertical blank: the picture is done, and the game can safely update video memory"
    :else "on the pre-render line, getting ready for the next picture"))

(defn ppu-view []
  @console/inspect-tick
  (if-let [e (console/emulator-instance)]
    (let [{:keys [ctrl mask status]} (emu/ppu-registers e)
          {:keys [scanline dot frame]} (emu/ppu-state e)
          paused? (= :paused (:status @db))]
      [:div
       [:p {:style (merge muted {:margin-top 0 :font-size "14px"})}
        "The game controls the PPU by writing these registers. Hover over a "
        "row for what it does."]
       [:div {:style {:display "flex" :gap "12px" :flex-wrap "wrap" :margin-bottom "16px"}}
        (for [k [:ctrl :mask :status]
              :let [spec (registers/bit-fields k)]]
          ^{:key k} [register-card spec ({:ctrl ctrl :mask mask :status status} k)])]
       [:h4 {:style {:margin "0 0 6px"}} "Where the PPU is"]
       [:div {:style (merge mono {:font-size "13px" :margin-bottom "6px"})}
        "Frame " frame " · scanline " scanline " · dot " dot]
       [:div {:style {:font-size "13px" :margin-bottom "8px"}} "The PPU is " (where-am-i scanline) "."]
       [beam-diagram scanline dot]
       [:p {:style (merge muted {:font-size "13px"})}
        "Every frame, the PPU sends 262 lines of 341 'dots' to the TV, one dot "
        "per PPU clock (three per CPU cycle). "
        (if paused?
          "Step one instruction at a time to watch the dot move; the screen shows the picture as far as it's been drawn."
          "Pause and step one instruction at a time to watch it move.")]])
    [:p {:style muted} "Load a ROM to see the PPU's registers."]))
