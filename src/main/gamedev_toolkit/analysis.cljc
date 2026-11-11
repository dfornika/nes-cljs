(ns gamedev-toolkit.analysis
  "Asking questions about code before it's assembled. Instructions are
  data, so 'which registers does this change?' is a function over that
  data, and nobody has to write the answer down by hand (or keep it up to
  date).

  The 6502 has three registers a program computes with: A, X and Y. Code
  that calls a subroutine in the middle of using X needs to know whether
  the subroutine changes X. `clobbers` answers that.

  `size` says how many bytes of ROM code takes. For how long code takes to
  run, run it: nes.cpu.harness/run-routine counts the cycles."
  (:require [gamedev-toolkit.asm :as asm]))

(def ^:private writes
  "The register each instruction changes, for the ones that change one."
  {:lda :a :adc :a :sbc :a :and :a :ora :a :eor :a :pla :a :txa :a :tya :a
   :ldx :x :inx :x :dex :x :tax :x :tsx :x
   :ldy :y :iny :y :dey :y :tay :y})

(def ^:private shifts #{:asl :lsr :rol :ror})

(def all-registers #{:a :x :y})

(defn clobbers
  "The registers (:a :x :y) that running `forms` might change.

  It's every register that any instruction in `forms` writes, whether or
  not that instruction would actually run, so it errs on the side of
  saying 'changed'. A call to a subroutine (see gamedev-toolkit.sub)
  counts what the subroutine changes; a plain [:jsr label] could be to
  anything, so it counts as changing all three.

  Code can also state its own answer, as metadata on a sequence of forms:
  (with-meta [...] {:clobbers #{:a}}). That's for code that puts back what
  it changes, which reading the instructions one by one can't see.
  gen/preserving does this."
  [forms]
  (cond
    (nil? forms) #{}
    (:clobbers (meta forms)) (:clobbers (meta forms))
    (and (vector? forms) (keyword? (first forms)))
    (let [[op & args] forms]
      (cond
        (= op :call) (clobbers (:body (first args)))
        (= op :jsr) all-registers
        ;; shifts work on memory, or on A when given :a or nothing
        (shifts op) (if (or (empty? args) (= args [:a])) #{:a} #{})
        (writes op) #{(writes op)}
        :else #{}))
    (sequential? forms) (into #{} (mapcat clobbers) forms)
    :else #{}))

(defn size
  "How many bytes `forms` take in the ROM. A subroutine call counts as its
  3-byte JSR, not the subroutine. Give `symbols` (as for asm/assemble) for
  an exact answer; without them, names are assumed to need 16-bit
  addresses, so the answer may be a little high but is never low."
  ([forms] (size forms {}))
  ([forms symbols]
   (asm/size (map (fn [[op :as form]] (if (= op :call) [:jsr 0xFFFF] form))
                  (asm/flatten-forms forms))
             symbols)))
