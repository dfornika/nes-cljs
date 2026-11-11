(ns gamedev-toolkit.gen-test
  "The generators, checked by running what they generate on the CPU and
  comparing with ordinary Clojure arithmetic, on random inputs."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer-macros [defspec]]
            [clojure.test.check.generators :as tc]
            [clojure.test.check.properties :as prop :include-macros true]
            [gamedev-toolkit.analysis :as analysis]
            [gamedev-toolkit.asm :as asm]
            [gamedev-toolkit.gen :as gen]
            [gamedev-toolkit.sub :as sub :refer [call]]
            [nes.cpu.harness :as harness]))

(def ^:private syms {:n 0x10 :m 0x12 :out 0x14 :buf 0x300 :src 0x400})

(defn- word [n] [(bit-and n 0xFF) (bit-and (bit-shift-right n 8) 0xFF)])

(defn- run
  "Run `forms` with the 16-bit values `n` and `m` in memory (and any other
  `memory`). Returns the harness's result with :n, :m and :out read back
  as 16-bit numbers."
  ([forms n m] (run forms n m {}))
  ([forms n m memory]
   (let [r (harness/run-routine [forms [:rts]]
                                {:symbols syms :memory (merge {0x10 (word n) 0x12 (word m)} memory)})
         word-at #(+ ((:peek r) %) (* 256 ((:peek r) (inc %))))]
     (assoc r :n (word-at 0x10) :m (word-at 0x12) :out (word-at 0x14)))))

(def ^:private u8 (tc/choose 0 0xFF))
(def ^:private u16 (tc/choose 0 0xFFFF))

(defspec add16-is-plus 100
  (prop/for-all [a u16, b u16]
    (and (= (bit-and (+ a b) 0xFFFF) (:n (run (gen/add16 :n :m) a b)))
         (= (bit-and (+ a b) 0xFFFF) (:n (run (gen/add16 :n [:imm b]) a 0))))))

(defspec sub16-is-minus 100
  (prop/for-all [a u16, b u16]
    (and (= (bit-and (- a b) 0xFFFF) (:n (run (gen/sub16 :n :m) a b)))
         (= (bit-and (- a b) 0xFFFF) (:n (run (gen/sub16 :n [:imm b]) a 0))))))

(defspec add-and-subtract-bytes 100
  (prop/for-all [a u8, b u8]
    (let [plus (run (gen/add :n :m) a b)
          minus (run (gen/subtract :n [:imm b]) a 0)]
      (and (= (bit-and (+ a b) 0xFF) (:n plus))
           (= (> (+ a b) 0xFF) (:carry? plus))
           (= (bit-and (- a b) 0xFF) (:n minus))
           (= (>= a b) (:carry? minus))))))

(defspec inc16-and-dec16 100
  ;; mostly near where the low byte wraps, which is the interesting part
  (prop/for-all [a (tc/one-of [u16 (tc/fmap #(bit-and (+ (* 256 %) 0xFF) 0xFFFF) u8) (tc/fmap #(* 256 %) u8)])]
    (and (= (bit-and (inc a) 0xFFFF) (:n (run (gen/inc16 :n) a 0)))
         (= (bit-and (dec a) 0xFFFF) (:n (run (gen/dec16 :n) a 0))))))

(defspec compare16-is-compare 200
  (prop/for-all [a u16
                 b (tc/one-of [u16 (tc/return nil)])]   ; nil: compare a with itself
    (let [b (or b a)
          r (run (gen/compare16 :n :m) a b)]
      (and (= (= a b) (:zero? r))
           (= (>= a b) (:carry? r))))))

(defspec move16-copies 50
  (prop/for-all [a u16, b u16]
    (and (= b (:n (run (gen/move16 :n :m) a b)))
         (= b (:out (run (gen/move16 :out [:imm b]) a 0))))))

(defspec times-runs-n-times 50
  (prop/for-all [n (tc/choose 1 256)
                 with (tc/elements [:x :y])]
    (= n (:out (run (gen/times n {:with with} (gen/inc16 :out)) 0 0)))))

(defspec fill-and-copy 50
  (prop/for-all [n (tc/choose 1 256)
                 v u8]
    (let [filled (run (gen/fill :buf n [:imm v]) 0 0 {0x300 (vec (repeat 256 0xEE)) 0x400 [0xEE]})
          data (mapv #(bit-and (* 7 %) 0xFF) (range 256))
          copied (run (gen/copy :buf :src n) 0 0 {0x300 (vec (repeat 256 0xEE)) 0x400 data})
          buf (fn [r] (mapv #((:peek r) (+ 0x300 %)) (range 257)))]
      (and (= (concat (repeat n v) (repeat (- 256 n) 0xEE) [0xEE]) (buf filled))
           (= (concat (take n data) (repeat (- 256 n) 0xEE) [(first data)]) (buf copied))))))

(deftest conditions
  (let [check (fn [a b]
                (run [(gen/compare16 :n :m)
                      (gen/if-flag :cc (gen/move :out [:imm 1]) (gen/move :out [:imm 2]))
                      [:lda :n] [:cmp :m]
                      (gen/when-flag :eq (gen/move [:+ :out 1] [:imm 9]))]
                     a b))]
    (is (= 0x0001 (:out (check 5 7))) "if: the 'then' branch")
    (is (= 0x0002 (:out (check 7 5))) "if: the 'else' branch")
    (is (= 0x0902 (:out (check 7 7))) "when: taken"))
  (testing "do-while runs until the condition fails"
    (is (= 5 (:x (run [[:ldx [:imm 0]] (gen/do-while :ne [:inx] [:cpx [:imm 5]])] 0 0)))))
  (testing "spelled-out names mean the same"
    (is (= :bne (ffirst (gen/when-flag :eq [:nop]))) "skip the body unless equal")
    (is (= :bne (ffirst (gen/when-flag :zero [:nop]))))
    (is (= :bcc (first (last (gen/do-while :no-carry [:nop]))))))
  (is (thrown-with-msg? js/Error #"Unknown condition" (gen/when-flag :maybe [:nop]))))

(deftest nested-loops
  (is (= 12 (:out (run (gen/times 3 {:with :y} (gen/times 4 (gen/inc16 :out))) 0 0))))
  (testing "a body that would overwrite the counter is refused when building"
    (is (thrown-with-msg? js/Error #"changes x, which is counting the loop"
                          (gen/times 3 (gen/times 4 [:nop]))))
    (is (thrown-with-msg? js/Error #"changes y" (gen/times 3 {:with :y} [:iny])))
    (is (thrown-with-msg? js/Error #"changes x"
                          (gen/times 3 (call (sub/sub :uses-x [[:ldx [:imm 0]]]))))
        "...including by a subroutine it calls"))
  (is (thrown-with-msg? js/Error #"1 to 65536" (gen/times 0 [:nop]))))

(deftest preserving
  (testing "only what the body changes is saved"
    (is (= [[:inc 0x20]] (asm/flatten-forms (gen/preserving [:a :x :y] [:inc 0x20]))))
    (is (= #{} (analysis/clobbers (gen/preserving [:a :x :y] [:lda [:imm 1]] [:ldx [:imm 2]] [:ldy [:imm 3]]))))
    (is (= [:pha :lda :pla] (map first (asm/flatten-forms (gen/preserving [:a :x] [:lda [:imm 1]]))))))
  (testing "and it works"
    (let [r (harness/run-routine [(gen/preserving [:a :x :y]
                                    [:lda [:imm 1]] [:ldx [:imm 2]] [:ldy [:imm 3]] [:sta 0x20])
                                  [:rts]]
                                 {:a 0x11 :x 0x22 :y 0x33})]
      (is (= [0x11 0x22 0x33 1] [(:a r) (:x r) (:y r) ((:peek r) 0x20)]))
      (is (= 0xFF (:sp r)) "everything pushed was pulled")))
  (testing "a loop around code that needs X"
    (is (= 6 (:out (run (gen/times 3 (gen/preserving [:x] (gen/times 2 (gen/inc16 :out)))) 0 0)))))
  (testing "saving X or Y goes through A, and says so"
    (is (= #{:a} (analysis/clobbers (gen/preserving [:x] [:ldx [:imm 2]]))))
    (is (= #{:y} (analysis/clobbers (gen/preserving [:a :x] [:ldx [:imm 2]] [:iny])))))
  (is (thrown-with-msg? js/Error #"Registers are" (gen/preserving [:z] [:nop]))))

;; ---------------------------------------------------------------------------
;; Multiplying, dividing, signed numbers, fixed point

(def ^:private s8 (tc/choose -128 127))
(def ^:private s16 (tc/choose -32768 32767))

(defspec multiply-is-times 200
  (prop/for-all [a u8, b u8]
    (and (= (* a b) (:out (run (gen/multiply :out :n :m) a b)))
         (= (* a b) (:out (run (gen/multiply :out :n [:imm b]) a 0))))))

(defspec divide-is-quot-and-rem 200
  (prop/for-all [a u8, b (tc/choose 1 255)]
    (let [r (run (gen/divide :out :n :m 0x16) a b)
          by-constant (run (gen/divide :out :n [:imm b]) a 0)]
      (and (= (quot a b) (bit-and (:out r) 0xFF) (bit-and (:out by-constant) 0xFF))
           (= (rem a b) (:a r) ((:peek r) 0x16) (:a by-constant))))))

(defspec multiply-a-by-a-constant 200
  (prop/for-all [a u8, n u8]
    (= (bit-and (* a n) 0xFF)
       (:a (harness/run-routine [(gen/multiply-a n 0x20) [:rts]] {:a a})))))

(deftest multiply-and-divide-a
  (is (= [[:asl :a] [:asl :a] [:asl :a]] (gen/multiply-a 8)) "a power of two is just shifts")
  (is (= [] (gen/multiply-a 1)))
  (is (thrown-with-msg? js/Error #"needs a scratch variable" (gen/multiply-a 5)))
  (doseq [n [1 2 4 8 16 32 64 128], a [0 1 7 100 255]]
    (is (= (quot a n) (:a (harness/run-routine [(gen/divide-a n) [:rts]] {:a a}))) (str a " / " n)))
  (is (thrown-with-msg? js/Error #"power of two" (gen/divide-a 10)))
  (testing "dividing by zero gives 255, remainder the number"
    (let [r (run (gen/divide :out :n :m) 77 0)]
      (is (= [255 77] [(bit-and (:out r) 0xFF) (:a r)])))))

(defspec signed-compares 300
  (prop/for-all [a s8, b s8, c s16, d s16]
    (let [bytes (run (gen/compare-signed :n :m) (bit-and a 0xFF) (bit-and b 0xFF))
          words (run (gen/compare16-signed :n :m) (bit-and c 0xFFFF) (bit-and d 0xFFFF))
          same (run (gen/compare16-signed :n :m) (bit-and c 0xFFFF) (bit-and c 0xFFFF))]
      (and (= (< a b) (:negative? bytes))
           (= (< c d) (:negative? words))
           (not (:negative? same))))))

(defspec add-signed-byte-steps 200
  (prop/for-all [a u16, step s8]
    (and (= (bit-and (+ a step) 0xFFFF) (:n (run (gen/add-signed-byte :n :m) a (bit-and step 0xFF))))
         (= (bit-and (+ a step) 0xFFFF) (:n (run (gen/add-signed-byte :n [:imm step]) a 0))))))

(deftest fixed-point
  (is (= 384 (gen/fixed 1.5)))
  (is (= 30720 (gen/fixed 120)))
  (is (= 0xFF00 (gen/fixed -1)))
  (is (= 0xFFC0 (gen/fixed -0.25)))
  (is (= [:+ :pos 1] (gen/whole :pos)))
  (testing "1.5 a step: 3 whole pixels after two steps"
    (let [step (gen/add16 :n [:imm (gen/fixed 1.5)])]
      (is (= (gen/fixed 103) (:n (run [step step] (gen/fixed 100) 0)))))))

;; ---------------------------------------------------------------------------
;; Past 256, and past a branch's reach

(defn- run-big [forms memory]
  (harness/run-routine [forms [:rts]] {:symbols {:dst 0x1000 :src 0x3000 :count 0x10}
                                       :memory memory :max-cycles 3000000}))

(defspec big-fill-and-copy 30
  (prop/for-all [n (tc/choose 1 4096)
                 v u8]
    (let [data (mapv #(bit-and (+ 1 (* 13 %)) 0xFF) (range 4097))
          filled (run-big (gen/fill :dst n [:imm v]) {})
          copied (run-big (gen/copy :dst :src n) {0x3000 data})
          dst (fn [r] (mapv #((:peek r) (+ 0x1000 %)) (range (inc n))))]
      (and (= (concat (repeat n v) [0]) (dst filled))
           (= (concat (take n data) [0]) (dst copied))))))

(defspec times-past-256 30
  (prop/for-all [n (tc/one-of [(tc/choose 257 3000) (tc/elements [512 768 1024])])]
    (let [r (run-big (gen/times n (gen/inc16 :count)) {})]
      (= n (+ ((:peek r) 0x10) (* 256 ((:peek r) 0x11)))))))

(deftest counting-to-65536
  (let [r (run-big [(gen/times 65536 (gen/inc16 :count))
                    ;; the count wraps to 0, so count the wraps too
                    (gen/times 65536 [:inc 0x20])]
                   {})]
    (is (= [0 0 0] [((:peek r) 0x10) ((:peek r) 0x11) ((:peek r) 0x20)])))
  (is (thrown-with-msg? js/Error #"changes y" (gen/times 300 [:iny])))
  (is (thrown-with-msg? js/Error #"no choosing" (gen/times 300 {:with :y} [:nop])))
  (is (thrown-with-msg? js/Error #"1 to 4096" (gen/fill :dst 5000 [:imm 0]))))

(deftest bodies-too-big-for-a-branch
  ;; 60 inc16s are about 400 bytes; a branch reaches 127. (A generator
  ;; makes its own labels, so each inc16 is a separate call: the same
  ;; result used twice would define the same label twice.)
  (let [big #(repeatedly 60 (fn [] (gen/inc16 :n)))]
    (is (= 180 (:n (run (gen/times 3 (big)) 0 0))) "times")
    (is (= 1260 (:n (run (gen/times 3 {:with :y} (gen/times 7 (big))) 0 0))) "nested")
    (is (= 5200 (:n (run (gen/times 260 (repeatedly 20 #(gen/inc16 :n))) 0 0))) "past 256")
    (is (= 60 (:n (run [[:lda [:imm 0]] (gen/when-flag :zero (big))] 0 0))) "when, taken")
    (is (= 0 (:n (run [[:lda [:imm 1]] (gen/when-flag :zero (big))] 0 0))) "when, skipped")
    (is (= 60 (:n (run [[:lda [:imm 0]] (gen/if-flag :zero (big) (gen/move :n [:imm 9]))] 0 0))) "if, then")
    (is (= 9 (:n (run [[:lda [:imm 1]] (gen/if-flag :zero (big) (gen/move :n [:imm 9]))] 0 0))) "if, else")
    (is (= 120 (:n (run [[:ldx [:imm 2]] (gen/do-while :not-zero (big) [:dex])] 0 0))) "do-while")
    (testing "small bodies still get a plain branch"
      (is (= [:dex :bne] (map first (take-last 2 (asm/flatten-forms (gen/times 3 [:nop]))))))
      (is (= [:dex :beq :jmp :label] (map first (take-last 4 (asm/flatten-forms (gen/times 3 (big))))))))
    (testing "using one generated piece twice is caught by the assembler"
      (let [once (gen/inc16 :n)]
        (is (thrown-with-msg? js/Error #"defined twice" (run [once once] 0 0)))))))

(deftest size
  (is (= 5 (analysis/size [[:lda :n] [:sta 0x200]] {:n 0x10})) "exact with symbols")
  (is (= 6 (analysis/size [[:lda :n] [:sta 0x200]])) "a little high without: :n might not be in the zero page")
  (is (= 4 (analysis/size [(call (sub/sub :big (repeat 100 [:nop]))) [:rts]])) "a call is its JSR")
  (is (= 4 (analysis/size [[:bne :somewhere] [:label :here] [:db 1 2]])) "labels needn't be defined"))
