(ns nes.rom-test
  (:require [nes.bytes :as bytes]
            [clojure.test :refer [deftest is testing]]
            [nes.rom :as rom]))

(defn- header-bytes
  "A byte array holding a 16-byte iNES header with the given bytes 4-7."
  [prg chr flags6 flags7]
  (bytes/from (concat [0x4E 0x45 0x53 0x1A prg chr flags6 flags7]
                      (repeat 8 0))))

(deftest rejects-non-ines
  (is (nil? (rom/parse-header (bytes/make 16))))
  (is (nil? (rom/parse-header (bytes/from [0x4E 0x45 0x53])))))

(deftest parses-nrom-header
  (let [h (rom/parse-header (header-bytes 2 1 0x01 0x00))]
    (is (= 2 (:prg-banks h)))
    (is (= 32768 (:prg-size h)))
    (is (= 8192 (:chr-size h)))
    (is (false? (:chr-ram? h)))
    (is (= 0 (:mapper h)))
    (is (= :vertical (:mirroring h)))))

(deftest parses-mapper-number-across-two-bytes
  (testing "MMC3 (mapper 4): low nibble in flags 6"
    (is (= 4 (:mapper (rom/parse-header (header-bytes 8 16 0x40 0x00))))))
  (testing "mapper 66: high nibble from flags 7, low nibble from flags 6"
    (is (= 66 (:mapper (rom/parse-header (header-bytes 2 4 0x20 0x40)))))))

(deftest flags
  (let [h (rom/parse-header (header-bytes 1 0 0x0A 0x00))]
    (is (:chr-ram? h))
    (is (:battery? h))
    (is (= :four-screen (:mirroring h)))))
