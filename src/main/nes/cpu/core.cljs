(ns nes.cpu.core
  "A 6502 CPU, written in ClojureScript.

  The NES's CPU (a Ricoh 2A03) is a 6502 without decimal mode. This
  namespace runs its 151 official instructions and 93 unofficial ones
  (see zeropage.opcodes), one at a time, with the right number of cycles
  for each, and takes interrupts (NMI, IRQ, RESET).

  It doesn't know anything about the rest of the NES. It talks to memory
  through a `bus`: anything that implements the `Bus` protocol below, so
  the same CPU can run against a flat 64 KB array (for testing, see
  `flat-bus`) or inside a whole console. The bus is told what kind of
  access each one is (an opcode, an operand, the stack...), because a
  console counts and times them differently.

  Real 6502s make extra 'dummy' reads and writes in the middle of some
  instructions (an indexed load that crosses a page reads the wrong
  address first; INC writes the old value back before the new one). Those
  are real bus cycles, and a dummy read of a PPU register has visible
  effects, so this CPU makes them too, in the same order as jsnes, so the
  two can be swapped (see nes.emulator.jsnes-cljs-cpu).

  The registers are kept in an array (a 'state', see nes.cpu.registers),
  because they change millions of times a second: `(reg s :a)` reads A
  and `(set-reg! s :a v)` sets it. From outside, `registers` gives them
  all as a map. `p` is the status register as one byte: N V - B D I Z C,
  bit 7 first."
  (:refer-clojure :exclude [read])
  (:require [nes.cpu.registers :as regs :refer [reg set-reg!]]
            [zeropage.opcodes :as ops]))

;; (The functions below take the bus as `^not-native bus`: that tells
;; ClojureScript the bus is something that implements Bus, so it calls the
;; method directly instead of checking first. The bus is called millions
;; of times a second, and the check cost about 5%.)
(defprotocol Bus
  "How the CPU reaches memory and the rest of the machine. Every method
  that reads returns a byte (0-255)."
  (fetch [bus addr] "Read the opcode at `addr`.")
  (read-operand [bus addr] "Read an operand byte (or make a dummy read nearby).")
  (read16 [bus addr] "Read a 2-byte operand, low byte first.")
  (read [bus addr] "Read data (or make a dummy read of an address).")
  (write [bus addr value] "Write data.")
  (push [bus sp value] "Write to the stack, at $0100 + sp.")
  (pull [bus sp] "Read the stack, at $0100 + ((sp + 1) & $FF).")
  (read-vector [bus addr] "Read an interrupt vector byte ($FFFA-$FFFF).")
  (tick [bus] "A cycle with no memory access (some interrupt cycles).")
  (dmc-during-instruction? [bus]
    "Did the sound chip's sample DMA take over the bus during this
    instruction? (Only the unstable SHA/SHX/SHY/TAS instructions care.)"))

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
  ;;                   a x y sp   pc p
  (js/Int32Array. #js [0 0 0 0xFD 0 0x24]))

(defn registers
  "All of `s`'s registers, as a map: {:a :x :y :sp :pc :p}."
  [s]
  (zipmap regs/names (array-seq s)))

(defn set-registers!
  "Set some of `s`'s registers from a map, like the one `registers` gives."
  [s m]
  (doseq [[k v] m]
    (aset s (or (regs/index k) (throw (ex-info (str "Not a register: " k) {:register k}))) v))
  s)

;; ---------------------------------------------------------------------------
;; Decoding tables: built once from zeropage.opcodes, as arrays indexed by
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
   0xAA 2 0xA8 2 0xBA 2 0x8A 2 0x9A 2 0x98 2               ; transfers
   ;; unofficial
   0x1A 2 0x3A 2 0x5A 2 0x7A 2 0xDA 2 0xFA 2 0x80 2 0x82 2 0x89 2 0xC2 2 0xE2 2   ; NOPs
   0x0C 4 0x1C 4 0x3C 4 0x5C 4 0x7C 4 0xDC 4 0xFC 4
   0x04 3 0x44 3 0x64 3 0x14 4 0x34 4 0x54 4 0x74 4 0xD4 4 0xF4 4
   0xEB 2 0x4B 2 0x0B 2 0x2B 2 0x6B 2 0xCB 2 0x8B 2 0xAB 2                 ; SBC ALR ANC ARR AXS XAA LXA
   0xA3 6 0xA7 3 0xAF 4 0xB3 5 0xB7 4 0xBF 4                               ; LAX
   0x83 6 0x87 3 0x8F 4 0x97 4                                             ; SAX
   0xC3 8 0xC7 5 0xCF 6 0xD3 8 0xD7 6 0xDB 7 0xDF 7                        ; DCP
   0xE3 8 0xE7 5 0xEF 6 0xF3 8 0xF7 6 0xFB 7 0xFF 7                        ; ISB
   0x03 8 0x07 5 0x0F 6 0x13 8 0x17 6 0x1B 7 0x1F 7                        ; SLO
   0x23 8 0x27 5 0x2F 6 0x33 8 0x37 6 0x3B 7 0x3F 7                        ; RLA
   0x43 8 0x47 5 0x4F 6 0x53 8 0x57 6 0x5B 7 0x5F 7                        ; SRE
   0x63 8 0x67 5 0x6F 6 0x73 8 0x77 6 0x7B 7 0x7F 7                        ; RRA
   0x93 6 0x9F 5 0x9B 5 0x9C 5 0x9E 5 0xBB 4})                             ; SHA TAS SHY SHX LAS

(def ^:private mode-codes
  {:imp 0 :acc 1 :imm 2 :zp 3 :zpx 4 :zpy 5 :abs 6 :abx 7 :aby 8 :ind 9 :izx 10 :izy 11 :rel 12})

(def ^:private mnemonics
  "Each mnemonic as a number, for a fast `case`."
  (into {} (map-indexed (fn [i m] [m (inc i)])
                        (sort (distinct (map :mnemonic (vals ops/all-opcodes)))))))

(defn- table [f]
  (let [a (js/Int32Array. 256)]
    (doseq [[op info] ops/all-opcodes] (aset a op (f op info)))
    a))

(def ^:private op-ins (table (fn [_ {:keys [mnemonic]}] (mnemonics mnemonic))))
(def ^:private op-mode (table (fn [_ {:keys [mode]}] (mode-codes mode))))
(def ^:private op-size (table (fn [_ {:keys [size]}] size)))
(def ^:private op-cycles (table (fn [op _] (base-cycles op))))

(def ^:private op-name
  "Each opcode's mnemonic (a keyword), or nil."
  (let [a (make-array 256)]
    (doseq [[op {:keys [mnemonic]}] ops/all-opcodes] (aset a op mnemonic))
    a))

(def ^:private op-penalty
  "1 for instructions that take an extra cycle when indexing crosses a
  page: the reads. (Stores and read-modify-writes always take it, and it's
  in their base cycles.)"
  (table (fn [_ {:keys [mnemonic mode]}]
           (if (and (#{:lda :ldx :ldy :adc :sbc :and :ora :eor :cmp :lax :las :nop} mnemonic)
                    (#{:abx :aby :izy} mode))
             1 0))))

(def ^:private official-set (set (keys ops/opcodes)))

(defn official?
  "Is `opcode` one of the 151 official instructions?"
  [opcode]
  (contains? official-set opcode))

(defn runnable?
  "Can this CPU run `opcode`? All but the 12 that jam a real 6502."
  [opcode]
  (pos? (aget op-ins opcode)))

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- set-nz!
  "Set N and Z from result `v` (a byte); return `v`."
  [s v]
  (set-reg! s :p (bit-or (bit-and (reg s :p) 0x7D) (bit-and v N) (if (zero? v) Z 0)))
  v)

(defn- flag! [s bit on?]
  (set-reg! s :p (if on? (bit-or (reg s :p) bit) (bit-and (reg s :p) (bit-not bit)))))

(defn- flag? [s bit] (pos? (bit-and (reg s :p) bit)))

(defn- push! [s ^not-native bus v]
  (push bus (reg s :sp) v)
  (set-reg! s :sp (bit-and (dec (reg s :sp)) 0xFF)))

(defn- pull! [s ^not-native bus]
  (let [v (pull bus (reg s :sp))]
    (set-reg! s :sp (bit-and (inc (reg s :sp)) 0xFF))
    v))

(defn- adc! [s v]
  (let [a (reg s :a)
        sum (+ a v (if (flag? s C) 1 0))
        r (bit-and sum 0xFF)]
    (flag! s C (> sum 0xFF))
    ;; overflow: both inputs had the same sign, and the result doesn't
    (flag! s V (not (zero? (bit-and (bit-xor a r) (bit-xor v r) 0x80))))
    (set-reg! s :a (set-nz! s r))))

(defn- compare! [s reg v]
  (flag! s C (>= reg v))
  (set-nz! s (bit-and (- reg v) 0xFF)))

;; ---------------------------------------------------------------------------
;; Interrupts
;;
;; An interrupt makes the CPU stop what it's doing, save where it was (PC
;; and P, on the stack) and jump to an address stored at the top of ROM:
;; the 'vector'. RTI goes back. The NES uses NMI for 'the picture is
;; finished' (once a frame), and IRQ for sound and some cartridges.

(defn- vector-at
  "The 2-byte address stored at `addr` (an interrupt vector)."
  [^not-native bus addr]
  (bit-or (read-vector bus addr) (bit-shift-left (read-vector bus (inc addr)) 8)))

(defn- interrupt! [s ^not-native bus vector]
  (push! s bus (bit-and (bit-shift-right (reg s :pc) 8) 0xFF))
  (push! s bus (bit-and (reg s :pc) 0xFF))
  ;; P as pushed by an interrupt: B clear (that's how RTI's caller can
  ;; tell an interrupt from BRK), the unused bit set
  (push! s bus (bit-and (bit-or (reg s :p) U) (bit-not B)))
  (flag! s I true)
  (set-reg! s :pc (vector-at bus vector))
  7)

(defn nmi!
  "Take a non-maskable interrupt (the PPU's 'frame finished'): save PC and
  P on the stack and jump to the address at $FFFA. Returns 7 (cycles)."
  [s ^not-native bus]
  ;; two cycles of the CPU getting ready, with nothing on the bus
  (tick bus)
  (tick bus)
  (interrupt! s bus 0xFFFA))

(defn irq!
  "Take an IRQ, unless interrupts are disabled (I set): like NMI, through
  $FFFE. Returns the cycles taken (0 or 7)."
  [s ^not-native bus]
  (if (flag? s I)
    0
    (do (flag! s B false)
        (interrupt! s bus 0xFFFE))))

(defn jump-to-reset-vector!
  "What RESET does to the CPU, as jsnes models it: go to the address at
  $FFFC. Returns 7 (cycles)."
  [s ^not-native bus]
  (set-reg! s :pc (vector-at bus 0xFFFC))
  7)

(defn press-reset!
  "Press RESET: jump to the address at $FFFC, with interrupts disabled
  (and the stack pointer moved down 3, as a real 6502 does)."
  [s ^not-native bus]
  (set-reg! s :sp (bit-and (- (reg s :sp) 3) 0xFF))
  (flag! s I true)
  (jump-to-reset-vector! s bus))

;; ---------------------------------------------------------------------------
;; Running one instruction

(defn- branch!
  "A branch that's taken: the dummy reads a real 6502 makes, then jump.
  Returns the extra cycles (1, or 2 if it crosses a page)."
  [s ^not-native bus next-pc target]
  (read bus next-pc)
  (if (= (bit-and next-pc 0xFF00) (bit-and target 0xFF00))
    (do (set-reg! s :pc target) 1)
    (do (read bus (bit-or (bit-and next-pc 0xFF00) (bit-and target 0xFF)))
        (set-reg! s :pc target)
        2)))

(defn step!
  "Run one instruction. Returns the number of CPU cycles it took, or nil
  (doing nothing) if the opcode at PC isn't an official one."
  [s ^not-native bus]
  (let [pc (reg s :pc)
        op (fetch bus pc)
        ins (aget op-ins op)]
    (when (pos? ins)
      (let [mode (aget op-mode op)
            next-pc (bit-and (+ pc (aget op-size op)) 0xFFFF)
            o (bit-and (inc pc) 0xFFFF)          ; where the operand is
            cross (volatile! 0)                  ; 1 if indexing crossed a page
            base-hi (volatile! 0)                ; high byte of the address before indexing
            index (fn [base i]
                    (vreset! base-hi (bit-shift-right base 8))
                    (let [addr (bit-and (+ base i) 0xFFFF)]
                      (when (not= (bit-and base 0xFF00) (bit-and addr 0xFF00))
                        ;; reads the wrong page first
                        (read bus (bit-or (bit-and base 0xFF00) (bit-and addr 0xFF)))
                        (vreset! cross 1))
                      addr))
            ;; Work out the effective address (see nes.cpu.reference/mode-notes)
            addr (case mode
                   (0 1) (do (read-operand bus o) 0)           ; implied, accumulator: dummy read
                   2 o                                          ; immediate: the operand itself
                   3 (read-operand bus o)                       ; zero page
                   4 (let [b (read-operand bus o)] (read-operand bus b) (bit-and (+ b (reg s :x)) 0xFF))
                   5 (let [b (read-operand bus o)] (read-operand bus b) (bit-and (+ b (reg s :y)) 0xFF))
                   6 (read16 bus o)                            ; absolute
                   7 (index (read16 bus o) (reg s :x))
                   8 (index (read16 bus o) (reg s :y))
                   9 (let [ptr (read16 bus o)]                 ; JMP ($nnnn), with the 6502's page bug
                       (bit-or (read bus ptr)
                               (bit-shift-left (read bus (bit-or (bit-and ptr 0xFF00) (bit-and (inc ptr) 0xFF))) 8)))
                   10 (let [p (read-operand bus o)
                            _ (read-operand bus p)
                            zp (bit-and (+ p (reg s :x)) 0xFF)]
                        (bit-or (read-operand bus zp) (bit-shift-left (read-operand bus (bit-and (inc zp) 0xFF)) 8)))
                   11 (let [zp (read-operand bus o)
                            base (bit-or (read-operand bus zp) (bit-shift-left (read-operand bus (bit-and (inc zp) 0xFF)) 8))]
                        (index base (reg s :y)))
                   12 (let [off (read-operand bus o)]           ; relative: the branch target
                        (bit-and (+ next-pc (if (< off 0x80) off (- off 256))) 0xFFFF)))
            indexed? (contains? #{7 8 11} mode)
            ;; stores and read-modify-writes always do the indexed dummy read
            store-dummy! #(when (and indexed? (zero? @cross)) (read bus addr))
            rmw! (fn [f]
                   (if (= mode 1)
                     (set-reg! s :a (f (reg s :a)))
                     (do (store-dummy!)
                         (let [old (read bus addr)]
                           (write bus addr old)       ; writes the old value back first
                           (write bus addr (f old))))))
            ;; the read-modify-write unofficial ones: f gets the old value and
            ;; returns the new one to write
            rmw-mem! (fn [f]
                       (store-dummy!)
                       (let [old (read bus addr)]
                         (write bus addr old)
                         (let [new (f old)] (write bus addr new) new)))
            ;; SHA/SHX/SHY/TAS: store v AND (high byte of the address + 1). If
            ;; indexing crossed a page, that value replaces the address's high
            ;; byte too; and if the sound chip's DMA took the bus meanwhile,
            ;; the AND doesn't happen. (These are 'unstable' on real chips.)
            sh! (fn [v]
                  (when (zero? @cross) (read bus addr))
                  (let [val (if (dmc-during-instruction? bus)
                              v
                              (bit-and v (bit-and (inc @base-hi) 0xFF)))
                        a (if (= 1 @cross) (bit-or (bit-shift-left val 8) (bit-and addr 0xFF)) addr)]
                    (write bus a val)))
            extra (volatile! 0)
            branch (fn [taken?] (when taken? (vreset! extra (branch! s bus next-pc addr))))]
        (set-reg! s :pc next-pc)
        (case (aget op-name op)
          :lda (set-reg! s :a (set-nz! s (read bus addr)))
          :ldx (set-reg! s :x (set-nz! s (read bus addr)))
          :ldy (set-reg! s :y (set-nz! s (read bus addr)))
          :sta (do (store-dummy!) (write bus addr (reg s :a)))
          :stx (write bus addr (reg s :x))
          :sty (write bus addr (reg s :y))
          :adc (adc! s (read bus addr))
          :sbc (adc! s (bit-xor (read bus addr) 0xFF))
          :and (set-reg! s :a (set-nz! s (bit-and (reg s :a) (read bus addr))))
          :ora (set-reg! s :a (set-nz! s (bit-or (reg s :a) (read bus addr))))
          :eor (set-reg! s :a (set-nz! s (bit-xor (reg s :a) (read bus addr))))
          :cmp (compare! s (reg s :a) (read bus addr))
          :cpx (compare! s (reg s :x) (read bus addr))
          :cpy (compare! s (reg s :y) (read bus addr))
          :bit (let [v (read bus addr)]
                 (flag! s Z (zero? (bit-and (reg s :a) v)))
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
          :inx (set-reg! s :x (set-nz! s (bit-and (inc (reg s :x)) 0xFF)))
          :iny (set-reg! s :y (set-nz! s (bit-and (inc (reg s :y)) 0xFF)))
          :dex (set-reg! s :x (set-nz! s (bit-and (dec (reg s :x)) 0xFF)))
          :dey (set-reg! s :y (set-nz! s (bit-and (dec (reg s :y)) 0xFF)))
          :tax (set-reg! s :x (set-nz! s (reg s :a)))
          :tay (set-reg! s :y (set-nz! s (reg s :a)))
          :txa (set-reg! s :a (set-nz! s (reg s :x)))
          :tya (set-reg! s :a (set-nz! s (reg s :y)))
          :tsx (set-reg! s :x (set-nz! s (reg s :sp)))
          :txs (set-reg! s :sp (reg s :x))
          :bcc (branch (not (flag? s C)))
          :bcs (branch (flag? s C))
          :bne (branch (not (flag? s Z)))
          :beq (branch (flag? s Z))
          :bpl (branch (not (flag? s N)))
          :bmi (branch (flag? s N))
          :bvc (branch (not (flag? s V)))
          :bvs (branch (flag? s V))
          :jmp (set-reg! s :pc addr)
          :jsr (let [ret (bit-and (dec next-pc) 0xFFFF)]   ; pushes the address of its last byte
                 (push! s bus (bit-shift-right ret 8))
                 (push! s bus (bit-and ret 0xFF))
                 (read-operand bus (bit-and (+ pc 2) 0xFFFF))
                 (set-reg! s :pc addr))
          :rts (let [lo (pull! s bus) hi (pull! s bus)]
                 (set-reg! s :pc (bit-and (inc (bit-or lo (bit-shift-left hi 8))) 0xFFFF)))
          :rti (let [p (pull! s bus) lo (pull! s bus) hi (pull! s bus)]
                 ;; B and the unused bit aren't really in P: keep what's there
                 (set-reg! s :p (bit-or (bit-and p 0xCF) (bit-and (reg s :p) 0x30)))
                 (set-reg! s :pc (bit-or lo (bit-shift-left hi 8))))
          :pha (push! s bus (reg s :a))
          :php (push! s bus (bit-or (reg s :p) B U))
          :pla (set-reg! s :a (set-nz! s (pull! s bus)))
          :plp (set-reg! s :p (bit-or (bit-and (pull! s bus) 0xCF) (bit-and (reg s :p) 0x30)))
          :brk (let [ret (bit-and (inc next-pc) 0xFFFF)]   ; BRK skips a byte
                 (push! s bus (bit-shift-right ret 8))
                 (push! s bus (bit-and ret 0xFF))
                 (push! s bus (bit-or (reg s :p) B U))
                 (flag! s I true)
                 (set-reg! s :pc (read16 bus 0xFFFE)))
          :clc (flag! s C false)
          :sec (flag! s C true)
          :cli (flag! s I false)
          :sei (flag! s I true)
          :clv (flag! s V false)
          :cld (flag! s D false)
          :sed (flag! s D true)
          ;; NOP: the unofficial ones with an address still read it (but the
          ;; immediate ones don't)
          :nop (when (#{3 4 6 7} mode) (read bus addr))
          ;; ---- unofficial ----
          :lax (let [v (set-nz! s (read bus addr))] (set-reg! s :a v) (set-reg! s :x v))
          :sax (write bus addr (bit-and (reg s :a) (reg s :x)))
          :dcp (let [v (rmw-mem! #(bit-and (dec %) 0xFF))] (compare! s (reg s :a) v))
          :isb (adc! s (bit-xor (rmw-mem! #(bit-and (inc %) 0xFF)) 0xFF))
          :slo (let [v (rmw-mem! (fn [v] (flag! s C (pos? (bit-and v 0x80))) (bit-and (bit-shift-left v 1) 0xFF)))]
                 (set-reg! s :a (set-nz! s (bit-or (reg s :a) v))))
          :rla (let [v (rmw-mem! (fn [v] (let [c (if (flag? s C) 1 0)]
                                           (flag! s C (pos? (bit-and v 0x80)))
                                           (bit-and (bit-or (bit-shift-left v 1) c) 0xFF))))]
                 (set-reg! s :a (set-nz! s (bit-and (reg s :a) v))))
          :sre (let [v (rmw-mem! (fn [v] (flag! s C (pos? (bit-and v 1))) (bit-shift-right v 1)))]
                 (set-reg! s :a (set-nz! s (bit-xor (reg s :a) v))))
          :rra (let [v (rmw-mem! (fn [v] (let [c (if (flag? s C) 0x80 0)]
                                           (flag! s C (pos? (bit-and v 1)))
                                           (bit-or (bit-shift-right v 1) c))))]
                 (adc! s v))
          :anc (let [v (set-nz! s (bit-and (reg s :a) (read bus addr)))]
                 (set-reg! s :a v)
                 (flag! s C (pos? (bit-and v 0x80))))
          :alr (let [v (bit-and (reg s :a) (read bus addr))]
                 (flag! s C (pos? (bit-and v 1)))
                 (set-reg! s :a (set-nz! s (bit-shift-right v 1))))
          :arr (let [v (bit-and (reg s :a) (read bus addr))
                     r (bit-or (bit-shift-right v 1) (if (flag? s C) 0x80 0))]
                 (set-reg! s :a (set-nz! s r))
                 (flag! s C (pos? (bit-and v 0x80)))
                 (flag! s V (pos? (bit-and (bit-xor (bit-shift-right v 7) (bit-shift-right v 6)) 1))))
          :axs (let [t (- (bit-and (reg s :x) (reg s :a)) (read bus addr))]
                 (flag! s C (>= t 0))
                 (set-reg! s :x (set-nz! s (bit-and t 0xFF))))
          :las (let [v (bit-and (read bus addr) (reg s :sp))]
                 (set-reg! s :a (set-nz! s v)) (set-reg! s :x v) (set-reg! s :sp v))
          :xaa (set-reg! s :a (set-nz! s (bit-and (bit-or (reg s :a) 0xFF) (reg s :x) (read bus addr))))
          :lxa (let [v (set-nz! s (bit-and (bit-or (reg s :a) 0xFF) (read bus addr)))]
                 (set-reg! s :a v) (set-reg! s :x v))
          :sha (sh! (bit-and (reg s :a) (reg s :x)))
          :shx (sh! (reg s :x))
          :shy (sh! (reg s :y))
          :tas (do (set-reg! s :sp (bit-and (reg s :a) (reg s :x)))
                   (sh! (reg s :sp))))
        (+ (aget op-cycles op) (* @cross (aget op-penalty op)) @extra)))))

;; ---------------------------------------------------------------------------
;; A bus for running the CPU by itself

(defn flat-bus
  "A bus over `mem`, a Uint8Array of 64 KB: every address is plain memory
  (writes to $8000 and up are ignored, as ROM). For tests and the REPL."
  [^js mem]
  (let [rd (fn [a] (aget mem (bit-and a 0xFFFF)))]
    (reify Bus
      (fetch [_ a] (rd a))
      (read-operand [_ a] (rd a))
      (read [_ a] (rd a))
      (read16 [_ a] (bit-or (rd a) (bit-shift-left (rd (inc a)) 8)))
      (write [_ a v] (when (< a 0x8000) (aset mem a v)))
      (read-vector [_ a] (rd a))
      (tick [_] nil)
      (dmc-during-instruction? [_] false)
      (push [_ sp v] (aset mem (bit-or 0x100 sp) v))
      (pull [_ sp] (aget mem (bit-or 0x100 (bit-and (inc sp) 0xFF)))))))
