(ns nes.cpu.core
  "A 6502 CPU, written in ClojureScript.

  The NES's CPU (a Ricoh 2A03) is a 6502 without decimal mode. This
  namespace runs its 151 official instructions, one at a time, with the
  right number of cycles for each.

  It doesn't know anything about the rest of the NES. It talks to memory
  through a `bus`: a JS object with these methods, so the same CPU can run
  against a flat 64 KB array (for testing) or inside a whole console:

    fetch(addr)         read an opcode
    readOperand(addr)   read an operand byte (or do a dummy read nearby)
    read16(addr)        read a 2-byte operand, low byte first
    read(addr)          read data (or do a dummy read of an address)
    write(addr, value)  write data
    push(sp, value)     write to the stack at $0100+sp
    pull(sp)            read the stack at $0100+((sp+1) & $FF)

  Real 6502s make extra 'dummy' reads and writes in the middle of some
  instructions (an indexed load that crosses a page reads the wrong
  address first; INC writes the old value back before the new one). Those
  are real bus cycles, and a dummy read of a PPU register has visible
  effects, so this CPU makes them too, in the same order as jsnes, so the
  two can be swapped (see nes.emulator.jsnes-cljs-cpu).

  The registers are kept in a JS object (a 'state'), because they change
  millions of times a second:

    #js {:a 0 :x 0 :y 0 :sp 0xFD :pc 0xC000 :p 0x24 :cycles 0}

  `p` is the status register as one byte: N V - B D I Z C, bit 7 first."
  (:require [nes.cpu.opcodes :as ops]))

;; Status flag bits
(def C 0x01)   ; carry
(def Z 0x02)   ; zero
(def I 0x04)   ; interrupt disable
(def D 0x08)   ; decimal (does nothing on the NES)
(def B 0x10)   ; 'break': only in copies of P pushed to the stack
(def U 0x20)   ; unused, always 1
(def V 0x40)   ; overflow
(def N 0x80)   ; negative

(defn create
  "A CPU in its state at power on (before RESET)."
  []
  #js {:a 0 :x 0 :y 0 :sp 0xFD :pc 0 :p 0x24 :cycles 0})

;; ---------------------------------------------------------------------------
;; Decoding tables: built once from nes.cpu.opcodes, as arrays indexed by
;; opcode, so decoding an instruction is two array lookups.

(def ^:private base-cycles
  "Cycles each official instruction takes, before page-crossing and
  branch penalties."
  {0x69 2 0x65 3 0x75 4 0x6D 4 0x7D 4 0x79 4 0x61 6 0x71 5   ; ADC
   0x29 2 0x25 3 0x35 4 0x2D 4 0x3D 4 0x39 4 0x21 6 0x31 5   ; AND
   0x0A 2 0x06 5 0x16 6 0x0E 6 0x1E 7                         ; ASL
   0x90 2 0xB0 2 0xF0 2 0x30 2 0xD0 2 0x10 2 0x50 2 0x70 2   ; branches
   0x24 3 0x2C 4 0x00 7                                       ; BIT, BRK
   0x18 2 0xD8 2 0x58 2 0xB8 2                                ; CLC CLD CLI CLV
   0xC9 2 0xC5 3 0xD5 4 0xCD 4 0xDD 4 0xD9 4 0xC1 6 0xD1 5   ; CMP
   0xE0 2 0xE4 3 0xEC 4 0xC0 2 0xC4 3 0xCC 4                 ; CPX CPY
   0xC6 5 0xD6 6 0xCE 6 0xDE 7 0xCA 2 0x88 2                 ; DEC DEX DEY
   0x49 2 0x45 3 0x55 4 0x4D 4 0x5D 4 0x59 4 0x41 6 0x51 5   ; EOR
   0xE6 5 0xF6 6 0xEE 6 0xFE 7 0xE8 2 0xC8 2                 ; INC INX INY
   0x4C 3 0x6C 5 0x20 6                                       ; JMP JSR
   0xA9 2 0xA5 3 0xB5 4 0xAD 4 0xBD 4 0xB9 4 0xA1 6 0xB1 5   ; LDA
   0xA2 2 0xA6 3 0xB6 4 0xAE 4 0xBE 4                         ; LDX
   0xA0 2 0xA4 3 0xB4 4 0xAC 4 0xBC 4                         ; LDY
   0x4A 2 0x46 5 0x56 6 0x4E 6 0x5E 7 0xEA 2                 ; LSR NOP
   0x09 2 0x05 3 0x15 4 0x0D 4 0x1D 4 0x19 4 0x01 6 0x11 5   ; ORA
   0x48 3 0x08 3 0x68 4 0x28 4                                ; PHA PHP PLA PLP
   0x2A 2 0x26 5 0x36 6 0x2E 6 0x3E 7                         ; ROL
   0x6A 2 0x66 5 0x76 6 0x6E 6 0x7E 7                         ; ROR
   0x40 6 0x60 6                                              ; RTI RTS
   0xE9 2 0xE5 3 0xF5 4 0xED 4 0xFD 4 0xF9 4 0xE1 6 0xF1 5   ; SBC
   0x38 2 0xF8 2 0x78 2                                       ; SEC SED SEI
   0x85 3 0x95 4 0x8D 4 0x9D 5 0x99 5 0x81 6 0x91 6          ; STA
   0x86 3 0x96 4 0x8E 4 0x84 3 0x94 4 0x8C 4                 ; STX STY
   0xAA 2 0xA8 2 0xBA 2 0x8A 2 0x9A 2 0x98 2})               ; transfers

(def ^:private mode-codes
  {:imp 0 :acc 1 :imm 2 :zp 3 :zpx 4 :zpy 5 :abs 6 :abx 7 :aby 8 :ind 9 :izx 10 :izy 11 :rel 12})

(def ^:private mnemonics
  "Each mnemonic as a number, for a fast `case`."
  (into {} (map-indexed (fn [i m] [m (inc i)])
                        (sort (distinct (map :mnemonic (vals ops/opcodes)))))))

(defn- table [f]
  (let [a (js/Int32Array. 256)]
    (doseq [[op info] ops/opcodes] (aset a op (f op info)))
    a))

(def ^:private op-ins (table (fn [_ {:keys [mnemonic]}] (mnemonics mnemonic))))
(def ^:private op-mode (table (fn [_ {:keys [mode]}] (mode-codes mode))))
(def ^:private op-size (table (fn [_ {:keys [size]}] size)))
(def ^:private op-cycles (table (fn [op _] (base-cycles op))))

(def ^:private op-name
  "Each opcode's mnemonic (a keyword), or nil."
  (let [a (make-array 256)]
    (doseq [[op {:keys [mnemonic]}] ops/opcodes] (aset a op mnemonic))
    a))

(def ^:private op-penalty
  "1 for instructions that take an extra cycle when indexing crosses a
  page: the reads. (Stores and read-modify-writes always take it, and it's
  in their base cycles.)"
  (table (fn [_ {:keys [mnemonic mode]}]
           (if (and (#{:lda :ldx :ldy :adc :sbc :and :ora :eor :cmp} mnemonic)
                    (#{:abx :aby :izy} mode))
             1 0))))

(defn official?
  "Is `opcode` one of the 151 official instructions?"
  [opcode]
  (pos? (aget op-ins opcode)))

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- set-nz!
  "Set N and Z from result `v` (a byte); return `v`."
  [^js s v]
  (set! (.-p s) (bit-or (bit-and (.-p s) 0x7D) (bit-and v N) (if (zero? v) Z 0)))
  v)

(defn- flag! [^js s bit on?]
  (set! (.-p s) (if on? (bit-or (.-p s) bit) (bit-and (.-p s) (bit-not bit)))))

(defn- flag? [^js s bit] (pos? (bit-and (.-p s) bit)))

(defn- push! [^js s ^js bus v]
  (.push bus (.-sp s) v)
  (set! (.-sp s) (bit-and (dec (.-sp s)) 0xFF)))

(defn- pull! [^js s ^js bus]
  (let [v (.pull bus (.-sp s))]
    (set! (.-sp s) (bit-and (inc (.-sp s)) 0xFF))
    v))

(defn- adc! [^js s v]
  (let [a (.-a s)
        sum (+ a v (if (flag? s C) 1 0))
        r (bit-and sum 0xFF)]
    (flag! s C (> sum 0xFF))
    ;; overflow: both inputs had the same sign, and the result doesn't
    (flag! s V (not (zero? (bit-and (bit-xor a r) (bit-xor v r) 0x80))))
    (set! (.-a s) (set-nz! s r))))

(defn- compare! [^js s reg v]
  (flag! s C (>= reg v))
  (set-nz! s (bit-and (- reg v) 0xFF)))

;; ---------------------------------------------------------------------------
;; Interrupts

(defn- interrupt! [^js s ^js bus vector pushed-p]
  (push! s bus (bit-and (bit-shift-right (.-pc s) 8) 0xFF))
  (push! s bus (bit-and (.-pc s) 0xFF))
  (push! s bus pushed-p)
  (flag! s I true)
  (set! (.-pc s) (.read16 bus vector))
  (set! (.-cycles s) (+ (.-cycles s) 7))
  7)

(defn nmi!
  "Take a non-maskable interrupt (the PPU's 'frame finished'): save PC and
  P on the stack and jump to the address at $FFFA. Returns 7 (cycles)."
  [s bus]
  (interrupt! s bus 0xFFFA (bit-and (bit-or (.-p s) U) (bit-not B))))

(defn irq!
  "Take an IRQ, unless interrupts are disabled (I set). Returns the cycles
  taken (0 or 7)."
  [s bus]
  (if (flag? s I) 0 (interrupt! s bus 0xFFFE (bit-and (bit-or (.-p s) U) (bit-not B)))))

(defn press-reset!
  "Press RESET: jump to the address at $FFFC, with interrupts disabled."
  [^js s ^js bus]
  (set! (.-sp s) (bit-and (- (.-sp s) 3) 0xFF))
  (flag! s I true)
  (set! (.-pc s) (.read16 bus 0xFFFC))
  (set! (.-cycles s) (+ (.-cycles s) 7)))

;; ---------------------------------------------------------------------------
;; Running one instruction

(defn- branch!
  "A branch that's taken: the dummy reads a real 6502 makes, then jump.
  Returns the extra cycles (1, or 2 if it crosses a page)."
  [^js s ^js bus next-pc target]
  (.read bus next-pc)
  (if (= (bit-and next-pc 0xFF00) (bit-and target 0xFF00))
    (do (set! (.-pc s) target) 1)
    (do (.read bus (bit-or (bit-and next-pc 0xFF00) (bit-and target 0xFF)))
        (set! (.-pc s) target)
        2)))

(defn step!
  "Run one instruction. Returns the number of CPU cycles it took, or nil
  (doing nothing) if the opcode at PC isn't an official one."
  [^js s ^js bus]
  (let [pc (.-pc s)
        op (.fetch bus pc)
        ins (aget op-ins op)]
    (when (pos? ins)
      (let [mode (aget op-mode op)
            next-pc (bit-and (+ pc (aget op-size op)) 0xFFFF)
            o (bit-and (inc pc) 0xFFFF)          ; where the operand is
            cross (volatile! 0)                  ; 1 if indexing crossed a page
            index (fn [base i]
                    (let [addr (bit-and (+ base i) 0xFFFF)]
                      (when (not= (bit-and base 0xFF00) (bit-and addr 0xFF00))
                        ;; reads the wrong page first
                        (.read bus (bit-or (bit-and base 0xFF00) (bit-and addr 0xFF)))
                        (vreset! cross 1))
                      addr))
            ;; Work out the effective address (see nes.cpu.reference/mode-notes)
            addr (case mode
                   (0 1) (do (.readOperand bus o) 0)           ; implied, accumulator: dummy read
                   2 o                                          ; immediate: the operand itself
                   3 (.readOperand bus o)                       ; zero page
                   4 (let [b (.readOperand bus o)] (.readOperand bus b) (bit-and (+ b (.-x s)) 0xFF))
                   5 (let [b (.readOperand bus o)] (.readOperand bus b) (bit-and (+ b (.-y s)) 0xFF))
                   6 (.read16 bus o)                            ; absolute
                   7 (index (.read16 bus o) (.-x s))
                   8 (index (.read16 bus o) (.-y s))
                   9 (let [ptr (.read16 bus o)]                 ; JMP ($nnnn), with the 6502's page bug
                       (bit-or (.read bus ptr)
                               (bit-shift-left (.read bus (bit-or (bit-and ptr 0xFF00) (bit-and (inc ptr) 0xFF))) 8)))
                   10 (let [p (.readOperand bus o)
                            _ (.readOperand bus p)
                            zp (bit-and (+ p (.-x s)) 0xFF)]
                        (bit-or (.readOperand bus zp) (bit-shift-left (.readOperand bus (bit-and (inc zp) 0xFF)) 8)))
                   11 (let [zp (.readOperand bus o)
                            base (bit-or (.readOperand bus zp) (bit-shift-left (.readOperand bus (bit-and (inc zp) 0xFF)) 8))]
                        (index base (.-y s)))
                   12 (let [off (.readOperand bus o)]           ; relative: the branch target
                        (bit-and (+ next-pc (if (< off 0x80) off (- off 256))) 0xFFFF)))
            indexed? (contains? #{7 8 11} mode)
            ;; stores and read-modify-writes always do the indexed dummy read
            store-dummy! #(when (and indexed? (zero? @cross)) (.read bus addr))
            rmw! (fn [f]
                   (if (= mode 1)
                     (set! (.-a s) (f (.-a s)))
                     (do (store-dummy!)
                         (let [old (.read bus addr)]
                           (.write bus addr old)       ; writes the old value back first
                           (.write bus addr (f old))))))
            extra (volatile! 0)
            branch (fn [taken?] (when taken? (vreset! extra (branch! s bus next-pc addr))))]
        (set! (.-pc s) next-pc)
        (case (aget op-name op)
          :lda (set! (.-a s) (set-nz! s (.read bus addr)))
          :ldx (set! (.-x s) (set-nz! s (.read bus addr)))
          :ldy (set! (.-y s) (set-nz! s (.read bus addr)))
          :sta (do (store-dummy!) (.write bus addr (.-a s)))
          :stx (.write bus addr (.-x s))
          :sty (.write bus addr (.-y s))
          :adc (adc! s (.read bus addr))
          :sbc (adc! s (bit-xor (.read bus addr) 0xFF))
          :and (set! (.-a s) (set-nz! s (bit-and (.-a s) (.read bus addr))))
          :ora (set! (.-a s) (set-nz! s (bit-or (.-a s) (.read bus addr))))
          :eor (set! (.-a s) (set-nz! s (bit-xor (.-a s) (.read bus addr))))
          :cmp (compare! s (.-a s) (.read bus addr))
          :cpx (compare! s (.-x s) (.read bus addr))
          :cpy (compare! s (.-y s) (.read bus addr))
          :bit (let [v (.read bus addr)]
                 (flag! s Z (zero? (bit-and (.-a s) v)))
                 (flag! s N (pos? (bit-and v N)))
                 (flag! s V (pos? (bit-and v V))))
          :asl (rmw! (fn [v] (flag! s C (pos? (bit-and v 0x80))) (set-nz! s (bit-and (bit-shift-left v 1) 0xFF))))
          :lsr (rmw! (fn [v] (flag! s C (pos? (bit-and v 1))) (set-nz! s (bit-shift-right v 1))))
          :rol (rmw! (fn [v] (let [c (if (flag? s C) 1 0)]
                               (flag! s C (pos? (bit-and v 0x80)))
                               (set-nz! s (bit-and (bit-or (bit-shift-left v 1) c) 0xFF)))))
          :ror (rmw! (fn [v] (let [c (if (flag? s C) 0x80 0)]
                               (flag! s C (pos? (bit-and v 1)))
                               (set-nz! s (bit-or (bit-shift-right v 1) c)))))
          :inc (rmw! (fn [v] (set-nz! s (bit-and (inc v) 0xFF))))
          :dec (rmw! (fn [v] (set-nz! s (bit-and (dec v) 0xFF))))
          :inx (set! (.-x s) (set-nz! s (bit-and (inc (.-x s)) 0xFF)))
          :iny (set! (.-y s) (set-nz! s (bit-and (inc (.-y s)) 0xFF)))
          :dex (set! (.-x s) (set-nz! s (bit-and (dec (.-x s)) 0xFF)))
          :dey (set! (.-y s) (set-nz! s (bit-and (dec (.-y s)) 0xFF)))
          :tax (set! (.-x s) (set-nz! s (.-a s)))
          :tay (set! (.-y s) (set-nz! s (.-a s)))
          :txa (set! (.-a s) (set-nz! s (.-x s)))
          :tya (set! (.-a s) (set-nz! s (.-y s)))
          :tsx (set! (.-x s) (set-nz! s (.-sp s)))
          :txs (set! (.-sp s) (.-x s))
          :bcc (branch (not (flag? s C)))
          :bcs (branch (flag? s C))
          :bne (branch (not (flag? s Z)))
          :beq (branch (flag? s Z))
          :bpl (branch (not (flag? s N)))
          :bmi (branch (flag? s N))
          :bvc (branch (not (flag? s V)))
          :bvs (branch (flag? s V))
          :jmp (set! (.-pc s) addr)
          :jsr (let [ret (bit-and (dec next-pc) 0xFFFF)]   ; pushes the address of its last byte
                 (push! s bus (bit-shift-right ret 8))
                 (push! s bus (bit-and ret 0xFF))
                 (.readOperand bus (bit-and (+ pc 2) 0xFFFF))
                 (set! (.-pc s) addr))
          :rts (let [lo (pull! s bus) hi (pull! s bus)]
                 (set! (.-pc s) (bit-and (inc (bit-or lo (bit-shift-left hi 8))) 0xFFFF)))
          :rti (let [p (pull! s bus) lo (pull! s bus) hi (pull! s bus)]
                 ;; B and the unused bit aren't really in P: keep what's there
                 (set! (.-p s) (bit-or (bit-and p 0xCF) (bit-and (.-p s) 0x30)))
                 (set! (.-pc s) (bit-or lo (bit-shift-left hi 8))))
          :pha (push! s bus (.-a s))
          :php (push! s bus (bit-or (.-p s) B U))
          :pla (set! (.-a s) (set-nz! s (pull! s bus)))
          :plp (set! (.-p s) (bit-or (bit-and (pull! s bus) 0xCF) (bit-and (.-p s) 0x30)))
          :brk (let [ret (bit-and (inc next-pc) 0xFFFF)]   ; BRK skips a byte
                 (push! s bus (bit-shift-right ret 8))
                 (push! s bus (bit-and ret 0xFF))
                 (push! s bus (bit-or (.-p s) B U))
                 (flag! s I true)
                 (set! (.-pc s) (.read16 bus 0xFFFE)))
          :clc (flag! s C false)
          :sec (flag! s C true)
          :cli (flag! s I false)
          :sei (flag! s I true)
          :clv (flag! s V false)
          :cld (flag! s D false)
          :sed (flag! s D true)
          :nop nil)
        (let [cycles (+ (aget op-cycles op) (* @cross (aget op-penalty op)) @extra)]
          (set! (.-cycles s) (+ (.-cycles s) cycles))
          cycles)))))

;; ---------------------------------------------------------------------------
;; A bus for running the CPU by itself

(defn flat-bus
  "A bus over `mem`, a Uint8Array of 64 KB: every address is plain memory
  (writes to $8000 and up are ignored, as ROM). For tests and the REPL."
  [^js mem]
  (let [rd (fn [a] (aget mem (bit-and a 0xFFFF)))]
    #js {:fetch rd
         :readOperand rd
         :read rd
         :read16 (fn [a] (bit-or (rd a) (bit-shift-left (rd (inc a)) 8)))
         :write (fn [a v] (when (< a 0x8000) (aset mem a v)))
         :push (fn [sp v] (aset mem (bit-or 0x100 sp) v))
         :pull (fn [sp] (aget mem (bit-or 0x100 (bit-and (inc sp) 0xFF))))}))
