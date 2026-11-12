(ns nes.cpu.disasm
  "Turning bytes of machine code back into readable 6502 assembly."
  (:require [nes.util :as util]
            [clojure.string :as str]
            [nes.cpu.opcodes :as ops]))

(defn hex
  "`n` as uppercase hex with a $ prefix, padded to `width` digits."
  [n width]
  (util/hex n width))

(defn- signed-byte [b]
  (if (>= b 0x80) (- b 0x100) b))

(defn decode
  "Decode the instruction at `addr`. `peek` is a function from an address
  to the byte there (or nil if it can't be read safely).

  Returns a map with `:addr`, `:bytes`, `:size`, `:mnemonic`, `:mode`,
  `:operand` (the 8- or 16-bit operand, if any) and, for branches,
  `:target`, and `:unofficial? true` for unofficial opcodes. The 12 that
  jam the CPU come back with `:mnemonic nil` and size 1."
  [peek addr]
  (let [op (peek addr)
        {:keys [mnemonic mode size] :as info} (when op (ops/all-opcodes op))]
    (if-not info
      {:addr addr :size 1 :bytes [op] :mnemonic nil}
      (let [bytes (mapv #(peek (bit-and 0xFFFF (+ addr %))) (range size))
            [_ lo hi] bytes
            operand (case size
                      1 nil
                      2 lo
                      3 (when (and lo hi) (bit-or lo (bit-shift-left hi 8))))]
        (cond-> {:addr addr :bytes bytes :size size
                 :mnemonic mnemonic :mode mode :operand operand}
          (:unofficial? info) (assoc :unofficial? true)
          (and (= mode :rel) lo)
          (assoc :target (bit-and 0xFFFF (+ addr 2 (signed-byte lo)))))))))

(defn operand-text
  "The operand part of an instruction in standard 6502 assembly syntax.

  `name-of` (optional) gives addresses names: (name-of instr addr) -> a
  name to show instead of the number, or nil. See nes.debug-info/namer."
  ([instr] (operand-text instr nil))
  ([{:keys [mode operand target] :as instr} name-of]
   (let [named (fn [addr number] (or (when name-of (name-of instr addr)) number))
         b #(if operand (named operand (hex operand 2)) "$??")
         w #(if operand (named operand (hex operand 4)) "$????")]
    (case mode
      :imp ""
      :acc "A"
      :imm (str "#" (if operand (hex operand 2) "$??"))
      :zp  (b)
      :zpx (str (b) ",X")
      :zpy (str (b) ",Y")
      :abs (w)
      :abx (str (w) ",X")
      :aby (str (w) ",Y")
      :ind (str "(" (w) ")")
      :izx (str "(" (b) ",X)")
      :izy (str "(" (b) "),Y")
      :rel (if target (named target (hex target 4)) "??")))))

(defn text
  "One decoded instruction as assembly text, like \"LDA #$01\". Unofficial
  ones get a * in front, as in nestest's log: \"*LAX $40\". With `name-of`
  (see `operand-text`), addresses that have names show them: \"STA PPUCTRL\"."
  ([instr] (text instr nil))
  ([{:keys [mnemonic bytes unofficial?] :as instr} name-of]
   (if mnemonic
     (str/trimr (str (when unofficial? "*") (str/upper-case (name mnemonic)) " " (operand-text instr name-of)))
     (str ".byte " (if-let [b (first bytes)] (hex b 2) "??")))))

(defn disassemble
  "Decode `n` instructions in a row, starting at `addr`."
  [peek addr n]
  (loop [addr addr, out []]
    (if (= n (count out))
      out
      (let [instr (decode peek addr)]
        (recur (bit-and 0xFFFF (+ addr (:size instr))) (conj out instr))))))
