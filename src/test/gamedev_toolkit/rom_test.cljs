(ns gamedev-toolkit.rom-test
  (:require [cljs.test :refer [deftest is testing]]
            [gamedev-toolkit.chr :as chr]
            [gamedev-toolkit.rom :as rom]
            [nes.chr]
            [nes.rom]))

(defn- u8 [bytes] (js/Uint8Array.from (clj->js bytes)))

(deftest ines-header
  (let [file (rom/ines {:prg (repeat 32768 0) :chr (repeat 8192 0) :mapper 0x42 :mirroring :vertical})]
    (is (= (+ 16 32768 8192) (count file)))
    (is (= {:prg-banks 2 :chr-banks 1 :mapper 0x42 :mirroring :vertical :chr-ram? false :trainer? false}
           (select-keys (nes.rom/parse-header (u8 file))
                        [:prg-banks :chr-banks :mapper :mirroring :chr-ram? :trainer?]))
        "our own ROM reader agrees"))
  (is (:chr-ram? (nes.rom/parse-header (u8 (rom/ines {:prg (repeat 16384 0)})))))
  (is (thrown? js/Error (rom/ines {:prg [1 2 3]})))
  (is (thrown? js/Error (rom/ines {:prg (repeat 16384 0) :chr [1 2 3]}))))

(deftest nrom
  (let [{:keys [rom prg labels]}
        (rom/nrom {:code [[:label :reset] [:jmp :reset]
                          [:label :nmi] [:label :irq] [:rti]]
                   :vectors {:nmi :nmi :reset :reset :irq :irq}
                   :chr (chr/bank [])})]
    (is (= 16384 (count prg)))
    (is (= (+ 16 16384 8192) (count rom)))
    (is (= {:reset 0xC000 :nmi 0xC003 :irq 0xC003} labels))
    (is (= [0x4C 0x00 0xC0 0x40] (subvec prg 0 4)) "the program is at $C000")
    (is (= [0x03 0xC0 0x00 0xC0 0x03 0xC0] (subvec prg (- 16384 6))) "the vectors are the last six bytes"))
  (testing "32 KB starts at $8000"
    (is (= 0x8000 (get-in (rom/nrom {:code [[:label :reset] [:rti]]
                                     :vectors {:nmi :reset :reset :reset :irq :reset}
                                     :prg-size 32768})
                          [:labels :reset]))))
  (testing "code that doesn't fit, or a missing vector, says so"
    (is (thrown-with-msg? js/Error #"too big"
                          (rom/nrom {:code [[:label :reset] [:db (repeat 16380 0)]]
                                     :vectors {:nmi :reset :reset :reset :irq :reset}})))
    (is (thrown-with-msg? js/Error #"No :irq"
                          (rom/nrom {:code [[:label :reset]] :vectors {:nmi :reset :reset :reset}})))))

(deftest tiles
  (is (= [0x80 0 0 0 0 0 0 0x01,  0x80 0 0 0 0 0 0x03 0]
         (chr/tile ["3......."
                    "........"
                    "........"
                    "........"
                    "........"
                    "........"
                    "......22"
                    ".......1"]))
      "plane 0 (low bits) then plane 1 (high bits), leftmost pixel in bit 7")
  (testing "the Tiles tab's decoder reads back what we drew"
    (let [rows ["..1111.." ".122221." "12322321" "12222221" "12322321" "12233221" ".122221." "..1111.."]
          pixels (nes.chr/tile-pixels (u8 (chr/tile rows)) 0)]
      (is (= (map (fn [row] (map #(if (= "." %) 0 (js/parseInt %)) row)) rows)
             (map seq pixels)))))
  (testing "a 16x8 picture is two tiles, left then right"
    (let [[left right] (chr/tiles (repeat 8 "11111111........"))]
      (is (= (chr/tile (repeat 8 "11111111")) left))
      (is (= chr/blank right))))
  (is (= 8192 (count (chr/bank [chr/blank (chr/tile (repeat 8 "33333333"))]))))
  (is (thrown? js/Error (chr/tile ["bad"])))
  (is (thrown? js/Error (chr/tile (repeat 8 "1234....")))))
