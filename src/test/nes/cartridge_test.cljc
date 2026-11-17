(ns nes.cartridge-test
  (:require [clojure.test :refer [deftest is testing]]
            [nes.bytes :as bytes]
            [nes.cartridge :as cart]
            [nes.ppu.memory :as memory]))

(defn- ines
  "An iNES file: `prg-banks` of 16 KB (each filled with its bank number,
  then the address's low byte at the end), `chr-banks` of 8 KB (filled
  with $C0), mapper 0, with these flags 6."
  [prg-banks chr-banks flags6]
  (bytes/from (concat [0x4E 0x45 0x53 0x1A prg-banks chr-banks flags6 0 0 0 0 0 0 0 0 0]
                      (for [b (range prg-banks), i (range 0x4000)] (if (< i 0x3FF0) b (bit-and i 0xFF)))
                      (repeat (* chr-banks 0x2000) 0xC0))))

(deftest nrom
  (testing "16 KB of program, seen at $8000 and again at $C000"
    (let [c (cart/load (ines 1 1 0))]
      (is (= [0 0 0xFC] [(cart/cpu-read c 0x8000) (cart/cpu-read c 0xC000) (cart/cpu-read c 0xFFFC)]))))
  (testing "32 KB: two banks"
    (let [c (cart/load (ines 2 1 0))]
      (is (= [0 1] [(cart/cpu-read c 0x8000) (cart/cpu-read c 0xC000)]))))
  (testing "program ROM can't be written; the RAM at $6000 can"
    (let [c (cart/load (ines 1 1 0))]
      (cart/cpu-write! c 0x8000 0x55)
      (cart/cpu-write! c 0x6000 0x55)
      (is (= [0 0x55] [(cart/cpu-read c 0x8000) (cart/cpu-read c 0x6000)]))
      (is (nil? (cart/cpu-read c 0x5000)) "nothing answers at $5000")))
  (testing "tiles: ROM, or RAM if there's no CHR ROM"
    (let [rom (cart/load (ines 1 1 0))
          ram (cart/load (ines 1 0 0))]
      (memory/video-write! rom 0x10 1)
      (memory/video-write! ram 0x10 1)
      (is (= [0xC0 1] [(memory/video-read rom 0x10) (memory/video-read ram 0x10)]))))
  (testing "mirroring from the header"
    (let [vertical (cart/load (ines 1 1 1))]
      (memory/video-write! vertical 0x2005 7)
      (is (= [7 0] [(memory/video-read vertical 0x2805) (memory/video-read vertical 0x2405)])))))

(deftest other-mappers
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Mapper 1 \(MMC1\)"
        (cart/load (bytes/from (concat [0x4E 0x45 0x53 0x1A 1 1 0x10 0 0 0 0 0 0 0 0 0]
                                       (repeat (+ 0x4000 0x2000) 0)))))))
