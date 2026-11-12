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

;; ---------------------------------------------------------------------------
;; A whole program, without a console
;;
;; A game built with gamedev-toolkit can run here too, as long as something
;; stands in for the hardware it talks to. This does the least that works:
;; the PPU always says it's in vertical blank, writes to hardware registers
;; just land in memory (so a test can look at the last one), there's a
;; controller, and the test says when a frame ends.
;;
;; It relies on two labels that both games.hello and programs made by
;; gamedev-toolkit.runtime have: :main (the top of the main loop) and
;; :wait-for-frame (where the main loop waits for the NMI).

(def ^:private button-bits
  ;; in the order the controller reports them
  [:a :b :select :start :up :down :left :right])

(defn console
  "`built` (what a game's `build` returns) loaded and ready to `boot!`.
  Returns a machine (see `machine`) with the game's :labels and :symbols."
  [{:keys [prg labels symbols]}]
  (let [org (- 0x10000 (count prg))
        {:keys [^js mem ^js bus] :as m} (machine prg org)
        held (atom #{})      ; the buttons down now
        reading (atom 0)     ; which button the next read of $4016 gives
        read (.-read bus)
        write (.-write bus)]
    (aset mem 0x2002 0x80)   ; PPUSTATUS: always 'in vertical blank'
    (set! (.-read bus)
          (fn [addr]
            (if (= addr 0x4016)
              (let [button (get button-bits @reading)]
                (swap! reading inc)
                (if (contains? @held button) 1 0))
              (read addr))))
    (set! (.-write bus)
          (fn [addr value]
            ;; writing 1 makes the controller start again from A
            (when (and (= addr 0x4016) (odd? value)) (reset! reading 0))
            (write addr value)))
    (assoc m :held held :labels labels :symbols symbols)))

(defn boot!
  "Run from RESET to the top of the main loop. Returns the machine."
  [{:keys [s bus labels] :as m}]
  (cpu/jump-to-reset-vector! s bus)
  (run-until! m (:main labels) 1000000)
  m)

(defn hold!
  "Hold down exactly these buttons (keywords like :start, :left) from now on."
  [m & buttons]
  (reset! (:held m) (set buttons))
  m)

(defn frame!
  "Run one frame: the main loop until it waits, the NMI handler, and on
  to the top of the main loop. Returns the CPU cycles each took,
  {:update n :nmi n}, so a test can check they fit: a frame is 29,780
  cycles, and the NMI handler should finish within the 2,270 of vertical
  blank (less the 513 that copying the sprites takes on a real NES, which
  isn't counted here)."
  [{:keys [s bus labels] :as m}]
  (let [waiting (:wait-for-frame labels)
        update (run-until! m waiting 1000000)
        nmi (+ (cpu/nmi! s bus) (run-until! m waiting))]
    (run-until! m (:main labels))
    {:update update :nmi nmi}))

(defn frames!
  "Run `n` frames. Returns the machine."
  [m n]
  (dotimes [_ n] (frame! m))
  m)

(defn peek-at
  "The byte at `addr` (a number, or one of the game's names) in the machine."
  [{:keys [^js mem labels symbols]} addr]
  (aget mem (if (keyword? addr)
              (or (get symbols addr) (get labels addr)
                  (throw (ex-info (str "The game has nothing called " addr) {})))
              addr)))

(defn poke-at!
  "Store `values` from `addr` (a number or a name) on."
  [{:keys [^js mem symbols] :as m} addr & values]
  (let [addr (if (keyword? addr) (get symbols addr) addr)]
    (doseq [[i v] (map-indexed vector values)]
      (aset mem (+ addr i) v))
    m))
