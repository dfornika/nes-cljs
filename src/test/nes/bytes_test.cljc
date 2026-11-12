(ns nes.bytes-test
  "nes.bytes and nes.util: the same answers in the browser and on the JVM."
  (:require [clojure.test :refer [deftest is testing]]
            [nes.bytes :as bytes]
            [nes.util :as util]))

(deftest bytes-are-0-to-255
  (let [b (bytes/make 4)]
    (is (= 4 (bytes/size b)))
    (is (= [0 0 0 0] (bytes/->vec b)))
    (bytes/put! b 1 0xFF)
    (bytes/put! b 2 0x80)
    (is (= 0xFF (bytes/at b 1)) "not -1, as a JVM byte would say")
    (is (= 0x80 (bytes/at b 2)))
    (is (= [0 0xFF 0x80 0] (bytes/->vec b))))
  (is (= [1 2 255] (bytes/->vec (bytes/from [1 2 255]))))
  (is (= 0 (bytes/size (bytes/from [])))))

(deftest slices-and-copies
  (let [b (bytes/from (range 10))]
    (is (= [7 8 9] (bytes/->vec (bytes/slice b 7))))
    (is (= [2 3] (bytes/->vec (bytes/slice b 2 4))))
    (is (= [0 1 200 201 4] (bytes/->vec (bytes/slice (bytes/copy-into! b (bytes/from [200 201]) 2) 0 5))))
    (is (= [9 9 9] (bytes/->vec (bytes/fill! (bytes/make 3) 9))))))

(deftest words-are-32-bit
  (let [w (bytes/make-words 2)]
    (bytes/put-word! w 0 0xFF112233)
    (bytes/put-word! w 1 7)
    (is (= 0xFF112233 (bytes/word-at w 0)) "a pixel with full alpha stays positive")
    (is (= [0xFF112233 7] (bytes/words->vec w)))
    (is (= 2 (bytes/size w)))))

(deftest hex
  (is (= "$FF" (util/hex 255 2)))
  (is (= "$00FF" (util/hex 255 4)))
  (is (= "$C000" (util/hex 0xC000 2)) "width is a minimum")
  (is (= "0A" (util/hex-digits 10 2)))
  (is (= "A" (util/hex-digits 10))))

(deftest reading-numbers
  (is (= 42 (util/parse-int "42")))
  (is (= -42 (util/parse-int " -42 ")))
  (is (= 255 (util/parse-int "ff" 16)))
  (is (= 255 (util/parse-int "FF" 16)))
  (testing "not numbers"
    (is (nil? (util/parse-int "")))
    (is (nil? (util/parse-int nil)))
    (is (nil? (util/parse-int "12abc")) "all of it has to be a number")
    (is (nil? (util/parse-int "g" 16)))
    (is (nil? (util/parse-int "1.5"))))
  (is (= 1.5 (util/parse-number "1.5")))
  (is (= -0.25 (util/parse-number "-.25")))
  (is (= 3.0 (util/parse-number "3")))
  (is (nil? (util/parse-number "three")))
  (is (= 384 (util/round 383.6)))
  (is (= -64 (util/round -64.2))))
