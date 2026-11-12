(ns nes.overlay-test
  (:require [clojure.test :refer [deftest is testing]]
            [games.catch :as catch]
            [nes.debug-info :as di]
            [nes.overlay :as overlay]))

(defn- ram-with [& addr-values]
  (reduce (fn [ram [a v]] (assoc ram a v)) (vec (repeat 0x800 0)) (partition 2 addr-values)))

(deftest shapes-from-a-lens
  (let [o {:fields {:x [:u8 0x10] :y [:u8 0x11]}
           :draw (fn [{:keys [x y]}]
                   [[:rect {:x x :y y :w 16 :h 16 :color :red}]
                    [:text {:x x :y (- y 2) :text (str x "," y)}]
                    nil                              ; (when ...) that didn't
                    [:triangle {:x 1}]               ; not a shape
                    "nor is this"])}]
    (is (= [[:rect {:x 40 :y 50 :w 16 :h 16 :color :red}]
            [:text {:x 40 :y 48 :text "40,50"}]]
           (overlay/shapes o {:ram (ram-with 0x10 40 0x11 50)}))
        "the lens's values go to :draw; what isn't a shape is left out"))
  (testing "no shapes is fine"
    (is (= [] (overlay/shapes {:draw (fn [_] nil)} {:ram (ram-with)}))))
  (testing "a mistake in :draw shows as text, not as nothing"
    (let [[[kind {:keys [text]}]] (overlay/shapes {:fields {:x [:u8 0]} :draw (fn [{:keys [x]}] (throw (ex-info "oops" {})))}
                                                  {:ram (ram-with)})]
      (is (= :text kind))
      (is (re-find #"overlay error: oops" text))))
  (testing "without :fields, :draw is given the snapshot"
    (is (= [[:circle {:x 7 :y 7 :r 3}]]
           (overlay/shapes {:draw (fn [s] [[:circle {:x (nth (:ram s) 5) :y 7 :r 3}]])} {:ram (ram-with 5 7)})))))

(deftest registry
  (overlay/register! {:rom "abc" :name "Mine" :draw (fn [_] [])})
  (overlay/register! {:rom "other" :name "Theirs" :draw (fn [_] [])})
  (is (= ["Mine" "Sprites"] (map :name (overlay/overlays-for {:sha1 "abc"}))) "the game's own first, then ones for any game")
  (is (= ["Sprites"] (map :name (overlay/overlays-for {:sha1 "nothing-registered"}))))
  (overlay/unregister! "abc" "Mine")
  (overlay/unregister! "other" "Theirs")
  (is (= ["Sprites"] (map :name (overlay/overlays-for {:sha1 "abc"})))))

(deftest sprites
  (let [oam (-> (vec (repeat 256 0xFF))
                (assoc 0 100, 1 5, 2 0x00, 3 60)        ; sprite 0, in front
                (assoc 4 120, 5 6, 6 0x20, 7 80))]      ; sprite 1, behind the background
    (is (= [[:rect {:x 60 :y 101 :w 8 :h 8 :color :lime :label "0"}]
            [:rect {:x 80 :y 121 :w 8 :h 16 :color :gray :label nil}]]
           [(first (overlay/sprite-boxes oam 8)) (second (overlay/sprite-boxes oam 16))]))
    (is (= 2 (count (overlay/sprite-boxes oam 8))) "the 62 off-screen ones aren't drawn")))

(deftest the-games-own-overlay
  (let [built (catch/build)
        sym (:symbols built)
        o (assoc (first (:overlays built)) :fields (di/lens-fields (:memory built)))
        ram (fn [state] (ram-with (:rt/state sym) state
                                  (inc (:player/x sym)) 120 (inc (:player/y sym)) 112
                                  (:star/x sym) 60 (:star/y sym) 70
                                  (inc (:enemy/x sym)) 30 (inc (:enemy/y sym)) 90
                                  (inc (:enemy/vx sym)) 2 (inc (:enemy/vy sym)) 0xFF))   ; +2.0, -1.0
        shapes (overlay/shapes o {:ram (ram 1)})
        labelled (into {} (for [[_ m] shapes :when (:label m)] [(:label m) m]))]
    (is (= [] (overlay/shapes o {:ram (ram 0)})) "nothing on the title screen")
    (is (= #{"player" "star" "spike"} (set (keys labelled))))
    (testing "a touch box is centred on its 16x16 picture"
      (is (= {:x 122.0 :y 115.0 :w 12 :h 12} (select-keys (labelled "player") [:x :y :w :h])))
      (is (= {:x 62 :y 73} (select-keys (labelled "star") [:x :y]))))
    (testing "the arrow points the way the spike is going"
      (let [[_ {:keys [x1 y1 x2 y2]}] (first (filter #(= :line (first %)) shapes))]
        (is (= [38.0 99.0] [x1 y1]))
        (is (> x2 x1) "right")
        (is (< y2 y1) "and up")))))
