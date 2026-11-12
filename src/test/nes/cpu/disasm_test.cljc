(ns nes.cpu.disasm-test
  (:require [clojure.test :refer [deftest is testing]]
            [nes.cpu.disasm :as disasm]
            [nes.cpu.opcodes :as ops]))

(deftest opcode-table
  (is (= 151 (count ops/opcodes)) "the 6502 has 151 official opcodes")
  (is (= {:mnemonic :lda :mode :imm :size 2} (ops/opcodes 0xA9)))
  (is (= {:mnemonic :jmp :mode :ind :size 3} (ops/opcodes 0x6C)))
  (is (= 0x8D (ops/opcode-for [:sta :abs])))
  (testing "every opcode round-trips through opcode-for"
    (is (every? (fn [[op {:keys [mnemonic mode]}]] (= op (ops/opcode-for [mnemonic mode])))
                ops/opcodes))))

(defn- peek-bytes
  "A peek function over `bytes` placed at `origin`."
  [origin bytes]
  (fn [addr]
    (let [i (- addr origin)]
      (when (< -1 i (count bytes)) (nth bytes i)))))

(deftest decoding
  (let [code [0xA9 0x01        ; LDA #$01
              0x8D 0x00 0x20   ; STA $2000
              0xB1 0x10        ; LDA ($10),Y
              0x0A             ; ASL A
              0xD0 0xF6        ; BNE back to $8000
              0x02]            ; not an official opcode
        peek (peek-bytes 0x8000 code)
        instrs (disasm/disassemble peek 0x8000 6)]
    (is (= ["LDA #$01" "STA $2000" "LDA ($10),Y" "ASL A" "BNE $8000" ".byte $02"]
           (map disasm/text instrs)))
    (is (= [0x8000 0x8002 0x8005 0x8007 0x8008 0x800A] (map :addr instrs)))
    (is (= 0x2000 (:operand (second instrs))))))

(deftest unreadable-operands
  (let [instr (disasm/decode (peek-bytes 0x8000 [0xAD 0x34]) 0x8000)]
    (is (= "LDA $????" (disasm/text instr)))))
