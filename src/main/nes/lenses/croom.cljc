(ns nes.lenses.croom
  "A lens for Concentration Room (croom.nes, by Damian Yerrick), the
  sample ROM.

  Found from the REPL with snapshots and `search`/`diff` (see nes.repl):

  - Moving the pointer right and searching for bytes that went up each
    time found its X position at $0055 (and the four sprites that draw it,
    in the OAM buffer at $0200). Down did the same for Y at $0056.
  - $0053 and $0054 went up by one for every 24 pixels: the cards are 24
    pixels apart, so these look like a board column and row for the
    pointer. Not exactly the card it selects, though: with these at
    (2, 4), pressing A flipped the card at (2, 5). Still to be worked out.
  - Flipping a card changed one byte in $0300-$033F, setting bit 6.
    Dumping that page showed an 8x8 board, one byte per cell, stored a
    column at a time (cell = column x 8 + row): $00 for an empty cell,
    otherwise bit 7 set, bit 6 for face up, and the low 6 bits saying
    which picture is on the card. Cards come in pairs with equal pictures.
    $FF (as at $0300) isn't a card; it seems to mark cells off the board.
  - After a mismatched pair, the number at the bottom of the screen went
    from 20 to 19, and so did $003F: the tries left.
  - $0010 goes up by one every frame (the NMI handler increments it, and
    the main loop waits for it to change)."
  (:require [nes.lens :as lens]
            [nes.snapshot :as snapshot]))

(def board-addr 0x0300)

(defn cards
  "The cards on the board in snapshot `s`, with their position and picture."
  [s]
  (vec (for [col (range 8)
             row (range 8)
             :let [v (snapshot/cpu-byte s (+ board-addr (* 8 col) row))]
             ;; $FF isn't a card: it marks cells outside the board
             :when (and (bit-test v 7) (not= v 0xFF))]
         {:col col
          :row row
          :picture (bit-and v 0x3F)
          :face-up? (bit-test v 6)})))

(def sha1
  "Concentration Room v0.02a (croom.nes), without its header. See
  nes.rom/rom-hash."
  "235a3396ef05e5a302d586a70a301fd7e651848b")

(def board
  {:cards [:fn cards]
   :cards-left [:fn #(count (cards %))]
   :face-up [:fn #(filterv :face-up? (cards %))]})

(def player
  [[:tries-left [:u8 0x003F]]
   [:pointer [:struct 0x0055 [:x :u8 :y :u8]]]
   ;; approximate: see the namespace docs
   [:pointer-cell [:struct 0x0053 [:col :u8 :row :u8]]]
   [:frame-counter [:u8 0x0010]]])

(def lens
  "Both lenses' fields together."
  (into player board))

(lens/register! {:rom sha1
                 :name "Pointer & score"
                 :doc "Where the pointer is, and how many tries are left."
                 :fields player})

(lens/register! {:rom sha1
                 :name "Board"
                 :doc "The cards on the table: an 8×8 grid at $0300, one byte per cell."
                 :fields board})
