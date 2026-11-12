(ns games.alienscape.import-test
  "JVM only (it reads PNG files): the art kept in the repository is what
  the importer makes from the pack's pictures today."
  (:require [clojure.test :refer [deftest is testing]]
            [gamedev-toolkit.png :as png]
            [games.alienscape.art :as art]
            [games.alienscape.import :as import]))

(deftest the-art-is-up-to-date
  (let [made (import/art)]
    (is (= art/background-palettes (:palettes made)))
    (is (= art/tiles (:tiles made)))
    (is (= art/rooms (:rooms made)))
    (is (= art/sprites (:sprites made))
        "if this fails, run: npm run import:alienscape")
    (testing "the pictures nearly fit the NES as drawn"
      (is (every? #(< % 400) (vals (:repainted made)))
          (str "pixels repainted per room (of 61,440): " (:repainted made))))))

(deftest reading-a-picture
  (let [{:keys [width height pixels]} (png/read-image (str import/pack "Animations/Tiles/liquid.png"))]
    (is (= [16 8] [width height]))
    (is (= 8 (count pixels)))
    (is (every? #(= 4 (count %)) (first pixels)) "red, green, blue, alpha")))

(deftest fitting-the-nes
  (let [k 0x0F
        ;; two 8x8 tiles side by side: the left uses white and gray, the
        ;; right white, gray and dark gray...
        row (vec (concat (take 8 (cycle [0x30 0x10])) (take 8 (cycle [0x30 0x10 0x00]))))
        picture (vec (repeat 8 row))]
    (is (= [[0x00 0x10 0x30]] (png/palettes-for [picture] k))
        "...so one palette covers both, darkest first")
    (testing "a tile's colors become 1 2 3 in its palette's order, the backdrop a dot"
      (let [colors (vec (repeat 16 (vec (take 16 (cycle [k 0x30 0x10 0x00])))))]
        (is (= [(vec (repeat 16 ".321.321.321.321"))]
               (png/frames colors k [0x00 0x10 0x30] 16)))))))
