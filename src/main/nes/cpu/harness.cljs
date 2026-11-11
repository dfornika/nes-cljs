(ns nes.cpu.harness
  "Running assembled code on the ClojureScript CPU by itself: no PPU, no
  sound, just the 6502 and 64 KB of plain memory. For testing routines
  (in `npm test`, or at the REPL) and for counting how many cycles they
  take.

    (run-routine [[:lda [:imm 2]] [:clc] [:adc :n] [:sta :n] [:rts]]
                 {:symbols {:n 0x10} :memory {0x10 40}})
    ;; => {:a 42 ... :cycles 16 :peek f}, and ((:peek result) 0x10) => 42"
  (:require [gamedev-toolkit.asm :as asm]
            [gamedev-toolkit.sub :as sub]
            [nes.cpu.core :as cpu]))

(defn machine
  "A CPU over 64 KB of flat memory, with `bytes` loaded at `org`.
  Returns {:mem (a Uint8Array) :s (the CPU's state) :bus}."
  [bytes org]
  (let [mem (js/Uint8Array. 0x10000)]
    (.set mem (clj->js bytes) org)
    {:mem mem :s (cpu/create) :bus (cpu/flat-bus mem)}))

(defn run-until!
  "Step the CPU until the program counter is `addr`. Returns the cycles
  that took. Throws if it takes more than `max-cycles` (default 100,000:
  about three frames), or reaches a byte that isn't an instruction."
  ([m addr] (run-until! m addr 100000))
  ([{:keys [^js s bus]} addr max-cycles]
   (loop [total 0]
     (cond
       (= addr (.-pc s)) total
       (> total max-cycles)
       (throw (ex-info (str "Still running after " max-cycles " cycles, at $" (.toString (.-pc s) 16)) {:pc (.-pc s)}))
       :else
       (let [pc (.-pc s)
             cycles (cpu/step! s bus)]
         (when-not cycles
           (throw (ex-info (str "Not an instruction at $" (.toString pc 16)) {:pc pc})))
         (recur (+ total cycles)))))))

(def ^:private return-address
  "Where a routine under test returns to: nothing is there, it's just a
  place to stop."
  0xFFF0)

(defn run-routine
  "Assemble `forms` (a routine ending in RTS, with any subroutines it
  calls), call it, and return the CPU's registers when it returns:

    {:a :x :y :sp :p   registers (p is the flags byte)
     :carry? :zero? :negative?
     :cycles           CPU cycles it took, including the final RTS
     :peek             (peek addr) -> the byte there afterwards
     :labels}

  Options:
    :symbols     as for asm/assemble
    :a :x :y     registers on entry (default 0)
    :memory      {addr byte, or addr [bytes...]} to set up first
    :org         where to put the routine (default $8000)
    :max-cycles  give up after this many (default 100,000)"
  ([forms] (run-routine forms {}))
  ([forms {:keys [symbols a x y memory org max-cycles]
           :or {symbols {} a 0 x 0 y 0 org 0x8000 max-cycles 100000}}]
   (let [{:keys [bytes labels]} (asm/assemble (:code (sub/link forms)) {:org org :symbols symbols})
         {:keys [^js mem ^js s] :as m} (machine bytes org)
         ;; RTS goes to the address on the stack plus one
         ret (dec return-address)]
     (doseq [[addr v] memory]
       (.set mem (clj->js (if (sequential? v) v [v])) addr))
     (aset mem 0x1FF (bit-shift-right ret 8))
     (aset mem 0x1FE (bit-and ret 0xFF))
     (set! (.-sp s) 0xFD)
     (set! (.-a s) a)
     (set! (.-x s) x)
     (set! (.-y s) y)
     (set! (.-pc s) org)
     (let [cycles (run-until! m return-address max-cycles)
           p (.-p s)]
       {:a (.-a s) :x (.-x s) :y (.-y s) :sp (.-sp s) :p p
        :carry? (pos? (bit-and p cpu/C))
        :zero? (pos? (bit-and p cpu/Z))
        :negative? (pos? (bit-and p cpu/N))
        :cycles cycles
        :peek #(aget mem %)
        :labels labels}))))
