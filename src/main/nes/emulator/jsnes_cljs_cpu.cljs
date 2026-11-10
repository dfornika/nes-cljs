(ns nes.emulator.jsnes-cljs-cpu
  "Running the CPU with NES.cljs's own ClojureScript 6502 (nes.cpu.core)
  instead of jsnes's, inside jsnes.

  jsnes's CPU does two jobs: running instructions, and keeping the rest of
  the console in step with it (each bus access moves the PPU on 3 dots;
  interrupts arrive at exactly the right moment). We replace the first
  job only. Each instruction:

  1. copy jsnes's registers into the ClojureScript CPU's state
  2. run the instruction with nes.cpu.core/step!, whose bus is jsnes's own
     memory methods (load, write, push...), so the PPU, the mapper, the
     bus monitor and breakpoints all see the same accesses as before
  3. copy the registers back, and do jsnes's end-of-instruction
     bookkeeping (PPU time for cycles with no bus access; NMI timing)

  Instructions jsnes handles better are left to it: whenever an interrupt
  is due (jsnes models exactly when one takes effect), and the unofficial
  opcodes a few games use."
  (:require [nes.cpu.core :as core]))

(defn- peek-opcode
  "The opcode at `pc`, read without a bus access."
  [^js cpu pc]
  (let [^js mem (.-mem cpu)]
    (if (< pc 0x2000) (aget mem (bit-and pc 0x7FF)) (aget mem pc))))

(defn- bus-for
  "A bus (see nes.cpu.core) over jsnes's CPU `cpu`."
  [^js cpu ^js ppu]
  #js {:fetch (fn [a]
                ;; what jsnes's emulate() does to fetch an opcode
                (let [op (.loadFromCartridge cpu a)]
                  (set! (.-dataBus cpu) op)
                  (set! (.-instrBusCycles cpu) 1)
                  (.advanceDots ppu 3)
                  op))
       :readOperand (fn [a] (.loadDirect cpu a))
       :read16 (fn [a] (.load16bit cpu a))
       :read (fn [a] (.load cpu a))
       :write (fn [a v] (.write cpu a v))
       ;; jsnes's push and pull move its own stack pointer: hand them ours
       :push (fn [sp v] (set! (.-REG_SP cpu) sp) (.push cpu v))
       :pull (fn [sp] (set! (.-REG_SP cpu) sp) (.pull cpu))})

(defn install!
  "Run `nes`'s (a jsnes NES) CPU with the ClojureScript 6502 from now on.
  Call again after jsnes replaces its CPU (on reset). `stats` (a JS
  object) counts instructions run by each: .cljs and .jsnes."
  [^js nes ^js stats]
  (let [^js cpu (.-cpu nes)
        ^js ppu (.-ppu nes)
        jsnes-emulate (aget (.getPrototypeOf js/Object cpu) "emulate")
        s (core/create)
        bus (bus-for cpu ppu)]
    (set! (.-emulate cpu)
          (fn []
            (let [pc (bit-and (inc (.-REG_PC cpu)) 0xFFFF)]
              (if (or (.-nmiImmediate cpu) (.-nmiRaised cpu) (.-nmiPending cpu)
                      (.-irqRequested cpu)
                      (not (core/official? (peek-opcode cpu pc))))
                (do (set! (.-jsnes stats) (inc (.-jsnes stats)))
                    (.call jsnes-emulate cpu))
                (do
                  (set! (.-cljs stats) (inc (.-cljs stats)))
                  (set! (.-instrBusCycles cpu) 0)
                  (set! (.-apuCatchupCycles cpu) 0)
                  (set! (.-nmiDotsRemainingInStep cpu) 0)
                  ;; 1. registers in
                  (set! (.-a s) (.-REG_ACC cpu))
                  (set! (.-x s) (.-REG_X cpu))
                  (set! (.-y s) (.-REG_Y cpu))
                  (set! (.-sp s) (bit-and (.-REG_SP cpu) 0xFF))
                  (set! (.-pc s) pc)
                  (set! (.-p s) (.getStatus cpu))
                  ;; 2. run it
                  (let [cycles (core/step! s bus)]
                    ;; 3. registers out (jsnes keeps PC one less)
                    (set! (.-REG_ACC cpu) (.-a s))
                    (set! (.-REG_X cpu) (.-x s))
                    (set! (.-REG_Y cpu) (.-y s))
                    (set! (.-REG_SP cpu) (.-sp s))
                    (set! (.-REG_PC cpu) (bit-and (dec (.-pc s)) 0xFFFF))
                    (.setStatus cpu (.-p s))
                    ;; PPU time for cycles with no bus access (as jsnes does)
                    (when (< (.-instrBusCycles cpu) cycles)
                      (let [missing (* 3 (- cycles (.-instrBusCycles cpu)))]
                        (set! (.-instrBusCycles cpu) cycles)
                        (.advanceDots ppu missing)))
                    ;; an NMI raised during this instruction: does it take
                    ;; effect before the next one, or after it? (as jsnes)
                    (when (.-nmiRaised cpu)
                      (when (>= (+ (* 3 (- (.-instrBusCycles cpu) (.-nmiRaisedAtCycle cpu)))
                                   (.-nmiDotsRemainingInStep cpu))
                                5)
                        (set! (.-nmiImmediate cpu) true)
                        (set! (.-nmiRaised cpu) false)))
                    (set! (.-_cpuCycleBase cpu) (+ (.-_cpuCycleBase cpu) cycles))
                    cycles))))))))

(defn uninstall!
  "Back to jsnes's own CPU."
  [^js nes]
  (js-delete (.-cpu nes) "emulate"))
