(ns gamedev-toolkit.asm-test
  (:require [cljs.test :refer [deftest is testing]]
            [gamedev-toolkit.asm :as asm]
            [nes.cpu.disasm :as disasm]
            [nes.cpu.harness :as harness]
            [nes.cpu.opcodes :as ops]))

(defn- bytes-of
  ([forms] (bytes-of forms {}))
  ([forms opts] (:bytes (asm/assemble forms opts))))

(defn- error-of [forms & [opts]]
  (try (asm/assemble forms (or opts {})) nil
       (catch :default e (ex-message e))))

(deftest addressing-modes
  (is (= [0x18] (bytes-of [[:clc]])))
  (is (= [0x0A] (bytes-of [[:asl :a]])))
  (is (= [0x0A] (bytes-of [[:asl]])) "no operand means the accumulator, for shifts")
  (is (= [0xA9 0x01] (bytes-of [[:lda [:imm 1]]])))
  (is (= [0xA9 0xFF] (bytes-of [[:lda [:imm -1]]])))
  (is (= [0xA5 0x10] (bytes-of [[:lda 0x10]])))
  (is (= [0xB5 0x10] (bytes-of [[:lda 0x10 :x]])))
  (is (= [0xB6 0x10] (bytes-of [[:ldx 0x10 :y]])))
  (is (= [0xAD 0x34 0x12] (bytes-of [[:lda 0x1234]])))
  (is (= [0xBD 0x34 0x12] (bytes-of [[:lda 0x1234 :x]])))
  (is (= [0xB9 0x34 0x12] (bytes-of [[:lda 0x1234 :y]])))
  (is (= [0x6C 0x34 0x12] (bytes-of [[:jmp [:ind 0x1234]]])))
  (is (= [0xA1 0x10] (bytes-of [[:lda [:ind 0x10 :x]]])))
  (is (= [0xB1 0x10] (bytes-of [[:lda [:ind 0x10] :y]]))))

(deftest every-official-opcode
  ;; Write each instruction as a form, assemble it, and disassemble the
  ;; bytes: the same mnemonic, mode and operand come back.
  (doseq [[opcode {:keys [mnemonic mode]}] ops/opcodes]
    (let [form (case mode
                 :imp [mnemonic]
                 :acc [mnemonic :a]
                 :imm [mnemonic [:imm 0x44]]
                 :zp  [mnemonic 0x44]
                 :zpx [mnemonic 0x44 :x]
                 :zpy [mnemonic 0x44 :y]
                 :abs [mnemonic 0x4400]
                 :abx [mnemonic 0x4400 :x]
                 :aby [mnemonic 0x4400 :y]
                 :ind [mnemonic [:ind 0x4400]]
                 :izx [mnemonic [:ind 0x44 :x]]
                 :izy [mnemonic [:ind 0x44] :y]
                 :rel [mnemonic :here])
          bytes (bytes-of [[:label :here] form])
          back (disasm/decode #(get bytes (- % 0x8000)) 0x8000)]
      (is (= opcode (first bytes)) (pr-str form))
      (is (= [mnemonic mode] [(:mnemonic back) (:mode back)]) (pr-str form))
      (when (= mode :rel)
        (is (= 0x8000 (:target back)))))))

(deftest zero-page-or-absolute
  (testing "symbols are known beforehand, so small ones get zero page"
    (is (= [0xA5 0x10] (bytes-of [[:lda :n]] {:symbols {:n 0x10}})))
    (is (= [0xAD 0x00 0x02] (bytes-of [[:lda :n]] {:symbols {:n 0x200}})))
    (is (= [0x8D 0x03 0x02] (bytes-of [[:sta [:+ :oam 3]]] {:symbols {:oam 0x200}}))))
  (testing "labels are always absolute"
    (is (= [0xAD 0x03 0x00 0x00]
           (bytes-of [[:lda :data] [:label :data] [:db 0]] {:org 0}))))
  (testing "forcing one or the other"
    (is (= [0xAD 0x10 0x00] (bytes-of [[:lda [:abs 0x10]]])))
    (is (= [0xA5 0x10] (bytes-of [[:lda [:zp :n]]] {:symbols {:n 0x10}}))))
  (testing "an instruction with no absolute form uses zero page"
    (is (= [0x96 0x10] (bytes-of [[:stx 0x10 :y]])))))

(deftest labels-and-branches
  (is (= [0xA2 0x08 0xCA 0xD0 0xFD 0x60]
         (bytes-of [[:ldx [:imm 8]] [:label :loop] [:dex] [:bne :loop] [:rts]]))
      "a branch backwards")
  (is (= [0xF0 0x01 0xEA 0x60]
         (bytes-of [[:beq :done] [:nop] [:label :done] [:rts]]))
      "a branch forwards")
  (is (= [0x4C 0x03 0xC0 0x60]
         (bytes-of [[:jmp :end] [:label :end] [:rts]] {:org 0xC000})))
  (is (= {:start 0xC000 :end 0xC003}
         (:labels (asm/assemble [[:label :start] [:jmp :end] [:label :end]] {:org 0xC000})))))

(deftest expressions
  (is (= [0xA9 0x34 0xA9 0x12] (bytes-of [[:lda [:imm [:lo 0x1234]]] [:lda [:imm [:hi 0x1234]]]])))
  (is (= [0xA9 0xC0] (bytes-of [[:label :here] [:lda [:imm [:hi :here]]]] {:org 0xC000})))
  (is (= [0xA5 0x0F] (bytes-of [[:lda [:- [:+ :n 2] 3]]] {:symbols {:n 0x10}}))))

(deftest data
  (is (= [1 2 72 73 3 4] (bytes-of [[:db 1 2 "HI" [3 4]]])))
  (is (= [0x34 0x12 0x04 0x80] (bytes-of [[:dw 0x1234 :here] [:label :here]])))
  (is (= [0xEA 0xFF 0xFF 0xFF 0x60] (bytes-of [[:nop] [:org 0x8004] [:rts]])))
  (is (= [0xEA 0x00 0x60] (bytes-of [[:nop] [:org 0x8002] [:rts]] {:fill 0}))))

(deftest nesting
  (let [twice (fn [body] [body body])]
    (is (= [0xE8 0xC8 0xE8 0xC8 0x60]
           (bytes-of [(twice [[:inx] [:iny]]) nil (list [:rts])]))
        "sequences nest; nil is skipped"))
  (is (= [0xEA] (bytes-of [:nop])) "a single instruction is a program too")
  (is (not= (asm/genlabel "loop") (asm/genlabel "loop"))))

(deftest listing
  (is (= [{:addr 0x8000 :form [:lda [:imm 1]] :bytes [0xA9 0x01]}
          {:addr 0x8002 :form [:rts] :bytes [0x60]}]
         (:listing (asm/assemble [[:lda [:imm 1]] [:label :x1] [:rts]])))))

(deftest errors
  (is (re-find #"Unknown instruction :lad" (error-of [[:lad 1]])))
  (is (re-find #"STA has no immediate mode" (error-of [[:sta [:imm 1]]])))
  (is (re-find #"LDA needs an operand" (error-of [[:lda]])))
  (is (re-find #"Nothing is called :nowhere" (error-of [[:jmp :nowhere]])))
  (is (re-find #"defined twice" (error-of [[:label :here] [:label :here]])))
  (is (re-find #"already one of the :symbols" (error-of [[:label :n]] {:symbols {:n 1}})))
  (is (re-find #"means a register" (error-of [[:label :x]])))
  (is (re-find #"doesn't fit in a byte" (error-of [[:lda [:imm 256]]])))
  (is (re-find #"isn't in the zero page" (error-of [[:lda [:ind :far] :y] [:label :far]])))
  (is (re-find #"too far" (error-of [[:bne :far] [:org 0x8100] [:label :far]])))
  (is (re-find #"Can't go back" (error-of [[:nop] [:nop] [:org 0x8001]])))
  (is (re-find #"Not an instruction" (error-of [[:nop] 42])))
  (is (re-find #"in \[:sta \[:imm 1\]\]" (error-of [[:sta [:imm 1]]])) "the message shows the form"))

(deftest running-assembled-code
  (testing "adding, with a symbol for the variable"
    (let [r (harness/run-routine [[:lda [:imm 2]] [:clc] [:adc :n] [:sta :n] [:rts]]
                                 {:symbols {:n 0x10} :memory {0x10 40}})]
      (is (= 42 (:a r)))
      (is (= 42 ((:peek r) 0x10)))
      (is (= (+ 2 2 3 3 6) (:cycles r)))))
  (testing "a generator: a 16-bit add, checked against +"
    (let [add16 (fn [dst src]
                  [[:clc]
                   [:lda dst] [:adc src] [:sta dst]
                   [:lda [:+ dst 1]] [:adc [:+ src 1]] [:sta [:+ dst 1]]])]
      (doseq [[a b] [[0 0] [1 2] [0x00FF 0x0001] [0x1234 0x0FF0] [0xFFFF 0x0001]]]
        (let [r (harness/run-routine [(add16 :a16 :b16) [:rts]]
                                     {:symbols {:a16 0x10 :b16 0x12}
                                      :memory {0x10 [(bit-and a 0xFF) (bit-shift-right a 8)]
                                               0x12 [(bit-and b 0xFF) (bit-shift-right b 8)]}})]
          (is (= (bit-and (+ a b) 0xFFFF)
                 (+ ((:peek r) 0x10) (* 256 ((:peek r) 0x11))))
              (str a " + " b))))))
  (testing "a loop, a subroutine and registers on entry"
    (let [r (harness/run-routine [[:label :loop] [:jsr :bump] [:dex] [:bne :loop] [:rts]
                                  [:label :bump] [:inc 0x20] [:rts]]
                                 {:x 5})]
      (is (= 5 ((:peek r) 0x20)))
      (is (:zero? r))))
  (testing "code that never returns is caught"
    (is (thrown-with-msg? js/Error #"Still running"
                          (harness/run-routine [[:label :forever] [:jmp :forever]] {:max-cycles 1000})))))
