(ns gamedev-toolkit.memory
  "Declaring a game's variables as data, and letting an allocator decide
  where in RAM each one lives.

    (def memory
      (allocate
       [[:frame   :u8]
        [:score   :u16]
        [:state   [:enum {0 :title, 1 :playing}]]
        [:player  {:x :u8, :y :u8, :hp :u8}]
        [:enemies [:soa 8 {:x :u8, :y :u8, :hp :u8}]]
        [:oam     [:array 256 :u8] {:at 0x0200}]]))

  The result's :symbols go to the assembler, so code says [:lda :player/x]
  and never mentions an address. Its :variables say what's where and of
  what type, which is what a debugger needs to show the game's state by
  name (NES.cljs turns it into a lens: see nes.debug-info).

  The NES has 2 KB of RAM, $0000-$07FF. Three parts are special:

  - $0000-$00FF, the 'zero page'. Instructions that use it are a byte
    shorter and a cycle faster, and pointers must live there, so it's the
    valuable part.
  - $0100-$01FF is the CPU's stack.
  - $0200-$02FF is, by convention, the copy of sprite memory that's sent
    to the PPU every frame.

  So single values and structs go in the zero page (while there's room),
  and arrays go in $0300-$07FF. Override with {:in :zero-page}, {:in :ram}
  or {:at address}.

  Types:

    :u8 :s8 :bool            one byte
    :u16                     two bytes, low byte first
    [:enum {0 :title ...}]   one byte that stands for one of a few things
    [:bits {:a 7 ...}]       one byte of named bits
    [:array n type]          n of a type, one after another
    {:x :u8 :y :u8}          a struct: fields one after another, named
                             :player/x, :player/y. (For more than 8 fields
                             use [[:x :u8] [:y :u8] ...], which keeps its
                             order.)
    [:soa n {:x :u8 ...}]    a 'structure of arrays', for n objects: one
                             n-byte table per field, named :enemies/x,
                             :enemies/y... Object i's x is at :enemies/x + i,
                             which is one instruction on the 6502 with i in
                             the X register: [:lda :enemies/x :x]

  Options, as a third element: :at, :in (above), :doc (a description),
  and :hide true to leave a variable out of debugger views.")

(def default-regions
  "Where variables may go: [first-address last-address]."
  {:zero-page [0x0000 0x00FF]
   :ram       [0x0300 0x07FF]})

(def ^:private scalar-sizes {:u8 1 :s8 1 :bool 1 :u16 2})

(defn- fail [message entry]
  (throw (ex-info (str message "\n  in " (pr-str entry)) {:entry entry})))

(defn- scalar-size [t entry]
  (cond
    (scalar-sizes t) (scalar-sizes t)
    (and (vector? t) (#{:enum :bits} (first t)) (map? (second t))) 1
    :else (fail (str "Unknown type " (pr-str t) ". Single values are :u8, :s8, :bool, :u16, [:enum {...}] or [:bits {...}]") entry)))

(defn- field-pairs
  "A struct's fields, as [[name type] ...], from a map or pairs."
  [fields entry]
  (let [pairs (if (map? fields) (vec fields) fields)]
    (when-not (and (seq pairs)
                   (every? #(and (sequential? %) (= 2 (count %)) (keyword? (first %))) pairs))
      (fail "Fields are a map like {:x :u8 :y :u8}, or pairs like [[:x :u8] [:y :u8]]" entry))
    (when-not (apply distinct? (map first pairs))
      (fail "Two fields have the same name" entry))
    (mapv vec pairs)))

(defn- struct-type? [t]
  (or (map? t) (and (sequential? t) (sequential? (first t)))))

(defn- normalize
  "A declared type as {:type (in a standard form) :size :kind}."
  [t entry]
  (cond
    (struct-type? t)
    (let [fields (field-pairs t entry)]
      {:kind :struct
       :type [:struct fields]
       :size (reduce + (map #(scalar-size (second %) entry) fields))})

    (and (vector? t) (= :array (first t)))
    (let [[_ n elem] t]
      (when-not (and (= 3 (count t)) (pos-int? n))
        (fail "Arrays look like [:array 16 :u8]" entry))
      {:kind :array :type t :size (* n (scalar-size elem entry))})

    (and (vector? t) (= :soa (first t)))
    (let [[_ n fields] t]
      (when-not (and (= 3 (count t)) (pos-int? n))
        (fail "A structure of arrays looks like [:soa 16 {:x :u8 :y :u8}]" entry))
      (when (> n 256)
        (fail "A structure of arrays has at most 256 slots: the index is one byte" entry))
      (let [fields (field-pairs fields entry)]
        (doseq [[k ft] fields]
          (when (not= 1 (scalar-size ft entry))
            (fail (str "Field " k " must be one byte: in a structure of arrays each field is a table of bytes."
                       " (Use two fields, like :x-lo and :x-hi.)")
                  entry)))
        {:kind :soa :type [:soa n fields] :size (* n (count fields))}))

    :else {:kind :scalar :type t :size (scalar-size t entry)}))

(defn- overlaps? [a size [b b-size]]
  (and (< a (+ b b-size)) (< b (+ a size))))

(defn- find-room
  "The lowest address from `from` up, inside the region ending at `last`,
  where `size` bytes are free of `taken` ([[addr size] ...]). nil if none."
  [from last size taken]
  (loop [a from]
    (when (<= (+ a size) (inc last))
      (if-let [[b b-size] (some #(when (overlaps? a size %) %) taken)]
        (recur (+ b b-size))
        a))))

(defn- field-symbols
  "The assembler names a variable brings: itself, and for structs and
  structures of arrays, name/field for each field."
  [{:keys [name addr kind type]}]
  (let [qualified #(keyword (clojure.core/name name) (clojure.core/name %))]
    (into {name addr}
          (case kind
            :struct (first (reduce (fn [[m at] [k t]]
                                     [(assoc m (qualified k) at)
                                      (+ at (if (= t :u16) 2 1))])
                                   [{} addr]
                                   (second type)))
            :soa (let [[_ n fields] type]
                   (map-indexed (fn [i [k _]] [(qualified k) (+ addr (* i n))]) fields))
            nil))))

(defn allocate
  "Give every declared variable an address. `declarations` is a sequence
  of [name type] or [name type options] (see the namespace docstring).

  `regions` (optional) says where variables may go, as
  {:zero-page [first last] :ram [first last]}; reserve part of the zero
  page for something else by making it smaller.

  Returns:
    :variables  [{:name :type :kind :addr :size :doc :hide} ...] in the
                order declared. :kind is :scalar, :array, :struct or :soa
    :symbols    {name address} for the assembler, including :struct/field
    :free       {:zero-page n :ram n} bytes left in each region"
  ([declarations] (allocate declarations default-regions))
  ([declarations regions]
   (let [regions (merge default-regions regions)
         vars (mapv (fn [[name type opts :as entry]]
                      (when-not (and (sequential? entry) (<= 2 (count entry) 3)
                                     (keyword? name) (nil? (namespace name))
                                     (or (nil? opts) (map? opts)))
                        (fail "A variable is [name type] or [name type options], with a plain keyword for a name" entry))
                      (when-not (contains? #{nil :zero-page :ram} (:in opts))
                        (fail ":in is :zero-page or :ram" entry))
                      (merge {:name name :entry entry}
                             (normalize type entry)
                             (select-keys opts [:at :in :doc :hide])))
                    declarations)
         _ (when-let [[n] (seq (for [[n c] (frequencies (map :name vars)) :when (> c 1)] n))]
             (throw (ex-info (str "The variable " n " is declared twice") {:name n})))
         ;; the ones with a fixed address first: everything else fits around them
         pinned (for [{:keys [at size entry]} vars :when at]
                  (do (when-not (and (int? at) (<= 0 at) (<= (+ at size) 0x800))
                        (fail (str ":at must be an address in RAM ($0000-$07FF) with room for " size " bytes") entry))
                      [at size]))
         _ (doseq [[i a] (map-indexed vector pinned)
                   b (drop (inc i) pinned)]
             (when (overlaps? (first a) (second a) b)
               (throw (ex-info (str "Two variables with :at overlap, at " (first a) " and " (first b)) {}))))
         placed (reduce
                 (fn [{:keys [taken] :as acc} {:keys [at in kind size entry] :as v}]
                   (if at
                     (update acc :out conj (assoc v :addr at))
                     (let [order (case (or in (if (#{:scalar :struct} kind) :zero-page :ram))
                                   ;; small things spill into RAM when the zero page is full
                                   :zero-page (if in [:zero-page] [:zero-page :ram])
                                   :ram [:ram])
                           addr (some (fn [r] (let [[lo hi] (regions r)] (find-room lo hi size taken)))
                                      order)]
                       (when-not addr
                         (fail (str "No room left for " size " more bytes in " (if (= order [:ram]) "RAM" "the zero page")) entry))
                       (-> acc
                           (update :out conj (assoc v :addr addr))
                           (update :taken conj [addr size])))))
                 {:taken (vec pinned) :out []}
                 vars)
         variables (mapv #(dissoc % :entry :at :in) (:out placed))
         used-in (fn [[lo hi]]
                   (reduce + (for [[a size] (:taken placed)]
                               (max 0 (- (min (inc hi) (+ a size)) (max lo a))))))]
     {:variables variables
      :symbols (reduce (fn [m v]
                         (let [syms (field-symbols v)]
                           (when-let [clash (some #(when (contains? m %) %) (keys syms))]
                             (throw (ex-info (str "Two variables are both called " clash) {:name clash})))
                           (merge m syms)))
                       {}
                       variables)
      :free (into {} (for [[k [lo hi :as r]] regions] [k (- (inc (- hi lo)) (used-in r))]))})))
