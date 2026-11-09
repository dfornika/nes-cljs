(ns nes.lenses.croom-test
  (:require [cljs.test :refer [deftest is]]
            [nes.lens :as lens]
            [nes.lenses.croom :as croom]))

(defn- snapshot-with-ram [addr-values]
  {:ram (reduce (fn [ram [a v]] (assoc ram a v)) (vec (repeat 0x800 0)) addr-values)
   :cart-ram (vec (repeat 0x2000 0))})

(deftest reading-the-board
  ;; Bytes like those seen in the game: $FF at $0300 isn't a card; a pair
  ;; of $9E cards, one face up ($DE); the board is stored column by column.
  (let [s (snapshot-with-ram {0x0300 0xFF
                              0x0326 0x9E    ; column 4, row 6
                              0x032E 0xDE    ; column 5, row 6, face up
                              0x003F 19
                              0x0055 152 0x0056 171})]
    (is (= [{:col 4 :row 6 :picture 0x1E :face-up? false}
            {:col 5 :row 6 :picture 0x1E :face-up? true}]
           (croom/cards s)))
    (is (= {:tries-left 19 :pointer {:x 152 :y 171} :cards-left 2}
           (select-keys (lens/interpret croom/lens s) [:tries-left :pointer :cards-left])))
    (is (= ["Pointer & score" "Board" "Machine"]
           (map :name (lens/lenses-for {:sha1 croom/sha1 :name "renamed.nes"}))))))
