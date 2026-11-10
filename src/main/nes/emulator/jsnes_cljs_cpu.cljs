(ns nes.emulator.jsnes-cljs-cpu
  "Running the CPU with NES.cljs's own ClojureScript 6502 (nes.cpu.core)
  instead of jsnes's, inside jsnes.

  jsnes's CPU does two jobs: running instructions, and keeping the rest of
  the console in step with it (each bus access moves the PPU on 3 dots;
  interrupts arrive at exactly the right moment). We replace the first
  job, and follow jsnes's rules for the second. Each call:

  1. copy jsnes's registers into the ClojureScript CPU's state
  2. take any interrupt that's due, then run one instruction, with
     nes.cpu.core, whose bus is jsnes's own memory methods (load, write,
     push...), so the PPU, the mapper, the bus monitor and breakpoints all
     see the same accesses as before
  3. copy the registers back, and do jsnes's end-of-instruction
     bookkeeping (PPU time for cycles with no bus access; when an NMI that
     arrived during the instruction takes effect)

  The timing rules are jsnes's (see emulate() in jsnes/src/cpu.js). Only
  the 12 opcodes that jam a real CPU are left to jsnes, which stops the
  game, as a real NES would."
  (:require [nes.cpu.core :as core]))

(defn- peek-opcode
  "The opcode at `pc`, read without a bus access."
  [^js cpu pc]
  (let [^js mem (.-mem cpu)]
    (if (< pc 0x2000) (aget mem (bit-and pc 0x7FF)) (aget mem pc))))

(defn- bus-cycle! [^js cpu ^js ppu]
  (set! (.-instrBusCycles cpu) (inc (.-instrBusCycles cpu)))
  (.advanceDots ppu 3))

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
       :pull (fn [sp] (set! (.-REG_SP cpu) sp) (.pull cpu))
       :readVector (fn [a]
                     (let [v (.loadFromCartridge cpu a)]
                       (set! (.-dataBus cpu) v)
                       (bus-cycle! cpu ppu)
                       v))
       :tick (fn [] (bus-cycle! cpu ppu))
       :dmcDuringInstruction (fn []
                               (let [f (.-_dmcFetchCycles cpu)]
                                 (and (pos? f) (<= f (.-instrBusCycles cpu)))))})

(defn install!
  "Run `nes`'s (a jsnes NES) CPU with the ClojureScript 6502 from now on.
  Call again after jsnes replaces its CPU (on reset). `stats` (a JS
  object) counts instructions run by each: .cljs and .jsnes."
  [^js nes ^js stats]
  (let [^js cpu (.-cpu nes)
        ^js ppu (.-ppu nes)
        jsnes-emulate (aget (.getPrototypeOf js/Object cpu) "emulate")
        s (core/create)
        bus (bus-for cpu ppu)
        regs-in! (fn []
                   (set! (.-a s) (.-REG_ACC cpu))
                   (set! (.-x s) (.-REG_X cpu))
                   (set! (.-y s) (.-REG_Y cpu))
                   (set! (.-sp s) (bit-and (.-REG_SP cpu) 0xFF))
                   (set! (.-pc s) (bit-and (inc (.-REG_PC cpu)) 0xFFFF))  ; jsnes keeps PC one less
                   (set! (.-p s) (.getStatus cpu)))
        regs-out! (fn []
                    (set! (.-REG_ACC cpu) (.-a s))
                    (set! (.-REG_X cpu) (.-x s))
                    (set! (.-REG_Y cpu) (.-y s))
                    (set! (.-REG_SP cpu) (.-sp s))
                    (set! (.-REG_PC cpu) (bit-and (dec (.-pc s)) 0xFFFF))
                    (.setStatus cpu (.-p s)))]
    (set! (.-emulate cpu)
          (fn []
            (cond
              ;; An NMI that arrived early enough in the last instruction
              ;; takes effect now, instead of the next instruction.
              (.-nmiImmediate cpu)
              (do (set! (.-nmiImmediate cpu) false)
                  (set! (.-nmiPending cpu) false)
                  (set! (.-nmiRaised cpu) false)
                  (set! (.-instrBusCycles cpu) 0)
                  (regs-in!)
                  (core/nmi! s bus)
                  (regs-out!)
                  (set! (.-cljs stats) (inc (.-cljs stats)))
                  (set! (.-_cpuCycleBase cpu) (+ (.-_cpuCycleBase cpu) 7))
                  7)

              ;; The opcodes that jam a real 6502: jsnes stops the game.
              (and (not (.-irqRequested cpu))
                   (not (core/runnable? (peek-opcode cpu (bit-and (inc (.-REG_PC cpu)) 0xFFFF)))))
              (do (set! (.-jsnes stats) (inc (.-jsnes stats)))
                  (.call jsnes-emulate cpu))

              :else
              (do
                (set! (.-cljs stats) (inc (.-cljs stats)))
                (regs-in!)
                ;; An NMI raised during the last instruction (too late for
                ;; it) is taken after this one.
                (when (.-nmiRaised cpu)
                  (set! (.-nmiPending cpu) true)
                  (set! (.-nmiRaised cpu) false))
                (let [interrupt-cycles
                      (if (.-irqRequested cpu)
                        (let [c (case (.-irqType cpu)
                                  0 (core/irq! s bus)                            ; IRQ (0 if masked)
                                  2 (do (core/jump-to-reset-vector! s bus) 7)    ; RESET
                                  0)]
                          (set! (.-irqRequested cpu) false)
                          c)
                        0)]
                  (set! (.-instrBusCycles cpu) 0)
                  (set! (.-apuCatchupCycles cpu) 0)
                  (set! (.-nmiDotsRemainingInStep cpu) 0)
                  (set! (.-_dmcFetchCycles cpu) (._cyclesToNextDmcFetch cpu))
                  (let [cycles (or (core/step! s bus)
                                   (throw (js/Error. (str "Game crashed, invalid opcode at address $"
                                                          (.toString (bit-and (dec (.-pc s)) 0xFFFF) 16)))))
                        ;; PPU time for cycles with no bus access (as jsnes does)
                        _ (when (< (.-instrBusCycles cpu) cycles)
                            (let [missing (* 3 (- cycles (.-instrBusCycles cpu)))]
                              (set! (.-instrBusCycles cpu) cycles)
                              (.advanceDots ppu missing)))
                        ;; an NMI raised during this instruction: does it
                        ;; take effect before the next one, or after it?
                        _ (when (.-nmiRaised cpu)
                            (when (>= (+ (* 3 (- (.-instrBusCycles cpu) (.-nmiRaisedAtCycle cpu)))
                                         (.-nmiDotsRemainingInStep cpu))
                                      5)
                              (set! (.-nmiImmediate cpu) true)
                              (set! (.-nmiRaised cpu) false)))
                        ;; an NMI from before this instruction: now
                        interrupt-cycles (if (.-nmiPending cpu)
                                           (do (core/nmi! s bus)
                                               (set! (.-nmiPending cpu) false)
                                               7)
                                           interrupt-cycles)]
                    (regs-out!)
                    (set! (.-_cpuCycleBase cpu) (+ (.-_cpuCycleBase cpu) cycles interrupt-cycles))
                    (+ cycles interrupt-cycles)))))))))

(defn uninstall!
  "Back to jsnes's own CPU."
  [^js nes]
  (js-delete (.-cpu nes) "emulate"))
