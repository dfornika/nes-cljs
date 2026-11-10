(ns nes.registers-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nes.registers :as regs]))

(deftest register-names
  (is (= "PPUCTRL" (:name (regs/register-at 0x2000))))
  (is (= "PPUDATA" (:name (regs/register-at 0x2007))))
  (testing "PPU registers repeat every 8 bytes up to $3FFF"
    (is (= "PPUSTATUS" (:name (regs/register-at 0x3FFA)))))
  (is (= "OAMDMA" (:name (regs/register-at 0x4014))))
  (is (= "JOY1" (:name (regs/register-at 0x4016))))
  (is (= "MAPPER" (:name (regs/register-at 0x8000))))
  (is (nil? (regs/register-at 0x0300))))

(deftest decoding
  (is (str/starts-with? (regs/describe 0x2000 0x90 true) "NMI on"))
  (is (str/includes? (regs/describe 0x2000 0x90 true) "BG tiles $1000"))
  (is (= "copy $0200–$02FF → OAM" (regs/describe 0x4014 0x02 true)))
  (is (str/starts-with? (regs/describe 0x2002 0x80 false) "vblank yes"))
  (testing "reads of write-only registers are explained as open bus"
    (is (str/includes? (regs/describe 0x4000 0x40 false) "open bus"))
    (is (str/includes? (regs/describe 0x2000 0x20 false) "open bus"))
    (is (not (str/includes? (or (regs/describe 0x2002 0x00 false) "") "open bus")))))

(deftest decoding-bits
  (let [ctrl (regs/decode-bits (get-in regs/bit-fields [:ctrl :fields]) 2r10010010)
        by-name (into {} (map (juxt :name :value) ctrl))]
    (is (= "on" (by-name "NMI at vblank")))
    (is (= "8×8" (by-name "Sprite size")))
    (is (= "$1000" (by-name "Background tiles")))
    (is (= "$2800" (by-name "Nametable"))))
  ;; every register's fields cover each bit exactly once
  (doseq [[_ {:keys [fields]}] regs/bit-fields]
    (is (= (range 8) (sort (mapcat :bits fields))))))

(deftest reads-and-writes-of-4017
  (is (= "JOY2" (:name (regs/register-at 0x4017))))
  (is (= "APU_FRAME" (:name (regs/register-at 0x4017 true))))
  (is (= "JOY1" (:name (regs/register-at 0x4016 true))) "the same register either way")
  (is (= "4-step sequence, no IRQ" (regs/describe 0x4017 0x40 true)))
  (is (= "button pressed" (regs/describe 0x4017 0x41 false))))

(deftest destinations
  (is (= :ppu (regs/destination 0x2007 true)))
  (is (= :ppu (regs/destination 0x3FFA false)) "a mirror of PPUSTATUS")
  (is (= :sprite-dma (regs/destination 0x4014 true)))
  (is (= :controllers (regs/destination 0x4016 true)))
  (is (= :controllers (regs/destination 0x4017 false)))
  (is (= :sound (regs/destination 0x4017 true)) "writing $4017 goes to the APU")
  (is (= :sound (regs/destination 0x4000 true)))
  (is (= :mapper (regs/destination 0x8000 true)))
  (is (nil? (regs/destination 0x8000 false)) "reading ROM isn't I/O")
  (is (nil? (regs/destination 0x0300 true))))
