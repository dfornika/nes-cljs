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

  Generators that need labels make fresh ones on every call. So to have
  the same code in two places, call the generator twice; putting one
  call's result in twice would define its labels twice, and the assembler
  will say so.

  Operands: where a generator reads a value (`src`), give it either an
  address (:points, 0x10) or an immediate number ([:imm 100]). Where it
  writes (`dst`), an address. 16-bit values are two bytes, low byte first,
  at addr and addr+1.

  Registers: the arithmetic and copying generators use A, loops count in
  X (or Y), `multiply` and `divide` use both A and X.
  gamedev-toolkit.analysis/clobbers will tell you what any piece
  of code changes, and `times` uses it to refuse a body that would
  overwrite its own counter. `preserving` saves and restores registers
  around code, but only the ones that code actually changes."
  ;; ClojureScript has a private helper called `divide`; ours replaces it here
  #?(:cljs (:refer-clojure :exclude [divide]))
  (:require [gamedev-toolkit.analysis :as analysis]
            [gamedev-toolkit.asm :as asm]))

;; ---------------------------------------------------------------------------
;; Operands
;;
;; Every generator here takes its operands in the same few shapes:
;;
;;   :score, 0x10, [:+ :oam 3]    the byte at an address (for 16-bit values,
;;                                the low byte; the high byte is the next)
;;   [:imm 5]                     a number
;;   (indexed :thing/x :x)        an entry in a table: the byte at the
;;                                address plus the X (or Y) register
;;   (pair lo hi)                 a 16-bit value whose two bytes aren't next
;;                                to each other: each an operand above. For
;;                                16-bit fields in a structure of arrays,
;;                                where the low and high bytes are in
;;                                different tables
;;
;; `lo` and `hi` pick the bytes of any of these, and `with-operand` makes
;; an instruction of one.

(defn- imm? [operand]
  (and (vector? operand) (= :imm (first operand))))

(defn indexed
  "An operand that's one entry of a table: the byte at `addr` plus the X
  (or Y) register: (move :speed (indexed :enemies/speed :x))"
  [addr register]
  {:addr addr :index register})

(defn- table-entry? [o] (and (map? o) (contains? o :addr)))

(defn pair
  "A 16-bit operand made of two one-byte operands, low byte first."
  [lo hi]
  {:lo lo :hi hi})

(defn pair?
  "Is `o` a 16-bit operand made with `pair` (or `wide`)?"
  [o]
  (and (map? o) (contains? o :lo)))

(defn lo
  "The operand for the low byte of 16-bit operand `o`."
  [o]
  (cond (imm? o) [:imm [:lo (second o)]]
        (pair? o) (:lo o)
        :else o))

(defn hi
  "The operand for the high byte of 16-bit operand `o`."
  [o]
  (cond (imm? o) [:imm [:hi (second o)]]
        (pair? o) (:hi o)
        (table-entry? o) (update o :addr (fn [a] [:+ a 1]))
        :else [:+ o 1]))

(defn wide
  "`o` (a 16-bit operand of any shape) as a `pair`: for generators that
  take either one byte or two and need to be told which."
  [o]
  (pair (lo o) (hi o)))

(defn with-operand
  "The instruction `mnemonic` on one-byte operand `operand`."
  [mnemonic operand]
  (cond (pair? operand)
        (throw (ex-info (str "A 16-bit operand where one byte was wanted: " (pr-str operand)
                             ". Pick a byte with lo or hi.")
                        {:operand operand}))
        (table-entry? operand) [mnemonic (:addr operand) (:index operand)]
        :else [mnemonic operand]))

(defn- op
  "An instruction on one byte of an operand: (op :lda lo x)"
  [mnemonic byte-of o]
  (with-operand mnemonic (byte-of o)))

;; ---------------------------------------------------------------------------
;; Moving and arithmetic. All of these use A.

(defn move
  "dst = src (one byte)."
  [dst src]
  [(with-operand :lda src) (with-operand :sta dst)])

(defn move16
  "dst = src (two bytes)."
  [dst src]
  [(move (lo dst) (lo src))
   (move (hi dst) (hi src))])

(defn add
  "dst = dst + src (one byte; it wraps around past 255). The carry flag
  is left set if it wrapped."
  [dst src]
  [[:clc] (with-operand :lda dst) (with-operand :adc src) (with-operand :sta dst)])

(defn subtract
  "dst = dst - src (one byte; it wraps around below 0). The carry flag is
  left CLEAR if it wrapped (on the 6502, carry means 'no borrow')."
  [dst src]
  [[:sec] (with-operand :lda dst) (with-operand :sbc src) (with-operand :sta dst)])

(defn add16
  "dst = dst + src (two bytes). ADC adds the carry flag in as well, which
  is how the carry out of the low bytes gets into the high bytes."
  [dst src]
  [[:clc]
   (op :lda lo dst) (op :adc lo src) (op :sta lo dst)
   (op :lda hi dst) (op :adc hi src) (op :sta hi dst)])

(defn sub16
  "dst = dst - src (two bytes)."
  [dst src]
  [[:sec]
   (op :lda lo dst) (op :sbc lo src) (op :sta lo dst)
   (op :lda hi dst) (op :sbc hi src) (op :sta hi dst)])

(defn inc16
  "dst = dst + 1 (two bytes). Changes no registers: INC works on memory."
  [dst]
  (let [done (asm/genlabel "inc16")]
    [(op :inc lo dst)
     [:bne done]             ; the low byte only becomes 0 by wrapping from 255
     (op :inc hi dst)
     [:label done]]))

(defn dec16
  "dst = dst - 1 (two bytes)."
  [dst]
  (let [no-borrow (asm/genlabel "dec16")]
    [(op :lda lo dst)
     [:bne no-borrow]        ; a low byte of 0 is about to wrap to 255: borrow
     (op :dec hi dst)
     [:label no-borrow]
     (op :dec lo dst)]))

(defn compare16
  "Compare two 16-bit values, leaving the flags as CMP does for bytes:
  zero set if a = b, carry set if a >= b (unsigned). So afterwards:

    (when-flag :eq ...)  a = b      (when-flag :cs ...)  a >= b
    (when-flag :ne ...)  a != b     (when-flag :cc ...)  a < b"
  [a b]
  (let [done (asm/genlabel "compare16")]
    [(op :lda hi a) (op :cmp hi b)
     [:bne done]             ; the high bytes differ: they decide it
     (op :lda lo a) (op :cmp lo b)
     [:label done]]))

;; ---------------------------------------------------------------------------
;; Control flow
;;
;; The 6502's only decisions are branches on its flags: BEQ jumps if the
;; zero flag is set, BCC if carry is clear, and so on. These generators
;; give the usual shapes (if, when, loops) made from them. A condition is
;; named after its branch instruction, or spelled out:
;;
;;   :eq :zero :equal          zero flag set: the last result was 0, or a
;;                             compare found the two equal
;;   :ne :not-zero :not-equal  zero flag clear
;;   :cs :carry      carry set: an add overflowed, or a compare found >=
;;   :cc :no-carry   carry clear
;;   :mi :negative   bit 7 of the last result was set
;;   :pl :positive   bit 7 clear
;;   :vs :vc         overflow set / clear

(def ^:private conditions
  {:eq :eq :zero :eq :equal :eq, :ne :ne :not-zero :ne :not-equal :ne
   :cs :cs :carry :cs, :cc :cc :no-carry :cc
   :mi :mi :negative :mi, :pl :pl :positive :pl
   :vs :vs, :vc :vc})

(def ^:private opposite
  {:eq :ne :ne :eq :cs :cc :cc :cs :mi :pl :pl :mi :vs :vc :vc :vs})

(defn- condition* [c]
  (or (conditions c)
      (throw (ex-info (str "Unknown condition " (pr-str c) ". Conditions: " (sort (keys conditions))) {}))))

(defn- branch-if [c] (keyword (str "b" (name (condition* c)))))
(defn- branch-unless [c] (keyword (str "b" (name (opposite (condition* c))))))

;; A branch instruction can only reach about 127 bytes either way (its
;; operand is one signed byte), a JMP anywhere. So to get past a big piece
;; of code, these generators branch the other way around a JMP instead:
;; 3 bytes and a few cycles more, and only when it's needed.

(def ^:private branch-reach
  "The most code (in bytes) to branch across. Sizes are counted without
  knowing which variables are in the zero page, so they can come out a
  little high; that only means a JMP is sometimes used a bit early."
  120)

(defn- far? [& code]
  (> (analysis/size code) branch-reach))

(defn- jump-if
  "Go to `label` if `c` holds; `far?` if it's too far for a branch."
  [c label far?]
  (if far?
    (let [stay (asm/genlabel "near")]
      [[(branch-unless c) stay]
       [:jmp label]
       [:label stay]])
    [(branch-if c) label]))

(defn when-flag
  "Run `body` only if `condition` holds (see above), given the flags as
  the code just before left them."
  [condition & body]
  (let [skip (asm/genlabel "skip")]
    [(jump-if (opposite (condition* condition)) skip (far? body))
     body
     [:label skip]]))

(defn if-flag
  "Run `then` if `condition` holds, otherwise `else`."
  [condition then else]
  (let [otherwise (asm/genlabel "else")
        done (asm/genlabel "endif")]
    [(jump-if (opposite (condition* condition)) otherwise (far? then [:jmp 0]))
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
     (jump-if condition again (far? body))]))

(defn forever
  "Run `body` over and over."
  [& body]
  (let [top (asm/genlabel "forever")]
    [[:label top]
     body
     [:jmp top]]))

(defn times
  "Run `body` `n` times, counting down in X. To count in Y instead, give
  {:with :y} before the body:

    (times 8 [:asl :n])
    (times 8 {:with :y} [:lda :table :x] ...)

  Up to 256 times, the counter goes n, n-1 ... 1 inside the body. More
  than that (up to 65536) takes both X and Y to count.

  The body mustn't change the registers doing the counting; that's
  checked when the ROM is built."
  [n & body]
  (let [[opts body] (if (map? (first body)) [(first body) (rest body)] [{} body])
        big? (and (int? n) (> n 256))
        counter (:with opts :x)
        counters (if big? [:x :y] [counter])
        again (asm/genlabel "times")
        long? (far? body)
        changed (analysis/clobbers body)]
    (when-not (#{:x :y} counter)
      (throw (ex-info ":with is :x or :y" {:with counter})))
    (when-not (and (int? n) (<= 1 n 65536))
      (throw (ex-info (str "times counts 1 to 65536 times, not " (pr-str n)) {:n n})))
    (when (and big? (:with opts))
      (throw (ex-info "Counting past 256 takes both X and Y, so there's no choosing with :with" {:n n})))
    (when-let [counter (some changed counters)]
      (throw (ex-info (str "The body of this loop changes " (name counter) ", which is counting the loop. "
                           (if big?
                             "(Counting past 256 takes both X and Y.) Wrap"
                             (str "Count in " (if (= counter :x) "Y with {:with :y}" "X") ", or wrap"))
                           " the part that changes it in (preserving [" counter "] ...).")
                      {:counter counter})))
    (if big?
      ;; X counts the odd ones first (or a whole 256 if there are none),
      ;; then Y counts whole rounds of 256.
      (let [odd (mod n 256)]
        [[:ldx [:imm odd]]
         [:ldy [:imm (mod (+ (quot n 256) (if (zero? odd) 0 1)) 256)]]
         [:label again]
         body
         [:dex]
         (jump-if :ne again long?)
         [:dey]
         (jump-if :ne again long?)])
      (let [[load decrement] (if (= counter :x) [:ldx :dex] [:ldy :dey])]
        ;; 256 times: start at 0, and the first decrement wraps it to 255
        [[load [:imm (mod n 256)]]
         [:label again]
         body
         [decrement]
         (jump-if :ne again long?)]))))

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
;;
;; `LDA table,X` reaches the 256 bytes after `table`, with X as the offset.
;; For more than that, the loop does several pages on each pass: the same
;; X, used against table, table+256, table+512...

(def ^:private most-bytes 4096)

(defn- check-count [n what]
  (when-not (and (int? n) (<= 1 n most-bytes))
    (throw (ex-info (str what " handles 1 to " most-bytes " bytes, not " (pr-str n)) {:n n}))))

(defn- each-byte
  "A loop over `n` bytes: (step offset) gives the instructions to handle
  the byte at [:+ address offset], indexed by X."
  [n step]
  (let [pages (quot n 256)
        odd (mod n 256)
        whole (asm/genlabel "pages")
        tail (asm/genlabel "bytes")]
    [(when (pos? pages)
       [[:ldx [:imm 0]]
        [:label whole]
        (for [page (range pages)] (step (* 256 page)))
        [:inx]
        ;; after 256, X has wrapped to 0 and INX has set the zero flag
        [:bne whole]])
     (when (pos? odd)
       [[:ldx [:imm 0]]
        [:label tail]
        (step (* 256 pages))
        [:inx]
        [:cpx [:imm odd]]
        [:bne tail]])]))

(defn- offset [addr n]
  (if (zero? n) addr [:+ addr n]))

(defn fill
  "Set `n` bytes starting at `dst` to `value` (a `src` operand: [:imm 0],
  or a variable)."
  [dst n value]
  (check-count n "fill")
  [[:lda value]
   (each-byte n (fn [at] [:sta (offset dst at) :x]))])

(defn copy
  "Copy `n` bytes from `src` to `dst` (both addresses)."
  [dst src n]
  (check-count n "copy")
  (each-byte n (fn [at] [[:lda (offset src at) :x]
                         [:sta (offset dst at) :x]])))

;; ---------------------------------------------------------------------------
;; Multiplying and dividing
;;
;; The 6502 can't do either, only add, subtract and shift bits left and
;; right (which doubles and halves). So multiplying is done as on paper,
;; in binary: for each 1 bit of one number, add the other, shifted along.
;; Dividing is long division the same way. Both loop 8 times, about 150
;; to 200 cycles in all, so games avoid them where a table or a shift
;; will do.

(defn multiply
  "dst (two bytes) = a x b (one byte each; `src` operands). Uses A and X.
  `dst` mustn't be the same variable as `b`."
  [dst a b]
  (let [again (asm/genlabel "multiply")
        skip (asm/genlabel "skip")]
    [[:lda a] [:sta (lo dst)]            ; a's bits come out of here, lowest first
     [:lda [:imm 0]]                     ; the high byte of the answer builds up in A
     [:ldx [:imm 8]]
     [:lsr (lo dst)]
     [:label again]
     [:bcc skip]                         ; this bit of a is 0: nothing to add
     [:clc] [:adc b]
     [:label skip]
     [:ror :a]                           ; shift the 16-bit answer right, as the
     [:ror (lo dst)]                     ; next bit of a comes out
     [:dex]
     [:bne again]
     [:sta (hi dst)]]))

(defn divide
  "quotient = a / b, rounded down (one byte each; a and b are `src`
  operands). The remainder is left in A, and stored in `remainder` if one
  is given. Uses A and X. Dividing by zero gives 255, remainder a."
  ([quotient a b] (divide quotient a b nil))
  ([quotient a b remainder]
   (let [again (asm/genlabel "divide")
         fits (asm/genlabel "fits")
         skip (asm/genlabel "skip")]
     [[:lda a] [:sta quotient]           ; a's bits come out the top of here...
      [:lda [:imm 0]]                    ; ...into A, the part being divided
      [:ldx [:imm 8]]
      [:label again]
      [:asl quotient]
      [:rol :a]
      [:bcs fits]                        ; (A overflowed: it's certainly big enough)
      [:cmp b]
      [:bcc skip]                        ; b doesn't go into it
      [:label fits]
      [:sbc b]                           ; it does: take it away,
      [:inc quotient]                    ; and that's a 1 in the answer
      [:label skip]
      [:dex]
      [:bne again]
      (when remainder [:sta remainder])])))

(defn- bits-of
  "The binary digits of `n`, highest first: 5 -> [1 0 1]."
  [n]
  (loop [n n, out ()]
    (if (zero? n) (vec out) (recur (quot n 2) (cons (mod n 2) out)))))

(defn multiply-a
  "A = A x `n`, for a number known when the ROM is built (0 to 255),
  using shifts and adds, which is much quicker than `multiply`. The answer
  wraps past 255. Unless `n` is a power of two (2, 4, 8...), it needs a
  byte to work in: give a variable as `scratch`."
  ([n] (multiply-a n nil))
  ([n scratch]
   (when-not (and (int? n) (<= 0 n 255))
     (throw (ex-info (str "multiply-a multiplies by 0 to 255, not " (pr-str n)) {:n n})))
   (let [[_ & more] (bits-of n)]
     (cond
       (zero? n) [[:lda [:imm 0]]]
       (every? zero? more) (vec (repeat (count more) [:asl :a]))
       (nil? scratch) (throw (ex-info (str "Multiplying by " n " needs a scratch variable: (multiply-a " n " :some-variable)") {:n n}))
       ;; A x 5 (binary 101) = ((A x 2) x 2) + A: double for every bit, and
       ;; add the original for every 1
       :else [[:sta scratch]
              (for [bit more]
                [[:asl :a]
                 (when (= 1 bit) [[:clc] [:adc scratch]])])]))))

(defn divide-a
  "A = A / `n`, rounded down, for `n` a power of two (2, 4, 8... 128):
  shifting right halves a number. For other divisors, use `divide` (its
  divisor can be [:imm n])."
  [n]
  (let [bits (when (and (int? n) (<= 1 n 128)) (bits-of n))]
    (when-not (and bits (every? zero? (rest bits)))
      (throw (ex-info (str "divide-a divides by a power of two, not " (pr-str n) ". For other numbers use divide.") {:n n})))
    (vec (repeat (dec (count bits)) [:lsr :a]))))

;; ---------------------------------------------------------------------------
;; Signed numbers
;;
;; A byte can be read as 0 to 255, or as -128 to 127 ('signed': 255 is
;; -1, 254 is -2...). Adding and subtracting work the same either way, so
;; `add` and `add16` need no signed versions. Comparing doesn't: as
;; unsigned numbers 255 > 1, as signed ones -1 < 1.

(defn compare-signed
  "Compare two signed bytes. Afterwards the negative flag says a < b:

    (when-flag :negative ...)  a < b      (when-flag :positive ...)  a >= b

  (It subtracts; the overflow flag says when the sign of the result is
  wrong, and the EOR puts it right.) The zero flag isn't reliable here:
  for equality use an ordinary CMP. Uses A."
  [a b]
  (let [ok (asm/genlabel "signed")]
    [[:sec] [:lda a] [:sbc b]
     [:bvc ok]
     [:eor [:imm 0x80]]
     [:label ok]]))

(defn compare16-signed
  "Compare two signed 16-bit values; the negative flag says a < b, as
  for `compare-signed`."
  [a b]
  (let [ok (asm/genlabel "signed")]
    [(op :lda lo a) (op :cmp lo b)       ; just for the borrow into the high bytes
     (op :lda hi a) (op :sbc hi b)
     [:bvc ok]
     [:eor [:imm 0x80]]
     [:label ok]]))

(defn add-signed-byte
  "dst (two bytes) = dst + src, where src is one signed byte (-128 to
  127): a small step forwards or backwards. Uses A."
  [dst src]
  (let [forwards (asm/genlabel "forwards")
        done (asm/genlabel "done")]
    [[:lda src]
     [:bpl forwards]
     [:dec (hi dst)]                     ; a negative byte is the same bits as (byte - 256)
     [:label forwards]
     [:clc] [:adc (lo dst)] [:sta (lo dst)]
     [:bcc done]
     [:inc (hi dst)]
     [:label done]]))

;; ---------------------------------------------------------------------------
;; More arithmetic for games

(defn negate16
  "dst = -dst (two bytes): turns a speed around. Uses A."
  [dst]
  [[:sec]
   [:lda [:imm 0]] (op :sbc lo dst) (op :sta lo dst)
   [:lda [:imm 0]] (op :sbc hi dst) (op :sta hi dst)])

(defn abs-difference
  "A = how far apart the bytes `a` and `b` are, whichever is bigger."
  [a b]
  [(with-operand :lda a) [:sec] (with-operand :sbc b)
   ;; carry clear means it went below zero: flip it to positive
   (when-flag :no-carry
     [:eor [:imm 0xFF]]
     [:adc [:imm 1]])])

(defn- when-close-across
  "Run `body` if the 16-bit values a and b are less than `distance` apart:
  that's when a - b + (distance - 1) is from 0 up to 2 x distance - 2,
  which is one unsigned comparison once it's worked out. Uses A and Y."
  [a b distance body]
  [[:sec]
   (op :lda lo a) (op :sbc lo b) [:tay]          ; (TAY leaves the carry alone)
   (op :lda hi a) (op :sbc hi b)
   [:pha]
   [:tya] [:clc] [:adc [:imm (dec distance)]] [:tay]
   [:pla]                                        ; (and so does PLA)
   [:adc [:imm 0]]
   (when-flag :zero                              ; not more than 255 apart
     [:tya]
     [:cmp [:imm (dec (* 2 distance))]]
     (when-flag :no-carry body))])

(defn when-close
  "Run `body` if two points are within `distance` of each other both
  across and down: a simple test for two things touching. Each point is
  [x y], as one-byte operands; or with x as a 16-bit operand (made with
  `pair` or `wide`), for points on a level wider than the screen (then it
  uses Y as well). Uses A."
  [[ax ay] [bx by] distance & body]
  (let [down [(abs-difference ay by)
              [:cmp [:imm distance]]
              (when-flag :no-carry body)]]
    (if (or (pair? ax) (pair? bx))
      (when-close-across (wide ax) (wide bx) distance down)
      [(abs-difference ax bx)
       [:cmp [:imm distance]]
       (when-flag :no-carry down)])))

(defn inc-decimal
  "Add 1 to a number kept as `n` decimal digits, one per byte, most
  significant first (as scores usually are on the NES: a digit is ready
  to show without dividing by ten). 999 wraps to 000. Uses A."
  [digits n]
  (let [done (asm/genlabel "counted")]
    [(for [i (range (dec n) -1 -1)
           :let [digit (if (zero? i) digits [:+ digits i])]]
       [[:inc digit]
        [:lda digit]
        [:cmp [:imm 10]]
        [:bne done]                      ; no carry into the next digit
        [:lda [:imm 0]]
        [:sta digit]])
     [:label done]]))

;; ---------------------------------------------------------------------------
;; Fixed point
;;
;; To move 1.5 pixels a frame, a position needs a fraction. The usual
;; trick: keep it as a 16-bit number of 256ths of a pixel. The high byte
;; is then the whole pixel (what the PPU wants) and the low byte the
;; fraction, and ordinary 16-bit adds do the arithmetic. gamedev-toolkit
;; .memory's :u8.8 and :s8.8 types are for these.

(defn fixed
  "A number as 8.8 fixed point, for immediates: (fixed 1.5) => 384, so
  (add16 :player/x [:imm (fixed 1.5)]) moves 1.5 to the right. Negative
  numbers work too."
  [x]
  (bit-and (int (Math/round (double (* x 256)))) 0xFFFF))

(defn whole
  "The operand for the whole-number byte of the 8.8 fixed-point variable
  at `addr` (its second byte): [:lda (whole :player/x)]"
  [addr]
  (hi addr))

(defn whole16
  "The whole number of a 16.8 fixed-point variable at `addr` (a :u16.8:
  a fraction byte, then two whole bytes), as a 16-bit `pair`: a position
  on a level up to 65,535 pixels wide."
  [addr]
  (pair [:+ addr 1] [:+ addr 2]))

(defn add-wide
  "dst = dst + v, where dst is a 16.8 fixed-point variable (three bytes:
  fraction, then a 16-bit whole number) and v is a signed 8.8 speed (any
  16-bit operand). The speed's sign is spread into the top byte: moving
  left by 1 adds $FF.FF00. Uses A."
  [dst v]
  [[:clc]
   [:lda dst] (op :adc lo v) [:sta dst]
   [:lda [:+ dst 1]] (op :adc hi v) [:sta [:+ dst 1]]
   (op :lda hi v)                        ; (LDA leaves the carry alone)
   (if-flag :negative
     [[:lda [:+ dst 2]] [:adc [:imm 0xFF]] [:sta [:+ dst 2]]]
     [[:lda [:+ dst 2]] [:adc [:imm 0]] [:sta [:+ dst 2]]])])

(defn clamp-fixed
  "Keep the 8.8 fixed-point variable at `addr` between the whole numbers
  `least` and `most`: if it's gone past either, put it back there. `body`
  (optional) also runs when that happens, e.g. to turn a speed around.
  Uses A."
  [addr least most & body]
  [[:lda (whole addr)]
   [:cmp [:imm least]]
   (when-flag :no-carry                  ; below `least`
     (move16 addr [:imm (fixed least)])
     body)
   [:lda (whole addr)]
   [:cmp [:imm (inc most)]]
   (when-flag :carry                     ; above `most`
     (move16 addr [:imm (fixed most)])
     body)])
