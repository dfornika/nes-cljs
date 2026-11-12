(ns nes.nametable-test
  (:require [clojure.test :refer [deftest is testing]]
            [nes.nametable :as nt]))

(deftest addresses
  (is (= 0x2000 (nt/tile-address 0x2000 0 0)))
  (is (= 0x23BF (nt/tile-address 0x2000 31 29)) "last tile: 960 tiles, then attributes")
  (is (= 0x23C0 (nt/attribute-address 0x2000 0 0)))
  (is (= 0x23C0 (nt/attribute-address 0x2000 3 3)) "one byte covers 4x4 tiles")
  (is (= 0x23C1 (nt/attribute-address 0x2000 4 0)))
  (is (= 0x23C8 (nt/attribute-address 0x2000 0 4)))
  (is (= 0x27FF (nt/attribute-address 0x2400 31 29))))

(deftest attribute-quarters
  ;; %11 10 01 00: bottom-right 3, bottom-left 2, top-right 1, top-left 0
  (let [peek (fn [a] (if (= a 0x23C0) 2r11100100 0))]
    (testing "each 2x2-tile quarter of the 4x4 block has its own palette"
      (is (= 0 (nt/palette-of peek 0x2000 0 0)))
      (is (= 0 (nt/palette-of peek 0x2000 1 1)))
      (is (= 1 (nt/palette-of peek 0x2000 2 0)))
      (is (= 2 (nt/palette-of peek 0x2000 0 2)))
      (is (= 3 (nt/palette-of peek 0x2000 3 3))))))

(deftest camera
  (is (= [{:x 0 :y 0 :w 256 :h 240}] (nt/camera-rects 0 0)))
  (testing "wrapping sideways splits the view in two"
    (is (= [{:x 400 :y 0 :w 112 :h 240} {:x 0 :y 0 :w 144 :h 240}]
           (nt/camera-rects 400 0))))
  (testing "wrapping both ways gives four pieces"
    (is (= 4 (count (nt/camera-rects 400 300))))
    (is (= (* 256 240) (reduce + (map #(* (:w %) (:h %)) (nt/camera-rects 400 300)))))))
