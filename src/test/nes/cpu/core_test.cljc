(ns nes.cpu.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [nes.cpu.core :as cpu]
            [nes.cpu.registers :refer [reg set-reg!]]
            [zeropage.opcodes :as ops]
            [nes.cpu.reference :as ref]
            [zeropage.disasm :as disasm]))

(defn- byte-at [mem addr] (aget #?(:clj ^ints mem :cljs mem) addr))
(defn- put! [mem addr v] (aset #?(:clj ^ints mem :cljs mem) addr (int v)))

(defn- machine
  "A CPU at $C000 over flat memory holding `program` there, with the NMI
  vector pointing at $D000 and IRQ at $E000."
  [program]
  (let [mem #?(:clj (int-array 0x10000) :cljs (js/Uint8Array. 0x10000))]
    (doseq [[i b] (map-indexed vector program)] (put! mem (+ 0xC000 i) b))
    (doseq [[i b] (map-indexed vector [0x00 0xD0 0x00 0xC0 0x00 0xE0])] (put! mem (+ 0xFFFA i) b))
    (let [s (cpu/create)]
      (set-reg! s :pc 0xC000)
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
      (set-reg! s :p 0x20)
      (is (= 7 (cpu/nmi! s bus)))
      (is (= 0xD000 (reg s :pc)))
      (is (= [0x20 0x00 0xC0] [(byte-at mem 0x1FB) (byte-at mem 0x1FC) (byte-at mem 0x1FD)]))
      (is (= 0xFA (reg s :sp)))
      (is (pos? (bit-and (reg s :p) cpu/I)))))
  (testing "IRQ is ignored while I is set"
    (let [{:keys [s bus]} (machine [0xEA])]
      (set-reg! s :p (bit-or 0x20 cpu/I))
      (is (= 0 (cpu/irq! s bus)))
      (is (= 0xC000 (reg s :pc)))))
  (testing "IRQ through $FFFE, and RTI comes back"
    (let [{:keys [mem s bus]} (machine [0xEA])]
      (set-reg! s :p 0x20)
      (put! mem 0xE000 0x40)            ; RTI
      (is (= 7 (cpu/irq! s bus)))
      (is (= 0xE000 (reg s :pc)))
      (cpu/step! s bus)
      (is (= 0xC000 (reg s :pc)))
      (is (zero? (bit-and (reg s :p) cpu/I)) "RTI restores the flags"))))

(deftest unofficial-instructions
  (testing "LAX loads A and X"
    (let [{:keys [mem s bus]} (machine [0xA7 0x10])]
      (put! mem 0x10 0x80)
      (is (= 3 (cpu/step! s bus)))
      (is (= [0x80 0x80] [(reg s :a) (reg s :x)]))
      (is (pos? (bit-and (reg s :p) cpu/N)))))
  (testing "DCP decrements memory, then compares with A"
    (let [{:keys [mem s bus]} (machine [0xC7 0x10])]
      (put! mem 0x10 0x05)
      (set-reg! s :a 0x04)
      (cpu/step! s bus)
      (is (= 0x04 (byte-at mem 0x10)))
      (is (pos? (bit-and (reg s :p) cpu/Z)) "equal after the decrement")))
  (testing "explained and disassembled"
    (let [ins (disasm/decode #(get {0xC000 0xA7 0xC001 0x10} %) 0xC000)]
      (is (= "*LAX $10" (disasm/text ins)))
      (is (= "Unofficial: Load A and X, the byte at $0010" (ref/explain ins))))))
