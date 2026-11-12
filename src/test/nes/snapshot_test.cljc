(ns nes.snapshot-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.datafy :as d]
            [nes.lens :as lens]
            [nes.snapshot :as snapshot]))

(defn- blank []
  (#'snapshot/with-nav
   {:frame 0
    :cpu {:a 0 :x 0 :y 0 :sp 0xFD :pc 0x8000 :p 0x24}
    :ram (vec (repeat 0x800 0))
    :cart-ram (vec (repeat 0x2000 0))
    :ppu {:oam (vec (repeat 256 0xFF))
          :palettes (vec (range 32))
          :nametables (vec (repeat 0x1000 0))}}))

(defn- with-ram [s & addr-values]
  (reduce (fn [s [addr v]]
            (if (< addr 0x2000)
              (assoc-in s [:ram (bit-and addr 0x7FF)] v)
              (assoc-in s [:cart-ram (- addr 0x6000)] v)))
          s (partition 2 addr-values)))

(deftest reading-by-address
  (let [s (with-ram (blank) 0x0010 7 0x6001 9)]
    (is (= 7 (snapshot/cpu-byte s 0x0010)))
    (is (= 7 (snapshot/cpu-byte s 0x0810)) "RAM is mirrored")
    (is (= 9 (snapshot/cpu-byte s 0x6001)))
    (is (nil? (snapshot/cpu-byte s 0x2002)) "I/O isn't captured")
    (is (= [0 7 0] (snapshot/cpu-bytes s 0x000F 3)))))

(deftest diffing
  (let [a (with-ram (blank) 0x0010 7)
        b (-> (with-ram a 0x0010 6 0x6000 1)
              (assoc-in [:ppu :oam 3] 0x40))]
    (is (= [{:space :cpu :addr 0x0010 :before 7 :after 6 :delta -1}
            {:space :cpu :addr 0x6000 :before 0 :after 1 :delta 1}
            {:space :oam :addr 3 :before 0xFF :after 0x40 :delta -191}]
           (snapshot/diff a b)))
    (is (= 2 (count (snapshot/diff a b :only #{:cpu}))))))

(deftest searching
  ;; "lives" at $0075 goes 3 -> 2 -> 1; a timer at $0020 goes down too,
  ;; but by 2 each time
  (let [s1 (with-ram (blank) 0x0075 3 0x0020 10)
        s2 (with-ram s1 0x0075 2 0x0020 8)
        s3 (with-ram s2 0x0075 1 0x0020 6)]
    (is (= [0x0075] (snapshot/search [s1 s2 s3] (fn [a b] (= b (dec a))))))
    (is (= [0x0020 0x0075] (snapshot/search [s1 s2 s3] >)))))

(deftest flags
  (is (= #{:zero :carry} (snapshot/decode-flags 0x03)))
  (is (= #{:negative :irq-disable} (snapshot/decode-flags 0xA4))))

(deftest navigating
  (let [s (blank)
        ppu (d/nav s :ppu (:ppu s))]
    (testing "navigating into OAM decodes the sprites"
      (let [sprites (d/nav ppu :oam (:oam ppu))]
        (is (= 64 (count sprites)))
        (is (:hidden? (first sprites)))))
    (testing "navigating into palettes groups them"
      (is (= [4 5 6 7] (get-in (d/nav ppu :palettes (:palettes ppu)) [:background 1])))
      (is (= [16 17 18 19] (get-in (d/nav ppu :palettes (:palettes ppu)) [:sprites 0]))))
    (testing "other keys navigate to themselves"
      (is (= (:ram s) (d/nav s :ram (:ram s)))))))

(deftest lenses
  (let [s (with-ram (blank)
            0x0010 0x12 0x0011 0x34            ; BCD 1234
            0x0020 1 0x0021 2 0x0022 3          ; digits 123
            0x0030 0xFF                         ; -1 signed
            0x0040 0x34 0x0041 0x12             ; $1234 little-endian
            0x0050 5 0x0051 6                   ; struct
            0x0060 0x81)]                       ; bits 7 and 0
    (is (= {:score 1234
            :coins 123
            :speed -1
            :ptr 0x1234
            :pos {:x 5 :y 6}
            :row [5 6 0]
            :flags {:paused true :two-player true :music false}
            :alive? true
            :custom 0x12}
           (lens/interpret
            {:score [:bcd 0x0010 2]
             :coins [:digits 0x0020 3]
             :speed [:s8 0x0030]
             :ptr [:u16 0x0040]
             :pos [:struct 0x0050 [:x :u8 :y :u8]]
             :row [:array 0x0050 3 :u8]
             :flags [:bits 0x0060 {:paused 7 :two-player 0 :music 1}]
             :alive? [:bool 0x0050]
             :custom [:fn #(snapshot/cpu-byte % 0x0010)]}
            s)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (lens/interpret {:x [:nope 0]} s)))))
