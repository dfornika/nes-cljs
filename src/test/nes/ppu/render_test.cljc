(ns nes.ppu.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test #?(:clj :refer :cljs :refer-macros) [defspec]]
            [clojure.test.check.generators :as tc]
            [clojure.test.check.properties :as prop #?@(:cljs [:include-macros true])]
            [nes.bytes :as bytes]
            [nes.ppu.memory :as memory]
            [nes.ppu.render :as render]))

;; A small scene. Palette RAM holds each entry's own index, so a pixel's
;; value says which entry it came from: background palette p color c is
;; 4p+c, sprite palette p color c is $10+4p+c. The backdrop is $3F.
;;
;; Tiles: 1 is all color 1, 2 all color 2, 3 a single column of color 3
;; on its left, 4 a single row of color 1 on its top.

(defn- tile! [mem table n lo hi]
  (dotimes [row 8]
    (memory/video-write! mem (+ (* table 0x1000) (* n 16) row) (lo row))
    (memory/video-write! mem (+ (* table 0x1000) (* n 16) 8 row) (hi row))))

(defn- scene []
  (let [mem (memory/video-memory {:mirroring :vertical})
        palette (bytes/from (cons 0x3F (range 1 32)))
        oam (bytes/from (repeat 256 0xFF))]       ; every sprite below the screen
    (doseq [table [0 1]]
      (tile! mem table 1 (constantly 0xFF) (constantly 0))
      (tile! mem table 2 (constantly 0) (constantly 0xFF))
      (tile! mem table 3 (constantly 0x80) (constantly 0x80))
      (tile! mem table 4 #(if (zero? %) 0xFF 0) (constantly 0)))
    {:memory mem :palette palette :oam oam :ctrl 0 :mask 0x1E :t 0 :x 0}))

(defn- name! [{:keys [memory]} addr tile] (memory/video-write! memory addr tile))
(defn- sprite! [{:keys [oam]} i y tile attr x]
  (doseq [[k v] (map vector (range 4) [y tile attr x])]
    (bytes/put! oam (+ (* 4 i) k) v)))

(defn- scroll
  "The scene scrolled to (sx, sy) in the 512x480 space of the four nametables."
  [in sx sy]
  (assoc in
         :t (bit-or (bit-and (bit-shift-right sx 3) 31) (bit-shift-left (bit-and (bit-shift-right sx 8) 1) 10)
                    (bit-shift-left (bit-shift-right (mod sy 240) 3) 5) (bit-shift-left (quot sy 240) 11)
                    (bit-shift-left (bit-and sy 7) 12))
         :x (bit-and sx 7)))

(defn- px [{:keys [pixels]} x y] (bytes/word-at pixels (+ x (* 256 y))))
(defn- row [r y] (mapv #(px r % y) (range 256)))

(deftest drawing-off
  (let [r (render/render (assoc (scene) :mask 0))]
    (is (= #{0x3F} (set (row r 0))) "the backdrop everywhere")))

(deftest background
  (let [in (scene)]
    (name! in 0x2001 1)                     ; column 1, row 0
    (name! in 0x2002 1)                     ; column 2: the top right of the attribute area
    (name! in 0x2040 2)                     ; column 0, row 2: bottom left
    (memory/video-write! (:memory in) 0x23C0 2r11100100)   ; palettes 0, 1, 2, 3
    (let [r (render/render in)]
      (is (= 0x3F (px r 0 0)) "color 0 is the backdrop")
      (is (= [1 1 5 5] (mapv #(px r % 0) [8 15 16 23])) "palette 0 color 1, then palette 1 color 1")
      (is (= 1 (px r 8 7)))
      (is (= 0x3F (px r 8 8)) "a tile is 8 lines")
      (is (= 10 (px r 0 16)) "palette 2 color 2"))))

(deftest fine-and-coarse-scroll
  (let [in (scene)]
    (name! in 0x2001 1)
    (name! in 0x2400 2)                     ; the next nametable across, column 0
    (testing "fine X: 3 pixels left"
      (let [r (render/render (scroll in 3 0))]
        (is (= [0x3F 1 1 0x3F] (mapv #(px r % 0) [4 5 12 13])))))
    (testing "column 31 then into the next nametable"
      (let [r (render/render (scroll in 248 0))]
        (is (= [2 2 0x3F] (mapv #(px r % 0) [8 15 16])))))
    (testing "down: row 29 is followed by the next nametable down, which is this one again"
      (let [r (render/render (scroll in 0 232))]
        (is (= [0x3F 1] [(px r 8 7) (px r 8 8)]))))
    (testing "rows 30 and 31 aren't on the screen, but a game can scroll to them"
      ;; t at row 30: its attribute bytes drawn as tiles, for 16 lines
      (let [r (render/render (assoc in :t (bit-shift-left 30 5)))]
        (is (= (row (render/render in) 0) (row r 16)) "then row 0 of the same nametable")))))

(defspec scrolling-one-pixel-moves-the-picture-one-pixel 30
  (prop/for-all [names (tc/vector (tc/choose 0 4) 960)
                 attrs (tc/vector (tc/choose 0 255) 64)
                 sx (tc/choose 0 510), sy (tc/choose 0 479)]
    (let [in (scene)]
      ;; both nametables (the two across; the two down are the same ones)
      (doseq [table [0 1]]
        (doseq [[i t] (map-indexed vector names)] (name! in (+ 0x2000 (* table 0x400) i) (mod (+ t table) 5)))
        (doseq [[i a] (map-indexed vector attrs)] (name! in (+ 0x23C0 (* table 0x400) i) (bit-xor a (* table 0xFF)))))
      (let [a (render/render (scroll in sx sy))
            b (render/render (scroll in (inc sx) sy))]
        (every? (fn [y] (= (subvec (row a y) 1) (subvec (row b y) 0 255))) (range 240))))))

(deftest sprites
  (let [in (scene)]
    (sprite! in 0 9 1 1 20)                 ; tile 1, palette 1, at x 20 on lines 10-17
    (let [r (render/render in)]
      (is (= [0x3F 0x15 0x15 0x3F] [(px r 20 9) (px r 20 10) (px r 27 17) (px r 20 18)]))
      (is (= 0x3F (px r 28 10)) "8 wide"))
    (testing "a sprite at Y 255 isn't on the screen; one at Y 0 starts on line 1"
      (sprite! in 0 0xFF 1 0 0) (sprite! in 1 0 1 0 100)
      (let [r (render/render in)]
        (is (= #{0x3F} (set (subvec (row r 0) 0 200))))
        (is (= 0x11 (px r 100 1)))))))

(deftest flips
  (let [in (scene)]
    (sprite! in 0 9 3 0 20)                 ; the column on the left
    (sprite! in 1 9 3 0x40 40)              ; flipped side to side: on the right
    (sprite! in 2 9 4 0x80 60)              ; the top row, flipped top to bottom: the bottom row
    (let [r (render/render in)]
      (is (= [0x13 0x3F] [(px r 20 10) (px r 27 10)]))
      (is (= [0x3F 0x13] [(px r 40 10) (px r 47 10)]))
      (is (= [0x3F 0x11] [(px r 60 10) (px r 60 17)])))))

(deftest priority
  (let [in (scene)]
    (name! in 0x2003 1)                     ; background color 1 at x 24-31, lines 0-7
    (sprite! in 1 0 2 0x20 20)              ; behind the background, at x 20-27, lines 1-8
    (let [r (render/render in)]
      (is (= [0x12 1 0x12] [(px r 23 1) (px r 24 1) (px r 24 8)])
          "behind: shows only where the background is color 0"))
    (testing "the lowest numbered sprite wins, even when it's behind and hidden"
      (sprite! in 0 0 2 0x20 24)            ; sprite 0 behind, covering x 24-31
      (sprite! in 2 0 1 0 24)               ; sprite 2 in front, same place
      (let [r (render/render in)]
        (is (= 1 (px r 26 1)) "sprite 0 wins the pixel, and loses it to the background")))))

(deftest eight-a-line
  (let [in (scene)]
    (doseq [i (range 9)] (sprite! in i 9 1 0 (* 10 i)))
    (let [r (render/render in)]
      (is (= 0x11 (px r 70 10)) "the 8th")
      (is (= 0x3F (px r 80 10)) "not the 9th")
      (is (= 10 (:overflow-line r))))))

(deftest left-edge
  (let [in (scene)]
    (name! in 0x2000 1)
    (sprite! in 0 9 1 0 4)                  ; x 4-11
    (let [shown (render/render in)
          hidden (render/render (assoc in :mask 0x18))]
      (is (= [1 0x11] [(px shown 0 0) (px shown 4 10)]))
      (is (= [0x3F 0x3F] [(px hidden 0 0) (px hidden 4 10)]))
      (is (= [0x11] [(px hidden 8 10)]) "only the left 8"))))

(deftest sprite-0-hit
  (let [in (scene)]
    (name! in 0x2043 1)                     ; background at x 24-31, lines 16-23
    (sprite! in 0 9 1 0 20)                 ; sprite 0 at x 20-27, lines 10-17
    (is (= [24 16] (:sprite-0-hit (render/render in))) "the first overlap")
    (is (nil? (:sprite-0-hit (render/render (assoc in :mask 0x0E)))) "not with sprites hidden")
    (sprite! in 0 9 1 0 4) (name! in 0x2040 1) (name! in 0x2041 1)   ; x 4-11 over x 0-15
    (is (= [8 16] (:sprite-0-hit (render/render (assoc in :mask 0x18)))) "not in a hidden left edge")))

(deftest tall-sprites
  (let [in (assoc (scene) :ctrl 0x20)]     ; 8x16 sprites
    (tile! (:memory in) 1 2 (constantly 0xFF) (constantly 0xFF))   ; table 1, tile 2: color 3
    (sprite! in 0 9 0x03 0 20)              ; odd: table 1, tiles 2 and 3
    (let [r (render/render in)]
      (is (= [0x13 0x13 0x13 0x3F] [(px r 20 10) (px r 20 17) (px r 20 25) (px r 20 26)])
          "tile 2 on top (color 3), then tile 3 (a column of color 3)")
      (is (= 0x3F (px r 21 18))))))

(deftest gray-and-tints
  (let [in (scene)]
    (name! in 0x2000 1)
    (memory/video-write! (:memory in) 0x23C0 3)   ; palette 3: color 1 is entry 13
    (let [r (render/render (assoc in :mask (bit-or 0x1E 0x01 0xA0)))]
      (is (= (bit-or (bit-and 13 0x30) (bit-shift-left 5 6)) (px r 0 0))
          "gray keeps the brightness bits; the tints ride above the 6 bits of color"))))
