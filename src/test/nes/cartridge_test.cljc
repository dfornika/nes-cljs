(ns nes.cartridge-test
  (:require [clojure.test :refer [deftest is testing]]
            [nes.bytes :as bytes]
            [nes.cartridge :as cart]
            [nes.ppu.memory :as memory]))

(defn- ines
  "An iNES file: `prg-banks` of 16 KB (each filled with its bank number,
  then the address's low byte at the end), `chr-banks` of 8 KB (filled
  with $C0, $C1, ...: $C0 + the bank number), with these flags 6 (whose top 4 bits are the mapper number's
  low 4)."
  [prg-banks chr-banks flags6]
  (bytes/from (concat [0x4E 0x45 0x53 0x1A prg-banks chr-banks flags6 0 0 0 0 0 0 0 0 0]
                      (for [b (range prg-banks), i (range 0x4000)] (if (< i 0x3FF0) b (bit-and i 0xFF)))
                      (for [b (range chr-banks), _ (range 0x2000)] (+ 0xC0 b)))))

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

(deftest uxrom
  (let [c (cart/load (ines 4 0 0x20))]            ; mapper 2: 64 KB of program, CHR RAM
    (testing "at power on: bank 0 at $8000, the last bank at $C000"
      (is (= [0 3] [(cart/cpu-read c 0x8000) (cart/cpu-read c 0xC000)])))
    (testing "writing a bank number anywhere from $8000 switches $8000-$BFFF"
      (cart/cpu-write! c 0x8000 2)
      (is (= [2 2] [(cart/cpu-read c 0x8000) (cart/cpu-read c 0xBFEF)]))
      (cart/cpu-write! c 0xFFFF 1)
      (is (= 1 (cart/cpu-read c 0x9000))))
    (testing "and $C000-$FFFF never moves: the vectors are always there"
      (doseq [b (range 4)]
        (cart/cpu-write! c 0x8000 b)
        (is (= 3 (cart/cpu-read c 0xC000)))
        (is (= 0xFC (cart/cpu-read c 0xFFFC)))))
    (testing "a bank number past the end wraps round"
      (cart/cpu-write! c 0x8000 5)
      (is (= 1 (cart/cpu-read c 0x8000))))
    (testing "the ROM itself isn't changed by those writes"
      (is (= 3 (bytes/at (cart/prg c) 0xC000))))
    (testing "the tiles are RAM"
      (memory/video-write! c 0x10 7)
      (is (= 7 (memory/video-read c 0x10))))))

(deftest cnrom
  (let [c (cart/load (ines 2 4 0x30))]            ; mapper 3: 32 KB of program, four 8 KB tile banks
    (testing "at power on, the PPU sees tile bank 0"
      (is (= [0xC0 0xC0] [(memory/video-read c 0x0000) (memory/video-read c 0x1FFF)])))
    (testing "writing a bank number anywhere from $8000 switches all 8 KB of tiles"
      (cart/cpu-write! c 0x8000 2)
      (is (= [0xC2 0xC2] [(memory/video-read c 0x0000) (memory/video-read c 0x1FFF)]))
      (cart/cpu-write! c 0xFFFF 3)
      (is (= 0xC3 (memory/video-read c 0x1000))))
    (testing "the program doesn't move"
      (doseq [b (range 4)]
        (cart/cpu-write! c 0x8000 b)
        (is (= [0 1 0xFC] [(cart/cpu-read c 0x8000) (cart/cpu-read c 0xC000) (cart/cpu-read c 0xFFFC)]))))
    (testing "a bank number past the end wraps round"
      (cart/cpu-write! c 0x8000 5)
      (is (= 0xC1 (memory/video-read c 0x0010))))
    (testing "the tiles are ROM"
      (memory/video-write! c 0x0010 7)
      (is (= 0xC1 (memory/video-read c 0x0010))))
    (testing "and the nametables are RAM, as on NROM"
      (memory/video-write! c 0x2005 9)
      (is (= 9 (memory/video-read c 0x2005))))))

(deftest other-mappers
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"Mapper 1 \(MMC1\)"
        (cart/load (bytes/from (concat [0x4E 0x45 0x53 0x1A 1 1 0x10 0 0 0 0 0 0 0 0 0]
                                       (repeat (+ 0x4000 0x2000) 0)))))))
