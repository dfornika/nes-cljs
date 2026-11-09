(ns nes.memory-map-test
  (:require [cljs.test :refer [deftest is]]
            [nes.memory-map :as mm]))

(deftest memory-maps-cover-each-address-space-exactly
  (doseq [space [:cpu :ppu]
          :let [size (get-in mm/memory-maps [space :size])
                regions (sort-by :start (flatten (get-in mm/memory-maps [space :regions])))]]
    (is (= 0 (:start (first regions))) (str space " starts at 0"))
    (is (= (dec size) (:end (last regions))) (str space " ends at the top"))
    (doseq [[a b] (partition 2 1 regions)]
      (is (= (inc (:end a)) (:start b))
          (str space ": " (:name a) " should end right before " (:name b))))))

(deftest region-lookup
  (is (= "Stack" (:name (mm/region-at :cpu 0x01FF))))
  (is (= "PRG ROM" (:name (mm/region-at :cpu 0x8000))))
  (is (= "Interrupt vectors" (:name (mm/region-at :cpu 0xFFFC))))
  (is (= "Attribute table 1" (:name (mm/region-at :ppu 0x27C0))))
  (is (= "Sprite palettes" (:name (mm/region-at :ppu 0x3F11)))))
