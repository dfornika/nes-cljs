(ns nes.inspect.compare-test
  (:require [clojure.test :refer [deftest is testing]]
            [nes.bytes :as bytes]
            [nes.inspect.compare :as compare]))

(defn- picture [f] (let [p (bytes/make-words (* 256 240))] (dotimes [i (* 256 240)] (bytes/put-word! p i (f i))) p))
(def all-drawn (bytes/from (repeat 240 1)))

(deftest pictures
  (let [a (picture (constantly 0x123456))
        b (picture #(if (#{(+ 100 (* 256 10)) 3 (+ 255 256)} %) 0 0x123456))]
    (testing "differences, by index"
      (is (= [3 (+ 255 256) (+ 100 (* 256 10))] (compare/picture-diffs a b 0x1E 0 all-drawn))))
    (testing "the left 8 columns are left out when PPUMASK hides a layer there"
      (is (= [(+ 255 256) (+ 100 (* 256 10))] (compare/picture-diffs a b 0x1C 0 all-drawn))))
    (testing "and the last fine-X columns"
      (is (= [3 (+ 100 (* 256 10))] (compare/picture-diffs a b 0x1E 2 all-drawn))))
    (testing "and lines drawn with drawing off"
      (is (= [3 (+ 255 256)] (compare/picture-diffs a b 0x1E 0 (bytes/from (assoc (vec (repeat 240 1)) 10 0))))))
    (testing "the top byte (alpha) doesn't count"
      (is (= [] (compare/picture-diffs a (picture (constantly 0xFF123456)) 0x1E 0 all-drawn))))))

(deftest ram
  (is (= [[5 1 2]] (compare/ram-diffs #(if (= % 5) 1 0) #(if (= % 5) 2 0)))))
