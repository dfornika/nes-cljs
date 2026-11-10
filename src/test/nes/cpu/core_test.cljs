(ns nes.cpu.core-test
  (:require [cljs.test :refer [deftest is testing]]
            [nes.cpu.core :as cpu]
            [nes.cpu.opcodes :as ops]
            [nes.cpu.reference :as ref]
            [nes.cpu.disasm :as disasm]))

(defn- machine
  "A CPU at $C000 over flat memory holding `program` there, with the NMI
  vector pointing at $D000 and IRQ at $E000."
  [program]
  (let [mem (js/Uint8Array. 0x10000)]
    (.set mem (clj->js program) 0xC000)
    (.set mem #js [0x00 0xD0 0x00 0xC0 0x00 0xE0] 0xFFFA)
    (let [s (cpu/create)]
      (set! (.-pc s) 0xC000)
      {:mem mem :s s :bus (cpu/flat-bus mem)})))

(deftest opcode-counts
  (is (= 151 (count ops/opcodes)))
  (is (= 93 (count ops/unofficial-opcodes)))
  (is (= 244 (count ops/all-opcodes)) "the other 12 jam the CPU")
  (is (not (cpu/runnable? 0x02)))
  (is (cpu/runnable? 0xA7))
  (is (not (cpu/official? 0xA7))))

(deftest interrupts
  (testing "NMI pushes PC and P (B clear), sets I, and jumps through $FFFA"
    (let [{:keys [mem s bus]} (machine [0xEA])]
      (set! (.-p s) 0x20)
      (is (= 7 (cpu/nmi! s bus)))
      (is (= 0xD000 (.-pc s)))
      (is (= [0x20 0x00 0xC0] [(aget mem 0x1FB) (aget mem 0x1FC) (aget mem 0x1FD)]))
      (is (= 0xFA (.-sp s)))
      (is (pos? (bit-and (.-p s) cpu/I)))))
  (testing "IRQ is ignored while I is set"
    (let [{:keys [s bus]} (machine [0xEA])]
      (set! (.-p s) (bit-or 0x20 cpu/I))
      (is (= 0 (cpu/irq! s bus)))
      (is (= 0xC000 (.-pc s)))))
  (testing "IRQ through $FFFE, and RTI comes back"
    (let [{:keys [mem s bus]} (machine [0xEA])]
      (set! (.-p s) 0x20)
      (aset mem 0xE000 0x40)            ; RTI
      (is (= 7 (cpu/irq! s bus)))
      (is (= 0xE000 (.-pc s)))
      (cpu/step! s bus)
      (is (= 0xC000 (.-pc s)))
      (is (zero? (bit-and (.-p s) cpu/I)) "RTI restores the flags"))))

(deftest unofficial-instructions
  (testing "LAX loads A and X"
    (let [{:keys [mem s bus]} (machine [0xA7 0x10])]
      (aset mem 0x10 0x80)
      (is (= 3 (cpu/step! s bus)))
      (is (= [0x80 0x80] [(.-a s) (.-x s)]))
      (is (pos? (bit-and (.-p s) cpu/N)))))
  (testing "DCP decrements memory, then compares with A"
    (let [{:keys [mem s bus]} (machine [0xC7 0x10])]
      (aset mem 0x10 0x05)
      (set! (.-a s) 0x04)
      (cpu/step! s bus)
      (is (= 0x04 (aget mem 0x10)))
      (is (pos? (bit-and (.-p s) cpu/Z)) "equal after the decrement")))
  (testing "explained and disassembled"
    (let [ins (disasm/decode #(get {0xC000 0xA7 0xC001 0x10} %) 0xC000)]
      (is (= "*LAX $10" (disasm/text ins)))
      (is (= "Unofficial: Load A and X, the byte at $0010" (ref/explain ins))))))
