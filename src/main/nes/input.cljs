(ns nes.input
  "Controllers: the keyboard (both players) and gamepads.

  Gamepads are read through the browser's Gamepad API, which has no
  events for button presses: we look at every gamepad's buttons once per
  animation frame and tell the emulator about anything that changed.
  The first gamepad plays as player 1, the second as player 2."
  (:require [nes.console :as console]
            [nes.gamepad :refer [pressed-buttons]]
            [nes.state :refer [db]]))

(def key-map
  "Which keyboard key (by `KeyboardEvent.code`, the physical key) presses
  which NES button, as [player button]."
  {"ArrowUp"    [1 :up]
   "ArrowDown"  [1 :down]
   "ArrowLeft"  [1 :left]
   "ArrowRight" [1 :right]
   "KeyX"       [1 :a]
   "KeyZ"       [1 :b]
   "Enter"      [1 :start]
   "ShiftRight" [1 :select]
   "ShiftLeft"  [1 :select]

   "KeyI"       [2 :up]
   "KeyK"       [2 :down]
   "KeyJ"       [2 :left]
   "KeyL"       [2 :right]
   "KeyM"       [2 :a]
   "KeyN"       [2 :b]
   "KeyO"       [2 :start]
   "KeyU"       [2 :select]})

(def key-legend
  "For the Controls panel: [button-label player-1-keys player-2-keys]."
  [["D-pad" "← ↑ → ↓" "J I L K"]
   ["A" "X" "M"]
   ["B" "Z" "N"]
   ["Start" "Enter" "O"]
   ["Select" "Shift" "U"]])

;; ---------------------------------------------------------------------------
;; Keyboard

(defn- typing?
  "True if the user is typing into a text box, where keys shouldn't
  control the game."
  [^js e]
  (let [tag (some-> e .-target .-tagName)]
    (contains? #{"INPUT" "TEXTAREA" "SELECT"} tag)))

(defn- on-key [^js e down?]
  (when-let [[player button] (key-map (.-code e))]
    (when-not (typing? e)
      (.preventDefault e) ; stop the arrow keys from scrolling the page
      (if down?
        (console/button-down! player button)
        (console/button-up! player button)))))

;; ---------------------------------------------------------------------------
;; Gamepads (see nes.gamepad for the button mapping)

;; What each player's gamepad had pressed last time we looked
(defonce ^:private held (atom {1 #{} 2 #{}}))

(defn- connected-pads []
  (if (exists? js/navigator.getGamepads)
    (->> (array-seq (.getGamepads js/navigator))
         (remove nil?)
         (filter #(.-connected ^js %))
         (sort-by #(.-index ^js %))
         (take 2))
    []))

(defn poll-gamepads!
  "Read the gamepads and press/release buttons that changed."
  []
  (let [pads (connected-pads)]
    (doseq [player [1 2]
            :let [^js pad (nth pads (dec player) nil)
                  now (if pad
                        (pressed-buttons (map #(.-pressed ^js %) (array-seq (.-buttons pad)))
                                         (array-seq (.-axes pad)))
                        #{})
                  before (get @held player)]
            :when (not= now before)]
      (doseq [b (remove before now)] (console/button-down! player b))
      (doseq [b (remove now before)] (console/button-up! player b))
      (swap! held assoc player now))))

(defn- update-gamepad-list! []
  (swap! db assoc :gamepads
         (mapv (fn [^js p] {:index (.-index p) :id (.-id p)}) (connected-pads))))

;; ---------------------------------------------------------------------------

(defonce ^:private installed? (atom false))

(defn install!
  "Start listening to the keyboard and gamepads. Safe to call more than once."
  []
  (when-not @installed?
    (reset! installed? true)
    ;; Call through the vars so hot-reloaded changes apply.
    (.addEventListener js/window "keydown" #(on-key % true))
    (.addEventListener js/window "keyup" #(on-key % false))
    (.addEventListener js/window "gamepadconnected" #(update-gamepad-list!))
    (.addEventListener js/window "gamepaddisconnected" #(update-gamepad-list!))
    (reset! console/input-poller #(poll-gamepads!))))
