(ns nes.routines
  "Finding a game's subroutines: where they are, how big, what they call
  and what calls them, what memory and hardware they touch.

  Two sources of evidence, combined:

  - The code/data log (nes.code-log): what actually ran while someone
    played. Certain, but only covers what was played.
  - Following the code (static analysis): start from known code and decode
    instruction by instruction, following branches and jumps, collecting
    the targets of JSR as subroutines. It reaches code that didn't run, but
    it can't follow jumps computed at run time (jump tables), and it can be
    fooled into decoding data as code. The log helps with both: logged
    jump-table targets become starting points, and bytes the log saw read
    as data stop it.

  Bank switching: the CPU sees 32 KB of ROM at $8000-$FFFF, but a game can
  have far more, switched in a bank at a time. A jump to $9000 means
  whichever bank is in $8000-$BFFF at that moment, which we can't know
  without running it. So we follow a jump only if the target is in the
  same bank as the jump, or in a part of CPU space that only ever holds
  one bank ('fixed'): all of it for small games, and usually the top 16 KB
  for bigger ones. The log shows which parts are fixed. Jumps we can't
  follow are kept as unresolved CPU addresses.

  Everything is by offset into the PRG ROM (the program part of the .nes
  file, after the header), like the code/data log."
  (:require [nes.bytes :as bytes]
            [nes.code-log :as cl]
            [nes.cpu.disasm :as disasm]))

;; ---------------------------------------------------------------------------
;; Where things are mapped

(defn region-size
  "The bank size to assume: 8 KB for MMC3 (mapper 4), 16 KB otherwise."
  [mapper]
  (if (= mapper 4) 8192 16384))

(defn fixed-regions
  "Which parts ('regions') of $8000-$FFFF only ever hold one bank:
  {region-index prg-offset-of-what's-there}.

  Small games (32 KB or less) are all fixed. Otherwise the top region is
  assumed fixed to the last bank (true for the common mappers), and the
  code/data log can show more: a region where everything seen came from
  one bank."
  [prg-size size flags]
  (let [regions (quot 32768 size)]
    (if (<= prg-size 32768)
      (into {} (for [r (range regions)] [r (mod (* r size) prg-size)]))
      (let [seen (when flags
                   (let [n (bytes/size flags)]
                     (loop [i 0 acc {}]
                       (if (< i n)
                         (let [f (bytes/at flags i)]
                           (recur (inc i)
                                  (if (pos? (bit-and f 3))
                                    (let [r (quot (* 8192 (cl/window-of f)) size)]
                                      (update acc r (fnil conj #{}) (- i (mod i size))))
                                    acc)))
                         acc))))
            from-log (into {} (for [[r bases] seen :when (= 1 (count bases))] [r (first bases)]))
            top (dec regions)]
        (cond-> from-log
          (and (not (contains? from-log top)) (not (contains? seen top)))
          (assoc top (- prg-size size)))))))

(defn- region-of [cpu size] (quot (- cpu 0x8000) size))

(defn- cpu-of
  "The CPU address of PRG offset `off` when its bank is in region `r`."
  [off r size]
  (+ 0x8000 (* r size) (mod off size)))

(defn- resolve-target
  "The PRG offset of CPU address `target`, jumped to from PRG offset `from`
  (in region `r`), or nil if we can't know."
  [{:keys [size fixed]} from r target]
  (when (>= target 0x8000)
    (let [rt (region-of target size)]
      (cond
        (= rt r) (+ (- from (mod from size)) (mod target size))
        (contains? fixed rt) (+ (fixed rt) (mod target size))))))

;; ---------------------------------------------------------------------------
;; Decoding

(defn- decode-at
  "Decode the instruction at PRG offset `off`, running in region `r`."
  [{:keys [prg size]} off r]
  (let [cpu (cpu-of off r size)]
    (assoc (disasm/decode (fn [a]
                            (let [i (+ off (- a cpu))]
                              (when (< -1 i (bytes/size prg)) (bytes/at prg i))))
                          cpu)
           :prg-offset off :region r)))

(defn- logged-data?
  "Did the log see this byte read as data, and never run?"
  [{:keys [flags]} off]
  (and flags (let [f (bytes/at flags off)] (and (pos? (bit-and f cl/flag-data))
                                            (zero? (bit-and f cl/flag-code))))))

(def ^:private stops #{:rts :rti :brk})

(defn- jump-target [{:keys [mnemonic mode operand target]}]
  (cond (= mode :rel) target
        (and (= mnemonic :jmp) (= mode :abs)) operand
        (= mnemonic :jsr) operand))

;; ---------------------------------------------------------------------------
;; Finding code and subroutine entries

(defn- vectors
  "The NMI, RESET and IRQ entry points, as PRG offsets."
  [{:keys [prg size fixed] :as ctx}]
  (let [n (bytes/size prg)
        word (fn [i] (bit-or (bytes/at prg i) (bit-shift-left (bytes/at prg (inc i)) 8)))
        top (region-of 0xFFFA size)
        top-base (get fixed top (- n size))]
    (into {}
          (for [[k i] [[:nmi (- n 6)] [:reset (- n 4)] [:irq (- n 2)]]
                :let [t (word i)
                      off (or (resolve-target ctx (+ top-base (mod 0xFFFA size)) top t))]
                :when off]
            [k [off (region-of t size)]]))))

(defn- logged-starts
  "Entry points from the log: [prg-offset region kind] for subroutine
  starts, jump-table targets, and every instruction that ran."
  [{:keys [flags extra size]}]
  (when (and flags extra)
    (let [n (bytes/size flags)]
      (loop [i 0 out (transient [])]
        (if (< i n)
          (let [f (bytes/at flags i)
                e (bytes/at extra i)
                r (quot (* 8192 (cl/window-of f)) size)]
            (recur (inc i)
                   (cond-> out
                     (pos? (bit-and e cl/extra-subroutine)) (conj! [i r :jsr-target])
                     (pos? (bit-and f cl/flag-indirect-code)) (conj! [i r :jump-target])
                     (pos? (bit-and e cl/extra-instruction)) (conj! [i r :ran]))))
          (persistent! out))))))

(defn- explore
  "Follow code from `seeds` ([offset region kind]). Returns
  {:instructions {offset decoded} :entries {offset #{kind}}} where entries
  are subroutine starts: vectors, JSR targets (logged or found), jump-table
  targets."
  [ctx seeds]
  (loop [todo (vec seeds)
         seen (transient {})
         entries (transient {})]
    (if-let [[off r kind] (peek todo)]
      (let [todo (pop todo)
            entries (if (#{:jsr-target :jump-target :nmi :reset :irq} kind)
                      (assoc! entries off (conj (get entries off #{}) kind))
                      entries)]
        (if (or (contains? seen off) (logged-data? ctx off))
          (recur todo seen entries)
          (let [{:keys [mnemonic size unofficial?] :as ins} (decode-at ctx off r)]
            ;; Data often decodes as unofficial opcodes, so following the
            ;; code stops at one, unless the log saw it run.
            (if (or (not mnemonic)
                    (and unofficial?
                         (not (and (:flags ctx) (pos? (bit-and (bytes/at (:flags ctx) off) cl/flag-code))))))
              (recur todo seen entries)
              (let [seen (assoc! seen off ins)
                    t (jump-target ins)
                    t-off (when t (resolve-target ctx off r t))
                    t-r (when t (region-of t (:size ctx)))
                    next-off (+ off size)
                    falls-through? (and (not (stops mnemonic)) (not= mnemonic :jmp)
                                        (< next-off (bytes/size (:prg ctx))))
                    todo (cond-> todo
                           t-off (conj [t-off t-r (if (= mnemonic :jsr) :jsr-target :code)])
                           falls-through? (conj [next-off r :code]))]
                (recur todo seen entries))))))
      {:instructions (persistent! seen)
       :entries (persistent! entries)})))

;; ---------------------------------------------------------------------------
;; One routine's body and what it does

(defn- access
  "What instruction `ins` reads and writes: [:read addr] / [:write addr]
  pairs for addresses it names directly (indexed ones by their base)."
  [{:keys [mnemonic mode operand]}]
  (when (and operand (#{:zp :zpx :zpy :abs :abx :aby :izx :izy} mode)
             (not (#{:jmp :jsr} mnemonic)))
    (let [rmw #{:inc :dec :asl :lsr :rol :ror}
          writes #{:sta :stx :sty}]
      (cond
        (rmw mnemonic) [[:read operand] [:write operand]]
        (writes mnemonic) (if (#{:izx :izy} mode) [[:read operand] [:write-indirect operand]] [[:write operand]])
        :else [[:read operand]]))))

(defn- tags-of [reads writes]
  (let [any? (fn [s pred] (some pred s))]
    (cond-> #{}
      (any? (concat reads writes) #(<= 0x2000 % 0x3FFF)) (conj :ppu)
      (any? writes #(= % 0x4014)) (conj :sprite-dma)
      (any? writes #(or (<= 0x4000 % 0x4013) (= % 0x4015) (= % 0x4017))) (conj :sound)
      (or (any? reads #(#{0x4016 0x4017} %)) (any? writes #(= % 0x4016))) (conj :controller)
      (any? writes #(>= % 0x8000)) (conj :bank-switch))))

(defn- normalize [a]
  (cond (< a 0x2000) (bit-and a 0x7FF)
        (< a 0x4000) (bit-or 0x2000 (bit-and a 7))
        :else a))

(defn- body
  "The instructions of the routine starting at `entry`: everything reached
  without following JSR, stopping at other routines' entries (a JMP there
  is a tail call)."
  [instructions entries entry]
  (loop [todo [entry] seen #{} tail-calls #{} jumps-out #{}]
    (if-let [off (peek todo)]
      (let [todo (pop todo)]
        (if (or (seen off) (not (instructions off)))
          (recur todo seen tail-calls jumps-out)
          (let [{:keys [mnemonic size mode] :as ins} (instructions off)
                t (when (or (= mode :rel) (and (= mnemonic :jmp) (= mode :abs))) (jump-target ins))
                t-off (when t (:target-offset ins))
                next-off (+ off size)
                cont? (and (not (stops mnemonic)) (not= mnemonic :jmp))]
            (recur (cond-> todo
                     (and t-off (not (entries t-off))) (conj t-off)
                     (and cont? (not (entries next-off))) (conj next-off))
                   (conj seen off)
                   (cond-> tail-calls (and t-off (entries t-off) (not= t-off entry)) (conj t-off))
                   (cond-> jumps-out (and t (nil? t-off) (not= mode :rel)) (conj t))))))
      {:instructions seen :tail-calls tail-calls :jumps-out jumps-out})))

(defn find-routines
  "Find the subroutines in a game.

  `prg` is the PRG ROM (a byte array), `mapper` the iNES mapper number,
  `log` the code/data log or nil ({:flags :extra :calls :frames}), and
  `header-size` the bytes before the PRG ROM in the .nes file.

  Returns {:routines [...] :code (a byte array, 1 for each byte found to be
  code)}. Each routine:
    :prg-offset :offset (in the .nes file) :cpu-addr :bank
    :found-by     #{:nmi :reset :irq :jsr-target :jump-target}
    :ran?         whether the log saw it run
    :size :instructions :exits (RTS/RTI/BRK count)
    :calls        PRG offsets of subroutines it calls
    :unresolved   CPU addresses it calls or jumps to in banks we can't know
    :callers      PRG offsets of routines that call it
    :tail-calls   routines it ends by jumping to
    :reads :writes  addresses it names (RAM folded to $0000-$07FF)
    :tags         #{:ppu :sound :controller :sprite-dma :bank-switch}
    :calls-logged :per-frame :runs-in   (from the log)"
  [prg mapper log header-size]
  (let [size (region-size mapper)
        flags (:flags log)
        extra (:extra log)
        ctx {:prg prg :size size :flags flags :extra extra
             :fixed (fixed-regions (bytes/size prg) size flags)}
        vecs (vectors ctx)
        seeds (concat (for [[k [off r]] vecs] [off r k])
                      (logged-starts ctx))
        {:keys [instructions entries]} (explore ctx seeds)
        ;; branch/jump targets resolved once, for walking bodies
        instructions (into {} (for [[off {:keys [region] :as ins}] instructions
                                    :let [t (jump-target ins)]]
                                [off (cond-> ins t (assoc :target-offset (resolve-target ctx off region t)))]))
        entry? (set (keys entries))
        bodies (into {} (for [e entry?] [e (body instructions entry? e)]))
        calls-of (fn [{body-ins :instructions}]
                   (let [jsrs (keep #(let [i (instructions %)] (when (= :jsr (:mnemonic i)) i)) body-ins)]
                     {:calls (set (keep :target-offset jsrs))
                      :unresolved (set (keep #(when-not (:target-offset %) (:operand %)) jsrs))}))
        call-map (into {} (for [[e b] bodies] [e (calls-of b)]))
        callers (reduce (fn [acc [e {:keys [calls]}]]
                          (reduce #(update %1 %2 (fnil conj #{}) e) acc calls))
                        {} call-map)
        calls-arr (:calls log)
        frames (:frames log 0)
        code (bytes/make (bytes/size prg))]
    (doseq [[off {:keys [size]}] instructions]
      (dotimes [i size] (when (< (+ off i) (bytes/size code)) (bytes/put! code (+ off i) 1))))
    {:code code
     :fixed (:fixed ctx)
     ;; every instruction found, by PRG offset (for `listing`)
     :instructions instructions
     :routines
     (vec
      (for [e (sort entry?)
            :let [{b :instructions :keys [tail-calls jumps-out]} (bodies e)
                  ins (map instructions b)
                  accesses (mapcat access ins)
                  reads (set (map (comp normalize second) (filter #(= :read (first %)) accesses)))
                  writes (set (map (comp normalize second) (filter #(= :write (first %)) accesses)))
                  {:keys [region]} (instructions e)
                  ex (when extra (bytes/at extra e))
                  logged-calls (if calls-arr (bytes/word-at calls-arr e) 0)]]
        {:prg-offset e
         :body (vec (sort b))
         :offset (+ header-size e)
         :bank (quot e 16384)
         :cpu-addr (cpu-of e region size)
         :found-by (entries e)
         :ran? (boolean (and ex (pos? (bit-and ex cl/extra-instruction))))
         :size (reduce + (map :size ins))
         :instructions (count ins)
         :exits (count (filter (comp stops :mnemonic) ins))
         :calls (:calls (call-map e))
         :unresolved (into (:unresolved (call-map e)) jumps-out)
         :callers (get callers e #{})
         :tail-calls tail-calls
         :reads reads
         :writes writes
         :tags (tags-of reads writes)
         :calls-logged logged-calls
         :per-frame (if (pos? frames) (/ logged-calls frames) 0)
         :runs-in (when ex
                    (cond (and (pos? (bit-and ex cl/extra-nmi)) (pos? (bit-and ex cl/extra-main))) :both
                          (pos? (bit-and ex cl/extra-nmi)) :nmi
                          (pos? (bit-and ex cl/extra-main)) :main))}))}))

(defn listing
  "Routine `routine` (from `find-routines`) as decoded instructions, in
  address order. Each has :prg-offset, :addr (CPU address), :bytes,
  :mnemonic, :mode, :operand, and :target-offset for jumps we could follow."
  [{:keys [instructions]} routine]
  (mapv instructions (:body routine)))
