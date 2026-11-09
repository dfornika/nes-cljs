(ns nes.lens-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [nes.lens :as lens]))

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
