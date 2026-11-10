(ns nes.cpu.reference
  "The 6502's instructions in plain English, and explanations of a
  particular instruction (\"store A into PPUCTRL\").

  The mnemonics (LDA, BEQ...) are three-letter abbreviations from the
  1970s. They're short for what each instruction does: LDA = LoaD A,
  BEQ = Branch if EQual (to zero)."
  (:require [clojure.string :as str]
            [nes.cpu.opcodes :as ops]
            [nes.registers :as registers]))

(def categories
  "Instruction groups, in the order to show them."
  [[:load-store "Load and store" "Move bytes between memory and the registers"]
   [:transfer "Copy between registers" "Copy one register into another"]
   [:arithmetic "Arithmetic" "Add, subtract, count up and down"]
   [:logic "Bits" "Combine bits with AND, OR, XOR; test bits"]
   [:shift "Shift and rotate" "Move all the bits one place left or right (×2 or ÷2)"]
   [:compare "Compare" "Compare two numbers, setting the flags for a branch to look at"]
   [:branch "Branch" "Jump somewhere nearby, but only if a flag says so: how if/else and loops are made"]
   [:jump "Jump, call and return" "Go somewhere else in the program, or into and out of subroutines"]
   [:stack "Stack" "Save bytes on the stack ($0100-$01FF) and get them back"]
   [:flags "Flags" "Set or clear one of the status flags directly"]
   [:other "Other" ""]])

(def instructions
  "mnemonic -> {:name :category :does :flags :about}. `:does` is a short
  formula (M is the byte in memory the instruction uses); `:flags` the
  status flags it can change."
  {:lda {:name "Load A" :category :load-store :does "A = M" :flags "N Z"}
   :ldx {:name "Load X" :category :load-store :does "X = M" :flags "N Z"}
   :ldy {:name "Load Y" :category :load-store :does "Y = M" :flags "N Z"}
   :sta {:name "Store A" :category :load-store :does "M = A" :flags ""}
   :stx {:name "Store X" :category :load-store :does "M = X" :flags ""}
   :sty {:name "Store Y" :category :load-store :does "M = Y" :flags ""}

   :tax {:name "Copy A to X" :category :transfer :does "X = A" :flags "N Z"}
   :tay {:name "Copy A to Y" :category :transfer :does "Y = A" :flags "N Z"}
   :txa {:name "Copy X to A" :category :transfer :does "A = X" :flags "N Z"}
   :tya {:name "Copy Y to A" :category :transfer :does "A = Y" :flags "N Z"}
   :tsx {:name "Copy the stack pointer to X" :category :transfer :does "X = SP" :flags "N Z"}
   :txs {:name "Copy X to the stack pointer" :category :transfer :does "SP = X" :flags ""}

   :adc {:name "Add with carry" :category :arithmetic :does "A = A + M + C" :flags "N V Z C"
         :about "Clear the carry (CLC) first for a plain add. The carry out lets you add numbers bigger than a byte, one byte at a time."}
   :sbc {:name "Subtract with borrow" :category :arithmetic :does "A = A - M - (1 - C)" :flags "N V Z C"
         :about "Set the carry (SEC) first for a plain subtract."}
   :inc {:name "Increment memory" :category :arithmetic :does "M = M + 1" :flags "N Z"}
   :inx {:name "Increment X" :category :arithmetic :does "X = X + 1" :flags "N Z"}
   :iny {:name "Increment Y" :category :arithmetic :does "Y = Y + 1" :flags "N Z"}
   :dec {:name "Decrement memory" :category :arithmetic :does "M = M - 1" :flags "N Z"}
   :dex {:name "Decrement X" :category :arithmetic :does "X = X - 1" :flags "N Z"}
   :dey {:name "Decrement Y" :category :arithmetic :does "Y = Y - 1" :flags "N Z"}

   :and {:name "AND" :category :logic :does "A = A AND M" :flags "N Z"
         :about "Keeps only the bits set in both: used to pick out some bits ('masking')."}
   :ora {:name "OR" :category :logic :does "A = A OR M" :flags "N Z"
         :about "Sets the bits that are set in either: used to turn bits on."}
   :eor {:name "Exclusive OR" :category :logic :does "A = A XOR M" :flags "N Z"
         :about "Flips the bits that are set in M."}
   :bit {:name "Test bits" :category :logic :does "flags from A AND M" :flags "N V Z"
         :about "Z is set if A AND M is zero; N and V are copied from bits 7 and 6 of M. A isn't changed. Often used to check PPUSTATUS."}

   :asl {:name "Shift left" :category :shift :does "C ← [7..0] ← 0" :flags "N Z C"
         :about "Doubles the number. The top bit goes into the carry."}
   :lsr {:name "Shift right" :category :shift :does "0 → [7..0] → C" :flags "N Z C"
         :about "Halves the number. The bottom bit goes into the carry."}
   :rol {:name "Rotate left" :category :shift :does "C ← [7..0] ← C" :flags "N Z C"
         :about "Like ASL, but the old carry comes in at the bottom."}
   :ror {:name "Rotate right" :category :shift :does "C → [7..0] → C" :flags "N Z C"
         :about "Like LSR, but the old carry comes in at the top."}

   :cmp {:name "Compare with A" :category :compare :does "flags from A - M" :flags "N Z C"
         :about "Subtracts without keeping the result: Z set if equal, C set if A ≥ M."}
   :cpx {:name "Compare with X" :category :compare :does "flags from X - M" :flags "N Z C"}
   :cpy {:name "Compare with Y" :category :compare :does "flags from Y - M" :flags "N Z C"}

   :beq {:name "Branch if equal (zero)" :category :branch :does "if Z: jump" :flags ""}
   :bne {:name "Branch if not equal (not zero)" :category :branch :does "if not Z: jump" :flags ""}
   :bcs {:name "Branch if carry set" :category :branch :does "if C: jump" :flags ""
         :about "After a compare: if the first number was ≥ the second."}
   :bcc {:name "Branch if carry clear" :category :branch :does "if not C: jump" :flags ""
         :about "After a compare: if the first number was < the second."}
   :bmi {:name "Branch if minus" :category :branch :does "if N: jump" :flags ""}
   :bpl {:name "Branch if plus" :category :branch :does "if not N: jump" :flags ""}
   :bvs {:name "Branch if overflow set" :category :branch :does "if V: jump" :flags ""}
   :bvc {:name "Branch if overflow clear" :category :branch :does "if not V: jump" :flags ""}

   :jmp {:name "Jump" :category :jump :does "PC = address" :flags ""}
   :jsr {:name "Call a subroutine" :category :jump :does "push return address; PC = address" :flags ""
         :about "'Jump to SubRoutine'. RTS comes back to the instruction after."}
   :rts {:name "Return from subroutine" :category :jump :does "PC = pull return address" :flags ""}
   :rti {:name "Return from interrupt" :category :jump :does "pull flags; PC = pull address" :flags "all"
         :about "Ends an interrupt handler like the NMI."}
   :brk {:name "Break" :category :jump :does "interrupt through $FFFE" :flags "B I"
         :about "A software interrupt. Rare in NES games; often a sign the CPU has run into data."}

   :pha {:name "Push A" :category :stack :does "stack ← A" :flags ""}
   :php {:name "Push the flags" :category :stack :does "stack ← P" :flags ""}
   :pla {:name "Pull A" :category :stack :does "A ← stack" :flags "N Z"}
   :plp {:name "Pull the flags" :category :stack :does "P ← stack" :flags "all"}

   :clc {:name "Clear carry" :category :flags :does "C = 0" :flags "C"}
   :sec {:name "Set carry" :category :flags :does "C = 1" :flags "C"}
   :cli {:name "Clear interrupt disable" :category :flags :does "I = 0" :flags "I"}
   :sei {:name "Set interrupt disable" :category :flags :does "I = 1" :flags "I"
         :about "Usually the very first instruction a game runs."}
   :clv {:name "Clear overflow" :category :flags :does "V = 0" :flags "V"}
   :cld {:name "Clear decimal mode" :category :flags :does "D = 0" :flags "D"
         :about "Decimal mode doesn't exist on the NES's CPU, but games clear it anyway, out of habit."}
   :sed {:name "Set decimal mode" :category :flags :does "D = 1" :flags "D"}

   :nop {:name "No operation" :category :other :does "nothing" :flags ""}})

(def mode-notes
  "Addressing modes in plain English: where the instruction's operand is."
  {:imp "No operand: the instruction says it all."
   :acc "Works on A itself."
   :imm "The value itself, right in the instruction (the # means 'this number', not an address)."
   :zp "A byte in zero page ($0000-$00FF): one byte of address, so a little smaller and quicker. Games keep their busiest variables here."
   :zpx "Zero page address + X: for stepping through a small table."
   :zpy "Zero page address + Y."
   :abs "Any address: two bytes, low byte first."
   :abx "Address + X: for stepping through a table, X being the index."
   :aby "Address + Y: the same, with Y."
   :ind "JMP only: jump to the address stored at this address (a pointer)."
   :izx "(Indirect,X): add X to a zero page address, and use the 2-byte pointer stored there."
   :izy "(Indirect),Y: use the 2-byte pointer stored at a zero page address, plus Y. The usual way to follow a pointer."
   :rel "Branches: a distance to jump, -128 to +127 bytes from the next instruction."})

(defn modes-of
  "The addressing modes `mnemonic` comes in, with their opcode bytes:
  [[mode opcode] ...]."
  [mnemonic]
  (sort-by second (for [[op {m :mnemonic mode :mode}] ops/opcodes :when (= m mnemonic)] [mode op])))

;; ---------------------------------------------------------------------------
;; Explaining one instruction

(defn- hex [n width] (str "$" (.padStart (.toUpperCase (.toString n 16)) width "0")))

(defn- io
  "The I/O register at `addr`, if it is one: the one a write goes to, if
  `write?`. (Not $8000 and up: writes there go to the mapper, but reads
  are just ROM.)"
  ([addr] (io addr false))
  ([addr write?]
   (when (< addr 0x8000) (registers/register-at addr write?))))

(defn- place
  "An address in words: with the register's name for I/O."
  [addr write?]
  (if-let [{:keys [name]} (io addr write?)]
    (str name " (" (hex addr 4) ")")
    (hex addr 4)))

(defn- effective-address
  "The address instruction `ins` uses, with registers `regs` and memory
  `peek` as they are now. nil if it doesn't use one."
  [{:keys [mode operand]} {:keys [x y]} peek]
  (let [word (fn [a] (let [lo (peek a) hi (peek (bit-and (inc a) 0xFF))]
                       (when (and lo hi) (bit-or lo (bit-shift-left hi 8)))))]
    (case mode
      (:zp :abs) operand
      :zpx (bit-and (+ operand x) 0xFF)
      :zpy (bit-and (+ operand y) 0xFF)
      :abx (bit-and (+ operand x) 0xFFFF)
      :aby (bit-and (+ operand y) 0xFFFF)
      :izx (word (bit-and (+ operand x) 0xFF))
      :izy (some-> (word operand) (+ y) (bit-and 0xFFFF))
      nil)))

(defn- operand-words
  "What the operand of `ins` refers to, in words. With `now` ({:regs
  :peek}), work out the actual address and its current value."
  [{:keys [mnemonic mode operand] :as ins} now]
  (let [write? (contains? #{:sta :stx :sty} mnemonic)
        place #(place % write?)
        ea (when now (effective-address ins (:regs now) (:peek now)))
        value (when (and ea now (not (io ea))) ((:peek now) ea))
        value-words (when value (str " (now " (hex value 2) ")"))]
    (case mode
      :imm (str (hex operand 2) (when (> operand 9) (str " (" operand ")")))
      :acc "A"
      (:zp :abs) (if (io operand)
                   (place operand)
                   (str "the byte at " (place operand) value-words))
      (:zpx :abx) (str "the byte at " (hex operand (if (= mode :zpx) 2 4)) " + X"
                       (when ea (str " = " (place ea) value-words)))
      (:zpy :aby) (str "the byte at " (hex operand (if (= mode :zpy) 2 4)) " + Y"
                       (when ea (str " = " (place ea) value-words)))
      :izy (str "the byte at (the pointer at " (hex operand 2) ") + Y"
                (when ea (str " = " (place ea) value-words)))
      :izx (str "the byte at the pointer at " (hex operand 2) " + X"
                (when ea (str " = " (place ea) value-words)))
      "")))

(def ^:private branch-flag
  {:beq [:z true "the last result was zero"] :bne [:z false "the last result wasn't zero"]
   :bcs [:c true "the carry is set"] :bcc [:c false "the carry is clear"]
   :bmi [:n true "the last result was negative"] :bpl [:n false "the last result wasn't negative"]
   :bvs [:v true "the overflow flag is set"] :bvc [:v false "the overflow flag is clear"]})

(def ^:private flag-bit {:n 7 :v 6 :z 1 :c 0})

(defn explain
  "Instruction `ins` (from nes.cpu.disasm/decode) in plain English. Pass
  `now` = {:regs (cpu registers) :peek (fn [addr] byte)} for the
  instruction about to run, to include the actual addresses, values and
  whether a branch will be taken."
  ([ins] (explain ins nil))
  ([{:keys [mnemonic mode operand target] :as ins} now]
   (let [o #(operand-words ins now)
         to (fn [verb] (str verb " " (o)))]
     (if-not mnemonic
       "Not an instruction (probably data)"
       (case mnemonic
         :lda (str "Load A with " (o))
         :ldx (str "Load X with " (o))
         :ldy (str "Load Y with " (o))
         :sta (str "Store A into " (o))
         :stx (str "Store X into " (o))
         :sty (str "Store Y into " (o))
         (:tax :tay :txa :tya :tsx :txs :pha :php :pla :plp :clc :sec :cli :sei :clv :cld :sed :nop :rts :rti :brk
          :inx :iny :dex :dey)
         (get-in instructions [mnemonic :name])
         :adc (to "Add (with carry) to A:")
         :sbc (to "Subtract (with borrow) from A:")
         :inc (str "Add 1 to " (o))
         :dec (str "Subtract 1 from " (o))
         :and (to "A = A AND")
         :ora (to "A = A OR")
         :eor (to "A = A XOR")
         :bit (to "Test bits of")
         :asl (str "Shift left " (o))
         :lsr (str "Shift right " (o))
         :rol (str "Rotate left " (o))
         :ror (str "Rotate right " (o))
         :cmp (to "Compare A with")
         :cpx (to "Compare X with")
         :cpy (to "Compare Y with")
         :jmp (if (= mode :ind)
                (str "Jump to the address stored at " (hex operand 4)
                     (when-let [p (:peek now)]
                       (let [lo (p operand) hi (p (bit-or (bit-and operand 0xFF00) (bit-and (inc operand) 0xFF)))]
                         (when (and lo hi) (str " (now " (hex (bit-or lo (bit-shift-left hi 8)) 4) ")")))))
                (str "Jump to " (hex operand 4)))
         :jsr (str "Call the subroutine at " (hex operand 4))
         (:beq :bne :bcs :bcc :bmi :bpl :bvs :bvc)
         (let [[flag want words] (branch-flag mnemonic)
               taken? (when-let [p (get-in now [:regs :p])]
                        (= want (bit-test p (flag-bit flag))))]
           (str "If " words ", jump to " (hex target 4)
                (case taken? true " — it will" false " — it won't" nil)))
         (str/upper-case (name mnemonic)))))))
