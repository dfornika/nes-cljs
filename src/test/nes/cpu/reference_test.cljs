(ns nes.cpu.reference-test
  (:require [cljs.test :refer [deftest is]]
            [nes.cpu.disasm :as disasm]
            [nes.cpu.opcodes :as ops]
            [nes.cpu.reference :as ref]))

(defn- decode [bytes]
  (let [mem (into {} (map-indexed (fn [i b] [(+ 0xC000 i) b]) bytes))]
    (disasm/decode #(get mem %) 0xC000)))

(deftest every-instruction-is-described
  (is (= 56 (count ref/instructions)))
  (is (= (set (map :mnemonic (vals ops/opcodes))) (set (keys ref/instructions))))
  (is (every? ref/mode-notes (keys ops/modes))))

(deftest explanations
  (is (= "Load A with $01" (ref/explain (decode [0xA9 0x01]))))
  (is (= "Store A into PPUCTRL ($2000)" (ref/explain (decode [0x8D 0x00 0x20]))))
  (is (= "Call the subroutine at $C010" (ref/explain (decode [0x20 0x10 0xC0]))))
  (is (= "Shift left A" (ref/explain (decode [0x0A])))))

(deftest explanations-with-the-machine-as-it-is-now
  (let [ram {0x10 3, 0x20 0x00, 0x21 0x03, 0x0305 0x2A}
        now {:regs {:x 5 :y 5 :p 2r00000010} :peek #(get ram % 0)}]
    (is (= "Load A with the byte at $0010 (now $03)" (ref/explain (decode [0xA5 0x10]) now)))
    (is (= "Load A with the byte at $0300 + X = $0305 (now $2A)" (ref/explain (decode [0xBD 0x00 0x03]) now)))
    (is (= "Load A with the byte at (the pointer at $20) + Y = $0305 (now $2A)"
           (ref/explain (decode [0xB1 0x20]) now)))
    ;; Z is set in p, so BEQ is taken and BNE isn't
    (is (= "If the last result was zero, jump to $C012 — it will" (ref/explain (decode [0xF0 0x10]) now)))
    (is (= "If the last result wasn't zero, jump to $C012 — it won't" (ref/explain (decode [0xD0 0x10]) now)))))

(deftest one-address-two-registers
  ;; $4017 reads controller 2, but a write goes to the sound chip
  (is (= "Store A into APU_FRAME ($4017)" (ref/explain (decode [0x8D 0x17 0x40]))))
  (is (= "Load A with JOY2 ($4017)" (ref/explain (decode [0xAD 0x17 0x40])))))
