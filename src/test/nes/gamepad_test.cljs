(ns nes.gamepad-test
  (:require [cljs.test :refer [deftest is testing]]
            [nes.gamepad :as gp]))

(defn- buttons [& pressed]
  (let [pressed (set pressed)]
    (mapv #(contains? pressed %) (range 17))))

(deftest standard-layout
  (is (= #{} (gp/pressed-buttons (buttons) [0 0])))
  (testing "face buttons: right/top are A, bottom/left are B, like the NES pad"
    (is (= #{:a} (gp/pressed-buttons (buttons 1) [0 0])))
    (is (= #{:a} (gp/pressed-buttons (buttons 3) [0 0])))
    (is (= #{:b} (gp/pressed-buttons (buttons 0) [0 0])))
    (is (= #{:b} (gp/pressed-buttons (buttons 2) [0 0]))))
  (is (= #{:start :select} (gp/pressed-buttons (buttons 8 9) [])))
  (is (= #{:up :left} (gp/pressed-buttons (buttons 12 14) []))))

(deftest left-stick
  (is (= #{:right :down} (gp/pressed-buttons (buttons) [0.9 0.8])))
  (is (= #{:left :up} (gp/pressed-buttons (buttons) [-0.9 -0.8])))
  (testing "small movements are ignored"
    (is (= #{} (gp/pressed-buttons (buttons) [0.3 -0.2])))))

(deftest short-button-lists
  ;; some gamepads report fewer buttons than the standard 17
  (is (= #{:a} (gp/pressed-buttons [false true] []))))
