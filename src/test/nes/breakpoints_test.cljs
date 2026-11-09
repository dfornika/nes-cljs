(ns nes.breakpoints-test
  (:require [cljs.test :refer [deftest is testing]]
            [nes.breakpoints :as bp]))

(def bps
  [{:id 1 :kind :exec :addr 0xC000 :enabled? true}
   {:id 2 :kind :write :addr 0x0712 :enabled? true}
   {:id 3 :kind :access :addr 0x0300 :end 0x033F :enabled? true}
   {:id 4 :kind :write :addr 0x003F :value 0 :enabled? true}
   {:id 5 :kind :read :addr 0x2002 :enabled? true}
   {:id 6 :kind :write :addr 0x0010 :enabled? false}])

(deftest mirrors
  (is (= 0x0712 (bp/normalize 0x0F12)))
  (is (= 0x2002 (bp/normalize 0x3FFA)))
  (is (= 0x8000 (bp/normalize 0x8000))))

(deftest matching
  (testing "execute"
    (is (= 1 (:id (bp/matching bps {:kind :exec :addr 0xC000}))))
    (is (nil? (bp/matching bps {:kind :exec :addr 0xC001}))))
  (testing "writes, through RAM mirrors"
    (is (= 2 (:id (bp/matching bps {:kind :write :addr 0x0712 :value 1}))))
    (is (= 2 (:id (bp/matching bps {:kind :write :addr 0x1F12 :value 1}))))
    (is (nil? (bp/matching bps {:kind :read :addr 0x0712 :value 1}))))
  (testing "ranges and :access"
    (is (= 3 (:id (bp/matching bps {:kind :read :addr 0x0320 :value 9}))))
    (is (= 3 (:id (bp/matching bps {:kind :write :addr 0x0B3F :value 9})))))
  (testing "values"
    (is (= 4 (:id (bp/matching bps {:kind :write :addr 0x003F :value 0}))))
    (is (nil? (bp/matching bps {:kind :write :addr 0x003F :value 3}))))
  (testing "PPU register mirrors"
    (is (= 5 (:id (bp/matching bps {:kind :read :addr 0x200A :value 0x80})))))
  (testing "disabled ones don't"
    (is (nil? (bp/matching bps {:kind :write :addr 0x0010 :value 1}))))
  (testing ":when"
    (let [b [{:id 7 :kind :write :addr 0x55 :enabled? true :when #(> (:value %) 200)}]]
      (is (nil? (bp/matching b {:kind :write :addr 0x55 :value 3})))
      (is (= 7 (:id (bp/matching b {:kind :write :addr 0x55 :value 201})))))))

(deftest flags
  (let [f (bp/flags bps)]
    (is (= bp/flag-exec (aget f 0xC000)))
    (is (= bp/flag-write (aget f 0x0712)))
    (is (= (bit-or bp/flag-read bp/flag-write) (aget f 0x0310)))
    (is (zero? (aget f 0x0010)) "disabled")
    (is (nil? (bp/flags [{:kind :exec :addr 1 :enabled? false}])))))

(deftest parsing-and-describing
  (is (= 0x3F (bp/parse-addr "$3f")))
  (is (= 0xC000 (bp/parse-addr "0xC000")))
  (is (nil? (bp/parse-addr "xyz")))
  (is (= "write $003F = $00" (bp/describe {:kind :write :addr 0x3F :value 0})))
  (is (= "read or write $0300–$033F" (bp/describe {:kind :access :addr 0x300 :end 0x33F}))))
