(ns nes.code-log-test
  (:require [cljs.test :refer [deftest is]]
            [nes.code-log :as cl]))

;; A tiny 32 KB "ROM": a subroutine at PRG offset $4000 (CPU $C000, window 2),
;; called 6 times from the main loop; a table read as data at $4100.
(defn- example-log []
  (let [size 32768
        flags (js/Uint8Array. size)
        extra (js/Uint8Array. size)
        calls (js/Uint32Array. size)
        window2 (bit-shift-left 2 2)]
    (doseq [i (range 0x4000 0x4004)] (aset flags i (bit-or cl/flag-code window2)))
    (aset extra 0x4000 (bit-or cl/extra-instruction cl/extra-subroutine cl/extra-main))
    (aset extra 0x4003 (bit-or cl/extra-instruction cl/extra-main))
    (aset calls 0x4000 6)
    (doseq [i (range 0x4100 0x4110)] (aset flags i (bit-or cl/flag-data window2)))
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
    (is (= (+ 32768 8192) (.-length f)))
    (is (= (bit-or cl/flag-code 8) (aget f 0x4000)))
    (is (zero? (aget f 32768)))))
