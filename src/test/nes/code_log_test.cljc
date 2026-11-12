(ns nes.code-log-test
  (:require [nes.bytes :as bytes]
            [clojure.test :refer [deftest is]]
            [nes.code-log :as cl]))

;; A tiny 32 KB "ROM": a subroutine at PRG offset $4000 (CPU $C000, window 2),
;; called 6 times from the main loop; a table read as data at $4100.
(defn- example-log []
  (let [size 32768
        flags (bytes/make size)
        extra (bytes/make size)
        calls (bytes/make-words size)
        window2 (bit-shift-left 2 2)]
    (doseq [i (range 0x4000 0x4004)] (bytes/put! flags i (bit-or cl/flag-code window2)))
    (bytes/put! extra 0x4000 (bit-or cl/extra-instruction cl/extra-subroutine cl/extra-main))
    (bytes/put! extra 0x4003 (bit-or cl/extra-instruction cl/extra-main))
    (bytes/put-word! calls 0x4000 6)
    (doseq [i (range 0x4100 0x4110)] (bytes/put! flags i (bit-or cl/flag-data window2)))
    {:flags flags :extra extra :calls calls :frames 3 :header-size 16}))

(deftest summarizing
  (let [s (cl/summary (example-log))]
    (is (= 4 (:code-bytes s)))
    (is (= 16 (:data-bytes s)))
    (is (= (- 32768 20) (:unseen-bytes s)))
    (is (= 2 (:instructions s)))
    (is (= [{:prg-offset 0x4000 :offset 0x4010 :bank 1 :cpu-addr 0xC000
             :calls 6 :per-frame 2 :runs-in :main}]
           (:routines s)))))

(deftest cdl-file
  (let [{:keys [flags]} (example-log)
        f (cl/cdl-file flags 8192)]
    (is (= (+ 32768 8192) (bytes/size f)))
    (is (= (bit-or cl/flag-code 8) (bytes/at f 0x4000)))
    (is (zero? (bytes/at f 32768)))))
