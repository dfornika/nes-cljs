(ns nes.code-log-view
  "The Code tab: record which parts of the game's ROM run as code and which
  are data (the code/data log, see nes.code-log), see it as a map of the
  ROM, and find the game's subroutines (nes.routines): from what ran, and
  by following the code from there."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [nes.canvas :refer [pixel-canvas]]
            [nes.code-log :as cl]
            [nes.console :as console]
            [nes.cpu.disasm :as disasm :refer [hex]]
            [nes.emulator :as emu]
            [nes.routines :as routines]
            [nes.state :refer [db]]))

(def ^:private muted {:color "#57606a"})
(def ^:private mono {:font-family "ui-monospace, Menlo, Consolas, monospace"})

;; Colors as 0xAABBGGRR (canvas pixel order), and as CSS for the key
(def ^:private colors
  [[:unseen 0xFF3B332D "#2d333b" "not seen yet"]
   [:main 0xFF4EA42D "#2da44e" "code (main loop)"]
   [:found 0xFF2F5A1A "#1a5a2f" "code (found, not run yet)"]
   [:nmi 0xFF448FFB "#fb8f44" "code (NMI handler)"]
   [:data 0xFFFFAE54 "#54aeff" "data"]
   [:both 0xFFF771A3 "#a371f7" "code and data"]
   [:entry 0xFFFFFFFF "#ffffff" "subroutine start"]
   [:jump 0xFF3DD3FF "#ffd33d" "jump table target"]])

(def ^:private color-of (into {} (map (fn [[k c]] [k c]) colors)))

(defn- byte-kind [flag extra found?]
  (cond
    (pos? (bit-and extra cl/extra-subroutine)) :entry
    (pos? (bit-and flag cl/flag-indirect-code)) :jump
    (= 3 (bit-and flag 3)) :both
    (pos? (bit-and flag cl/flag-code)) (if (and (pos? (bit-and extra cl/extra-nmi))
                                                (zero? (bit-and extra cl/extra-main)))
                                         :nmi :main)
    (pos? (bit-and flag cl/flag-data)) :data
    found? :found
    :else :unseen))

(def ^:private bank-px 128)   ; a 16 KB bank as a 128 × 128 square, one pixel per byte

(defn- paint-bank! [^js pixels ^js flags ^js extra ^js found bank]
  (let [start (* bank 16384)]
    (dotimes [i 16384]
      (let [j (+ start i)]
        (aset pixels i (color-of (byte-kind (aget flags j) (aget extra j)
                                            (and found (pos? (aget found j))))))))))

(defn- bank-map [{:keys [flags extra]} found tick bank hover]
  [:div
   [:div {:style (merge muted mono {:font-size "11px"})} "bank " bank]
   [pixel-canvas {:width bank-px :height bank-px :scale 1
                  :tick [tick bank found]
                  :paint #(paint-bank! % flags extra found bank)
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
        (nth (some #(when (= (first %) (byte-kind f e false)) %) colors) 3)
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

(defn- offset-text [o] (str "0x" (.padStart (.toString o 16) 5 "0")))

(def ^:private tag-names
  {:ppu "PPU" :sound "sound" :controller "controller" :sprite-dma "sprite DMA"
   :bank-switch "bank switch"})

(defn- routine-link [by-offset off selected]
  (if-let [{:keys [cpu-addr]} (by-offset off)]
    [:a {:href "#" :style mono
         :on-click (fn [e] (.preventDefault e) (reset! selected off))}
     (hex cpu-addr 4)]
    [:span {:style mono} (offset-text off)]))

(defn- addr-list [addrs]
  (if (empty? addrs)
    [:span {:style muted} "–"]
    [:span {:style mono} (str/join " " (map #(hex % 4) (sort addrs)))]))

(defn- routine-detail [result by-offset selected]
  (when-let [{:keys [offset cpu-addr bank found-by ran? size instructions exits calls unresolved
                     callers tail-calls reads writes tags per-frame runs-in] :as rt}
             (by-offset @selected)]
    (let [ram (fn [s] (filter #(< % 0x800) s))]
      [:div {:style {:border "1px solid #d0d7de" :border-radius "6px" :padding "10px 12px"
                     :margin-bottom "12px"}}
       [:div {:style {:display "flex" :justify-content "space-between" :gap "8px" :flex-wrap "wrap"}}
        [:div [:strong {:style mono} (hex cpu-addr 4)] " "
         [:span {:style muted} "file offset " (offset-text offset) " · bank " bank " · "
          size " bytes, " instructions " instructions, " exits (if (= 1 exits) " exit" " exits")]]
        [:div {:style {:display "flex" :gap "6px"}}
         [:button {:on-click #(console/add-breakpoint! {:kind :exec :addr cpu-addr})
                   :title "Stop before this routine runs (if its bank is switched in)"} "● break"]
         [:button {:on-click #(reset! selected nil)} "×"]]]
       [:div {:style {:font-size "13px" :margin "6px 0"}}
        (if ran?
          (str "Ran" (when runs-in (str " in the " (case runs-in :nmi "NMI handler" :main "main loop" :both "NMI handler and main loop")))
               (when (pos? per-frame) (str ", " (.toFixed per-frame 2) " calls a frame")) ". ")
          "Found by following the code; hasn't run while recording. ")
        "Found as: " (str/join ", " (map #(case % :jsr-target "JSR target" :jump-target "jump table target"
                                                   :nmi "NMI vector" :reset "RESET vector" :irq "IRQ vector")
                                         found-by)) "."
        (when (seq tags) [:span " Touches: " (str/join ", " (map tag-names (sort tags))) "."])]
       [:table {:style {:font-size "13px" :border-spacing "0 3px"}}
        [:tbody
         [:tr [:td {:style (merge muted {:padding-right "10px"})} "calls"]
          [:td (interpose " " (for [c (sort calls)] ^{:key c} [routine-link by-offset c selected]))
           (when (seq unresolved)
             [:span {:style muted :title "In a bank that's switched in at run time, so we can't tell which"}
              " and in another bank: " (str/join " " (map #(hex % 4) (sort unresolved)))])
           (when (and (empty? calls) (empty? unresolved)) [:span {:style muted} "nothing"])]]
         [:tr [:td {:style muted} "called by"]
          [:td (if (seq callers)
                 (interpose " " (for [c (sort callers)] ^{:key c} [routine-link by-offset c selected]))
                 [:span {:style muted} "no calls found (an interrupt, a jump table, or code we couldn't follow)"])]]
         (when (seq tail-calls)
           [:tr [:td {:style muted} "ends by jumping to"]
            [:td (interpose " " (for [c (sort tail-calls)] ^{:key c} [routine-link by-offset c selected]))]])
         [:tr [:td {:style muted} "RAM read"] [:td [addr-list (ram reads)]]]
         [:tr [:td {:style muted} "RAM written"] [:td [addr-list (ram writes)]]]]]
       [:div {:style (merge mono {:font-size "12px" :background "#f6f8fa" :padding "6px 8px"
                                  :border-radius "4px" :margin-top "6px" :max-height "320px" :overflow "auto"})}
        (for [{:keys [prg-offset addr bytes mnemonic target-offset] :as ins} (routines/listing result rt)]
          ^{:key prg-offset}
          [:div {:style {:white-space "pre"}}
           [:span {:style {:color "#0969da"}} (subs (hex addr 4) 1)] "  "
           [:span {:style muted} (.padEnd (str/join " " (map #(subs (hex % 2) 1) bytes)) 9)]
           (disasm/text ins)
           (when (and (= :jsr mnemonic) target-offset (by-offset target-offset))
             [:span " " [routine-link by-offset target-offset selected]])])]])))

(defn- routines-table [rows by-offset selected]
  [:table {:style {:border-collapse "collapse" :font-size "13px"}}
   [:thead
    [:tr {:style (merge muted {:text-align "left"})}
     (for [h ["CPU address" "file offset" "bank" "size" "ran?" "per frame" "calls" "called by" "touches"]]
       ^{:key h} [:th {:style {:font-weight "normal" :padding "0 12px 4px 0"}} h])]]
   [:tbody
    (for [{:keys [prg-offset offset cpu-addr bank size ran? per-frame calls unresolved callers tags]} rows]
      ^{:key prg-offset}
      [:tr {:style {:border-top "1px solid #eaeef2" :cursor "pointer"
                    :background (when (= prg-offset @selected) "#ddf4ff")}
            :on-click #(reset! selected prg-offset)}
       [:td {:style (merge mono {:padding "2px 12px 2px 0" :color "#0969da"})} (hex cpu-addr 4)]
       [:td {:style (merge mono {:padding "2px 12px 2px 0"})} (offset-text offset)]
       [:td {:style {:padding "2px 12px 2px 0"}} bank]
       [:td {:style (merge mono {:padding "2px 12px 2px 0"})} size]
       [:td {:style {:padding "2px 12px 2px 0"}} (if ran? "✓" [:span {:style muted} "–"])]
       [:td {:style (merge mono {:padding "2px 12px 2px 0"})}
        (if (pos? per-frame) (.toFixed per-frame (if (< per-frame 10) 2 1)) "")]
       [:td {:style (merge mono {:padding "2px 12px 2px 0"})} (+ (count calls) (count unresolved))]
       [:td {:style (merge mono {:padding "2px 12px 2px 0"})} (count callers)]
       [:td {:style {:padding "2px 0" :font-size "12px"}} (str/join ", " (map tag-names (sort tags)))]])]])

(def ^:private sorts
  [["most called" #(vector (- (:per-frame %)) (- (count (:callers %))) (:prg-offset %))]
   ["address" :prg-offset]
   ["most callers" #(vector (- (count (:callers %))) (:prg-offset %))]
   ["biggest" #(vector (- (:size %)) (:prg-offset %))]])

(defn- routines-section [result selected filters]
  (let [by-offset (into {} (map (juxt :prg-offset identity)) (:routines result))
        {:keys [sort-by-name tag only-ran?]} @filters
        rows (cond->> (:routines result)
               only-ran? (filter :ran?)
               tag (filter #(contains? (:tags %) tag))
               true (sort-by (some (fn [[n f]] (when (= n sort-by-name) f)) sorts)))]
    [:div
     [routine-detail result by-offset selected]
     [:div {:style {:display "flex" :gap "10px" :align-items "center" :flex-wrap "wrap"
                    :font-size "13px" :margin-bottom "6px"}}
      [:label "Sort by "
       [:select {:value sort-by-name :on-change #(swap! filters assoc :sort-by-name (.. % -target -value))}
        (for [[n] sorts] ^{:key n} [:option {:value n} n])]]
      [:label "Touches "
       [:select {:value (if tag (name tag) "") :on-change #(let [v (.. % -target -value)]
                                                              (swap! filters assoc :tag (when (seq v) (keyword v))))}
        [:option {:value ""} "anything"]
        (for [[k n] tag-names] ^{:key k} [:option {:value (name k)} n])]]
      [:label [:input {:type "checkbox" :checked (boolean only-ran?)
                       :on-change #(swap! filters update :only-ran? not)}]
       " only ones that ran"]
      [:span {:style muted} (count rows) " shown"]]
     [:div {:style {:max-height "420px" :overflow "auto"}}
      [routines-table (take 300 rows) by-offset selected]]
     (when (> (count rows) 300) [:div {:style (merge muted {:font-size "12px"})} "(first 300)"])]))

(defn- find-routines [log]
  (when-let [e (console/emulator-instance)]
    (let [^js file (emu/rom-file e)
          header-size (or (:header-size log) 16)
          prg-size (* 16384 (get-in @db [:rom :header :prg-banks]))
          prg (.subarray file header-size (+ header-size prg-size))]
      (routines/find-routines prg (get-in @db [:rom :header :mapper]) log header-size))))

(defn code-log-view []
  (let [hover (r/atom nil)
        result (r/atom nil)       ; the last find-routines result
        selected (r/atom nil)     ; PRG offset of the routine being looked at
        filters (r/atom {:sort-by-name "most called"})]
    (fn []
      (let [tick @console/inspect-tick
            recording? (:code-logging? @db)
            log (console/code-log)
            rom-key (get-in @db [:rom :sha1])
            res (when (= rom-key (:rom @result)) (:value @result))]
        [:div
         [:p {:style (merge muted {:margin-top 0 :font-size "14px"})}
          "Which parts of the game's program ROM are code, and which are data? You can't tell by "
          "looking at the bytes, but you can by watching the game run: record, then play. "
          "Every instruction the CPU runs marks its bytes as code, every other read from ROM as data, "
          "and every JSR marks the start of a subroutine. Then Find routines follows the code from "
          "there to find more, including code that hasn't run yet."]
         [:div {:style {:display "flex" :gap "8px" :align-items "center" :flex-wrap "wrap" :margin-bottom "10px"}}
          [:button {:on-click #(console/log-code! (not recording?))
                    :disabled (nil? (console/emulator-instance))
                    :style {:font-weight "bold" :color (when-not recording? "#cf222e")}}
           (if recording? "■ Stop recording" "● Record")]
          [:button {:on-click #(do (console/clear-code-log!) (reset! result nil)) :disabled (nil? log)} "Clear"]
          [:button {:on-click #(reset! result {:rom rom-key :value (find-routines log)})
                    :disabled (nil? (console/emulator-instance))
                    :title "Follow the code from the interrupt vectors and everything recorded"}
           (if res "Find routines again" "Find routines")]
          [:button {:on-click #(download! log) :disabled (nil? log)
                    :title "The log in FCEUX's .cdl format (PRG ROM flags; CHR isn't recorded)"}
           "Download .cdl"]
          (when recording? [:span {:style {:color "#cf222e" :font-size "13px"}} "recording…"])]
         (when log
           (let [{:keys [prg-size frames code-bytes data-bytes code-and-data-bytes unseen-bytes
                         instructions jump-targets routines]} (cl/summary log)]
             [:div
              [:div {:style {:font-size "13px" :margin-bottom "6px"}}
               [:strong frames] " frames recorded · "
               [:strong (percent code-bytes prg-size)] " of the ROM seen as code (" code-bytes " bytes, "
               instructions " instructions) · "
               [:strong (percent data-bytes prg-size)] " as data · "
               [:strong (percent unseen-bytes prg-size)] " not seen yet · "
               (count routines) " subroutines called · " jump-targets " jump table targets"
               (when (pos? code-and-data-bytes)
                 (str " · " code-and-data-bytes " bytes read both ways"))]
              [key-row]
              [hover-info log hover]
              [:div {:style {:display "flex" :gap "8px" :flex-wrap "wrap" :margin "4px 0 14px"}}
               (for [bank (range (quot prg-size 16384))]
                 ^{:key bank} [bank-map log (:code res) tick bank hover])]]))
         (when res
           (let [^js code (:code res)
                 found (reduce + (array-seq code))]
             [:div {:style {:font-size "13px" :margin-bottom "6px"}}
              "Following the code: " [:strong (count (:routines res))] " routines, "
              [:strong (percent found (.-length code))] " of the ROM is code"
              (when log (str " (" (count (filter :ran? (:routines res))) " of the routines ran while recording)"))
              "."]))
         (cond
           res [:div [:h4 {:style {:margin "0 0 6px"}} "Routines"]
                [routines-section res selected filters]]
           (console/emulator-instance)
           [:p {:style muted} (if log
                                "Press Find routines to follow the code from what's been recorded."
                                "Record some play first, or press Find routines to follow the code from the interrupt vectors alone.")]
           :else [:p {:style muted} "Load a ROM first."])]))))
