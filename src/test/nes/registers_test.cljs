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
