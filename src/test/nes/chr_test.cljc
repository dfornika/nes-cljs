(ns nes.chr-test
  (:require [nes.bytes :as bytes]
            [clojure.test :refer [deftest is testing]]
            [nes.chr :as chr]))

(defn- mem-with
  "A 16 KB Uint8Array (like PPU memory) with `bytes` written at `addr`."
  [addr bytes]
  (let [m (bytes/make 0x4000)]
    (doseq [[i b] (map-indexed vector bytes)]
      (bytes/put! m (+ addr i) b))
    m))

(deftest tile-addresses
  (is (= 0x0000 (chr/tile-address 0 0)))
  (is (= 0x0410 (chr/tile-address 0 0x41)))
  (is (= 0x1FF0 (chr/tile-address 1 0xFF))))

(deftest combines-bitplanes
  ;; Row 0: plane 0 = 10000001, plane 1 = 11000000
  ;; -> pixel 0 has both bits (3), pixel 1 only the high bit (2),
  ;;    pixel 7 only the low bit (1), the rest neither (0).
  (let [mem (mem-with 0x10 [0x81 0 0 0 0 0 0 0
                            0xC0 0 0 0 0 0 0 0])]
    (is (= [3 2 0 0 0 0 0 1] (first (chr/tile-pixels mem 0x10))))
    (is (= [0 0 0 0 0 0 0 0] (second (chr/tile-pixels mem 0x10))))
    (testing "planes are exposed separately"
      (let [{:keys [plane0 plane1 bytes]} (chr/tile-planes mem 0x10)]
        (is (= [1 0 0 0 0 0 0 1] (first plane0)))
        (is (= [1 1 0 0 0 0 0 0] (first plane1)))
        (is (= 16 (count bytes)))))))

(deftest palettes
  (let [mem (mem-with 0x3F00 [0x0F 0x01 0x02 0x03
                              0x0F 0x11 0x12 0x13
                              0 0 0 0 0 0 0 0
                              0x0F 0x21 0x22 0x23])]
    (is (= [0x0F 0x01 0x02 0x03] (chr/palette-colors mem 0)))
    (is (= [0x0F 0x11 0x12 0x13] (chr/palette-colors mem 1)))
    (testing "entry 0 of every palette is the shared background color"
      (is (= [0x0F 0x21 0x22 0x23] (chr/palette-colors mem 4))))))

(deftest css-colors
  ;; 0x00BBGGRR: red in the low byte
  (is (= "rgb(255,0,0)" (chr/bgr->css 0x0000FF)))
  (is (= "rgb(0,0,255)" (chr/bgr->css 0xFF0000))))

(deftest painting
  (let [mem (mem-with 0x10 (repeat 16 0))
        write! (fn [writes] (doseq [[a v] writes] (bytes/put! mem a v)))]
    (testing "a pixel's color is a bit in each of two bytes"
      (is (= [[0x12 0x10] [0x1A 0x10]] (chr/pixel-writes mem 0x10 3 2 3)) "color 3: both planes")
      (is (= [[0x12 0x10] [0x1A 0x00]] (chr/pixel-writes mem 0x10 3 2 1)) "color 1: the low plane")
      (is (= [[0x12 0x00] [0x1A 0x10]] (chr/pixel-writes mem 0x10 3 2 2)) "color 2: the high plane"))
    (write! (chr/pixel-writes mem 0x10 3 2 3))
    (write! (chr/pixel-writes mem 0x10 0 0 1))
    (write! (chr/pixel-writes mem 0x10 7 7 2))
    (is (= 3 (chr/pixel mem 0x10 3 2)))
    (testing "other pixels in the row are left alone"
      (write! (chr/pixel-writes mem 0x10 4 2 1))
      (is (= [3 1] [(chr/pixel mem 0x10 3 2) (chr/pixel mem 0x10 4 2)]))
      (write! (chr/pixel-writes mem 0x10 3 2 0))
      (is (= [0 1] [(chr/pixel mem 0x10 3 2) (chr/pixel mem 0x10 4 2)])))
    (testing "as text, and back from text"
      (is (= ["1......." "........" "....1..." "........" "........" "........" "........" ".......2"]
             (chr/tile-text mem 0x10)))
      (write! (chr/text-writes 0x20 ["33333333" "........" "........" "........" "........" "........" "........" "12121212"]))
      (is (= [3 3 3 3 3 3 3 3] (first (chr/tile-pixels mem 0x20))))
      (is (= [1 2 1 2 1 2 1 2] (last (chr/tile-pixels mem 0x20))))
      (is (= 16 (count (chr/text-writes 0x20 (chr/tile-text mem 0x20))))))))
