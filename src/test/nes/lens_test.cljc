(ns nes.lens-test
  (:require [nes.bytes :as bytes]
            [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nes.lens :as lens]
            [nes.snapshot :as snapshot]))

(deftest several-lenses-per-rom
  (lens/register! {:rom "test.nes" :name "B" :fields {:b [:u8 1]}})
  (lens/register! {:rom "0123abcd" :name "A" :fields {:a [:u8 0]}})
  (lens/register! {:rom "other.nes" :name "C" :fields {:c [:u8 2]}})
  (try
    (let [found (lens/lenses-for {:sha1 "0123abcd" :name "test.nes"})]
      ;; the game's own, in the order registered, then ones for any game
      (is (= ["B" "A" "Machine"] (map :name found)))
      (is (= [:file-name :sha1 :any] (map :matched-by found))))
    ;; registering the same name again replaces it, keeping its place
    (lens/register! {:rom "test.nes" :name "B" :fields {:b2 [:u8 1]}})
    (is (= {:b2 [:u8 1]} (lens/lens-for "test.nes")))
    (is (= ["B" "A" "Machine"] (map :name (lens/lenses-for {:sha1 "0123abcd" :name "test.nes"}))))
    ;; the two-argument form is a lens called "Main"
    (lens/register! "two.nes" {:x [:u8 3]})
    (is (= "Main" (:name (first (lens/lenses-for {:name "two.nes"})))))
    (finally
      (doseq [[r n] [["test.nes" "B"] ["0123abcd" "A"] ["other.nes" "C"] ["two.nes" "Main"]]]
        (lens/unregister! r n)))))

(deftest lens-source
  (let [src (lens/source {:rom "game.nes" :name "Player"
                          :fields [[:lives [:u8 0x075A]]
                                   [:pos [:struct 0x86 [:x :u8 :y :u8]]]
                                   [:f [:fn identity]]]}
                         "abc123")]
    (is (str/includes? src "{:rom \"abc123\" ; game.nes"))
    (is (str/includes? src "[:lives [:u8 0x075A]]"))
    (is (str/includes? src "[:pos [:struct 0x0086 [:x :u8 :y :u8]]]"))
    (is (str/includes? src "[:f [:fn (fn [s] ...)]]"))))

(defn- snap-with [ram rom]
  (snapshot/with-rom-file
    {:ram (reduce (fn [r [a v]] (assoc r a v)) (vec (repeat 0x800 0)) ram)
     :cart-ram (vec (repeat 0x2000 0))}
    (bytes/from rom)))

(def table {1 "A" 2 "B" 32 " "})

(deftest reading-rom-and-text
  (let [s (snap-with {0x10 1 0x11 2 0x12 32} [0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 7 9 1 2])]
    (is (= 7 (lens/read-field s [:rom [:u8 16]])))
    (is (= [7 9] (lens/read-field s [:rom [:array 16 2 :u8]])))
    (is (= "AB" (lens/read-field s [:rom [:text 18 2 table]])))
    (is (= "AB " (lens/read-field s [:text 0x10 3 table])))
    (is (= {:on "on" :n 9} (lens/read-field s [:rom [:struct 16 [:on [:enum {7 "on"}] :n :u8]]])))))

(deftest writing
  (let [s (snap-with {0x20 2r1000} [])]
    (is (= [{:space :cpu :addr 0x10 :value 42}] (lens/writes s [:u8 0x10] 42)))
    (is (= [{:space :cpu :addr 0x10 :value 0xFF}] (lens/writes s [:s8 0x10] -1)))
    (is (= [{:space :cpu :addr 0x10 :value 0x34} {:space :cpu :addr 0x11 :value 0x12}]
           (lens/writes s [:u16 0x10] 0x1234)))
    (is (= [{:space :cpu :addr 0x10 :value 1}] (lens/writes s [:enum 0x10 {0 "air" 1 "ground"}] "ground")))
    (is (= [{:space :cpu :addr 0x20 :value 2r1001}] (lens/writes s [:bits 0x20 {:a 0 :d 3}] [:a] true)))
    (is (= [{:space :cpu :addr 0x20 :value 0}] (lens/writes s [:bits 0x20 {:a 0 :d 3}] [:d] false)))
    (is (= [{:space :cpu :addr 0x10 :value 0x12} {:space :cpu :addr 0x11 :value 0x34}]
           (lens/writes s [:bcd 0x10 2] 1234)))
    (is (= [{:space :cpu :addr 0x11 :value 5}]
           (lens/writes s [:struct 0x10 [:x :u8 :y :u8]] [:y] 5)))
    (is (= [{:space :cpu :addr 0x12 :value 5}] (lens/writes s [:array 0x10 4 :u8] [2] 5)))
    (is (= [{:space :rom :addr 0x2E953 :value 28}]
           (lens/writes s [:rom [:struct 0x2E952 [:heat :u8 :air :u8]]] [:air] 28)))
    (is (= [{:space :rom :addr 5 :value 2} {:space :rom :addr 6 :value 1} {:space :rom :addr 7 :value 32}]
           (lens/writes s [:rom [:text 5 3 table]] "BA")))
    (is (nil? (lens/writes s [:fn identity] 1)))))

(deftest what-can-be-edited
  (is (lens/editable? [:u8 1]))
  (is (lens/editable? [:rom [:u8 1]]))
  (is (lens/editable? [:struct 0 [:x :u8]] [:x]))
  (is (not (lens/editable? [:struct 0 [:x :u8]])))
  (is (lens/editable? [:rom [:array 0 3 :u8]] [1]))
  (is (lens/editable? [:bits 0 {:a 0}] [:a]))
  (is (not (lens/editable? [:fn identity])))
  (is (not (lens/editable? [:screen-text table]))))
