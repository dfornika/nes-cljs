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


(defn usable?
  "Is this a gamepad we can play with? Browsers also list other devices
  that have buttons (headsets, for one): they have no standard layout and
  no sticks, and would otherwise take player 1's place. `mapping` is the
  Gamepad's .mapping, `axes` its axes, as plain data so this can be
  tested without a browser.

  Pads with the standard layout are always fine. Older USB pads often
  report a mapping of \"\" but have a stick or a D-pad on two axes or
  more, so those are kept too. (A pad with no mapping whose D-pad shows
  up only as buttons would be left out. If one turns up, a list of
  device ids to ignore would be the way round it.)"
  [mapping axes]
  (or (= "standard" mapping) (>= (count axes) 2)))
