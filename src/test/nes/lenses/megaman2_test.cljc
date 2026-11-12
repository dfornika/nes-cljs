(ns nes.lenses.megaman2-test
  (:require [clojure.test :refer [deftest is]]
            [nes.lens :as lens]
            [nes.lenses.megaman2 :as mm2]))

(defn- snapshot-with-ram [addr-values]
  {:ram (reduce (fn [ram [a v]] (assoc ram a v)) (vec (repeat 0x800 0)) addr-values)
   :cart-ram (vec (repeat 0x2000 0))})

;; Values seen in Wood Man's stage, a few seconds in: Mega Man facing
;; right, one shot out, two enemies.
(def wood-man-stage
  (snapshot-with-ram
   {0x002A 2, 0x00A8 3, 0x00A9 0, 0x0032 1, 0x009A 0x05
    0x0420 0xC0, 0x0400 0x09, 0x0440 0, 0x0460 248, 0x04A0 180, 0x06C0 28
    0x0424 0xC1, 0x0404 0x23, 0x0444 1, 0x0464 16, 0x04A4 180
    0x043E 0x8B, 0x041E 0x16, 0x045E 1, 0x047E 72, 0x04BE 104, 0x06DE 20
    0x0023 0x82}))

(deftest mega-man
  (let [v (lens/interpret mm2/mega-man wood-man-stage)]
    (is (= 28 (:hp v)))
    (is (= "Mega Buster" (:weapon v)))
    (is (= 248 (:x v)))
    (is (= :right (:facing v)))
    (is (= "ground" (:standing-on v)))))

(deftest stage-and-arsenal
  (is (= "Wood Man" (:stage (lens/interpret mm2/stage wood-man-stage))))
  (is (= ["Heat Man" "Wood Man"]
         (:robot-masters-beaten (lens/interpret mm2/arsenal wood-man-stage))))
  (is (= [:b :right] (:buttons-held (lens/interpret mm2/input-and-sound wood-man-stage)))))

(deftest objects
  (let [objs (mm2/objects wood-man-stage)]
    (is (= [0 4 30] (map :slot objs)))
    (is (= [:mega-man :weapon-or-effect :enemy] (map :kind objs)))
    (is (= {:x 328 :y 104 :hp 20 :facing :left}
           (select-keys (last objs) [:x :y :hp :facing])))))

(deftest registered-by-hash
  (is (= ["Mega Man" "Arsenal" "Stage" "Objects" "Input & sound"
          "Boss weaknesses" "Game rules" "Credits" "Screen text" "Machine"]
         (map :name (lens/lenses-for {:sha1 mm2/sha1 :name "Mega Man II.nes"})))))
