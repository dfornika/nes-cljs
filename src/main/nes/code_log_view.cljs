(ns nes.code-log-view
  "The Code tab: record which parts of the game's ROM run as code and which
  are data (the code/data log, see nes.code-log), see it as a map of the
  ROM, and list the subroutines found."
  (:require [reagent.core :as r]
            [nes.canvas :refer [pixel-canvas]]
            [nes.code-log :as cl]
            [nes.console :as console]
            [nes.cpu.disasm :refer [hex]]
            [nes.state :refer [db]]))

(def ^:private muted {:color "#57606a"})
(def ^:private mono {:font-family "ui-monospace, Menlo, Consolas, monospace"})

;; Colors as 0xAABBGGRR (canvas pixel order), and as CSS for the key
(def ^:private colors
  [[:unseen 0xFF3B332D "#2d333b" "not seen yet"]
   [:main 0xFF4EA42D "#2da44e" "code (main loop)"]
   [:nmi 0xFF448FFB "#fb8f44" "code (NMI handler)"]
   [:data 0xFFFFAE54 "#54aeff" "data"]
   [:both 0xFFF771A3 "#a371f7" "code and data"]
   [:entry 0xFFFFFFFF "#ffffff" "subroutine start"]
   [:jump 0xFF3DD3FF "#ffd33d" "jump table target"]])

(def ^:private color-of (into {} (map (fn [[k c]] [k c]) colors)))

(defn- byte-kind [flag extra]
  (cond
    (pos? (bit-and extra cl/extra-subroutine)) :entry
    (pos? (bit-and flag cl/flag-indirect-code)) :jump
    (= 3 (bit-and flag 3)) :both
    (pos? (bit-and flag cl/flag-code)) (if (and (pos? (bit-and extra cl/extra-nmi))
                                                (zero? (bit-and extra cl/extra-main)))
                                         :nmi :main)
    (pos? (bit-and flag cl/flag-data)) :data
    :else :unseen))

(def ^:private bank-px 128)   ; a 16 KB bank as a 128 × 128 square, one pixel per byte

(defn- paint-bank! [^js pixels ^js flags ^js extra bank]
  (let [start (* bank 16384)]
    (dotimes [i 16384]
      (aset pixels i (color-of (byte-kind (aget flags (+ start i)) (aget extra (+ start i))))))))

(defn- bank-map [{:keys [flags extra]} tick bank hover]
  [:div
   [:div {:style (merge muted mono {:font-size "11px"})} "bank " bank]
   [pixel-canvas {:width bank-px :height bank-px :scale 1
                  :tick [tick bank]
                  :paint #(paint-bank! % flags extra bank)
                  :style {:cursor "crosshair"}
                  :on-mouse-move (fn [^js e]
                                   (let [rect (.getBoundingClientRect (.-target e))
                                         x (int (- (.-clientX e) (.-left rect)))
                                         y (int (- (.-clientY e) (.-top rect)))]
                                     (when (and (< -1 x bank-px) (< -1 y bank-px))
                                       (reset! hover (+ (* bank 16384) (* y bank-px) x)))))
                  :on-mouse-leave #(reset! hover nil)}]])

(defn- hover-info
  "Details of the byte under the mouse. Its own component, so moving the
  mouse redraws just this, not the maps."
  [{:keys [flags extra calls header-size]} hover]
  (let [prg-offset @hover
        f (when prg-offset (aget flags prg-offset))
        e (when prg-offset (aget extra prg-offset))]
    [:div {:style (merge mono {:font-size "12px" :min-height "18px"})}
     (if prg-offset
       [:span
        "file offset " (str "0x" (.toString (+ header-size prg-offset) 16)) " · "
        (if (pos? (bit-and f 3))
          (str "CPU " (hex (cl/cpu-address f prg-offset) 4) " · ")
          "")
        (nth (some #(when (= (first %) (byte-kind f e)) %) colors) 3)
        (when (pos? (aget calls prg-offset)) (str " · called " (aget calls prg-offset) " times"))]
       [:span {:style muted} "Hover over the map for details."])]))

(defn- key-row []
  [:div {:style {:display "flex" :gap "12px" :flex-wrap "wrap" :font-size "12px" :margin "6px 0"}}
   (for [[k _ css label] colors]
     ^{:key k}
     [:span {:style {:display "inline-flex" :gap "4px" :align-items "center"}}
      [:span {:style {:width "10px" :height "10px" :background css :border "1px solid #8c959f"}}]
      label])])

(defn- download! [{:keys [flags chr-size]}]
  (let [bytes (cl/cdl-file flags chr-size)
        name (-> (get-in @db [:rom :name] "game.nes") (.replace (js/RegExp. "\\.nes$" "i") ""))
        url (js/URL.createObjectURL (js/Blob. #js [bytes] #js {:type "application/octet-stream"}))
        a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) (str name ".cdl"))
    (.click a)
    (js/setTimeout #(js/URL.revokeObjectURL url) 1000)))

(defn- percent [n total] (if (pos? total) (str (js/Math.round (/ (* 100 n) total)) "%") "–"))

(defn- routines-table [routines frames]
  [:table {:style {:border-collapse "collapse" :font-size "13px"}}
   [:thead
    [:tr {:style (merge muted {:text-align "left"})}
     (for [h ["file offset" "CPU address" "bank" "calls" "per frame" "runs in" ""]]
       ^{:key h} [:th {:style {:font-weight "normal" :padding "0 12px 4px 0"}} h])]]
   [:tbody
    (for [{:keys [offset cpu-addr bank calls per-frame runs-in]} (take 40 routines)]
      ^{:key offset}
      [:tr {:style {:border-top "1px solid #eaeef2"}}
       [:td {:style (merge mono {:padding "2px 12px 2px 0"})} (str "0x" (.padStart (.toString offset 16) 5 "0"))]
       [:td {:style (merge mono {:padding "2px 12px 2px 0"})} (hex cpu-addr 4)]
       [:td {:style {:padding "2px 12px 2px 0"}} bank]
       [:td {:style (merge mono {:padding "2px 12px 2px 0"})} calls]
       [:td {:style (merge mono {:padding "2px 12px 2px 0"})}
        (if (pos? frames) (.toFixed per-frame (if (< per-frame 10) 2 1)) "–")]
       [:td {:style {:padding "2px 12px 2px 0"}}
        (case runs-in :nmi "NMI handler" :main "main loop" :both "both")]
       [:td [:button {:title "Stop before this subroutine runs (if its bank is switched in)"
                      :style {:font-size "11px"}
                      :on-click #(console/add-breakpoint! {:kind :exec :addr cpu-addr})}
             "● break"]]])]])

(defn code-log-view []
  (let [hover (r/atom nil)]
    (fn []
      (let [tick @console/inspect-tick
            recording? (:code-logging? @db)
            log (console/code-log)]
        [:div
         [:p {:style (merge muted {:margin-top 0 :font-size "14px"})}
          "Which parts of the game's program ROM are code, and which are data? You can't tell by "
          "looking at the bytes, but you can by watching the game run: record, then play. "
          "Every instruction the CPU runs marks its bytes as code, every other read from ROM as data, "
          "and every JSR marks the start of a subroutine. The more of the game you play, the more you see."]
         [:div {:style {:display "flex" :gap "8px" :align-items "center" :flex-wrap "wrap" :margin-bottom "10px"}}
          [:button {:on-click #(console/log-code! (not recording?))
                    :disabled (nil? (console/emulator-instance))
                    :style {:font-weight "bold" :color (when-not recording? "#cf222e")}}
           (if recording? "■ Stop recording" "● Record")]
          [:button {:on-click console/clear-code-log! :disabled (nil? log)} "Clear"]
          [:button {:on-click #(download! log) :disabled (nil? log)
                    :title "The log in FCEUX's .cdl format (PRG ROM flags; CHR isn't recorded)"}
           "Download .cdl"]
          (when recording? [:span {:style {:color "#cf222e" :font-size "13px"}} "recording…"])]
         (if-not log
           [:p {:style muted} (if (console/emulator-instance)
                                "Nothing recorded yet."
                                "Load a ROM first.")]
           (let [{:keys [prg-size frames code-bytes data-bytes code-and-data-bytes unseen-bytes
                         instructions jump-targets routines]} (cl/summary log)]
             [:div
              [:div {:style {:font-size "13px" :margin-bottom "6px"}}
               [:strong frames] " frames recorded · "
               [:strong (percent code-bytes prg-size)] " of the ROM seen as code (" code-bytes " bytes, "
               instructions " instructions) · "
               [:strong (percent data-bytes prg-size)] " as data · "
               [:strong (percent unseen-bytes prg-size)] " not seen yet · "
               (count routines) " subroutines · " jump-targets " jump table targets"
               (when (pos? code-and-data-bytes)
                 (str " · " code-and-data-bytes " bytes read both ways"))]
              [key-row]
              [hover-info log hover]
              [:div {:style {:display "flex" :gap "8px" :flex-wrap "wrap" :margin "4px 0 14px"}}
               (for [bank (range (quot prg-size 16384))]
                 ^{:key bank} [bank-map log tick bank hover])]
              [:h4 {:style {:margin "0 0 6px"}} "Subroutines, most called first"]
              (if (empty? routines)
                [:p {:style muted} "None yet."]
                [routines-table routines frames])]))]))))
