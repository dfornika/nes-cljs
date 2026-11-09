(ns nes.text-table-test
  (:require [cljs.test :refer [deftest is]]
            [nes.text-table :as tt]))

(def table (tt/parse "; a comment\nC0= \nC1=A\nC2=B\nc3=C\n\nDB=r͘\r\n0102=XY\n"))

(deftest parsing
  ;; spaces count, case doesn't, CRLF is fine, multi-byte entries are skipped
  (is (= {0xC0 " " 0xC1 "A" 0xC2 "B" 0xC3 "C" 0xDB "r͘"} table)))

(deftest round-trip
  (is (= "CAB ·" (tt/decode table [0xC3 0xC1 0xC2 0xC0 0x00])))
  (is (= [0xC3 0xC1 0xC2 0xC0 0xDB] (tt/encode table "CAB r͘")))
  (is (nil? (tt/encode table "CAT"))))

(deftest screen
  ;; a nametable with "AB" at row 1, column 2
  (let [mem (-> (vec (repeat 0x1000 0)) (assoc (+ 32 2) 0xC1) (assoc (+ 32 3) 0xC2))
        peek #(nth mem (- % 0x2000))]
    (is (= "  AB" (.trimEnd (nth (tt/screen-lines table peek 0x2000) 1))))
    ;; scrolled right by 16 pixels: two columns earlier
    (is (= "AB" (.trimEnd (nth (tt/visible-lines table peek 16 0) 1))))))
