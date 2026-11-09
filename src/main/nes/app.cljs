(ns nes.app
  "The top level of the site."
  (:require [reagent.core :as r]
            [nes.components :as c]
            [nes.console :as console]
            [nes.rom :as rom]
            [nes.screen :as screen]
            [nes.background-view :refer [background-view]]
            [nes.breakpoints-view :refer [break-notice]]
            [nes.cpu-view :refer [cpu-view]]
            [nes.drawer :refer [drawer]]
            [nes.input :as input]
            [nes.sound :as sound]
            [nes.lens-view :refer [lens-view]]
            [nes.logo :refer [logo]]
            [nes.palette-view :refer [palette-view]]
            [nes.ppu-view :refer [ppu-view]]
            [nes.schematic :refer [schematic-view]]
            [nes.sprites-view :refer [sprites-view]]
            [nes.memory-view :refer [memory-view]]
            [nes.state :refer [db dev-tools]]
            [nes.tiles-view :refer [tiles-view]]
            [nes.ui :as ui]))

(def sample-roms
  [{:name "Concentration Room"
    :url "roms/croom/croom.nes"
    :about "roms/croom/README.html"
    :blurb "A card-matching game. Flip two cards; keep them if they match."
    :credit "by Damian Yerrick · GPL v3"}
   {:name "Thwaite"
    :url "roms/thwaite/thwaite.nes"
    :about "https://github.com/pinobatch/thwaite-nes/tree/v0.04"
    :blurb "Defend the town from missiles. Explosions flicker, which may bother people sensitive to flashing light."
    :credit "by Damian Yerrick · GPL v3"}
   {:name "240p Test Suite"
    :url "roms/240p/240pee.nes"
    :about "https://github.com/pinobatch/240p-test-mini/tree/v0.23/nes"
    :blurb "Test patterns: grids, color bars, scrolling, sprites, sound. Good for poking at with the inspectors."
    :credit "by Damian Yerrick, after Artemio Urbina · GPL v2+"}])

(defn- file-button
  "A button that opens the file chooser, through a hidden file input."
  []
  (let [input (atom nil)]
    (fn []
      [:<>
       [:button {:on-click #(some-> ^js @input (.click))} "Open ROM…"]
       [:input {:type "file"
                :accept ".nes,.zip,.gz"
                :ref #(reset! input %)
                :style {:display "none"}
                :on-change (fn [^js e]
                             (when-let [f (aget (.. e -target -files) 0)]
                               (console/load-file! f))
                             ;; so choosing the same file again still fires
                             (set! (.. e -target -value) ""))}]])))

(defn- controls []
  (let [{:keys [status]} @db
        loaded? (contains? #{:running :paused :crashed} status)]
    [:div {:style {:display "flex" :gap "8px" :align-items "center" :flex-wrap "wrap"}}
     (if (= status :running)
       [:button {:on-click console/pause!} "Pause"]
       [:button {:on-click console/resume! :disabled (not= status :paused)} "Run"])
     [:button {:on-click console/step-instruction!
               :disabled (not= status :paused)
               :title "Run one CPU instruction, then pause"}
      "Step instruction"]
     [:button {:on-click console/step-scanline!
               :disabled (not= status :paused)
               :title "Run until the PPU starts the next line of the picture, then pause"}
      "Step line"]
     [:button {:on-click console/step-frame!
               :disabled (not= status :paused)
               :title "Run to the end of this frame (up to 1/60th of a second), then pause"}
      "Step frame"]
     [:button {:on-click console/reset-console! :disabled (not loaded?)} "Reset"]]))

(defn- sound-and-speed []
  (let [on? (sound/on?)
        speed (console/speed)]
    [:div {:style {:display "flex" :gap "14px" :align-items "center" :flex-wrap "wrap" :font-size "14px"}}
     [:span {:style {:display "inline-flex" :gap "6px" :align-items "center"}}
      [:button {:on-click sound/toggle! :title (if on? "Turn sound off" "Turn sound on")
                :style {:min-width "90px"}}
       (if on? "🔊 Sound on" "🔇 Sound off")]
      [:input {:type "range" :min 0 :max 1 :step 0.05 :value (sound/volume)
               :disabled (not on?) :title "Volume" :style {:width "90px"}
               :on-change #(sound/set-volume! (js/parseFloat (.. % -target -value)))}]]
     [:label {:title "Slow motion or fast-forward. Sound is muted at speeds other than 1×."}
      "Speed "
      [:select {:value (str speed)
                :on-change #(console/set-speed! (js/parseFloat (.. % -target -value)))}
       (for [s console/speeds] ^{:key s} [:option {:value (str s)} (str s "×")])]]
     (when (and on? (not= speed 1))
       [:small {:style {:color "#57606a"}} "(muted while not at 1×)"])]))

(defn- screen-area
  "The screen, which you can also drop a .nes file onto."
  []
  (let [dragging? (r/atom false)]
    (fn []
      (let [{:keys [status]} @db]
        [:div {:class "screen-box"
               :style {:outline (when @dragging? "3px dashed #54aeff")}
               :on-drag-over (fn [^js e] (.preventDefault e) (reset! dragging? true))
               :on-drag-leave #(reset! dragging? false)
               :on-drop (fn [^js e]
                          (.preventDefault e)
                          (reset! dragging? false)
                          (when-let [f (aget (.. e -dataTransfer -files) 0)]
                            (console/load-file! f)))}
         [screen/canvas {:fill? true}]
         (when (contains? #{nil :empty} status)
           [:div {:style {:position "absolute" :inset 0
                          :display "flex" :align-items "center" :justify-content "center"
                          :color "#8c959f" :text-align "center" :padding "24px"}}
            "Open a .nes ROM file (or a .zip with one inside), drop one here, or pick a sample ROM from the menu above."])
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
  (let [pads (:gamepads @db)]
    [:div
     [:h3 {:style {:margin-top 0}} "Controls"]
     [:table {:style {:border-spacing "0 3px"}}
      [:thead
       [:tr {:style {:color "#57606a" :font-size "13px" :text-align "left"}}
        [:th] [:th {:style {:font-weight "normal"}} "Player 1"]
        [:th {:style {:font-weight "normal" :padding-left "16px"}} "Player 2"]]]
      [:tbody
       (for [[button p1 p2] input/key-legend]
         ^{:key button}
         [:tr [:td {:style {:padding-right "12px"}} button]
          [:td [kb p1]]
          [:td {:style {:padding-left "16px"}} [kb p2]]])]]
     [:div {:style {:margin-top "10px" :font-size "13px"}}
      [:strong "Gamepads: "]
      (if (empty? pads)
        [:span {:style {:color "#57606a"}}
         "none found. Connect one and press a button on it (browsers only reveal gamepads after a button press)."]
        [:span (interpose ", " (for [[i {:keys [id]}] (map-indexed vector pads)]
                                 ^{:key i} [:span "player " (inc i) ": " [:span {:style {:color "#57606a"}} id]]))])
      [:div {:style {:color "#57606a" :margin-top "4px"}}
       "Face buttons: right/top = A, bottom/left = B, like the NES pad. D-pad or left stick to move."]]]))

(defn- hex [n width]
  (str "$" (.padStart (.toUpperCase (.toString n 16)) width "0")))

(defn- kib [n] (str (/ n 1024) " KB"))

(defn- bits-label
  "A size in bytes the way 1991 game magazines wrote memory sizes: in
  kilobits (K) or megabits (M). 32 KB = 256 K."
  [bytes]
  (let [kbit (/ (* bytes 8) 1024)]
    (if (>= kbit 1024) (str (/ kbit 1024) "M") (str kbit "K"))))

(defn- data-box
  "The cartridge summarized like Nintendo Power's 'Power Data Box'."
  [{:keys [prg-size chr-size chr-ram? mapper battery?]}]
  (let [cell {:padding "4px 10px" :text-align "center"}
        pink "#f8c8d8"]
    [:div {:style {:display "flex" :gap "14px" :align-items "flex-start" :margin-top "14px"}}
     [:div {:style {:border "2px solid #24292f" :min-width "170px"
                    :font-family "system-ui, sans-serif" :background pink}}
      [:div {:style {:background "#24292f" :color "white" :font-size "10px"
                     :letter-spacing "1px" :padding "2px 6px"}}
       "GAME PAK DATA BOX"]
      [:div {:style (merge cell {:font-size "22px" :border-bottom "2px solid #24292f"})}
       (bits-label prg-size) " × " (if chr-ram? "RAM" (bits-label chr-size))]
      [:div {:style {:display "flex" :font-size "18px"}}
       [:div {:style (merge cell {:flex 1 :border-right "2px solid #24292f"})}
        (or (rom/mapper-names mapper) (str "#" mapper))]
       [:div {:style (merge cell {:flex 1})} (if battery? "64K" "—")]]]
     [:small {:style {:color "#57606a" :max-width "300px"}}
      "In the style of the \"Power Data Box\" from Nintendo Power #20 (Jan 1991): "
      "program memory × character memory, then the mapper and the cartridge's "
      "work RAM. Sizes are in kilobits (K) or megabits (M), as chip makers counted them."]]))

(defn- rom-info
  "What the cartridge's iNES header says about it."
  []
  (if-let [{:keys [name size header sha1 patched archive archive-roms archive-entry]} (:rom @db)]
    (let [{:keys [prg-banks prg-size chr-banks chr-size chr-ram?
                  mapper mirroring battery?]} header
          row (fn [label value note]
                [:tr
                 [:th {:style {:text-align "left" :padding-right "12px" :vertical-align "top"}} label]
                 [:td value (when note [:div [:small {:style {:color "#57606a"}} note]])]])]
      [:div
       [:div [:code name] " " [:small {:style {:color "#57606a"}} "(" size " bytes"
                                       (when archive [:span ", from " [:code archive]]) ")"]]
       (when (> (count archive-roms) 1)
         [:div {:style {:margin-top "6px" :font-size "13px"}}
          "Other ROMs in " [:code archive] ": "
          [:select {:value archive-entry
                    :on-change #(console/load-from-archive! (.. % -target -value))}
           (for [r archive-roms] ^{:key r} [:option {:value r} r])]])
       (when patched
         [:div {:style {:margin-top "6px" :font-size "13px" :background "#fbefff"
                        :border "1px solid #d8b9ff" :border-radius "6px" :padding "4px 10px"}}
          "You've changed " patched (if (= 1 patched) " byte" " bytes")
          " of the ROM (from the Lens tab or the REPL), in the running game. The changes last until you load another ROM. "
          [:a {:href "#" :on-click (fn [e] (.preventDefault e) (console/download-rom!))}
           "Download the changed ROM"]])
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
         (when battery? [row "Battery" "yes" "Has battery-backed save RAM"])
         [row "SHA-1" [:code {:style {:font-size "12px" :word-break "break-all"}} sha1]
          "Identifies this game whatever the file is called (the ROM without its header, as ROM databases list it). Lenses are matched by it."]]]
       [data-box header]])
    [:p {:style {:color "#57606a" :margin 0}} "No cartridge inserted."]))

(defn- popover
  "A panel that pops up over the page when a button with
  `:popover-target id` is clicked, and goes away on a click outside it
  or Escape (the browser's Popover API does that part)."
  [id & children]
  (into [:div {:id id :popover "auto" :class "popover"}] children))

(defn- hide-popover! [id]
  (some-> (js/document.getElementById id) (.hidePopover)))

(defn- samples []
  [:div {:style {:max-width "420px"}}
   [:h3 {:style {:margin-top 0}} "Sample ROMs"]
   [:p {:style {:color "#57606a" :font-size "13px"}}
    "Homebrew games and programs, free to share."]
   (for [{:keys [name url about blurb credit]} sample-roms]
     ^{:key url}
     [:div {:style {:margin-bottom "12px"}}
      [:button {:on-click (fn []
                            (hide-popover! "samples-menu")
                            (console/load-url! url))
                :style {:font-weight "bold"}}
       name]
      [:div {:style {:font-size "13px" :margin-top "3px"}} blurb]
      [:small {:style {:color "#57606a"}}
       credit " · " [:a {:href about :target "_blank"} "about & source"]]])])

(def ^:private tabs
  [{:id :schematic :label "Schematic" :view schematic-view}
   {:id :cartridge :label "Cartridge" :view rom-info}
   {:id :cpu :label "CPU" :view cpu-view}
   {:id :ppu :label "PPU" :view ppu-view}
   {:id :memory :label "Memory" :view memory-view}
   {:id :lens :label "Lens" :view lens-view}
   {:id :tiles :label "Tiles (CHR)" :view tiles-view}
   {:id :sprites :label "Sprites (OAM)" :view sprites-view}
   {:id :background :label "Background" :view background-view}
   {:id :palettes :label "Palettes" :view palette-view}])

(defn- inspector
  "The tabbed panel of views into the console's insides."
  []
  (let [current (or (:inspector-tab @db) :schematic)
        {:keys [view]} (some #(when (= current (:id %)) %) tabs)]
    [:div {:class "inspector"}
     [:div {:style {:display "flex" :flex-wrap "wrap" :border-bottom "1px solid #d0d7de" :flex "none"}}
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
     [:div {:class "inspector-body"}
      [ui/error-boundary [view]]]]))

(defn- fullscreen? [] (some? (.-fullscreenElement js/document)))

(defn- toggle-fullscreen! []
  (if (fullscreen?)
    (.exitFullscreen js/document)
    (when-let [el (js/document.querySelector ".app")]
      (swap! db assoc :theater? true)
      (.requestFullscreen el))))

(defonce ^:private fullscreen-listener
  ;; Keep :fullscreen? in step with the browser (Escape also exits).
  ;; (true, so defonce sees a value and doesn't add it again on reload)
  (do (.addEventListener js/document "fullscreenchange"
                         #(swap! db assoc :fullscreen? (fullscreen?)))
      true))

(defn- header []
  (let [{:keys [theater? fullscreen? rom]} @db]
    [:header {:class "app-header"}
     [logo {:height (if theater? 40 52)}]
     [file-button]
     [:button {:popover-target "samples-menu"} "Sample ROMs ▾"]
     [popover "samples-menu" [samples]]
     (when rom
       [:span {:class "rom-name" :title (:name rom)} [:code (:name rom)]])
     [:span {:style {:flex 1}}]
     [:button {:popover-target "controls-help" :title "Keyboard keys and gamepads"}
      "🎮 Controls"]
     [popover "controls-help" [key-legend]]
     [:button {:on-click #(swap! db update :theater? not)
               :disabled fullscreen?
               :title "Make the TV as big as the window. The inspectors pull up from the bottom."}
      (if theater? "Exit big screen" "Big screen")]
     [:button {:on-click toggle-fullscreen!
               :title "Fill the whole display (Escape to exit)"}
      (if fullscreen? "Exit full screen" "Full screen")]
     ;; development-only tools (see src/dev)
     (for [[k {:keys [label title action]}] @dev-tools]
       ^{:key k} [:button {:on-click action :title title} label])]))

(defn app
  "The page: a header, the console (screen and buttons), and the
  inspectors, either beside the screen or (in big-screen mode) in a drawer
  that pulls up over it. The page itself never scrolls; the inspector
  panel scrolls inside itself."
  []
  (let [{:keys [theater? error]} @db]
    [:div {:class (str "app" (when theater? " theater"))}
     [header]
     [ui/error-boundary
      [:main {:class "app-main"}
       [:div {:class "console-column"}
        [screen-area]
        [break-notice]
        [:div {:class "console-buttons"}
         [controls]
         [sound-and-speed]]
        (when error
          [c/callout {:kind :error} error])]
       (if theater?
         [drawer db :drawer-height "Inspectors" [inspector]]
         [inspector])]]]))
