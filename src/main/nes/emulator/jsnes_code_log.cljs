(ns nes.emulator.jsnes-code-log
  "Recording the code/data log (see nes.code-log) inside jsnes.

  Every byte the CPU reads from the cartridge goes through one method,
  `loadFromCartridge`. While logging, we wrap it (on the live CPU object)
  and sort each read:

  - a read at PC+1 (jsnes keeps PC one behind) is fetching an opcode: an
    instruction starts here, and its operand bytes follow
  - reads of those next few bytes are the operands: code too (jsnes has
    already moved PC on by then, so we remember where the instruction
    started)
  - anything else is the program reading data from ROM

  To turn a CPU address into a place in the ROM file we need to know which
  bank the mapper has switched into each 8 KB window of $8000-$FFFF. We
  find out once by comparing contents, then follow the mapper's bank
  switches (jsnes's mappers all switch banks through `loadRomBank` and
  `load8kRomBank`).

  Kept as plain JS typed arrays and numbers: this runs on every ROM read."
  (:require [nes.code-log :as cl]
            [nes.cpu.opcodes :as ops]))

(def ^:private sizes
  "Instruction size by opcode. Unofficial opcodes count as 1 byte."
  (let [a (js/Uint8Array. 256)]
    (.fill a 1)
    (doseq [[op {:keys [size]}] ops/all-opcodes] (aset a op size))
    a))

(defn create
  "A fresh, empty log for a PRG ROM of `prg-size` bytes."
  [prg-size]
  #js {:flags (js/Uint8Array. prg-size)
       :extra (js/Uint8Array. prg-size)
       :calls (js/Uint32Array. prg-size)
       ;; PRG offset shown in each 8 KB CPU window ($8000, $A000, $C000, $E000), or -1
       :windows (js/Int32Array. #js [-1 -1 -1 -1])
       :opStart 0        ; CPU address of the current instruction
       :opEnd 0          ; CPU address just past its operands
       :pending 0        ; 1 after JMP (ind), 2 after RTS: is the next opcode a jump target?
       :nmiDepth 0
       :frames 0})

(defn find-windows!
  "Work out which PRG ROM banks are in the CPU's windows now, by comparing
  memory contents."
  [^js log ^js nes]
  (let [^js mem (.. nes -cpu -mem)
        ^js banks (.. nes -rom -rom)
        n8k (* 2 (.-length banks))
        ^js windows (.-windows log)]
    (dotimes [w 4]
      (let [base (+ 0x8000 (* w 0x2000))]
        (aset windows w
              (or (some (fn [k]
                          (let [^js bank (aget banks (quot k 2))
                                start (* 8192 (mod k 2))]
                            (when (loop [i 0]
                                    (cond (= i 8192) true
                                          (not= (aget mem (+ base i)) (aget bank (+ start i))) false
                                          :else (recur (inc i))))
                              (* k 8192))))
                        (range n8k))
                  -1))))))

(defn bank-loaded!
  "The mapper put 16 KB bank `bank` at CPU `address` (`loadRomBank`)."
  [^js log bank address]
  (let [w (bit-shift-right (- address 0x8000) 13)]
    (when (<= 0 w 2)
      (aset (.-windows log) w (* bank 16384))
      (aset (.-windows log) (inc w) (+ (* bank 16384) 8192)))))

(defn bank8k-loaded!
  "The mapper put 8 KB bank `bank8k` at CPU `address` (`load8kRomBank`)."
  [^js log bank8k address prg-banks]
  (let [w (bit-shift-right (- address 0x8000) 13)]
    (when (<= 0 w 3)
      (aset (.-windows log) w (* 8192 (mod bank8k (* 2 prg-banks)))))))

(defn nmi!
  "The CPU is starting the NMI handler."
  [^js log]
  (set! (.-nmiDepth log) (inc (.-nmiDepth log)))
  ;; the next instruction is the handler's, not where an RTS went
  (set! (.-pending log) 0))

(defn frame! [^js log]
  (set! (.-frames log) (inc (.-frames log))))

(defn- offset-of
  "PRG ROM offset of CPU address `addr` ($8000 and up), or -1."
  [^js log addr]
  (let [base (aget (.-windows log) (bit-shift-right (- addr 0x8000) 13))]
    (if (neg? base) -1 (+ base (bit-and addr 0x1FFF)))))

(defn- mark! [^js arr i bits]
  (aset arr i (bit-or (aget arr i) bits)))

(defn- opcode-fetch! [^js log ^js cpu addr op off window-bits]
  (let [^js flags (.-flags log)
        ^js extra (.-extra log)
        ^js mem (.-mem cpu)
        size (aget sizes op)
        pending (.-pending log)]
    (mark! flags off (bit-or cl/flag-code window-bits))
    ;; its operand bytes are code too. (Marked here rather than when they're
    ;; read: jsnes skips reading a branch's offset when it isn't taken.)
    (loop [i 1]
      (when (< i size)
        (let [a (+ addr i)
              o (when (<= a 0xFFFF) (offset-of log a))]
          (when (and o (not (neg? o)))
            (mark! flags o (bit-or cl/flag-code (bit-shift-left (bit-shift-right (- a 0x8000) 13) 2)))))
        (recur (inc i))))
    (mark! extra off (bit-or cl/extra-instruction
                             (if (pos? (.-nmiDepth log)) cl/extra-nmi cl/extra-main)))
    ;; reached by JMP ($nnnn), or by an RTS that isn't returning from a JSR
    ;; (the 'push an address, then RTS' jump-table trick)
    (when (or (= pending 1)
              (and (= pending 2) (not= 0x20 (aget mem (- addr 3)))))
      (mark! flags off cl/flag-indirect-code))
    (set! (.-pending log) (case op 0x6C 1 0x60 2 0))
    (set! (.-opStart log) addr)
    (set! (.-opEnd log) (+ addr size))
    (case op
      ;; JSR: a subroutine starts at the target
      0x20 (let [target (bit-or (aget mem (+ addr 1)) (bit-shift-left (aget mem (+ addr 2)) 8))]
             (when (>= target 0x8000)
               (let [t (offset-of log target)]
                 (when-not (neg? t)
                   (mark! extra t cl/extra-subroutine)
                   (aset (.-calls log) t (inc (aget (.-calls log) t)))))))
      ;; RTI: leaving an interrupt handler
      0x40 (when (pos? (.-nmiDepth log))
             (set! (.-nmiDepth log) (dec (.-nmiDepth log))))
      nil)))

(defn read!
  "The CPU read `addr` from the cartridge (value `value`)."
  [^js log ^js cpu addr value]
  (when (>= addr 0x8000)
    (let [off (offset-of log addr)]
      (when-not (neg? off)
        (let [window-bits (bit-shift-left (bit-shift-right (- addr 0x8000) 13) 2)
              pc1 (bit-and (inc (.-REG_PC cpu)) 0xFFFF)]
          (cond
            (= addr pc1) (opcode-fetch! log cpu addr value off window-bits)
            (and (< (.-opStart log) addr) (< addr (.-opEnd log)))
            (mark! (.-flags log) off (bit-or cl/flag-code window-bits))
            :else
            (mark! (.-flags log) off (bit-or cl/flag-data window-bits))))))))

(defn install!
  "Start recording into `log` on `nes`'s current CPU and mapper (call again
  after jsnes replaces them, on reset)."
  [^js log ^js nes]
  (let [^js cpu (.-cpu nes)
        ^js mmap (.-mmap nes)
        cart (aget (.getPrototypeOf js/Object cpu) "loadFromCartridge")
        mproto (.getPrototypeOf js/Object mmap)
        load16k (aget mproto "loadRomBank")
        load8k (aget mproto "load8kRomBank")
        prg-banks (.. nes -rom -romCount)]
    (find-windows! log nes)
    (set! (.-loadFromCartridge cpu)
          (fn [addr]
            (let [v (.call cart cpu addr)]
              (read! log cpu addr v)
              v)))
    (set! (.-loadRomBank mmap)
          (fn [bank address]
            (bank-loaded! log bank address)
            (.call load16k mmap bank address)))
    (set! (.-load8kRomBank mmap)
          (fn [bank8k address]
            (bank8k-loaded! log bank8k address prg-banks)
            (.call load8k mmap bank8k address)))))

(defn uninstall!
  "Stop recording: jsnes's own methods take over again."
  [^js nes]
  (js-delete (.-cpu nes) "loadFromCartridge")
  (js-delete (.-mmap nes) "loadRomBank")
  (js-delete (.-mmap nes) "load8kRomBank"))
