(ns nes.rom-suite
  "blargg's test ROMs (test-roms/, see its README) on NES.cljs's own
  console: `npm run test:roms`.

  Every test ROM should pass, apart from the ones in `known-failures`,
  which should fail as described there (so that a fix, or a new way to
  fail, gets noticed). They take a few minutes of emulated time, so they
  have their own build rather than being part of `npm test`."
  (:require [cljs.test :refer [deftest is testing]]
            [nes.emulator.machine :as machine]
            [nes.test-roms :as t]))

(def known-failures
  "Test ROMs that don't pass yet: the failure code they give, and why."
  {"ppu_vbl_nmi/10-even_odd_timing.nes"
   {:code 3
    :why (str "The odd frame's skipped dot: on a real PPU, turning drawing on and turning "
              "it off take effect after different delays, and ours treats them the same.")}})

(deftest test-roms
  (doseq [[folder files] t/roms
          file files
          :let [rom (str folder "/" file)
                {:keys [result code text frames]}
                (t/run (machine/create {:on-frame (fn [_])}) (t/rom-bytes rom) 3600 (t/result-address folder))
                expected (known-failures rom)]]
    (println (if (= result :passed) "  passed " (str "  " (name result) " #" code)) rom
             (str "(" frames " frames)") (if expected "(known)" ""))
    (testing rom
      (if expected
        (is (= [:failed (:code expected)] [result code]) (str "known failure, expected #" (:code expected) ": " (:why expected)))
        (is (= :passed result) (str "#" code " " text))))))
