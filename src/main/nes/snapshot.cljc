(ns nes.snapshot
  "The state of the whole machine as an immutable ClojureScript value.

  `capture` copies the emulator's registers and memory into plain maps and
  vectors. After that it's just data: it doesn't change when the emulator
  keeps running, and the rest of this namespace is pure functions over it
  (comparing snapshots, searching for where a game keeps a variable).

  A snapshot looks like:

    {:frame 212
     :rom   {:name \"croom.nes\" :mapper 0 ...}
     :cpu   {:a 209 :x 255 :y 0 :sp 251 :pc 52517 :p 55 :cycles ... :flags #{...}}
     :ram      [... 2048 bytes, CPU $0000-$07FF ...]
     :cart-ram [... 8192 bytes, CPU $6000-$7FFF ...]
     :ppu   {:scanline 241 :dot 65 :flags {...}
             :oam [... 256 bytes ...]
             :palettes [... 32 bytes ...]
             :nametables [... 4096 bytes, PPU $2000-$2FFF ...]}}

  Snapshots support `clojure.datafy/nav` (through metadata), so data
  browsers like Portal can drill into them: navigating to `:oam` gives the
  64 decoded sprites, `:palettes` the eight 4-color palettes, `:flags` stay
  as they are."
  (:require [nes.bytes :as bytes]
            [nes.emulator :as emu]
            [nes.sprites :as sprites]))

;; ---------------------------------------------------------------------------
;; Capturing

(defn- copy
  "Copy `n` values from `f` (a function of an index) into a vector."
  [n f]
  (persistent! (reduce (fn [v i] (conj! v (f i))) (transient []) (range n))))

(def ^:private flag-bits
  [[7 :negative] [6 :overflow] [3 :decimal] [2 :irq-disable] [1 :zero] [0 :carry]])

(defn decode-flags
  "The status register `p` as a set of the flags that are set."
  [p]
  (into #{} (for [[bit k] flag-bits :when (bit-test p bit)] k)))

(declare with-nav)

(defn capture
  "Copy the current state of emulator `e` into an immutable snapshot.
  `rom` is optional extra information about the cartridge to include."
  ([e] (capture e nil))
  ([e rom]
   (let [{:keys [p] :as cpu} (emu/cpu-state e)
         {:keys [frame scanline dot]} (emu/ppu-state e)
         ppu-mem (emu/ppu-memory e)
         oam (emu/oam e)]
     (vary-meta
      (with-nav
        {:frame frame
         :rom rom
         :cpu (assoc cpu :flags (decode-flags p))
         :ram (copy 0x800 #(emu/cpu-peek e %))
         :cart-ram (copy 0x2000 #(emu/cpu-peek e (+ 0x6000 %)))
         :ppu {:scanline scanline
               :dot dot
               :flags (emu/ppu-flags e)
               :oam (copy 256 #(bytes/at oam %))
               :palettes (copy 32 #(bit-and 0x3F (bytes/at ppu-mem (+ 0x3F00 %))))
               :nametables (copy 0x1000 #(emu/ppu-peek e (+ 0x2000 %)))}})
      ;; The cartridge file, for lenses that read ROM. In metadata (and
      ;; not copied: ROM doesn't change by itself) so printing a snapshot
      ;; doesn't print the whole ROM.
      assoc ::rom-file (emu/rom-file e)))))

;; ---------------------------------------------------------------------------
;; Reading values out of a snapshot by address

(defn cpu-byte
  "The byte at CPU address `addr` in snapshot `s`, or nil if the snapshot
  doesn't include that address (ROM and I/O registers aren't captured)."
  [s addr]
  (cond
    (<= 0 addr 0x1FFF) (nth (:ram s) (bit-and addr 0x7FF))
    (<= 0x6000 addr 0x7FFF) (nth (:cart-ram s) (- addr 0x6000))
    :else nil))

(defn rom-byte
  "The byte at `offset` in the cartridge file (an iNES file offset,
  counting the 16-byte header) the snapshot was taken from, or nil."
  [s offset]
  (when-let [f (::rom-file (meta s))]
    (when (< -1 offset (bytes/size f))
      (bytes/at f offset))))

(defn with-rom-file
  "Snapshot `s` with cartridge file `bytes` (a byte array) attached, for
  `rom-byte`. (`capture` does this; this is for tests.)"
  [s bytes]
  (vary-meta s assoc ::rom-file bytes))

(defn cpu-bytes
  "`n` bytes starting at CPU address `addr`."
  [s addr n]
  (mapv #(cpu-byte s (+ addr %)) (range n)))

;; ---------------------------------------------------------------------------
;; Comparing snapshots

(def ^:private regions
  "The parts of a snapshot that hold memory: [path, address space, base address]."
  [[[:ram] :cpu 0x0000]
   [[:cart-ram] :cpu 0x6000]
   [[:ppu :oam] :oam 0x00]
   [[:ppu :palettes] :ppu 0x3F00]
   [[:ppu :nametables] :ppu 0x2000]])

(defn diff
  "Every byte that differs between snapshots `a` and `b`, as maps of
  `:space` (:cpu, :ppu or :oam), `:addr`, `:before`, `:after` and `:delta`
  (after - before). Optional `:only` limits it to some spaces, e.g.
  `(diff a b :only #{:cpu})`."
  [a b & {:keys [only]}]
  (for [[path space base] regions
        :when (or (nil? only) (contains? only space))
        :let [before (get-in a path)
              after (get-in b path)]
        i (range (count before))
        :let [x (nth before i) y (nth after i)]
        :when (not= x y)]
    {:space space :addr (+ base i) :before x :after y :delta (- y x)}))

(defn search
  "Find the CPU RAM addresses whose value changed in the way `pred`
  describes between every consecutive pair of `snapshots`.

  This is the classic way to find where a game keeps something: take a
  snapshot, lose a life, take another, lose another life, take a third,
  then search for addresses that went down by one each time:

    (search [s1 s2 s3] (fn [before after] (= after (dec before))))

  Returns a sorted vector of addresses (in $0000-$07FF and $6000-$7FFF)."
  [snapshots pred]
  (let [pairs (partition 2 1 snapshots)
        addrs (concat (range 0x800) (range 0x6000 0x8000))]
    (vec (for [addr addrs
               :when (every? (fn [[a b]] (pred (cpu-byte a addr) (cpu-byte b addr))) pairs)]
           addr))))

;; ---------------------------------------------------------------------------
;; datafy / nav

(defn- palettes-as-groups [bytes]
  {:background (mapv #(subvec bytes (* 4 %) (+ 4 (* 4 %))) (range 4))
   :sprites (mapv #(subvec bytes (+ 16 (* 4 %)) (+ 20 (* 4 %))) (range 4))})

(defn- nav-ppu [_ k v]
  (case k
    :oam (sprites/sprites (bytes/from v))
    :palettes (palettes-as-groups v)
    v))

(defn- nav-snapshot [_ k v]
  (if (= k :ppu)
    (vary-meta v assoc `clojure.core.protocols/nav nav-ppu)
    v))

(defn- with-nav [s]
  (vary-meta s assoc `clojure.core.protocols/nav nav-snapshot))
