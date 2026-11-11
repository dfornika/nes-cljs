(ns gamedev-toolkit.asm
  "A 6502 assembler whose source is Clojure data.

  A program is a sequence of instructions, each a vector: the mnemonic,
  then the operand. The operand's shape says which addressing mode to use,
  much as it does in ordinary assembly text:

    [:clc]                    CLC            no operand
    [:asl :a]                 ASL A          the accumulator
    [:lda [:imm 0x01]]        LDA #$01       immediate: the number itself
    [:lda 0x10]               LDA $10        the byte at an address
    [:lda :player-x]          LDA player_x   ...or at a named address
    [:lda :table :x]          LDA table,X    indexed by X (or :y)
    [:jmp [:ind :vector]]     JMP (vector)   indirect
    [:lda [:ind 0x10 :x]]     LDA ($10,X)    indexed indirect
    [:lda [:ind 0x10] :y]     LDA ($10),Y    indirect indexed
    [:bne :loop]              BNE loop       branches take a label

  Besides instructions there are a few directives:

    [:label :loop]            name the current address
    [:org 0xFFFA]             skip ahead to an address (filling the gap)
    [:db 1 2 \"HI\" [3 4]]      bytes (numbers, strings, nested sequences)
    [:dw :reset 0x1234]       16-bit words, low byte first (the 6502's order)

  Anywhere a number goes, an expression can go instead:

    :name                     a label, or a symbol from the :symbols option
    [:lo e]  [:hi e]          the low or high byte of a 16-bit value
    [:+ e ...]  [:- e ...]    sums and differences, e.g. [:+ :oam 3]

  Sequences nest, and nils are skipped, so code can be built with ordinary
  functions (`for`, `when`, `concat`...): a function that returns
  instructions is a macro for the assembler.

    (defn add-to [dst n] [[:lda dst] [:clc] [:adc [:imm n]] [:sta dst]])
    (assemble [(add-to :x 2) (add-to :y 3)] {:symbols {:x 0x10 :y 0x11}})

  Zero page or absolute? Addresses $00-$FF (the 'zero page') have shorter,
  faster instructions. The assembler uses them when it knows the address
  before assembling: a number, or a name from :symbols. Labels in the code
  itself always get a full 16-bit address. [:zp e] and [:abs e] force one
  or the other.

  Nothing here knows about the NES; see gamedev-toolkit.rom for that."
  (:require [clojure.string :as str]
            [nes.cpu.opcodes :as ops]))

(def ^:private modes-of
  "mnemonic -> the set of addressing modes it has (official opcodes only)."
  (reduce (fn [m [mnemonic mode]] (update m mnemonic (fnil conj #{}) mode))
          {}
          (keys ops/opcode-for)))

(def ^:private reserved #{:a :x :y})

(defn- hex [n]
  (let [s #?(:clj (Long/toHexString n) :cljs (.toString n 16))]
    (str "$" (str/upper-case s))))

(defn- fail
  ([message form] (fail message form {}))
  ([message form data]
   (throw (ex-info (str message "\n  in " (pr-str form)) (assoc data :form form)))))

(defn genlabel
  "A fresh label that can't clash with any other, for code generators
  that need a label of their own: (genlabel \"loop\") -> :loop__42"
  [prefix]
  (keyword (str (gensym (str prefix "__")))))

;; ---------------------------------------------------------------------------
;; Flattening: nested sequences of forms -> one flat vector of forms

(defn- form?
  "An instruction or directive: a vector that starts with a keyword."
  [x]
  (and (vector? x) (keyword? (first x))))

(defn- flatten-forms [x]
  (cond
    (nil? x) []
    (form? x) [x]
    (sequential? x) (into [] (mapcat flatten-forms) x)
    :else (throw (ex-info (str "Not an instruction: " (pr-str x)
                               ". Instructions are vectors like [:lda [:imm 1]].")
                          {:form x}))))

;; ---------------------------------------------------------------------------
;; Expressions

(defn- evaluate
  "The value of expression `e`, looking names up with `lookup`. nil if a
  name isn't known (yet)."
  [e lookup form]
  (cond
    (number? e) e
    (keyword? e) (lookup e)
    (form? e) (let [[op & args] e
                    vs (map #(evaluate % lookup form) args)]
                (when-not (#{:lo :hi :+ :-} op)
                  (fail (str "Unknown expression " (pr-str e) ". There are [:lo e], [:hi e], [:+ e ...] and [:- e ...]") form))
                (when (and (#{:lo :hi} op) (not= 1 (count args)))
                  (fail (str (pr-str e) " takes one value") form))
                (when (every? some? vs)
                  (case op
                    :lo (bit-and (first vs) 0xFF)
                    :hi (bit-and (bit-shift-right (first vs) 8) 0xFF)
                    :+ (apply + vs)
                    :- (apply - vs))))
    :else (fail (str "Expected a number, a name or an expression, not " (pr-str e)) form)))

;; ---------------------------------------------------------------------------
;; Operands -> addressing modes

(defn- parse-operand
  "The shape of an instruction's operand: {:kind :expr :index :force}."
  [[_ & args :as form]]
  (let [[a b] args
        tagged? (fn [tag] (and (vector? a) (= tag (first a))))]
    (cond
      (> (count args) 2) (fail "Too many operands" form)
      (empty? args) {:kind :none}
      (= args [:a]) {:kind :acc}
      (tagged? :imm) (if (and (= 2 (count a)) (nil? b))
                       {:kind :imm :expr (second a)}
                       (fail "Immediate operands look like [:imm value]" form))
      (tagged? :ind) (let [[_ e idx & more] a]
                       (cond
                         (or more (nil? e)) (fail "Indirect operands look like [:ind addr], [:ind addr :x] or [:ind addr] :y" form)
                         (and (= idx :x) (nil? b)) {:kind :izx :expr e}
                         (and (nil? idx) (= b :y)) {:kind :izy :expr e}
                         (and (nil? idx) (nil? b)) {:kind :ind :expr e}
                         :else (fail "Indirect operands look like [:ind addr], [:ind addr :x] or [:ind addr] :y" form)))
      :else (let [force (when (or (tagged? :zp) (tagged? :abs)) (first a))]
              (when-not (contains? #{nil :x :y} b)
                (fail (str "An address can be indexed by :x or :y, not " (pr-str b)) form))
              (when (and (nil? b) (= 2 (count args)))
                (fail "An address can be indexed by :x or :y, not nil" form))
              {:kind :addr :expr (if force (second a) a) :index b :force force}))))

(defn- choose-mode
  "The addressing mode for `mnemonic` with this operand. `early` looks up
  names that are known before assembling (the :symbols)."
  [mnemonic {:keys [kind expr index force]} early form]
  (let [has? (modes-of mnemonic)
        mode (case kind
               :none (cond (has? :imp) :imp
                           (has? :acc) :acc
                           :else (fail (str (str/upper-case (name mnemonic)) " needs an operand") form))
               :addr (if (has? :rel)
                       (if (or index force) (fail "A branch takes just a label" form) :rel)
                       (let [[zp abs] (case index nil [:zp :abs], :x [:zpx :abx], :y [:zpy :aby])
                             v (evaluate expr early form)]
                         (cond
                           (= force :zp) zp
                           (= force :abs) abs
                           (and v (<= 0 v 0xFF) (has? zp)) zp
                           (has? abs) abs
                           (has? zp) zp
                           :else abs)))
               kind)]
    (when-not (has? mode)
      (fail (str (str/upper-case (name mnemonic)) " has no " (str/lower-case (:name (ops/modes mode)))
                 " mode (like " (:example (ops/modes mode)) "). It has: "
                 (str/join ", " (sort (map #(:name (ops/modes %)) has?))))
            form {:mode mode}))
    mode))

;; ---------------------------------------------------------------------------
;; Pass 1: decide every form's size, and so every label's address

(defn- data-items
  "The values in a :db form, flattened: strings become their characters'
  codes, nested sequences are spliced in. Expressions are left alone."
  [args]
  (mapcat (fn [x]
            (cond
              (string? x) (map #?(:clj int :cljs #(.charCodeAt % 0)) x)
              (form? x) [x]
              (sequential? x) (data-items x)
              (nil? x) []
              :else [x]))
          args))

(defn- layout
  "Go through the forms, working out where each one goes.
  Returns {:items [{:form :addr :size ...}] :labels {name addr} :end addr}."
  [forms org symbols]
  (let [early #(get symbols %)]
    (reduce
     (fn [{:keys [pc labels] :as acc} [op & args :as form]]
       (let [place (fn [item]
                     (-> acc
                         (update :items conj (assoc item :form form :addr pc))
                         (update :pc + (:size item))))]
         (case op
           :label (let [[k] args]
                    (cond
                      (or (not= 1 (count args)) (not (keyword? k))) (fail "Labels look like [:label :name]" form)
                      (reserved k) (fail (str (pr-str k) " means a register, so it can't be a label") form)
                      (contains? labels k) (fail (str "The label " (pr-str k) " is defined twice") form)
                      (contains? symbols k) (fail (str (pr-str k) " is already one of the :symbols") form)
                      :else (assoc-in acc [:labels k] pc)))
           :org (let [target (evaluate (first args) early form)]
                  (cond
                    (nil? target) (fail "[:org addr] needs an address that's known before assembling (a number, or one of the :symbols)" form)
                    (< target pc) (fail (str "Can't go back to " (hex target) ": already at " (hex pc)
                                             ". (The code before this is " (- pc target) " bytes too big.)")
                                        form)
                    :else (place {:type :fill :size (- target pc)})))
           :db (let [items (vec (data-items args))]
                 (place {:type :bytes :exprs items :size (count items)}))
           :dw (place {:type :words :exprs (vec args) :size (* 2 (count args))})
           (if-not (modes-of op)
             (fail (str "Unknown instruction " (pr-str op)) form)
             (let [operand (parse-operand form)
                   mode (choose-mode op operand early form)]
               (place {:type :instruction :mnemonic op :mode mode :expr (:expr operand)
                       :size (:size (ops/modes mode))}))))))
     {:pc org :labels {} :items []}
     forms)))

;; ---------------------------------------------------------------------------
;; Pass 2: with every label known, turn each form into bytes

(defn- lo [v] (bit-and v 0xFF))
(defn- hi [v] (bit-and (bit-shift-right v 8) 0xFF))

(defn- byte-value [v form]
  (if (<= -128 v 0xFF)
    (lo v)
    (fail (str v " doesn't fit in a byte") form)))

(defn- word-value [v form]
  (if (<= 0 v 0xFFFF)
    v
    (fail (str v " isn't a 16-bit address") form)))

(defn- encode
  "One laid-out item as a vector of bytes."
  [{:keys [type form addr size mnemonic mode expr exprs]} lookup fill]
  (let [value #(or (evaluate % lookup form)
                   (fail (str "Nothing is called " (pr-str %)) form))]
    (case type
      :fill (vec (repeat size fill))
      :bytes (mapv #(byte-value (value %) form) exprs)
      :words (vec (mapcat #(let [v (word-value (value %) form)] [(lo v) (hi v)]) exprs))
      :instruction
      (let [opcode (ops/opcode-for [mnemonic mode])]
        (case size
          1 [opcode]
          2 (let [v (value expr)]
              [opcode
               (cond
                 (= mode :rel)
                 (let [offset (- v (+ addr 2))]
                   (if (<= -128 offset 127)
                     (lo offset)
                     (fail (str "That branch is too far: " offset " bytes, and a branch reaches -128 to 127."
                                " Branch the other way around a [:jmp ...] instead.")
                           form)))
                 (= mode :imm) (byte-value v form)
                 (<= 0 v 0xFF) v
                 :else (fail (str (hex v) " isn't in the zero page ($00-$FF)") form))])
          3 (let [v (word-value (value expr) form)]
              [opcode (lo v) (hi v)]))))))

(defn assemble
  "Assemble `forms` (see the namespace docstring) into machine code.

  Options:
    :org      the address of the first byte (default $8000)
    :symbols  a map of names to numbers, known before assembling: variables'
              addresses, hardware registers, constants
    :fill     the byte [:org] fills gaps with (default $FF)

  Returns:
    :bytes    the machine code, a vector of numbers 0-255
    :org      where it starts
    :labels   label -> address
    :listing  what each form became: [{:addr :bytes :form} ...]

  Throws an ex-info naming the offending form if something is wrong."
  ([forms] (assemble forms {}))
  ([forms {:keys [org symbols fill] :or {org 0x8000 symbols {} fill 0xFF}}]
   (doseq [k (keys symbols)]
     (when (reserved k)
       (throw (ex-info (str (pr-str k) " means a register, so it can't be a symbol") {:symbol k}))))
   (let [{:keys [items labels]} (layout (flatten-forms forms) org symbols)
         names (merge symbols labels)
         listing (mapv (fn [item]
                         {:addr (:addr item)
                          :form (:form item)
                          :bytes (encode item #(get names %) fill)})
                       items)]
     {:bytes (into [] (mapcat :bytes) listing)
      :org org
      :labels labels
      :listing listing})))
