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
