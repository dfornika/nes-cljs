(ns nes.compare
  "Comparing two emulators frame by frame: NES.cljs's own console against
  jsnes.

  Run both from power on, press the same buttons on the same frames, and
  after every frame compare their RAM and their pictures. They keep in
  step cycle for cycle (nes.machine follows jsnes's timing rules), so any
  difference is a difference in what they emulate.

  Not every difference is ours to fix, though. jsnes draws some pixels
  differently from a real PPU, so those are left out of the comparison:

  - The left 8 pixels, when PPUMASK hides either layer there: jsnes blacks
    them out; a PPU shows the other layer, or the backdrop.
  - The rightmost pixels, when scrolled by part of a tile (fine X): jsnes
    draws 32 tiles a line, leaving the last fine-X pixels as backdrop; a
    PPU draws a 33rd tile.
  - Lines drawn with drawing off: jsnes fills them with the backdrop color
    as it was when the frame began; a PPU shows it as it is at each pixel
    (and, while a game writes the palette, the color being written). Games
    load their palettes with drawing off, so this shows up as they start.

  Two more need setting up before running (see `nes.console/compare!`):
  jsnes blacks out the edges a TV hides (its clipToTvSize, turned off), and
  jsnes never skips the odd frame's dot, as a real NES does (so ours
  doesn't either, while comparing). And jsnes fills RAM with $FF at power
  on, where ours has zeros, so ours starts with a copy of jsnes's.

  Others found so far only matter to the tests: see
  nes.ppu.jsnes-compare-test and nes.ppu.render-jsnes-test."
  (:require [nes.bytes :as bytes]))

(defn skipped-columns
  "The columns of the picture left out of the comparison: [from to), the
  ones jsnes is known to draw wrong. `mask` is jsnes's PPUMASK and
  `fine-x` its fine X scroll (0-7) this frame."
  [mask fine-x]
  [(if (= 6 (bit-and mask 6)) 0 8)
   (- 256 fine-x)])

(defn picture-diffs
  "The pixels that differ between `ours` and `theirs` (each 256x240 32-bit
  pixels, 0x00BBGGRR), as indexes (y * 256 + x), leaving out the columns
  jsnes draws wrong (see `skipped-columns`), and the lines our PPU drew
  with drawing off (`drawn`: 240 bytes, 1 for a line drawn with it on)."
  [ours theirs mask fine-x drawn]
  (let [[from to] (skipped-columns mask fine-x)]
    (loop [i 0, out (transient [])]
      (if (= i (* 256 240))
        (persistent! out)
        (let [x (bit-and i 0xFF)]
          (recur (inc i)
                 (if (and (<= from x) (< x to)
                          (pos? (bytes/at drawn (bit-shift-right i 8)))
                          (not= (bit-and (bytes/word-at ours i) 0xFFFFFF)
                                (bit-and (bytes/word-at theirs i) 0xFFFFFF)))
                   (conj! out i)
                   out)))))))

(defn ram-diffs
  "The addresses where two copies of RAM differ, as [addr ours theirs].
  `ours` and `theirs` are functions from address to byte."
  [ours theirs]
  (vec (for [a (range 0x800)
             :let [x (ours a) y (theirs a)]
             :when (not= x y)]
         [a x y])))

(defn difference-picture!
  "Fill `out` with a picture of where `ours` and `theirs` differ: ours, dimmed
  to gray, with the differing pixels (`diffs`, from `picture-diffs`) in
  bright magenta, and what's left out striped."
  [out ours diffs mask fine-x drawn]
  (let [[from to] (skipped-columns mask fine-x)]
    (dotimes [i (* 256 240)]
      (let [p (bytes/word-at ours i)
            gray (quot (+ (bit-and p 0xFF) (bit-and (bit-shift-right p 8) 0xFF) (bit-and (bit-shift-right p 16) 0xFF)) 6)
            x (bit-and i 0xFF)
            skipped? (or (< x from) (>= x to) (zero? (bytes/at drawn (bit-shift-right i 8))))]
        (bytes/put-word! out i (if (and skipped? (odd? (+ x (quot i 256))))
                                 0xFF404040
                                 (bit-or 0xFF000000 (* gray 0x010101)))))))
  (doseq [i diffs] (bytes/put-word! out i 0xFFFF00FF))
  out)
