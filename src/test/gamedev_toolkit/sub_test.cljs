(ns gamedev-toolkit.sub-test
  (:require [cljs.test :refer [deftest is testing]]
            [gamedev-toolkit.analysis :as analysis]
            [gamedev-toolkit.asm :as asm]
            [gamedev-toolkit.rom :as rom]
            [gamedev-toolkit.sub :as sub :refer [call]]
            [nes.cpu.harness :as harness]))

(def bump (sub/sub :bump "Adds 1 to $20." [[:inc 0x20]]))
(def bump-twice (sub/sub :bump-twice [(call bump) (call bump)]))
(def unused (sub/sub :unused [[:lda [:imm 1]]]))
(def load-x (sub/sub :load-x [[:ldx [:imm 7]]]))

(deftest linking
  (let [{:keys [code subroutines]} (sub/link [(call bump-twice) (call bump) [:rts]])]
    (is (= [[:jsr :bump-twice] [:jsr :bump] [:rts]
            [:label :bump-twice] [:jsr :bump] [:jmp :bump]
            [:label :bump] [:inc 0x20] [:rts]]
           (asm/flatten-forms code))
        "each subroutine once, after the program; the unused one isn't there; a final call is a jump")
    (is (= [{:name :bump-twice} {:name :bump :doc "Adds 1 to $20."}] subroutines)))
  (testing "no RTS is added after code that can't reach it"
    (is (= [[:jsr :spin] [:label :spin] [:jmp :spin]]
           (asm/flatten-forms (:code (sub/link [(call (sub/sub :spin [[:jmp :spin]]))]))))))
  (testing "a program with no calls is unchanged"
    (is (= [[:nop]] (asm/flatten-forms (:code (sub/link [[:nop]])))))))

(deftest running
  (let [r (harness/run-routine [(call bump-twice) (call bump) [:rts]])]
    (is (= 3 ((:peek r) 0x20)))
    (is (= {:bump-twice 0x8007 :bump 0x800D} (:labels r)))))

(deftest tail-calls
  (testing "turned off, a final call is a JSR and an RTS"
    (is (= [[:jsr :bump-twice] [:label :bump-twice] [:jsr :bump] [:jsr :bump] [:rts] [:label :bump] [:inc 0x20] [:rts]]
           (asm/flatten-forms (:code (sub/link [(call bump-twice)] {:tail-calls? false}))))))
  (testing "a call that isn't last stays a call"
    (let [then-more (sub/sub :then-more [(call bump) [:inc 0x21]])]
      (is (= [[:jsr :bump] [:inc 0x21] [:rts]]
             (take 3 (drop 2 (asm/flatten-forms (:code (sub/link [(call then-more)])))))))))
  (testing "a chain of tail calls uses no more stack than one call"
    ;; one -> two -> three -> four, each ending by calling the next; the
    ;; last records the stack pointer
    (let [four (sub/sub :four [[:tsx] [:stx 0x30]])
          three (sub/sub :three [[:inc 0x20] (call four)])
          two (sub/sub :two [[:inc 0x20] (call three)])
          one (sub/sub :one [[:inc 0x20] (call two)])
          depth (fn [opts]
                  (let [r (harness/run-routine (:code (sub/link [(call one) [:rts]] opts)))]
                    (is (= 3 ((:peek r) 0x20)))
                    ;; the harness starts with the stack pointer at $FD
                    (- 0xFD ((:peek r) 0x30))))]
      (is (= 2 (depth {})) "just the one return address")
      (is (= 8 (depth {:tail-calls? false})) "four of them"))))

(deftest inline
  (is (= [[:inc 0x20]] (sub/inline bump)))
  (is (= 1 ((:peek (harness/run-routine [(sub/inline bump) [:rts]])) 0x20)))
  (is (thrown-with-msg? js/Error #"RTS of its own"
                        (sub/inline (sub/sub :early [[:beq :out] [:rts] [:label :out]])))))

(deftest mistakes
  (is (thrown-with-msg? js/Error #"Two different subroutines are both called :bump"
                        (sub/link [(call bump) (call (sub/sub :bump [[:nop]]))])))
  (is (thrown-with-msg? js/Error #"call takes a subroutine" (call :bump)))
  (is (thrown-with-msg? js/Error #"means a register" (sub/sub :x [[:nop]])))
  (is (thrown-with-msg? js/Error #"has to be linked" (asm/assemble [(call bump)]))))

(deftest in-a-cartridge
  (let [built (rom/nrom {:code [[:label :reset] (call bump) [:jmp :reset]]
                         :vectors {:nmi :reset :reset :reset :irq :reset}})]
    (is (= 0xC006 (get-in built [:labels :bump])))
    (is (= [{:name :bump :doc "Adds 1 to $20."}] (:subroutines built)))))

(deftest clobbers
  (is (= #{} (analysis/clobbers [[:inc 0x20] [:sta 0x21] [:cmp [:imm 1]] [:asl 0x20]])))
  (is (= #{:a} (analysis/clobbers [[:lda [:imm 1]] [:sta 0x20]])))
  (is (= #{:a} (analysis/clobbers [[:asl :a]])))
  (is (= #{:a} (analysis/clobbers [[:lsr]])))
  (is (= #{:x :y} (analysis/clobbers [[:inx] [[:dey]] nil])))
  (is (= #{:a :x} (analysis/clobbers [[:txa] [:tax]])))
  (testing "through calls"
    (is (= #{} (analysis/clobbers [(call bump-twice)])))
    (is (= #{:x} (analysis/clobbers [(call load-x)])))
    (is (= #{:a :x :y} (analysis/clobbers [[:jsr :somewhere]])) "an unknown routine might change anything")))
