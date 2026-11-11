(ns nes.debug-info
  "Names for the addresses in a game: its variables and the labels in its
  code. With them the disassembly can say `STA player.x` instead of
  `STA $04`, and you can set a breakpoint on `main`.

  A game built here with gamedev-toolkit brings its names with it: the
  assembler knows every label, and the allocator every variable (see
  `symbol-table` and `lens-fields`, which read what a game's `build`
  returns). Other games have only the names every NES game shares: the
  hardware registers, like PPUCTRL at $2000.

  Tables are kept per ROM, by its SHA-1 (as lenses are)."
  (:require [nes.registers :as registers]))

(defn display-name
  "A symbol as it's shown: :main -> \"main\", :player/x -> \"player.x\"."
  [k]
  (if (keyword? k)
    (if-let [n (namespace k)] (str n "." (name k)) (name k))
    (str k)))

;; ---------------------------------------------------------------------------
;; From a build: a lens, and a table of names

(defn- lens-spec
  "A one-value type at `addr` as a lens field spec: :u8 -> [:u8 addr],
  [:enum names] -> [:enum addr names]."
  [t addr]
  (if (vector? t) (into [(first t) addr] (rest t)) [t addr]))

(defn lens-fields
  "The variables in `memory` (from gamedev-toolkit.memory/allocate) as a
  lens: [[name spec] ...], in the order they were declared (see nes.lens).
  A structure of arrays becomes one array per field, named like
  :enemies.x."
  [memory]
  (vec
   (mapcat
    (fn [{:keys [name kind type addr hide]}]
      (when-not hide
        (case kind
          :scalar [[name (lens-spec type addr)]]
          :array (let [[_ n elem] type] [[name [:array addr n elem]]])
          :struct [[name [:struct addr (vec (apply concat (second type)))]]]
          :soa (let [[_ n fields] type]
                 (map-indexed (fn [i [k t]]
                                [(keyword (str (clojure.core/name name) "." (clojure.core/name k)))
                                 [:array (+ addr (* i n)) n t]])
                              fields)))))
    (:variables memory))))

(defn- byte-names
  "Names for `size` bytes from `addr`: base, base+1, base+2..."
  [base addr size]
  (for [i (range size)]
    [(+ addr i) (if (zero? i) base (str base "+" i))]))

(defn- variable-names
  "[[addr name] ...] for every byte of a variable."
  [{:keys [name kind type addr size]}]
  (let [base (display-name name)
        size-of #(if (= % :u16) 2 1)]
    (case kind
      (:scalar :array) (byte-names base addr size)
      :struct (first (reduce (fn [[out at] [k t]]
                               [(into out (byte-names (str base "." (clojure.core/name k)) at (size-of t)))
                                (+ at (size-of t))])
                             [[] addr]
                             (second type)))
      :soa (let [[_ n fields] type]
             (mapcat (fn [i [k _]] (byte-names (str base "." (clojure.core/name k)) (+ addr (* i n)) n))
                     (range) fields)))))

(defn symbol-table
  "The names in a game built with gamedev-toolkit (what its `build`
  returns: :labels from the assembler, :memory from the allocator):

    {:names     {address \"name\"}    every byte of every variable, and every label
     :labels    {address \"name\"}    just the labels in the code
     :docs      {address \"...\"}     what subroutines and variables are for
     :addresses {\"name\" address}}"
  [{:keys [labels memory subroutines]}]
  (let [label-names (into {} (for [[k addr] labels] [addr (display-name k)]))]
    {:names (into label-names (mapcat variable-names (:variables memory)))
     :labels label-names
     :docs (into {} (concat (for [{:keys [name doc]} subroutines :when doc] [(labels name) doc])
                            (for [{:keys [addr doc]} (:variables memory) :when doc] [addr doc])))
     :addresses (into {} (for [[k addr] (merge (:symbols memory) labels)] [(display-name k) addr]))}))

;; ---------------------------------------------------------------------------
;; Which table goes with which game

;; {sha1 table}
(defonce registry (atom {}))

(defn register!
  "Use `table` (see `symbol-table`) for the ROM with this SHA-1."
  [sha1 table]
  (swap! registry assoc sha1 table)
  sha1)

(defn table-for
  "The symbol table for the ROM with this SHA-1, or nil."
  [sha1]
  (get @registry sha1))

;; ---------------------------------------------------------------------------
;; Looking names up

(defn address
  "The address of `x` in `table`: a keyword (:player/x), a name as shown
  (\"player.x\"), or nil if there's no such name."
  [table x]
  (get-in table [:addresses (display-name x)]))

(defn name-at
  "What `addr` is called: its name in `table` (which can be nil), or if
  it's a hardware register, the register's name. `write?` picks between
  the two registers at $4017 (and similar). nil if it has no name."
  ([table addr] (name-at table addr false))
  ([table addr write?]
   (or (get-in table [:names addr])
       (when (< addr 0x8000)
         (:name (registers/register-at addr write?))))))

(defn label-at
  "The label on the code at `addr`, if any."
  [table addr]
  (get-in table [:labels addr]))

(defn doc-at
  "What the game's source says the subroutine or variable at `addr` is
  for, if anything."
  [table addr]
  (get-in table [:docs addr]))

(def ^:private stores #{:sta :stx :sty :sax})

(defn namer
  "A function for nes.cpu.disasm's `name-of` argument: (f instruction addr)
  -> the name to show for an address in that instruction's operand."
  [table]
  (fn [{:keys [mnemonic]} addr]
    (name-at table addr (contains? stores mnemonic))))
