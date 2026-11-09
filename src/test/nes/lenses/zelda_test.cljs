(ns nes.lenses.zelda-test
  (:require [cljs.test :refer [deftest is]]
            [nes.lens :as lens]
            [nes.lenses.zelda :as zelda]
            [nes.text-table :as text-table]))

(defn- snapshot-with-ram [addr-values]
  {:ram (reduce (fn [ram [a v]] (assoc ram a v)) (vec (repeat 0x800 0)) addr-values)
   :cart-ram (vec (repeat 0x2000 0))})

;; Values seen on the screen west of the start, with "ABC" in file 1
(def west-of-start
  (snapshot-with-ram
   {0x0012 5, 0x00EB 0x76, 0x0070 36, 0x0084 141, 0x0098 2
    0x066F 0x22, 0x0670 0xFF, 0x0657 1
    0x0350 0x0E, 0x0071 29, 0x0085 97, 0x0099 9
    0x0638 0x0A, 0x0639 0x0B, 0x063A 0x0C, 0x063B 0x24, 0x063C 0x24, 0x063D 0x24, 0x063E 0x24, 0x063F 0x24}))

(deftest link
  (let [v (lens/interpret zelda/link west-of-start)]
    (is (= {:containers 3 :filled 3} (:hearts v)))
    (is (= "west" (:facing v)))
    (is (= {:column 6 :row 7 :id 0x76} (:screen v)))
    (is (= "wooden sword" (:sword v)))))

(deftest screen
  (let [[e] (:enemies (lens/interpret zelda/screen west-of-start))]
    (is (= {:slot 1 :type 0x0E :x 29 :y 97 :facing "north-east"} (dissoc e :invincible-for)))))

(deftest save-file-names
  (is (= "ABC     " (:file-1-name (lens/interpret zelda/save-files west-of-start)))))

(deftest cave-text-encoding
  ;; "HI" then a new line, "YO", the end: H, I+$80, Y, O+$C0
  (is (= "HI|YO⏹" (text-table/decode zelda/npc-text-table [0x11 (+ 0x80 0x12) 0x22 (+ 0xC0 0x18)])))
  (is (= [0x11 (+ 0x80 0x12) 0x22 (+ 0xC0 0x18)] (text-table/encode zelda/npc-text-table "HI|YO⏹"))))
