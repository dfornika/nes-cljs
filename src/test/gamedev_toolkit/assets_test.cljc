(ns gamedev-toolkit.assets-test
  "Tilesets, palettes and screens: a game's art as data."
  (:require [clojure.test :refer [deftest is testing]]
            [games.catch :as catch]
            [gamedev-toolkit.chr :as chr]
            [gamedev-toolkit.font :as font]
            [gamedev-toolkit.palette :as palette]
            [gamedev-toolkit.screen :as screen]
            [gamedev-toolkit.tileset :as tileset]))

(def ^:private big (vec (repeat 16 "1111111122222222")))   ; 16x16: 2 by 2 tiles
(def ^:private small (vec (repeat 8 "33333333")))

(deftest tilesets
  (let [ts (tileset/tileset [[:font font/tiles] [:big big] [:small small]])
        n (count font/tiles)]
    (is (= (+ n 4 1) (count (:tiles ts))))
    (is (= 0 (tileset/tile ts :font)))
    (is (= n (tileset/tile ts :big)))
    (is (= (+ n 3) (tileset/tile ts :big 1 1)))
    (is (= (+ n 4) (tileset/tile ts :small)))
    (is (= [n (+ n 1) (+ n 2) (+ n 3)] (tileset/block ts :big)))
    (is (= [[n 0 0 2] [(+ n 1) 8 0 2] [(+ n 2) 0 8 2] [(+ n 3) 8 8 2]] (tileset/sprites ts :big 2)))
    (is (= (chr/tile small) (peek (:tiles ts))))
    (testing "mistakes"
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"nothing called :nope" (tileset/tile ts :nope)))
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"no tile at column 2" (tileset/tile ts :big 2 0)))
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"A block is 16x16" (tileset/block ts :small)))
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"both called :a" (tileset/tileset [[:a small] [:a small]]))))))

(deftest colors-by-name
  (is (= 0x0F (palette/color :black)))
  (is (= 0x30 (palette/color :white)))
  (is (= [0x06 0x16 0x26 0x36] (map palette/color [:dark-red :red :light-red :pale-red])))
  (is (= 0x2A (palette/color :light-green)))
  (is (= 0x21 (palette/color 0x21)) "numbers pass through")
  (is (every? #(<= 0 % 0x3F) (vals palette/colors)))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Unknown color :reddish" (palette/color :reddish)))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Unknown color 64" (palette/color 64))))

(deftest palettes
  (let [p (palette/palettes {:backdrop :dark-blue
                             :background [[:white :gray :dark-gray]]
                             :sprites [[:black :yellow :white] [0x27 0x38 0x30]]})]
    (is (= 32 (count p)))
    (is (= [0x01 0x30 0x10 0x00] (subvec p 0 4)))
    (is (= [0x01 0x0F 0x0F 0x0F] (subvec p 4 8)) "palettes left out are black")
    (is (= [0x01 0x0F 0x18 0x30] (subvec p 16 20)))
    (is (= [0x01 0x27 0x38 0x30] (subvec p 20 24)))
    (is (every? #(= 0x01 (p %)) (range 0 32 4)) "the backdrop starts every palette"))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"three colors" (palette/palettes {:background [[:white :gray]]})))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"four sprite palettes" (palette/palettes {:sprites (repeat 5 [:white :white :white])}))))

(def ^:private metatiles
  {:sky {:tiles [0 0 0 0] :palette 0}
   :wall {:tiles [50 51 52 53] :palette 2}})

(defn- rows-with [& changes]
  (reduce (fn [rows [r row]] (assoc rows r row)) (vec (repeat 15 "................")) (partition 2 changes)))

(deftest screens
  (let [s (screen/screen {:metatiles metatiles
                          :legend {\. :sky \# :wall}
                          :rows (rows-with 0 "#..............#" 1 "..#.............")
                          :text [[4 5 "HI"]]})
        tile-at (fn [col row] (nth (:tiles s) (+ (* 32 row) col)))]
    (is (= 960 (count (:tiles s))))
    (is (= 64 (count (:attributes s))))
    (testing "a metatile is four tiles"
      (is (= [50 51 52 53] [(tile-at 0 0) (tile-at 1 0) (tile-at 0 1) (tile-at 1 1)]))
      (is (= [50 51] [(tile-at 30 0) (tile-at 31 0)]) "the last column")
      (is (= [50 53] [(tile-at 4 2) (tile-at 5 3)]) "the second row of blocks")
      (is (= 0 (tile-at 2 0))))
    (testing "text goes over the top"
      (is (= (font/encode "HI") [(tile-at 4 5) (tile-at 5 5)])))
    (testing "palettes: two bits per block, four blocks to a byte"
      (is (= 2 (nth (:attributes s) 0)) "top left block of the first area")
      (is (= (bit-shift-left 2 4) (nth (:attributes s) 1)) "bottom left block of the second area (column 2, row 1)")
      (is (= (bit-shift-left 2 2) (nth (:attributes s) 7)) "top right block of the last area in the row")
      (is (zero? (nth (:attributes s) 8))))
    (is (= 1024 (count (screen/nametable s)))))
  (testing "mistakes"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"15 rows of 16" (screen/screen {:metatiles metatiles :legend {\. :sky} :rows ["...."]})))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"isn't in the :legend" (screen/screen {:metatiles metatiles :legend {\. :sky} :rows (rows-with 0 "?...............")})))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"isn't one of the :metatiles" (screen/screen {:metatiles metatiles :legend {\. :lava} :rows (rows-with)})))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"doesn't fit" (screen/screen {:metatiles metatiles :legend {\. :sky} :rows (rows-with) :text [[30 0 "TOO LONG"]]})))))

(deftest packing
  (is (= [3 7 1 8 0] (screen/pack [7 7 7 8])))
  (is (= [0] (screen/pack [])))
  (is (= [255 0 45 0 0] (screen/pack (repeat 300 0))) "a run longer than 255 is split")
  (is (= [7 7 7 8] (screen/unpack [3 7 1 8 0])))
  (doseq [bytes [[] [1] (repeat 1024 0) (range 256) (mapcat #(repeat (inc (mod % 7)) (mod % 5)) (range 200))]]
    (is (= (vec bytes) (screen/unpack (screen/pack bytes)))))
  (testing "the game's screens"
    (doseq [[name s] catch/screens
            :let [bytes (screen/nametable s)
                  packed (screen/pack bytes)]]
      (is (= bytes (screen/unpack packed)) (str name))
      (is (< (count packed) 700) (str name " packs well: " (count packed) " bytes for 1024")))))
