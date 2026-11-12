(ns nes.breakpoints
  "Breakpoints: telling the emulator to stop when something happens.

  A breakpoint is a map:

    {:id 3
     :kind :exec          ; :exec  - about to run the instruction at addr
                          ; :read  - the CPU reads addr
                          ; :write - the CPU writes addr
                          ; :access - reads or writes it
     :addr 0x003F
     :end 0x003F          ; optional: the last address of a range
     :value 0x13          ; optional: only when this value is read/written
     :when (fn [hit] ...) ; optional (REPL): only when this returns true
     :enabled? true}

  Execute breakpoints stop *before* the instruction runs: the program
  counter is at `addr`. Read and write breakpoints ('watchpoints') stop
  *after* the instruction that did the reading or writing finishes, since
  the CPU can't stop halfway through one.

  Addresses are compared the way the hardware decodes them: the 2 KB of RAM
  appears four times over $0000-$1FFF, so a watch on $0712 also catches a
  write to $0F12 (the same byte). The PPU's 8 registers likewise repeat
  through $2000-$3FFF."
  (:require [clojure.string :as str]
            [nes.bytes :as bytes]
            [nes.util :as util]))

(def kinds [:exec :read :write :access])

(def kind-names
  {:exec "execute" :read "read" :write "write" :access "read or write"})

;; Bit flags, one byte per CPU address, for the fast check the emulator
;; does on every memory access
(def flag-read 1)
(def flag-write 2)
(def flag-exec 4)

(defn normalize
  "The address the hardware really means: RAM mirrors folded into
  $0000-$07FF, PPU register mirrors into $2000-$2007."
  [addr]
  (cond
    (< addr 0x2000) (bit-and addr 0x07FF)
    (< addr 0x4000) (bit-or 0x2000 (bit-and addr 7))
    :else addr))

(defn- addresses
  "Every (normalized) address a breakpoint covers."
  [{:keys [addr end]}]
  (distinct (map normalize (range addr (inc (or end addr))))))

(defn- flag-of [kind]
  (case kind
    :exec flag-exec
    :read flag-read
    :write flag-write
    :access (bit-or flag-read flag-write)))

(defn flags
  "A byte array (see nes.bytes) of 65536 bit flags (`flag-read` etc.) marking every
  address an enabled breakpoint in `bps` watches, or nil if none are
  enabled."
  [bps]
  (let [on (filter :enabled? bps)]
    (when (seq on)
      (let [a (bytes/make 0x10000)]
        (doseq [bp on
                :let [f (flag-of (:kind bp))]
                n (addresses bp)]
          (bytes/put! a n (bit-or (bytes/at a n) f)))
        a))))

(defn- covers? [{:keys [addr end] :as bp} a]
  (let [end (or end addr)]
    (or (<= addr a end)
        ;; small ranges: compare after folding mirrors
        (and (< (- end addr) 0x800)
             (some #(= (normalize a) %) (addresses bp))))))

(defn- kind-matches? [bp-kind kind]
  (or (= bp-kind kind)
      (and (= bp-kind :access) (#{:read :write} kind))))

(defn matching
  "The first enabled breakpoint in `bps` that `hit` sets off, or nil.
  `hit` is {:kind :exec/:read/:write, :addr, :value (for reads and
  writes), :pc}."
  [bps {:keys [kind addr value] :as hit}]
  (some (fn [bp]
          (when (and (:enabled? bp)
                     (kind-matches? (:kind bp) kind)
                     (covers? bp addr)
                     (or (nil? (:value bp)) (= (:value bp) value))
                     (or (nil? (:when bp)) ((:when bp) hit)))
            bp))
        bps))

(defn- hex [n width]
  (util/hex n width))

(defn describe
  "A breakpoint in words, e.g. \"write $003F = $13\"."
  [{:keys [kind addr end value] :as bp}]
  (str (kind-names kind) " " (hex addr 4)
       (when (and end (not= end addr)) (str "–" (hex end 4)))
       (when value (str " = " (hex value 2)))
       (when (:when bp) " (with a condition)")))

(defn describe-hit
  "Why the emulator stopped, in words."
  [{:keys [kind addr value pc]}]
  (case kind
    :exec (str "about to run the instruction at " (hex addr 4))
    :read (str "the instruction at " (hex pc 4) " read " (hex value 2) " from " (hex addr 4))
    :write (str "the instruction at " (hex pc 4) " wrote " (hex value 2) " to " (hex addr 4))))

(defn parse-addr
  "Read an address typed by a person: \"3F\", \"$3F\", \"0x3F\". nil if
  it isn't one."
  [s]
  (let [t (-> (str s) str/trim (str/replace #"(?i)^(\$|0x)" ""))]
    (when (re-matches #"[0-9a-fA-F]{1,4}" t)
      (util/parse-int t 16))))
