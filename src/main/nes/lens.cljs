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
;; Which lens goes with which game

;; Lenses for particular games, by ROM file name. (Matching by a hash of
;; the ROM would be sturdier; see TODO.md.)
(defonce registry (atom {}))

(defn register!
  "Use `lens` for the ROM called `rom-name`."
  [rom-name lens]
  (swap! registry assoc rom-name lens)
  rom-name)

(defn lens-for
  "The lens registered for `rom-name`, or nil."
  [rom-name]
  (get @registry rom-name))

(defn default-lens
  "With no game-specific lens, the most interpretable thing we can say
  without knowing the game: the CPU's registers and where the stack is."
  [s]
  {:cpu (:cpu s)
   :stack (let [sp (get-in s [:cpu :sp])]
            (snapshot/cpu-bytes s (+ 0x101 sp) (- 0xFF sp)))
   :visible-sprites (count (remove #(>= % 0xEF) (take-nth 4 (get-in s [:ppu :oam]))))})
