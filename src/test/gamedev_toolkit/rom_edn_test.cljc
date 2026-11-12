(ns gamedev-toolkit.rom-edn-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [games.catch :as catch]
            [games.hello :as hello]
            [gamedev-toolkit.chr :as chr]
            [gamedev-toolkit.rom :as rom]
            [gamedev-toolkit.rom-edn :as rom-edn]))

(deftest hex-text
  (is (= "00FF10AB" (rom-edn/hex [0 255 16 171])))
  (is (= [0 255 16 171] (rom-edn/unhex "00ff 10\nAB")))
  (is (= (range 256) (rom-edn/unhex (rom-edn/hex (range 256)))))
  (is (= 8 (count (str/split-lines (rom-edn/hex (range 256))))) "32 bytes to a line")
  (is (thrown? #?(:clj Exception :cljs js/Error) (rom-edn/unhex "0G")))
  (is (thrown? #?(:clj Exception :cljs js/Error) (rom-edn/unhex "ABC"))))

(deftest tiles-as-drawings
  (let [drawing ["..1111.." ".122221." "12322321" "12222221" "12322321" "12233221" ".122221." "..1111.."]]
    (is (= drawing (chr/rows (chr/tile drawing))))
    (is (= [(chr/tile drawing)] (chr/bank-tiles (chr/bank [(chr/tile drawing)]))) "the empty tiles at the end are dropped")
    (is (= [chr/blank (chr/tile drawing)] (chr/bank-tiles (chr/bank [chr/blank (chr/tile drawing)]))) "but not ones before")))

(deftest containers
  (doseq [built [(hello/build) (catch/build)]]
    (let [c (rom-edn/container (:rom built))]
      (is (= {:mapper 0 :prg-banks 1 :chr-banks 1 :mirroring :horizontal :battery? false} (:about c)))
      (is (= (:rom built) (rom-edn/->rom c)))
      (is (= (:rom built) (rom-edn/->rom (rom-edn/parse (rom-edn/write c)))) "through text and back")))
  (testing "a trainer, and bytes after the graphics, survive"
    (let [file (vec (concat [0x4E 0x45 0x53 0x1A 1 1 0x14 0x20 0 0 0 0 0 0 0 0]
                            (repeat 512 7) (map #(mod % 251) (range 16384)) (repeat 8192 3) [9 9 9]))
          c (rom-edn/container file)]
      (is (= {:mapper 0x21 :mirroring :horizontal} (select-keys (:about c) [:mapper :mirroring])))
      (is (= 1024 (count (remove #{\space \newline} (:trainer c)))))
      (is (= "090909" (:rest c)))
      (is (= file (rom-edn/->rom c)))))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Not an iNES" (rom-edn/container [1 2 3 4]))))

(deftest sources
  (doseq [built [(hello/build) (catch/build)]]
    (let [s (rom-edn/source built)
          text (rom-edn/write s)]
      (is (= (:rom built) (rom-edn/->rom s)) "the instructions and drawings give back the same file")
      (is (= (:rom built) (rom-edn/->rom (rom-edn/parse text))) "...through text too")
      (is (= s (rom-edn/parse text)))))
  (let [text (rom-edn/write (rom-edn/source (hello/build)))]
    (testing "it reads like a program"
      (is (str/includes? text "[:label :reset]\n  [:sei]\n  [:cld]"))
      (is (str/includes? text "[:jsr :read-controller]"))
      (is (str/includes? text ":player/x "))
      (is (str/includes? text "\"....1111\"") "the top left of the face")))
  (testing "a ROM hack is a change to the data: here, a different start position"
    (let [s (rom-edn/source (hello/build))
          hacked (update s :code (fn [code] (mapv #(if (= % [:lda [:imm [:hi 30720]]]) [:lda [:imm 200]] %) code)))]
      (is (not= (rom-edn/->rom s) (rom-edn/->rom hacked)))
      (is (= (count (rom-edn/->rom s)) (count (rom-edn/->rom hacked))))))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"what a game's build returns" (rom-edn/source {:rom [1 2 3]})))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Unknown :format" (rom-edn/->rom {:format :zip}))))
