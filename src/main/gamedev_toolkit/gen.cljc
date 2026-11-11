(ns gamedev-toolkit.gen
  "Code generators: functions that return instructions.

  The 6502 does very little per instruction, so even 'add this 16-bit
  number to that one' is seven instructions, the same seven every time
  with different addresses. A function writes them out:

    (add16 :score :points)        ; score += points
    (add16 :score [:imm 100])     ; score += 100
    (times 8 (add16 :x :speed))   ; generators nest: it's all just data

  These run when the ROM is built, not on the NES. What the NES gets is
  the instructions, exactly as if they'd been typed out, so a generator
  costs nothing at run time.

  Operands: where a generator reads a value (`src`), give it either an
  address (:points, 0x10) or an immediate number ([:imm 100]). Where it
  writes (`dst`), an address. 16-bit values are two bytes, low byte first,
  at addr and addr+1.

  Registers: the arithmetic and copying generators use A, loops count in
  X (or Y). gamedev-toolkit.analysis/clobbers will tell you what any piece
  of code changes, and `times` uses it to refuse a body that would
  overwrite its own counter. `preserving` saves and restores registers
  around code, but only the ones that code actually changes."
  (:require [gamedev-toolkit.analysis :as analysis]
            [gamedev-toolkit.asm :as asm]))

;; ---------------------------------------------------------------------------
;; Operands

(defn- imm? [operand]
  (and (vector? operand) (= :imm (first operand))))

(defn lo
  "The operand for the low byte of 16-bit operand `o`."
  [o]
  (if (imm? o) [:imm [:lo (second o)]] o))

(defn hi
  "The operand for the high byte of 16-bit operand `o`."
  [o]
  (if (imm? o) [:imm [:hi (second o)]] [:+ o 1]))

;; ---------------------------------------------------------------------------
;; Moving and arithmetic. All of these use A.

(defn move
  "dst = src (one byte)."
  [dst src]
  [[:lda src] [:sta dst]])

(defn move16
  "dst = src (two bytes)."
  [dst src]
  [(move (lo dst) (lo src))
   (move (hi dst) (hi src))])

(defn add
  "dst = dst + src (one byte; it wraps around past 255). The carry flag
  is left set if it wrapped."
  [dst src]
  [[:clc] [:lda dst] [:adc src] [:sta dst]])

(defn subtract
  "dst = dst - src (one byte; it wraps around below 0). The carry flag is
  left CLEAR if it wrapped (on the 6502, carry means 'no borrow')."
  [dst src]
  [[:sec] [:lda dst] [:sbc src] [:sta dst]])

(defn add16
  "dst = dst + src (two bytes). ADC adds the carry flag in as well, which
  is how the carry out of the low bytes gets into the high bytes."
  [dst src]
  [[:clc]
   [:lda (lo dst)] [:adc (lo src)] [:sta (lo dst)]
   [:lda (hi dst)] [:adc (hi src)] [:sta (hi dst)]])

(defn sub16
  "dst = dst - src (two bytes)."
  [dst src]
  [[:sec]
   [:lda (lo dst)] [:sbc (lo src)] [:sta (lo dst)]
   [:lda (hi dst)] [:sbc (hi src)] [:sta (hi dst)]])

(defn inc16
  "dst = dst + 1 (two bytes). Changes no registers: INC works on memory."
  [dst]
  (let [done (asm/genlabel "inc16")]
    [[:inc (lo dst)]
     [:bne done]             ; the low byte only becomes 0 by wrapping from 255
     [:inc (hi dst)]
     [:label done]]))

(defn dec16
  "dst = dst - 1 (two bytes)."
  [dst]
  (let [no-borrow (asm/genlabel "dec16")]
    [[:lda (lo dst)]
     [:bne no-borrow]        ; a low byte of 0 is about to wrap to 255: borrow
     [:dec (hi dst)]
     [:label no-borrow]
     [:dec (lo dst)]]))

(defn compare16
  "Compare two 16-bit values, leaving the flags as CMP does for bytes:
  zero set if a = b, carry set if a >= b (unsigned). So afterwards:

    (when-flag :eq ...)  a = b      (when-flag :cs ...)  a >= b
    (when-flag :ne ...)  a != b     (when-flag :cc ...)  a < b"
  [a b]
  (let [done (asm/genlabel "compare16")]
    [[:lda (hi a)] [:cmp (hi b)]
     [:bne done]             ; the high bytes differ: they decide it
     [:lda (lo a)] [:cmp (lo b)]
     [:label done]]))

;; ---------------------------------------------------------------------------
;; Control flow
;;
;; The 6502's only decisions are branches on its flags: BEQ jumps if the
;; zero flag is set, BCC if carry is clear, and so on. These generators
;; give the usual shapes (if, when, loops) made from them. A condition is
;; named after its branch instruction, or spelled out:
;;
;;   :eq :zero       zero flag set: the last result was 0, or a compare found equal
;;   :ne :not-zero   zero flag clear
;;   :cs :carry      carry set: an add overflowed, or a compare found >=
;;   :cc :no-carry   carry clear
;;   :mi :negative   bit 7 of the last result was set
;;   :pl :positive   bit 7 clear
;;   :vs :vc         overflow set / clear

(def ^:private conditions
  {:eq :eq :zero :eq, :ne :ne :not-zero :ne
   :cs :cs :carry :cs, :cc :cc :no-carry :cc
   :mi :mi :negative :mi, :pl :pl :positive :pl
   :vs :vs, :vc :vc})

(def ^:private opposite
  {:eq :ne :ne :eq :cs :cc :cc :cs :mi :pl :pl :mi :vs :vc :vc :vs})

(defn- condition [c]
  (or (conditions c)
      (throw (ex-info (str "Unknown condition " (pr-str c) ". Conditions: " (sort (keys conditions))) {}))))

(defn- branch-if [c] (keyword (str "b" (name (condition c)))))
(defn- branch-unless [c] (keyword (str "b" (name (opposite (condition c))))))

(defn when-flag
  "Run `body` only if `condition` holds (see above), given the flags as
  the code just before left them."
  [condition & body]
  (let [skip (asm/genlabel "skip")]
    [[(branch-unless condition) skip]
     body
     [:label skip]]))

(defn if-flag
  "Run `then` if `condition` holds, otherwise `else`."
  [condition then else]
  (let [otherwise (asm/genlabel "else")
        done (asm/genlabel "endif")]
    [[(branch-unless condition) otherwise]
     then
     [:jmp done]
     [:label otherwise]
     else
     [:label done]]))

(defn do-while
  "Run `body`, then again for as long as `condition` holds afterwards.
  (The body always runs at least once.)"
  [condition & body]
  (let [again (asm/genlabel "again")]
    [[:label again]
     body
     [(branch-if condition) again]]))

(defn forever
  "Run `body` over and over."
  [& body]
  (let [top (asm/genlabel "forever")]
    [[:label top]
     body
     [:jmp top]]))

(defn times
  "Run `body` `n` times (1 to 256), counting down in X. To count in Y
  instead, give {:with :y} before the body:

    (times 8 [:asl :n])
    (times 8 {:with :y} [:lda :table :x] ...)

  Inside the body the counter goes n, n-1 ... 1. The body mustn't change
  the counter's register; that's checked when the ROM is built."
  [n & body]
  (let [[opts body] (if (map? (first body)) [(first body) (rest body)] [{} body])
        counter (:with opts :x)
        [load decrement] (case counter
                           :x [:ldx :dex]
                           :y [:ldy :dey]
                           (throw (ex-info ":with is :x or :y" {:with counter})))
        again (asm/genlabel "times")]
    (when-not (and (int? n) (<= 1 n 256))
      (throw (ex-info (str "times counts 1 to 256 times, not " (pr-str n)) {:n n})))
    (when (contains? (analysis/clobbers body) counter)
      (throw (ex-info (str "The body of this loop changes " (name counter) ", which is counting the loop. Count in "
                           (if (= counter :x) "Y with {:with :y}" "X")
                           ", or wrap the part that changes it in (preserving [" counter "] ...).")
                      {:counter counter})))
    ;; 256 times: start at 0, and the first decrement wraps it to 255
    [[load [:imm (mod n 256)]]
     [:label again]
     body
     [decrement]
     [:bne again]]))

;; ---------------------------------------------------------------------------
;; Saving registers

(defn preserving
  "Run `body`, but leave the registers in `regs` (some of :a :x :y) as
  they were before, by saving them on the stack first and restoring them
  after. Only registers that `body` actually changes are saved, so
  wrapping code 'just in case' costs nothing when there was no need.

  The 6502 can only push and pull A, so X and Y go through A: preserving
  :x or :y without :a loses what was in A.

  The body mustn't leave by jumping or returning past the end: what's
  been pushed has to be pulled again."
  [regs & body]
  (let [regs (set regs)
        changed (analysis/clobbers body)
        x? (boolean (and (regs :x) (changed :x)))
        y? (boolean (and (regs :y) (changed :y)))
        ;; saving X or Y changes A too
        a? (boolean (and (regs :a) (or (changed :a) x? y?)))]
    (when-not (every? analysis/all-registers regs)
      (throw (ex-info (str "Registers are :a, :x and :y, not " (pr-str (remove analysis/all-registers regs))) {})))
    (with-meta
      [(when a? [:pha])
       (when x? [[:txa] [:pha]])
       (when y? [[:tya] [:pha]])
       body
       (when y? [[:pla] [:tay]])
       (when x? [[:pla] [:tax]])
       (when a? [:pla])]
      ;; What this changes, all told. The instructions alone would say
      ;; 'A, X and Y' (PLA and TAX write registers), so say it here.
      {:clobbers (cond-> changed
                   x? (disj :x)
                   y? (disj :y)
                   (or x? y?) (conj :a)
                   a? (disj :a))})))

;; ---------------------------------------------------------------------------
;; Blocks of memory. These use A and X.

(defn- check-count [n what]
  (when-not (and (int? n) (<= 1 n 256))
    (throw (ex-info (str what " handles 1 to 256 bytes, not " (pr-str n)) {:n n}))))

(defn fill
  "Set `n` bytes (1 to 256) starting at `dst` to `value` (a `src`
  operand: [:imm 0], or a variable)."
  [dst n value]
  (check-count n "fill")
  (let [again (asm/genlabel "fill")]
    [[:lda value]
     [:ldx [:imm 0]]
     [:label again]
     [:sta dst :x]
     [:inx]
     ;; after 256, X has wrapped to 0 and INX has set the zero flag itself
     (when (< n 256) [:cpx [:imm n]])
     [:bne again]]))

(defn copy
  "Copy `n` bytes (1 to 256) from `src` to `dst` (both addresses)."
  [dst src n]
  (check-count n "copy")
  (let [again (asm/genlabel "copy")]
    [[:ldx [:imm 0]]
     [:label again]
     [:lda src :x]
     [:sta dst :x]
     [:inx]
     (when (< n 256) [:cpx [:imm n]])
     [:bne again]]))
