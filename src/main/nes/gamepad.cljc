(ns nes.gamepad
  "How gamepad buttons map to NES buttons.

  Browsers describe most gamepads with the 'standard' layout:
  https://w3c.github.io/gamepad/#remapping
  The NES's B is to the left of A, so the bottom and left face buttons
  are B, and the right and top ones are A.")

(def gamepad-buttons
  "Standard-layout button index -> NES button."
  {0 :b 2 :b    ; bottom, left face buttons
   1 :a 3 :a    ; right, top face buttons
   8 :select 9 :start
   12 :up 13 :down 14 :left 15 :right})

(def ^:private stick-threshold 0.5)

(defn pressed-buttons
  "The NES buttons held on a gamepad, as a set. Takes the button and
  axis values as plain data so it can be tested without a gamepad:
  `buttons` is a seq of booleans, `axes` a seq of numbers from -1 to 1."
  [buttons axes]
  (let [[x y] axes]
    (cond-> (into #{} (keep (fn [[i b]] (when (nth buttons i false) b))) gamepad-buttons)
      (and x (< x (- stick-threshold))) (conj :left)
      (and x (> x stick-threshold)) (conj :right)
      (and y (< y (- stick-threshold))) (conj :up)
      (and y (> y stick-threshold)) (conj :down))))

