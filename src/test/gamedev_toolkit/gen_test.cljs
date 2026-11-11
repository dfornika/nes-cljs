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
  (is (thrown-with-msg? js/Error #"1 to 256" (gen/times 0 [:nop]))))

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
