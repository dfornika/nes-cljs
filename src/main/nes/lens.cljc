(ns nes.lens
  "Lenses: describing where a game keeps its variables, as data.

  To the machine, a game's state is just bytes. A lens names them. It's a
  map of field names to field specs, each spec saying where the value is
  and how to read it:

    {:score  [:digits 0x07DD 6]        ; six bytes, one decimal digit each
     :lives  [:u8 0x075A]
     :cursor [:struct 0x0050 [:x :u8, :y :u8]]
     :cards  [:array 0x0300 20 :u8]
     :status [:bits 0x0010 {:paused 7, :two-player 0}]
     :custom [:fn (fn [snapshot] ...)]}

  `interpret` applies a lens to a snapshot (see nes.snapshot) and returns
  a map with the same keys and the decoded values.

  A lens can also be a vector of [name spec] pairs. Maps with more than 8
  keys don't keep their order, so use pairs to control the order fields
  are shown in (e.g. in the Lens tab).

  Field types:
    [:u8 addr]              unsigned byte, 0-255
    [:s8 addr]              signed byte, -128 to 127
    [:u16 addr]             16-bit number, low byte first (the 6502's order)
    [:u16.8 addr]           24-bit fixed point: a fraction byte, then a
                            16-bit whole number (positions on wide levels)
    [:u8.8 addr]            16-bit fixed point: a fraction byte, then a whole
    [:s8.8 addr]            byte, read as one number (the 16 bits / 256).
                            Common for positions and speeds finer than a
                            pixel. :s8.8 can be negative
    [:bcd addr n]           n bytes of binary-coded decimal, two digits
                            per byte, most significant byte first
    [:digits addr n]        n bytes holding one decimal digit each, most
                            significant first (very common on the NES)
    [:bool addr]            true if the byte isn't 0
    [:bits addr {k bit}]    named single bits of one byte
    [:enum addr {n name}]   a byte that stands for one of a few things,
                            e.g. {0 \"air\" 1 \"ground\"}; values not
                            listed come out as the number
    [:array addr n type]    n values of a fixed-size type, one after another
    [:struct addr [k type ...]]  fields of fixed-size types, one after another
    [:text addr n table]    n bytes of text, through a text table (a map
                            of byte -> string, see nes.text-table)
    [:screen-text table]    the text on screen right now, through a text
                            table: a vector of the lines that have any
    [:fn f]                 anything else: (f snapshot)

  ROM: wrap a spec in [:rom ...] to read it from the cartridge instead of
  from RAM, with the address being an offset in the .nes file (counting
  its 16-byte header, as hex editors and ROM maps like Data Crystal's
  do):

    [:rom [:u8 0x038315]]            ; Mega Man 2's maximum energy
    [:rom [:array 0x02E952 14 :u8]]  ; a damage table

  Changing values: `writes` works out which bytes to write to give a field
  a new value (the Lens tab's editing uses it). A ROM field's new value is
  written into the cartridge of the running game: a live ROM hack."
  (:require [nes.util :as util]
            [clojure.string :as str]
            [nes.snapshot :as snapshot]
            [nes.text-table :as text-table]))

(def ^:private sizes {:u8 1 :s8 1 :u16 2 :u8.8 2 :s8.8 2 :u16.8 3 :bool 1 :enum 1 :bits 1})

(defn- size-of [type]
  (or (sizes type)
      (throw (ex-info (str "Can't use " type " inside :array or :struct; only fixed-size types: "
                           (keys sizes))
                      {:type type}))))

(defn- elem-type
  "An :array or :struct element type: a keyword (:u8) or a vector with
  extra arguments ([:enum {0 \"off\"}])."
  [t]
  (if (vector? t) (first t) t))

(defn- elem-spec [t addr]
  (if (vector? t) (into [(first t) addr] (rest t)) [t addr]))

(defn- struct-offsets
  "[[k type offset] ...] for a :struct's fields."
  [fields]
  (loop [[k t & more] fields, at 0, out []]
    (if k
      (recur more (+ at (size-of (elem-type t))) (conj out [k t at]))
      out)))

(defn- read-with
  "Decode `spec` from snapshot `s`, getting bytes with (byte addr)."
  [s byte [type addr & args :as spec]]
  (let [bytes (fn [a n] (mapv #(byte (+ a %)) (range n)))]
    (case type
      :u8 (byte addr)
      :s8 (when-let [b (byte addr)] (if (>= b 0x80) (- b 0x100) b))
      :u16 (let [lo (byte addr) hi (byte (inc addr))]
             (when (and lo hi) (bit-or lo (bit-shift-left hi 8))))
      :u16.8 (let [f (byte addr) lo (byte (inc addr)) hi (byte (+ addr 2))]
               (when (and f lo hi)
                 (/ (+ f (* 256 lo) (* 65536 hi)) 256.0)))
      (:u8.8 :s8.8) (let [lo (byte addr) hi (byte (inc addr))]
                      (when (and lo hi)
                        (let [v (bit-or lo (bit-shift-left hi 8))]
                          ;; 256.0, not 256: on the JVM, dividing whole numbers gives a fraction (401/4)
                          (/ (if (and (= type :s8.8) (>= v 0x8000)) (- v 0x10000) v) 256.0))))
      :bool (some-> (byte addr) pos?)
      :bcd (let [[n] args]
             (reduce (fn [acc b] (+ (* acc 100)
                                    (* 10 (bit-shift-right b 4))
                                    (bit-and b 0xF)))
                     0 (bytes addr n)))
      :digits (let [[n] args]
                (reduce (fn [acc d] (+ (* acc 10) d)) 0 (bytes addr n)))
      :enum (let [[names] args
                  b (byte addr)]
              (get names b b))
      :bits (let [[names] args
                  b (byte addr)]
              (into {} (for [[k bit] names] [k (bit-test b bit)])))
      :array (let [[n elem] args
                   size (size-of (elem-type elem))]
               (mapv #(read-with s byte (elem-spec elem (+ addr (* % size)))) (range n)))
      :struct (let [[fields] args]
                (into {} (for [[k t at] (struct-offsets fields)]
                           [k (read-with s byte (elem-spec t (+ addr at)))])))
      :text (let [[n table] args]
              (text-table/decode table (bytes addr n)))
      :screen-text (let [table addr   ; [:screen-text table]
                         nt (get-in s [:ppu :nametables])]
                     (vec (remove str/blank?
                                  (map str/trimr
                                       (text-table/screen-lines table #(nth nt (- % 0x2000)) 0x2000)))))
      :rom (read-with s #(snapshot/rom-byte s %) addr)   ; [:rom inner-spec]
      :fn ((second spec) s)
      (throw (ex-info (str "Unknown field type " type) {:spec spec})))))

(defn read-field
  "Decode one field spec (see the namespace docs) from snapshot `s`."
  [s spec]
  (read-with s #(snapshot/cpu-byte s %) spec))

(defn interpret
  "Apply `lens` (a map of field name -> field spec) to snapshot `s`."
  [lens s]
  (into {} (for [[k spec] lens] [k (read-field s spec)])))

;; ---------------------------------------------------------------------------
;; Changing values

(defn- digits-of
  "`v` as `n` decimal digits, most significant first."
  [v n]
  (mapv #(mod (quot v (reduce * (repeat % 10))) 10) (range (dec n) -1 -1)))

(defn- writes-with
  [s byte space [type addr & args] path value]
  (let [w (fn [a v] {:space space :addr a :value (bit-and v 0xFF)})
        number (fn [v] (if (number? v) v (util/parse-int v)))]
    (case type
      (:u8 :s8) [(w addr (number value))]
      :u16 (let [v (number value)] [(w addr v) (w (inc addr) (bit-shift-right v 8))])
      :u16.8 (let [v (util/round (* 256 (if (number? value) value (util/parse-number value))))]
               [(w addr v) (w (inc addr) (bit-shift-right v 8)) (w (+ addr 2) (bit-shift-right v 16))])
      (:u8.8 :s8.8) (let [v (util/round (* 256 (if (number? value) value (util/parse-number value))))]
                      [(w addr v) (w (inc addr) (bit-shift-right v 8))])
      :bool [(w addr (if value 1 0))]
      :enum (let [[names] args
                  by-name (into {} (map (fn [[n nm]] [nm n]) names))]
              [(w addr (if (contains? by-name value) (by-name value) (number value)))])
      :bits (let [[names] args
                  [k] path
                  bit (names k)
                  b (byte addr)]
              (when (and bit b)
                [(w addr (if value (bit-or b (bit-shift-left 1 bit)) (bit-and b (bit-not (bit-shift-left 1 bit)))))]))
      :bcd (let [[n] args
                 ds (digits-of (number value) (* 2 n))]
             (vec (for [i (range n)]
                    (w (+ addr i) (+ (* 16 (nth ds (* 2 i))) (nth ds (inc (* 2 i))))))))
      :digits (let [[n] args]
                (vec (map-indexed #(w (+ addr %1) %2) (digits-of (number value) n))))
      :array (let [[n elem] args
                   [i & more] path]
               (when (and (int? i) (< -1 i n))
                 (writes-with s byte space (elem-spec elem (+ addr (* i (size-of (elem-type elem))))) more value)))
      :struct (let [[fields] args
                    [k & more] path]
                (some (fn [[fk t at]] (when (= fk k) (writes-with s byte space (elem-spec t (+ addr at)) more value)))
                      (struct-offsets fields)))
      :text (let [[n table] args]
              (when-let [bs (text-table/encode table value)]
                ;; pad (with whatever the table uses for a space) or cut to n
                (let [pad (or (first (text-table/encode table " ")) (last bs) 0)]
                  (vec (map-indexed #(w (+ addr %1) %2)
                                    (take n (concat bs (repeat pad))))))))
      :rom (writes-with s #(snapshot/rom-byte s %) :rom addr path value)
      nil)))

(defn writes
  "The bytes to write to give field `spec` the value `value`, as a vector
  of {:space :cpu/:rom :addr :value}, or nil if that field can't be
  changed (computed fields, the screen). For part of an :array, :struct
  or :bits field, `path` says which part: [3], [:x], [:paused]. `s` is a
  snapshot, for fields that change some bits of a byte and keep the rest."
  ([s spec value] (writes s spec [] value))
  ([s spec path value]
   (writes-with s #(snapshot/cpu-byte s %) :cpu spec path value)))

(defn editable?
  "Can field `spec` (or its part at `path`) be changed?"
  ([spec] (editable? spec []))
  ([[type addr & args] path]
   (case type
     (:u8 :s8 :u16 :u8.8 :s8.8 :u16.8 :bool :enum :bcd :digits :text) (empty? path)
     :bits (= 1 (count path))
     :array (and (seq path) (editable? (elem-spec (second args) 0) (rest path)))
     :struct (and (seq path)
                  (some (fn [[k t]] (when (= k (first path)) (editable? (elem-spec t 0) (rest path))))
                        (partition 2 (first args))))
     :rom (editable? addr path)
     false)))

;; ---------------------------------------------------------------------------
;; Which lenses go with which game
;;
;; A game can have any number of lenses, each looking at a different part
;; of its state (the board, the player, the sound engine...). Each one is
;; registered as a map:
;;
;;   {:rom    "a1b2c3..."     ; which game: the ROM's SHA-1 (see nes.rom/rom-hash),
;;                            ; or its file name, or :any for every game
;;    :name   "Board"         ; unique for that game
;;    :doc    "What it shows" ; optional
;;    :fields {...}}          ; the lens itself: field name -> spec
;;
;; The SHA-1 is of the ROM without its 16-byte header, the same as ROM
;; databases like No-Intro list, so it doesn't matter what the file is
;; called. A file name is handy while exploring, before you've looked up
;; the hash.

;; {[rom name] entry}
(defonce registry (atom {}))

;; Registration order, so lenses are listed in the order they were added
(defonce ^:private counter (atom 0))

(defn register!
  "Add a lens (a map, see above), replacing any lens with the same :rom
  and :name. Returns its [rom name] key.

  The older two-argument form, (register! rom-name fields), registers
  `fields` under the name \"Main\"."
  ([{:keys [rom name fields] :as entry}]
   (assert (and rom name fields) "A lens needs :rom, :name and :fields")
   (let [k [rom name]]
     (swap! registry update k
            (fn [old] (assoc entry :order (or (:order old) (swap! counter inc)))))
     k))
  ([rom fields]
   (register! {:rom rom :name "Main" :fields fields})))

(defn unregister!
  "Remove the lens registered as [rom name]."
  [rom name]
  (swap! registry dissoc [rom name])
  nil)

(defn matches?
  "Does lens `entry` apply to the ROM described by `rom`
  ({:sha1 ... :name ...}, as in the app db)? How it matched: :sha1,
  :file-name, :any, or nil."
  [entry rom]
  (let [k (:rom entry)]
    (cond
      (= k :any) :any
      (and (:sha1 rom) (= k (:sha1 rom))) :sha1
      (and (:name rom) (= k (:name rom))) :file-name)))

(defn lenses-for
  "The lenses that apply to `rom` ({:sha1 ... :name ...}), the game's own
  first, then ones for every game. Each has :matched-by added."
  [rom]
  (->> (vals @registry)
       (keep #(when-let [how (matches? % rom)] (assoc % :matched-by how)))
       (sort-by (juxt #(= :any (:matched-by %)) :order))
       vec))

(defn lens-for
  "The first lens (its fields) registered for `rom`, a map
  {:sha1 ... :name ...} or just a file name. nil if the game has none of
  its own."
  [rom]
  (let [rom (if (string? rom) {:name rom} rom)]
    (->> (lenses-for rom)
         (remove #(= :any (:matched-by %)))
         first
         :fields)))

(defn default-lens
  "With no game-specific lens, the most interpretable thing we can say
  without knowing the game: the CPU's registers and where the stack is."
  [s]
  {:cpu (:cpu s)
   :stack (let [sp (get-in s [:cpu :sp])]
            (snapshot/cpu-bytes s (+ 0x101 sp) (- 0xFF sp)))
   :visible-sprites (count (remove #(>= % 0xEF) (take-nth 4 (get-in s [:ppu :oam]))))})

(register! {:rom :any
            :name "Machine"
            :doc "What any game's state shows without knowing the game: the CPU, the stack, how many sprites are on screen."
            :fields [[:cpu [:fn :cpu]]
                     [:stack [:fn #(:stack (default-lens %))]]
                     [:visible-sprites [:fn #(:visible-sprites (default-lens %))]]]})

(defn- spec-source
  "A field spec as code. Functions can't be printed back as code, so :fn
  fields come out as a placeholder to fill in."
  [[type :as spec]]
  (if (= type :fn)
    "[:fn (fn [s] ...)]"
    (pr-str spec)))

(defn- hex-addresses
  "Rewrite the address in field specs like [:u8 63] as [:u8 0x003F]."
  [src]
  (str/replace src #"(\[:[a-z0-9]+ )(\d+)"
               (fn [[_ before n]]
                 (str before "0x" (util/hex-digits (util/parse-int n) 4)))))

(defn source
  "Lens `entry` as a `register!` form to paste into a file. If it was
  registered by file name and the ROM's `sha1` is given, it's written with
  the hash instead, so it'll work whatever the file is called."
  ([entry] (source entry nil))
  ([{:keys [rom name doc fields]} sha1]
   (let [by-hash? (and sha1 (string? rom) (not= rom sha1))]
     (str "(lens/register!\n"
          " {:rom " (pr-str (if by-hash? sha1 rom))
          (when by-hash? (str " ; " rom))
          "\n  :name " (pr-str name)
          (when doc (str "\n  :doc " (pr-str doc)))
          "\n  :fields\n  ["
          (apply str (interpose "\n   "
                                (for [[k spec] fields]
                                  (str "[" (pr-str k) " " (hex-addresses (spec-source spec)) "]"))))
          "]})"))))
