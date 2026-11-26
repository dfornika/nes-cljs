(ns nes.cpu.zeropage-test
  "Our CPU (nes.cpu.core, through nes.cpu.zeropage) and zeropage's simple
  CPU (zeropage.cpu.simple), side by side: the same random programs on
  both, and after every instruction the same registers, the same cycle
  count and, at the end, the same memory.

  They were written separately, one for an emulator (every dummy read, in
  the order jsnes makes them) and one to be read, so agreeing is good
  evidence that both are right. When they disagree, one of them has a
  bug, and the failing program shows where."
  (:require [cljs.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer-macros [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop :include-macros true]
            [nes.cpu.zeropage :as ours]
            [zeropage.cpu :as cpu]
            [zeropage.cpu.simple :as simple]
            [zeropage.opcodes :as ops]))

(defn- machine
  "`create` (a CPU implementation) over its own 64 KB of memory, which
  starts as `bytes` at $8000, `zp` from $0000, and its vectors pointing
  into the program."
  [create bytes zp regs]
  (let [mem (js/Uint8Array. 0x10000)]
    (.set mem (clj->js zp) 0)
    (.set mem (clj->js bytes) 0x8000)
    (.set mem #js [0x10 0x80 0x00 0x80 0x20 0x80] 0xFFFA)   ; NMI $8010, RESET $8000, IRQ $8020
    {:mem mem
     :cpu (doto (create {:read #(aget mem %) :write #(aset mem %1 %2)})
            (cpu/set-registers! regs))}))

(defn- official-at? [{:keys [^js mem cpu]}]
  (contains? ops/opcodes (aget mem (:pc (cpu/registers cpu)))))

(defn- run-both
  "Step both machines up to `n` instructions, stopping at an unofficial
  opcode (which only ours runs). Returns nil if they agree throughout, or
  what differed."
  [a b n]
  (loop [i 0]
    (cond
      (= i n) nil
      (not (official-at? a)) nil
      :else
      (let [before (cpu/registers (:cpu a))
            ca (cpu/step! (:cpu a))
            cb (cpu/step! (:cpu b))
            ra (cpu/registers (:cpu a))
            rb (cpu/registers (:cpu b))]
        (if (and (= ca cb) (= ra rb))
          (recur (inc i))
          {:step i :before before :ours [ra ca] :simple [rb cb]})))))

(def ^:private program-gen
  "Random programs: mostly official instructions with random operands,
  so most of what runs is real code rather than BRKs."
  (gen/fmap (fn [instrs] (vec (mapcat identity instrs)))
            (gen/vector
             (gen/bind (gen/elements (vec (keys ops/opcodes)))
                       (fn [op]
                         (gen/fmap #(into [op] %)
                                   (gen/vector (gen/choose 0 255)
                                               (dec (get-in ops/opcodes [op :size]))))))
             1 60)))

(defspec the-two-cpus-agree 300
  (prop/for-all [program program-gen
                 zp (gen/vector (gen/choose 0 255) 256)
                 [a x y p] (gen/vector (gen/choose 0 255) 4)]
    ;; P as a real 6502 can have it: U set, and B clear (B exists only in
    ;; the copies of P pushed to the stack; the two CPUs treat a B that's
    ;; set in the register differently, and neither is wrong)
    (let [regs {:a a :x x :y y :p (bit-and (bit-or p 0x20) 0xEF) :sp 0xFD :pc 0x8000}
          ours (machine ours/create program zp regs)
          theirs (machine simple/create program zp regs)
          diff (run-both ours theirs 300)]
      (and (nil? diff)
           (= (vec (array-seq (:mem ours))) (vec (array-seq (:mem theirs))))))))

(deftest interrupts-agree
  (doseq [create [ours/create simple/create]]
    (let [{c :cpu} (machine create [0xEA] [] {:pc 0x8005 :p 0x20 :sp 0xFD})]
      (is (= 7 (cpu/nmi! c)))
      (is (= {:pc 0x8010 :sp 0xFA} (select-keys (cpu/registers c) [:pc :sp])))
      (is (= 7 (cpu/jump-to-reset-vector! c)))
      (is (= 0x8000 (:pc (cpu/registers c)))))))

