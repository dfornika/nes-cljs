(ns nes.memory-view
  "The memory inspector: a hex view of the CPU's or PPU's address space,
  labelled by what each region is for, plus a list of watched addresses."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [nes.console :as console]
            [nes.cpu.disasm :refer [hex]]
            [nes.emulator :as emu]
            [nes.memory-map :refer [memory-maps region-at]]))

(def ^:private mono {:font-family "ui-monospace, Menlo, Consolas, monospace"})
(def ^:private muted {:color "#57606a"})

(def ^:private jump-points
  {:cpu [["Zero page" 0x0000] ["Stack" 0x0100] ["RAM" 0x0200] ["Cart RAM" 0x6000]
         ["PRG ROM" 0x8000] ["Vectors" 0xFF00]]
   :ppu [["Patterns 0" 0x0000] ["Patterns 1" 0x1000] ["Nametable 0" 0x2000]
         ["Nametable 1" 0x2400] ["Palettes" 0x3F00]]})

;; ---------------------------------------------------------------------------
;; State

(defonce ^:private view
  (r/atom {:space :cpu
           :base {:cpu 0x0000 :ppu 0x2000}
           :hover nil
           :watches []}))

(def ^:private rows 16)
(def ^:private page (* rows 16))

(defn- peek-fn [emulator space]
  (if (= space :cpu)
    #(emu/cpu-peek emulator %)
    #(emu/ppu-peek emulator %)))

(defn- clamp-base [space addr]
  (let [size (get-in memory-maps [space :size])]
    (-> addr (bit-and (bit-not 0xF)) (max 0) (min (- size page)))))

(defn- go-to! [addr]
  (let [space (:space @view)]
    (swap! view assoc-in [:base space] (clamp-base space addr))))

(defn show!
  "Point the hex view at `addr` in `space` (:cpu or :ppu)."
  [space addr]
  (swap! view assoc :space space)
  (go-to! addr))

(defn- watching? [space addr]
  (some #(and (= space (:space %)) (= addr (:addr %))) (:watches @view)))

(defn- toggle-watch! [space addr]
  (swap! view update :watches
         (fn [ws]
           (if (watching? space addr)
             (vec (remove #(and (= space (:space %)) (= addr (:addr %))) ws))
             (conj ws {:space space :addr addr :label "" :format :hex})))))

;; Remember what each byte held on the previous refresh, so bytes that
;; just changed can be highlighted.
(defonce ^:private history (atom {:tick nil :key nil :values nil :changed #{}}))

(defn- page-values
  "The bytes on the current page, plus which offsets changed since the
  last inspector tick."
  [peek space base tick]
  (let [{prev-tick :tick prev-key :key prev :values :as h} @history
        k [space base]]
    (if (and (= tick prev-tick) (= k prev-key))
      h
      (let [values (mapv #(peek (+ base %)) (range page))
            changed (if (= k prev-key)
                      (set (filter #(not= (nth values %) (nth prev %)) (range page)))
                      #{})]
        (reset! history {:tick tick :key k :values values :changed changed})))))

;; ---------------------------------------------------------------------------
;; Formatting

(def formats
  [[:hex "Hex"] [:dec "Decimal"] [:signed "Signed"] [:bin "Binary"] [:word "16-bit word"]])

(defn- format-value [peek addr format]
  (let [v (peek addr)]
    (cond
      (nil? v) "--"
      (= format :hex) (hex v 2)
      (= format :dec) (str v)
      (= format :signed) (str (if (>= v 0x80) (- v 0x100) v))
      (= format :bin) (str "%" (.padStart (.toString v 2) 8 "0"))
      (= format :word) (if-let [hi (peek (inc addr))]
                         (let [w (bit-or v (bit-shift-left hi 8))]
                           (str (hex w 4) " (" w ")"))
                         "--"))))

(defn- ascii [v]
  (if (and v (<= 0x20 v 0x7E)) (js/String.fromCharCode v) "."))

;; ---------------------------------------------------------------------------
;; Components

(defn- space-picker []
  (let [space (:space @view)]
    [:div {:style {:display "flex" :gap "4px" :align-items "center" :flex-wrap "wrap"}}
     (for [[s label] [[:cpu "CPU memory"] [:ppu "PPU memory"]]]
       ^{:key s}
       [:button {:on-click #(swap! view assoc :space s :hover nil)
                 :style {:font-weight (when (= s space) "bold")
                         :background (when (= s space) "#ddf4ff")}}
        label])
     [:span {:style {:width "12px"}}]
     (for [[label addr] (jump-points space)]
       ^{:key label}
       [:button {:on-click #(go-to! addr) :style {:font-size "12px"}} label])]))

(defn- address-input []
  (let [text (r/atom "")]
    (fn []
      [:input {:value @text
               :placeholder "Go to $…"
               :style (merge mono {:width "90px"})
               :on-change #(reset! text (.. % -target -value))
               :on-key-down (fn [^js e]
                              (when (= "Enter" (.-key e))
                                (let [n (js/parseInt (str/replace @text #"^\$|^0x" "") 16)]
                                  (when-not (js/isNaN n) (go-to! n)))))}])))

(defn- nav-bar [base]
  (let [step (fn [label delta] [:button {:on-click #(go-to! (+ base delta))
                                         :style (merge mono {:font-size "12px"})} label])]
    [:div {:style {:display "flex" :gap "4px" :align-items "center" :margin "8px 0"}}
     (step "◀◀" (- page)) (step "◀" -16) (step "▶" 16) (step "▶▶" page)
     [:span {:style {:width "8px"}}]
     [address-input]]))

(defn- hex-grid [emulator space base tick]
  (let [peek (peek-fn emulator space)
        {:keys [values changed]} (page-values peek space base tick)
        {:keys [hover watches]} @view
        ;; read the atom up here, not inside the lazy `for`s below
        watched (set (for [w watches :when (= space (:space w))] (:addr w)))
        cell {:padding "1px 3px" :text-align "center" :cursor "pointer"}]
    [:table {:style (merge mono {:border-collapse "collapse" :font-size "13px"})
             :on-mouse-leave #(swap! view assoc :hover nil)}
     [:thead
      [:tr {:style muted}
       [:th]
       (for [c (range 16)] ^{:key c} [:th {:style {:font-weight "normal" :padding "0 3px"}}
                                      (.toUpperCase (.toString c 16))])
       [:th] [:th]]]
     [:tbody
      (for [row (range rows)
            :let [row-addr (+ base (* 16 row))
                  region (region-at space row-addr)
                  prev-region (when (pos? row) (region-at space (- row-addr 16)))]]
        ^{:key row}
        [:tr
         [:td {:style {:color "#0969da" :padding-right "8px"}} (subs (hex row-addr 4) 1)]
         (for [c (range 16)
               :let [i (+ (* 16 row) c)
                     addr (+ row-addr c)
                     v (nth values i)
                     r (region-at space addr)]]
           ^{:key c}
           [:td {:style (merge cell
                               {:background (cond (= addr hover) "#54aeff"
                                                  (changed i) "#ffd33d"
                                                  :else (:color r))
                                :color (if (nil? v) "#8c959f" "inherit")
                                :outline (when (watched addr) "2px solid #fd8c73")
                                :outline-offset "-2px"})
                 :on-mouse-enter #(swap! view assoc :hover addr)
                 :on-click #(toggle-watch! space addr)}
            (if v (subs (hex v 2) 1) "--")])
         [:td {:style (merge muted {:padding-left "8px" :white-space "pre"})}
          (apply str (map #(ascii (nth values (+ (* 16 row) %))) (range 16)))]
         [:td {:style (merge muted {:padding-left "12px" :font-family "inherit" :font-size "12px"
                                    :white-space "nowrap"})}
          (when (or (zero? row) (not= region prev-region)) (:name region))]])]]))

(defn- hover-info [emulator space]
  (let [addr (:hover @view)
        peek (peek-fn emulator space)]
    [:div {:style {:min-height "40px" :font-size "13px" :margin-top "8px"}}
     (if addr
       (let [{:keys [name about io?]} (region-at space addr)]
         [:div
          [:span mono (hex addr 4)] " · " [:strong name] " · "
          (if (peek addr)
            [:span mono (str/join " = " (map #(format-value peek addr %) [:hex :dec :bin]))]
            [:span muted (if io?
                           "I/O register: not shown, because reading it can change the hardware's state"
                           "Not readable")])
          [:div {:style muted} about]])
       [:div {:style muted}
        "Hover a byte for details; click to add it to the watch list. "
        "Bytes that changed since the last refresh flash yellow."])]))

(defn- watch-list [emulator]
  (let [watches (:watches @view)]
    [:div {:style {:margin-top "12px"}}
     [:h4 {:style {:margin "0 0 6px"}} "Watch list"]
     (if (empty? watches)
       [:div {:style (merge muted {:font-size "13px"})} "Nothing watched yet. Click a byte above to watch it."]
       [:table {:style {:font-size "13px" :border-collapse "collapse"}}
        [:tbody
         (for [[i {:keys [space addr label format]}] (map-indexed vector watches)]
           ^{:key (str (name space) addr)}
           [:tr
            [:td {:style (merge muted {:padding-right "6px"})} (str/upper-case (name space))]
            [:td {:style (merge mono {:padding-right "8px" :color "#0969da" :cursor "pointer"})
                  :title "Show in the hex view"
                  :on-click #(do (swap! view assoc :space space)
                                 (go-to! (- addr (mod addr 16))))}
             (hex addr 4)]
            [:td [:input {:value label :placeholder "label"
                          :style {:width "110px"}
                          :on-change #(swap! view assoc-in [:watches i :label] (.. % -target -value))}]]
            [:td {:style {:padding "0 8px"}}
             [:select {:value (name format)
                       :on-change #(swap! view assoc-in [:watches i :format]
                                          (keyword (.. % -target -value)))}
              (for [[f l] formats] ^{:key f} [:option {:value (name f)} l])]]
            [:td {:style (merge mono {:min-width "110px" :font-size "14px"})}
             (format-value (peek-fn emulator space) addr format)]
            [:td [:button {:on-click #(toggle-watch! space addr) :title "Stop watching"} "×"]]])]])]))

(defn memory-view []
  (let [tick @console/inspect-tick
        {:keys [space base]} @view
        base (get base space)]
    (if-let [emulator (console/emulator-instance)]
      [:div
       [space-picker]
       [nav-bar base]
       [hex-grid emulator space base tick]
       [hover-info emulator space]
       [watch-list emulator]]
      [:p {:style muted} "Load a ROM to see its memory."])))
