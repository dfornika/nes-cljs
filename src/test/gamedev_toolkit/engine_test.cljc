(ns gamedev-toolkit.engine-test
  "The engine's data: sounds, solid maps, and objects found in a level's text."
  (:require [clojure.test :refer [deftest is testing]]
            [gamedev-toolkit.objects :as objects]
            [gamedev-toolkit.screen :as screen]
            [gamedev-toolkit.sound :as sound]
            [gamedev-toolkit.tileset :as tileset]))

(deftest pitches
  (is (= 253 (sound/period 440)) "A above middle C")
  (is (= 507 (sound/period 220)) "an octave down is twice the period (and one more)")
  (is (thrown? #?(:clj Exception :cljs js/Error) (sound/period 20)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (sound/period 20000))))

(deftest effects
  (testing "a fading square wave"
    (is (= [0x83 0x00 253 0x08] (sound/registers {:channel :pulse1 :hz 440 :duty 2 :decay 3}))
        "duty 2 in the top bits, decay 3; no sweep; the period's low byte; a long length"))
  (testing "constant volume, and a period with high bits"
    (is (= [0x5A 0x00 (bit-and 507 0xFF) (bit-or 0x08 (bit-shift-right 507 8))]
           (sound/registers {:channel :pulse2 :hz 220 :duty 1 :volume 10}))))
  (testing "a sweep upwards"
    (is (= 0xAB (second (sound/registers {:channel :pulse1 :hz 220 :decay 1 :sweep {:period 2 :shift 3 :up? true}})))))
  (testing "noise"
    (is (= [0x06 0x00 0x0C 0x08] (sound/registers {:channel :noise :pitch 12 :decay 6}))))
  (testing "playing one is four loads and stores"
    (is (= [[:lda [:imm 0x06]] [:sta 0x400C] [:lda [:imm 0]] [:sta 0x400D]
            [:lda [:imm 0x0C]] [:sta 0x400E] [:lda [:imm 0x08]] [:sta 0x400F]]
           (apply concat (sound/play {:channel :noise :pitch 12 :decay 6})))))
  (is (thrown? #?(:clj Exception :cljs js/Error) (sound/registers {:channel :kazoo}))))

(deftest solid-maps
  (let [s (screen/screen {:metatiles {:air {:tiles [0 0 0 0] :palette 0}
                                      :rock {:tiles [1 1 1 1] :palette 1 :solid? true}}
                          :legend {\. :air \# :rock}
                          :rows (assoc (vec (repeat 15 "................")) 14 "################" 3 "..#............#")})]
    (is (= 240 (count (:solid s))))
    (is (= [0 0 1 0] (subvec (:solid s) 48 52)) "row 3: a byte per block, a row of 16 at a time")
    (is (= 1 (nth (:solid s) 63)))
    (is (every? #{1} (subvec (:solid s) 224 240)) "the floor")
    (is (= 18 (reduce + (:solid s))))))

(deftest objects-in-a-drawing
  (is (= [[:coin 32 0] [:bat 0 16] [:coin 48 16]]
         (objects/in-drawing ["..C." "B..C"] {\C :coin \B :bat}))
      "left to right, top to bottom; in pixels"))

(deftest pools
  (let [pool (objects/pool :thing 4 [:coin :bat] {:x :u8 :y :u8})]
    (is (= [:thing [:soa 4 [[:type :u8] [:x :u8] [:y :u8]]]] (:variable pool)))
    (is (= [1 2] [(objects/type-id pool :coin) (objects/type-id pool :bat)]) "0 is kept for 'free'")
    (is (= {:addr :thing/x :index :x} (objects/field pool :x)))
    (is (= [2 40 96 1 8 16 0] (objects/placements pool [[:bat 40 96] [:coin 8 16]])))
    (is (thrown? #?(:clj Exception :cljs js/Error) (objects/type-id pool :dragon)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (objects/field pool :hp)))))

(deftest facing-the-other-way
  (let [ts (tileset/tileset [[:thing (vec (repeat 16 "1111111122222222"))]])]
    (is (= [[0 0 0 2] [1 8 0 2] [2 0 8 2] [3 8 8 2]] (tileset/sprites ts :thing 2)))
    (is (= [[1 0 0 0x42] [0 8 0 0x42] [3 0 8 0x42] [2 8 8 0x42]] (tileset/sprites-flipped ts :thing 2))
        "the tiles swap sides, and each is flipped")))

(deftest kinds-of-block
  (let [s (screen/screen {:metatiles {:air {:tiles [0 0 0 0] :palette 0}
                                      :rock {:tiles [1 1 1 1] :palette 0 :kind :solid}
                                      :old-rock {:tiles [1 1 1 1] :palette 0 :solid? true}
                                      :plank {:tiles [2 2 2 2] :palette 0 :kind :one-way}
                                      :ladder {:tiles [3 3 3 3] :palette 0 :kind :ladder}}
                          :legend {\. :air \# :rock \% :old-rock \= :plank \H :ladder}
                          :rows (-> (vec (repeat 15 "................"))
                                    (assoc 0 "H.......H......."
                                           1 "H.......=......."
                                           2 "H......%H#......"))})
        at (fn [col row] (nth (:solid s) (+ (* 16 row) col)))]
    (is (= [4 3 3] (map #(at 0 %) [0 1 2])) "a ladder's top block is its top: climbable, and stood on")
    (is (= [4 2 4] (map #(at 8 %) [0 1 2])) "a ladder under a plank has a top of its own")
    (is (= [1 1] [(at 7 2) (at 9 2)]) ":solid? true still means solid")
    (is (zero? (at 1 0))))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (screen/screen {:metatiles {:x {:tiles [0 0 0 0] :palette 0 :kind :jelly}}
                               :legend {\. :x} :rows (vec (repeat 15 "................"))}))))

(deftest kinds-from-text
  (let [s (screen/from-tiles {:grid (vec (repeat 960 0))
                              :block-palettes (vec (repeat 240 0))
                              :solid (assoc (vec (repeat 15 "................")) 3 "#.=.H...........")
                              :kinds {\# :solid \= :one-way \H :ladder}})]
    (is (= [1 0 2 0 4] (subvec (:solid s) 48 53))))
  (testing "by default only # is solid"
    (let [s (screen/from-tiles {:grid (vec (repeat 960 0))
                                :block-palettes (vec (repeat 240 0))
                                :solid (assoc (vec (repeat 15 "................")) 3 "#.=.H...........")})]
      (is (= [1 0 0 0 0] (subvec (:solid s) 48 53))))))

(deftest songs
  (is (= 440.0 (sound/note-hz :a4)))
  (is (= 880.0 (sound/note-hz :a5)))
  (is (< 261 (sound/note-hz :c4) 262) "middle C")
  (is (= (sound/note-hz :cs4) (sound/note-hz :db4)) "sharps and flats")
  (is (= 126 (sound/triangle-period 440)) "an octave below a pulse channel's timer for the same pitch")
  (is (= [10 126 0, 5 0 0xFF, 0] (sound/song [[:a4 10] [:rest 5]])) "frames, low, high; a rest; the end")
  (is (thrown? #?(:clj Exception :cljs js/Error) (sound/note-hz :h2)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (sound/song [[:a4 0]])))
  (is (thrown? #?(:clj Exception :cljs js/Error) (sound/song []))))
