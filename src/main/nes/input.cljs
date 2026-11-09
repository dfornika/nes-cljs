(ns nes.input
  "Keyboard controls for controller 1."
  (:require [nes.console :as console]))

(def key-map
  "Which keyboard key (by `KeyboardEvent.code`, the physical key) presses
  which NES button."
  {"ArrowUp"    :up
   "ArrowDown"  :down
   "ArrowLeft"  :left
   "ArrowRight" :right
   "KeyX"       :a
   "KeyZ"       :b
   "Enter"      :start
   "ShiftRight" :select
   "ShiftLeft"  :select})

(defn- typing?
  "True if the user is typing into a text box, where keys shouldn't
  control the game."
  [^js e]
  (let [tag (some-> e .-target .-tagName)]
    (contains? #{"INPUT" "TEXTAREA" "SELECT"} tag)))

(defn- on-key [^js e down?]
  (when-let [button (key-map (.-code e))]
    (when-not (typing? e)
      (.preventDefault e) ; stop the arrow keys from scrolling the page
      (if down?
        (console/button-down! 1 button)
        (console/button-up! 1 button)))))

(defonce ^:private installed? (atom false))

(defn install!
  "Start listening to the keyboard. Safe to call more than once."
  []
  (when-not @installed?
    (reset! installed? true)
    ;; Call through the var so hot-reloaded changes to `on-key` apply.
    (.addEventListener js/window "keydown" #(on-key % true))
    (.addEventListener js/window "keyup" #(on-key % false))))
