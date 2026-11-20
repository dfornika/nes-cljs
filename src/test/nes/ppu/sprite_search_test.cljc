(ns nes.ppu.sprite-search-test
  (:require [clojure.test :refer [deftest is testing]]
            [nes.bytes :as bytes]
            [nes.ppu.sprite-search :as search]))

(defn- oam
  "OAM with these sprites [y tile attr x] first, and the rest off screen."
  [& sprites]
  (bytes/from (take 256 (concat (apply concat sprites) (repeat 0xFF)))))

(deftest finding-sprites
  (testing "the sprites on the next line, the first 8"
    (is (= {:found [0 2] :overflow-dot nil}
           (search/search (oam [10 0 0 0] [50 0 0 0] [5 0 0 0]) 12 8))))
  (testing "8x16 sprites reach further"
    (is (= [0] (:found (search/search (oam [10 0 0 0]) 20 16))))
    (is (= [] (:found (search/search (oam [10 0 0 0]) 20 8))))))

(deftest overflow
  (testing "a 9th sets the flag, after 8 x 8 dots of copying and 2 to read its Y"
    (is (= {:found (vec (range 8)) :overflow-dot (+ 65 64 1)}
           (search/search (apply oam (repeat 9 [10 0 0 0])) 10 8))))
  (testing "8 is fine"
    (is (nil? (:overflow-dot (search/search (apply oam (repeat 8 [10 0 0 0])) 10 8)))))
  (testing "the bug: after 8, a sprite that isn't on the line makes it read the next one's byte 1 as Y"
    (let [eight (repeat 8 [10 0 0 0])]
      (is (some? (:overflow-dot (search/search (apply oam (concat eight [[200 0 0 0] [200 10 0 0]])) 10 8)))
          "sprite 10's tile number, 10, read as its Y")
      (is (nil? (:overflow-dot (search/search (apply oam (concat eight [[200 0 0 0] [10 200 200 200]])) 10 8)))
          "and sprite 10's real Y is never looked at"))))
