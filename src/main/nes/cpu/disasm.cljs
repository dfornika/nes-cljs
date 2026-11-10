(ns nes.cpu.disasm
  "Turning bytes of machine code back into readable 6502 assembly."
  (:require [clojure.string :as str]
            [nes.cpu.opcodes :as ops]))

(defn hex
  "`n` as uppercase hex with a $ prefix, padded to `width` digits."
  [n width]
  (str "$" (.padStart (.toUpperCase (.toString n 16)) width "0")))

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
  "The operand part of an instruction in standard 6502 assembly syntax."
  [{:keys [mode operand target]}]
  (let [b #(if operand (hex operand 2) "$??")
        w #(if operand (hex operand 4) "$????")]
    (case mode
      :imp ""
      :acc "A"
      :imm (str "#" (b))
      :zp  (b)
      :zpx (str (b) ",X")
      :zpy (str (b) ",Y")
      :abs (w)
      :abx (str (w) ",X")
      :aby (str (w) ",Y")
      :ind (str "(" (w) ")")
      :izx (str "(" (b) ",X)")
      :izy (str "(" (b) "),Y")
      :rel (if target (hex target 4) "??"))))

(defn text
  "One decoded instruction as assembly text, like \"LDA #$01\". Unofficial
  ones get a * in front, as in nestest's log: \"*LAX $40\"."
  [{:keys [mnemonic bytes unofficial?] :as instr}]
  (if mnemonic
    (str/trimr (str (when unofficial? "*") (str/upper-case (name mnemonic)) " " (operand-text instr)))
    (str ".byte " (if-let [b (first bytes)] (hex b 2) "??"))))

(defn disassemble
  "Decode `n` instructions in a row, starting at `addr`."
  [peek addr n]
  (loop [addr addr, out []]
    (if (= n (count out))
      out
      (let [instr (decode peek addr)]
        (recur (bit-and 0xFFFF (+ addr (:size instr))) (conj out instr))))))
