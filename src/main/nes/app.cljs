(ns nes.app
  "The top level of the site."
  (:require [reagent.core :as r]
            [nes.components :as c]
            [nes.console :as console]
            [nes.rom :as rom]
            [nes.screen :as screen]
            [nes.state :refer [db]]
            [nes.tiles-view :refer [tiles-view]]
            [nes.ui :as ui]))

(def sample-roms
  [{:name "Concentration Room"
    :url "roms/croom/croom.nes"
    :about "roms/croom/README.html"
    :credit "by Damian Yerrick (GPL v3)"}])

(def ^:private panel-style
  {:background "white"
   :border "1px solid #d0d7de"
   :border-radius "6px"
   :padding "12px 16px"})

(defn- file-button []
  [:label {:style {:display "inline-block"
                   :padding "4px 10px"
                   :border "1px solid #8c959f"
                   :border-radius "4px"
                   :background "#f6f8fa"
                   :cursor "pointer"}}
   "Open ROM…"
   [:input {:type "file"
            :accept ".nes"
            :style {:display "none"}
            :on-change (fn [^js e]
                         (when-let [f (aget (.. e -target -files) 0)]
                           (console/load-file! f))
                         ;; so choosing the same file again still fires
                         (set! (.. e -target -value) ""))}]])

(defn- controls []
  (let [{:keys [status]} @db
        loaded? (contains? #{:running :paused :crashed} status)]
    [:div {:style {:display "flex" :gap "8px" :align-items "center" :flex-wrap "wrap"}}
     [file-button]
     (if (= status :running)
       [:button {:on-click console/pause!} "Pause"]
       [:button {:on-click console/resume! :disabled (not= status :paused)} "Run"])
     [:button {:on-click console/step-frame!
               :disabled (not= status :paused)
               :title "Run one frame (1/60th of a second), then pause"}
      "Step frame"]
     [:button {:on-click console/reset-console! :disabled (not loaded?)} "Reset"]]))

(defn- screen-area
  "The screen, which you can also drop a .nes file onto."
  []
  (let [dragging? (r/atom false)]
    (fn []
      (let [{:keys [status]} @db]
        [:div {:style {:position "relative"
                       :width "fit-content"
                       :outline (when @dragging? "3px dashed #54aeff")}
               :on-drag-over (fn [^js e] (.preventDefault e) (reset! dragging? true))
               :on-drag-leave #(reset! dragging? false)
               :on-drop (fn [^js e]
                          (.preventDefault e)
                          (reset! dragging? false)
                          (when-let [f (aget (.. e -dataTransfer -files) 0)]
                            (console/load-file! f)))}
         [screen/canvas {:scale 2}]
         (when (contains? #{nil :empty} status)
           [:div {:style {:position "absolute" :inset 0
                          :display "flex" :align-items "center" :justify-content "center"
                          :color "#8c959f" :text-align "center" :padding "24px"}}
            "Open a .nes ROM file, drop one here, or try a sample below."])
         (when (= status :paused)
           [:div {:style {:position "absolute" :top "8px" :right "8px"
                          :background "rgba(0,0,0,0.6)" :color "white"
                          :padding "2px 8px" :border-radius "4px" :font-size "12px"}}
            "PAUSED"])]))))

(defn- kb [s]
  [:kbd {:style {:font-family "ui-monospace, Menlo, Consolas, monospace"
                 :border "1px solid #d0d7de" :border-bottom-width "2px"
                 :border-radius "4px" :padding "0 5px" :background "#f6f8fa"}}
   s])

(defn- key-legend []
  [:div {:style panel-style}
   [:h3 {:style {:margin-top 0}} "Controls"]
   [:table
    [:tbody
     (for [[k b] [["← ↑ → ↓" "D-pad"] ["X" "A"] ["Z" "B"]
                  ["Enter" "Start"] ["Shift" "Select"]]]
       ^{:key b} [:tr [:td [kb k]] [:td {:style {:padding-left "8px"}} b]])]]])

(defn- hex [n width]
  (str "$" (.padStart (.toUpperCase (.toString n 16)) width "0")))

(defn- kib [n] (str (/ n 1024) " KB"))

(defn- rom-info
  "What the cartridge's iNES header says about it."
  []
  (if-let [{:keys [name size header]} (:rom @db)]
    (let [{:keys [prg-banks prg-size chr-banks chr-size chr-ram?
                  mapper mirroring battery?]} header
          row (fn [label value note]
                [:tr
                 [:th {:style {:text-align "left" :padding-right "12px" :vertical-align "top"}} label]
                 [:td value (when note [:div [:small {:style {:color "#57606a"}} note]])]])]
      [:div
       [:div [:code name] " " [:small {:style {:color "#57606a"}} "(" size " bytes)"]]
       [:table {:style {:margin-top "8px" :border-spacing "0 6px"}}
        [:tbody
         [row "PRG ROM" (str (kib prg-size) " (" prg-banks " × 16 KB)")
          "Program code and data, read by the CPU"]
         [row "CHR" (if chr-ram?
                      "8 KB of CHR RAM"
                      (str (kib chr-size) " (" chr-banks " × 8 KB)"))
          (if chr-ram?
            "No tile graphics on the cartridge: the game writes its own into RAM"
            "Tile graphics, read by the PPU")]
         [row "Mapper" (str mapper (when-let [n (rom/mapper-names mapper)] (str " (" n ")")))
          "Hardware on the cartridge for swapping banks of ROM in and out"]
         [row "Mirroring" (cljs.core/name mirroring)
          "How the PPU's background screens are arranged"]
         (when battery? [row "Battery" "yes" "Has battery-backed save RAM"])]]])
    [:p {:style {:color "#57606a" :margin 0}} "No cartridge inserted."]))

(defn- samples []
  [:div {:style panel-style}
   [:h3 {:style {:margin-top 0}} "Sample ROMs"]
   (for [{:keys [name url about credit]} sample-roms]
     ^{:key url}
     [:div
      [:button {:on-click #(console/load-url! url)} name]
      " " [:small credit " · " [:a {:href about :target "_blank"} "about"]]])])

(def ^:private tabs
  [{:id :cartridge :label "Cartridge" :view rom-info}
   {:id :tiles :label "Tiles (CHR)" :view tiles-view}])

(defn- inspector
  "The tabbed panel of views into the console's insides."
  []
  (let [current (or (:inspector-tab @db) :cartridge)
        {:keys [view]} (some #(when (= current (:id %)) %) tabs)]
    [:div {:style (merge panel-style {:padding 0})}
     [:div {:style {:display "flex" :border-bottom "1px solid #d0d7de"}}
      (for [{:keys [id label]} tabs]
        ^{:key id}
        [:button {:on-click #(swap! db assoc :inspector-tab id)
                  :style {:border "none" :background "none" :cursor "pointer"
                          :padding "10px 16px" :font-size "14px"
                          :font-weight (when (= id current) "bold")
                          :border-bottom (if (= id current)
                                           "2px solid #fd8c73"
                                           "2px solid transparent")}}
         label])]
     [:div {:style {:padding "12px 16px"}}
      [ui/error-boundary [view]]]]))

(defn app
  ""
  []
  [:div {:style {:max-width "1400px" :margin "0 auto" :padding "16px"}}
   [:h1 {:style {:margin "0 0 12px"}} "NES.cljs"]
   [ui/error-boundary
    [:div {:style {:display "flex" :gap "16px" :flex-wrap "wrap" :align-items "flex-start"}}
     [:div {:style {:display "flex" :flex-direction "column" :gap "12px" :width "512px"}}
      [screen-area]
      [controls]
      (when-let [err (:error @db)]
        [c/callout {:kind :error} err])
      [samples]
      [key-legend]]
     [:div {:style {:flex "1" :min-width "300px"}}
      [inspector]]]]])
