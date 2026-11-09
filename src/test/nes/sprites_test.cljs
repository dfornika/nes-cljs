(ns nes.sprites-test
  (:require [cljs.test :refer [deftest is testing]]
            [nes.sprites :as sprites]))

(defn- oam-with [i [y tile attr x]]
  (let [oam (js/Uint8Array. 256)]
    (aset oam (* 4 i) y)
    (aset oam (+ 1 (* 4 i)) tile)
    (aset oam (+ 2 (* 4 i)) attr)
    (aset oam (+ 3 (* 4 i)) x)
    oam))

(deftest decoding-oam
  (let [s (sprites/sprite (oam-with 5 [0x40 0x21 0xE2 0x80]) 5)]
    (is (= {:index 5 :y 0x40 :tile 0x21 :attr 0xE2 :x 0x80 :palette 6
            :behind-bg? true :flip-h? true :flip-v? true :hidden? false}
           s)))
  (is (:hidden? (sprites/sprite (oam-with 0 [0xEF 0 0 0]) 0)))
  (is (= 64 (count (sprites/sprites (js/Uint8Array. 256))))))

(deftest tile-addresses
  (testing "8x8 sprites use the pattern table from PPUCTRL"
    (is (= [0x1210] (sprites/tile-addresses {:tile 0x21} 8 1))))
  (testing "8x16 sprites: bit 0 picks the table, the rest the top tile"
    (is (= [0x1020 0x1030] (sprites/tile-addresses {:tile 0x03} 16 0)))))

(deftest drawing-with-flips
  ;; A tile whose only pixel is the top-left one (color 1)
  (let [mem (js/Uint8Array. 0x4000)
        _ (aset mem 0x0000 0x80)
        colors [0 0x111111 0x222222 0x333333]
        draw (fn [attr]
               (let [px (js/Uint32Array. 64)]
                 (sprites/draw-sprite! px 8 8 mem (sprites/sprite (oam-with 0 [0 0 attr 0]) 0)
                                       8 0 colors 0 0)
                 (vec (keep-indexed #(when (pos? %2) %1) (array-seq px)))))]
    (is (= [0] (draw 0x00)) "top-left")
    (is (= [7] (draw 0x40)) "flipped horizontally: top-right")
    (is (= [56] (draw 0x80)) "flipped vertically: bottom-left")
    (is (= [63] (draw 0xC0)) "both: bottom-right")))
