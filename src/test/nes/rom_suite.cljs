(ns nes.rom-suite
  "blargg's test ROMs (test-roms/, see its README) on NES.cljs's own
  console: `npm run test:roms`.

  Every test ROM should pass, apart from the ones in `known-failures`,
  which should fail as described there (so that a fix, or a new way to
  fail, gets noticed). They take a few minutes of emulated time, so they
  have their own build rather than being part of `npm test`."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nes.emulator.machine :as machine]
            [nes.test-roms :as t]))

(def ^:private no-apu
  "We have no sound chip (APU) yet, so nothing at $4000-$4017 but the
  controllers and sprite DMA answers."
  "No sound chip yet: ")

(def known-failures
  "Test ROMs that don't pass yet: the failure code they give, the line of
  their text that says which test failed (an all-in-one ROM gives code 1
  whichever of its tests fails), and why."
  {"ppu_vbl_nmi/10-even_odd_timing.nes"
   {:code 3
    :why (str "The odd frame's skipped dot: on a real PPU, turning drawing on and turning "
              "it off take effect after different delays, and ours treats them the same.")}
   "ppu_vbl_nmi/ppu_vbl_nmi.nes"
   {:code 1 :text "While running test 10 of 10"
    :why "All ten of the single-test ROMs in one: only 10-even_odd_timing fails, as above."}
   "instr_misc/instr_misc.nes"
   {:code 1 :text "While running test 4 of 4"
    :why (str no-apu "04-dummy_reads_apu checks that the CPU's dummy reads of $4015 clear "
              "the sound chip's frame interrupt flag. Tests 1-3 pass.")}
   "instr_timing/instr_timing.nes"
   {:code 1 :text "While running test 1 of 2"
    :why (str no-apu "it times instructions with the sound chip's length counter.")}
   "cpu_interrupts_v2/cpu_interrupts.nes"
   {:code 1 :text "While running test 1 of 5"
    :why (str no-apu "its tests make the sound chip's frame interrupt (IRQ). "
              "(jsnes fails it too, at the same test.)")}
   "ppu_read_buffer/test_ppu_read_buffer.nes"
   {:code 67
    :why (str "Sprite DMA from page $20 ($4014 <- $20): a real DMA reads $2000-$20FF "
              "one byte at a time, so it reads the PPU's registers, with their side effects "
              "($2007 moves on, $2002 clears vblank). Ours copies the page at once with "
              "nes.machine's peek-cpu, which has no side effects. Every other check passes.")}})

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
        (is (and (= [:failed (:code expected)] [result code])
                 (or (nil? (:text expected)) (str/includes? (str text) (:text expected))))
            (str "known failure, expected #" (:code expected) " " (:text expected) ": " (:why expected)
                 "\nbut got " (name result) " #" code ": " text))
        (is (= :passed result) (str "#" code " " text))))))
