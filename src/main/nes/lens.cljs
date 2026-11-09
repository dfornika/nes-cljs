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
    [:bcd addr n]           n bytes of binary-coded decimal, two digits
                            per byte, most significant byte first
    [:digits addr n]        n bytes holding one decimal digit each, most
                            significant first (very common on the NES)
    [:bool addr]            true if the byte isn't 0
    [:bits addr {k bit}]    named single bits of one byte
    [:array addr n type]    n values of a fixed-size type, one after another
    [:struct addr [k type ...]]  fields of fixed-size types, one after another
    [:fn f]                 anything else: (f snapshot)"
  (:require [nes.snapshot :as snapshot]))

(defn- byte-at [s addr] (snapshot/cpu-byte s addr))

(def ^:private sizes {:u8 1 :s8 1 :u16 2 :bool 1})

(defn- size-of [type]
  (or (sizes type)
      (throw (ex-info (str "Can't use " type " inside :array or :struct; only fixed-size types: "
                           (keys sizes))
                      {:type type}))))

(declare read-field)

(defn- read-simple [s type addr]
  (read-field s [type addr]))

(defn read-field
  "Decode one field spec (see the namespace docs) from snapshot `s`."
  [s [type addr & args :as spec]]
  (case type
    :u8 (byte-at s addr)
    :s8 (when-let [b (byte-at s addr)] (if (>= b 0x80) (- b 0x100) b))
    :u16 (let [lo (byte-at s addr) hi (byte-at s (inc addr))]
           (when (and lo hi) (bit-or lo (bit-shift-left hi 8))))
    :bool (some-> (byte-at s addr) pos?)
    :bcd (let [[n] args]
           (reduce (fn [acc b] (+ (* acc 100)
                                  (* 10 (bit-shift-right b 4))
                                  (bit-and b 0xF)))
                   0 (snapshot/cpu-bytes s addr n)))
    :digits (let [[n] args]
              (reduce (fn [acc d] (+ (* acc 10) d)) 0 (snapshot/cpu-bytes s addr n)))
    :bits (let [[names] args
                b (byte-at s addr)]
            (into {} (for [[k bit] names] [k (bit-test b bit)])))
    :array (let [[n elem] args
                 size (size-of elem)]
             (mapv #(read-simple s elem (+ addr (* % size))) (range n)))
    :struct (let [[fields] args]
              (loop [[k t & more] fields, at addr, out {}]
                (if k
                  (recur more (+ at (size-of t)) (assoc out k (read-simple s t at)))
                  out)))
    :fn ((second spec) s)
    (throw (ex-info (str "Unknown field type " type) {:spec spec}))))

(defn interpret
  "Apply `lens` (a map of field name -> field spec) to snapshot `s`."
  [lens s]
  (into {} (for [[k spec] lens] [k (read-field s spec)])))

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
  (.replace src (js/RegExp. "(\\[:[a-z0-9]+ )(\\d+)" "g")
            (fn [_ before n]
              (str before "0x" (.padStart (.toUpperCase (.toString (js/parseInt n) 16)) 4 "0")))))

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
