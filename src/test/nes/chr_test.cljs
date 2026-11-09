(ns nes.chr-test
  (:require [cljs.test :refer [deftest is testing]]
            [nes.chr :as chr]))

(defn- mem-with
  "A 16 KB Uint8Array (like PPU memory) with `bytes` written at `addr`."
  [addr bytes]
  (let [m (js/Uint8Array. 0x4000)]
    (doseq [[i b] (map-indexed vector bytes)]
      (aset m (+ addr i) b))
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
