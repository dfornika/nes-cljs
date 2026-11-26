(ns nes.cpu.zeropage
  "Our CPU (nes.cpu.core) behind zeropage's CPU interface
  (zeropage.cpu/Cpu), so code that runs 6502 programs through that
  interface can run them here. zeropage's test harness can run on it
  instead of zeropage's own simple CPU:

    (binding [zeropage.harness/*cpu* nes.cpu.zeropage/create] ...)

  and nes.cpu.zeropage-test runs the same programs on both CPUs to check
  they agree.

  zeropage's bus is just {:read :write}. nes.cpu.core's Bus has more,
  so it can tell an opcode fetch from a data read, or a stack push from a
  store (a console needs to know). Here they're all plain reads and
  writes."
  (:require [nes.cpu.core :as core]
            [zeropage.cpu :as cpu]))

(defn- core-bus
  "A nes.cpu.core bus made from a zeropage bus."
  [{:keys [read write]}]
  (reify core/Bus
    (fetch [_ a] (read a))
    (read-operand [_ a] (read a))
    (read [_ a] (read a))
    (read16 [_ a] (bit-or (read a) (bit-shift-left (read (bit-and (inc a) 0xFFFF)) 8)))
    (write [_ a v] (write a v))
    (read-vector [_ a] (read a))
    (tick [_] nil)
    (dmc-during-instruction? [_] false)
    (push [_ sp v] (write (bit-or 0x100 sp) v))
    (pull [_ sp] (read (bit-or 0x100 (bit-and (inc sp) 0xFF))))))

(defn create
  "nes.cpu.core on `bus` ({:read :write}), as a zeropage.cpu/Cpu. Unlike
  zeropage's simple CPU it also runs the unofficial opcodes."
  [bus]
  (let [^js s (core/create)
        b (core-bus bus)]
    (reify cpu/Cpu
      (registers [_]
        {:a (.-a s) :x (.-x s) :y (.-y s) :sp (.-sp s) :pc (.-pc s) :p (.-p s)})
      (set-registers! [this regs]
        (doseq [[k v] regs]
          (case k
            :a (set! (.-a s) v)
            :x (set! (.-x s) v)
            :y (set! (.-y s) v)
            :sp (set! (.-sp s) v)
            :pc (set! (.-pc s) v)
            :p (set! (.-p s) v)))
        this)
      (step! [_] (core/step! s b))
      (nmi! [_] (core/nmi! s b))
      (jump-to-reset-vector! [_]
        (core/jump-to-reset-vector! s b)
        7))))
