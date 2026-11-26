(ns nes.inspect.debug-info
  "Names for the addresses in a game: its variables and the labels in its
  code. With them the disassembly can say `STA player.x` instead of
  `STA $04`, and you can set a breakpoint on `main`.

  A ROM file is only bytes: the names were in the source, and the
  assembler threw them away. So a game can bring them along in a second
  file, a 'names file' beside its .nes file (as ca65's .dbg files do for
  the games it builds). The games built with cartridj
  (https://github.com/dfornika/nes-gamedev-toolkit-clj) write one; the
  sample games in resources/public/roms/cartridj have theirs. It's EDN:

    {:name        \"hopper.nes\"
     :labels      {:main 0x8012 ...}              ; places in the code
     :memory      {:variables [{:name :kind :type :addr :size :doc} ...]
                   :symbols   {:player/x 0x04 ...}}  ; what's where in RAM
     :subroutines [{:name :doc} ...]}               ; what the code is for

  Other games have only the names every NES game shares: the hardware
  registers, like PPUCTRL at $2000.

  Tables are kept per ROM, by its SHA-1 (as lenses are)."
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [nes.inspect.lens :as lens]
            [nes.io-registers :as registers]))

(defn display-name
  "A symbol as it's shown: :main -> \"main\", :player/x -> \"player.x\"."
  [k]
  (if (keyword? k)
    (if-let [n (namespace k)] (str n "." (name k)) (name k))
    (str k)))

;; ---------------------------------------------------------------------------
;; From a names file: a lens, and a table of names

(defn- lens-spec
  "A one-value type at `addr` as a lens field spec: :u8 -> [:u8 addr],
  [:enum names] -> [:enum addr names]."
  [t addr]
  (if (vector? t) (into [(first t) addr] (rest t)) [t addr]))

(defn lens-fields
  "The variables in `memory` (a names file's :memory) as a lens: [[name spec] ...], in the order they were declared (see nes.inspect.lens).
  A structure of arrays becomes one array per field, named like
  :enemies.x. So does a struct that wouldn't fit on one line of the Lens
  tab: one with more than four fields, or with a field of named bits (a
  row of checkboxes each), like :pad.held."
  [memory]
  (vec
   (mapcat
    (fn [{:keys [name kind type addr hide]}]
      (when-not hide
        (case kind
          :scalar [[name (lens-spec type addr)]]
          :array (let [[_ n elem] type] [[name [:array addr n elem]]])
          :struct (if (or (> (count (second type)) 4)
                          (some #(and (vector? (second %)) (= :bits (first (second %)))) (second type)))
                    ;; one field each, at the addresses the struct gives them
                    (first (reduce (fn [[out at] [k t]]
                                     [(conj out [(keyword (str (clojure.core/name name) "." (clojure.core/name k)))
                                                 (lens-spec t at)])
                                      (+ at ({:u16 2 :u8.8 2 :s8.8 2 :u16.8 3} t 1))])
                                   [[] addr]
                                   (second type)))
                    [[name [:struct addr (vec (apply concat (second type)))]]])
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
        size-of #({:u16 2 :u8.8 2 :s8.8 2 :u16.8 3} % 1)]
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
  "The names in a names file (its :labels, :memory and :subroutines), as
  a table for looking them up:

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

(defn read-names
  "A names file's text, read."
  [text]
  (edn/read-string text))

(defn install!
  "Use the names in `names` (a names file, read) for the ROM with this
  SHA-1: in the disassembly, for breakpoints and at the REPL, and its
  variables as a lens called \"Variables\" in the Lens tab. Returns
  `sha1`."
  [sha1 names]
  (register! sha1 (symbol-table names))
  (when-let [fields (seq (lens-fields (:memory names)))]
    (lens/register! {:rom sha1
                     :name "Variables"
                     :doc "The game's variables, as its names file gives them."
                     :fields (vec fields)}))
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
  "A function for zeropage.disasm's `name-of` argument: (f instruction addr)
  -> the name to show for an address in that instruction's operand."
  [table]
  (fn [{:keys [mnemonic]} addr]
    (name-at table addr (contains? stores mnemonic))))
