(ns nes.routines-test
  (:require [cljs.test :refer [deftest is testing]]
            [nes.code-log :as cl]
            [nes.routines :as routines]))

;; A 16 KB program (NROM), hand-assembled, seen by the CPU at $C000:
;;   $C000 reset: JSR $C010 / JMP $C003 (loop forever)
;;   $C010 sub-1: LDA $10 / STA $2000 / JSR $C020 / RTS
;;   $C020 sub-2: STA $4014 / BEQ $C026 / RTS / RTS
;;   $C030 nmi:   INC $10 / RTI
;;   $C040 irq:   RTI
;;   $C050 only the log knows about: LDA #$01 / RTS
(defn- program []
  (let [prg (js/Uint8Array. 16384)
        put (fn [cpu bytes] (.set prg (clj->js bytes) (- cpu 0xC000)))]
    (put 0xC000 [0x20 0x10 0xC0  0x4C 0x03 0xC0])
    (put 0xC010 [0xA5 0x10  0x8D 0x00 0x20  0x20 0x20 0xC0  0x60])
    (put 0xC020 [0x8D 0x14 0x40  0xF0 0x01  0x60  0x60])
    (put 0xC030 [0xE6 0x10  0x40])
    (put 0xC040 [0x40])
    (put 0xC050 [0xA9 0x01  0x60])
    (put 0xFFFA [0x30 0xC0  0x00 0xC0  0x40 0xC0])
    prg))

(defn- log-with-0x50 []
  (let [flags (js/Uint8Array. 16384) extra (js/Uint8Array. 16384) calls (js/Uint32Array. 16384)
        window2 (bit-shift-left 2 2)]
    (doseq [i [0x50 0x51 0x52]] (aset flags i (bit-or cl/flag-code window2)))
    (aset extra 0x50 (bit-or cl/extra-instruction cl/extra-subroutine cl/extra-main))
    (aset extra 0x52 (bit-or cl/extra-instruction cl/extra-main))
    (aset calls 0x50 5)
    {:flags flags :extra extra :calls calls :frames 5}))

(defn- by-offset [result] (into {} (map (juxt :prg-offset identity)) (:routines result)))

(deftest from-the-vectors
  (let [rs (by-offset (routines/find-routines (program) 0 nil 16))]
    (is (= #{0x00 0x10 0x20 0x30 0x40} (set (keys rs))))
    (testing "what each does"
      (is (= #{:reset} (:found-by (rs 0x00))))
      (is (= 0xC010 (:cpu-addr (rs 0x10))))
      (is (= #{0x20} (:calls (rs 0x10))))
      (is (= #{0x10} (:callers (rs 0x20))))
      (is (= #{0x10} (:reads (rs 0x10))))
      (is (= #{0x2000} (:writes (rs 0x10))))
      (is (= #{:ppu} (:tags (rs 0x10))))
      (is (= #{:sprite-dma} (:tags (rs 0x20))))
      (is (= 2 (:exits (rs 0x20))) "both RTSs, one after the branch")
      (is (= 7 (:size (rs 0x20))))
      (is (= #{0x10} (:writes (rs 0x30))) "INC writes")
      (is (= 0x10 (:offset (rs 0x00))) "file offset counts the header"))))

(deftest with-a-log
  (let [result (routines/find-routines (program) 0 (log-with-0x50) 16)
        rs (by-offset result)]
    (is (contains? rs 0x50) "found only because it ran")
    (is (:ran? (rs 0x50)))
    (is (not (:ran? (rs 0x10))) "found by following code, not run")
    (is (= 1 (:per-frame (rs 0x50))))
    (is (= :main (:runs-in (rs 0x50))))
    (is (= [:lda :rts] (map :mnemonic (routines/listing result (rs 0x50)))))
    (is (= 1 (aget (:code result) 0x51)) "code map includes found code")
    (is (zero? (aget (:code result) 0x60)))))

(deftest fixed-regions
  (testing "small games: everything is fixed"
    (is (= {0 0 1 0} (routines/fixed-regions 16384 16384 nil))))
  (testing "big games: the top 16 KB is assumed to be the last bank"
    (is (= {1 (- 262144 16384)} (routines/fixed-regions 262144 16384 nil)))))
