(ns nes.ppu.sprite-search
  "How the PPU finds the sprites on the next line, and when it sets the
  sprite overflow flag, bug and all.

  On each visible line, over dots 65-256, the PPU goes through the 64
  sprites in OAM looking for the ones on the next line, a read every dot
  and a write every other one:

  1. For sprite n, it reads byte 0 (Y). If the sprite is on the line, it
     copies its 4 bytes into 'secondary OAM' (8 dots); if not, it moves on
     (2 dots).
  2. Once 8 are found, there's no room for more, but it carries on looking,
     to set the overflow flag if there's a 9th. And here's the bug: when
     sprite n isn't on the line, it moves to the next sprite *and* the next
     byte at once. So it reads byte 0 of one sprite as its Y, byte 1 of the
     next, byte 2 of the one after, and so on, round and round. If one of
     those bytes, read as a Y, puts its sprite on the line, the flag goes
     up (2 dots after the read starts), whether or not that sprite really
     is on it. Games rarely rely on the flag for this reason.

  (See https://www.nesdev.org/wiki/PPU_sprite_evaluation. blargg's
  sprite_overflow_tests check the timing and the bug.)"
  (:require [nes.bytes :as bytes]))

(defn- on-line? [line y height] (< -1 (- line y) height))

(defn search
  "Search `oam` (256 bytes) on `line` for the sprites on the next one, with
  sprites `height` (8 or 16) tall. Returns {:found [sprite numbers, up to 8]
  :overflow-dot the dot the overflow flag goes up, or nil}."
  [oam line height]
  (loop [n 0, found [], dot 65]
    (cond
      (== n 64) {:found found :overflow-dot nil}

      (< (count found) 8)
      (if (on-line? line (bytes/at oam (* 4 n)) height)
        (recur (inc n) (conj found n) (+ dot 8))
        (recur (inc n) found (+ dot 2)))

      :else
      ;; 8 found: the buggy search for a 9th
      (loop [n n, m 0, dot dot]
        (cond
          (>= n 64) {:found found :overflow-dot nil}
          (on-line? line (bytes/at oam (+ (* 4 n) m)) height) {:found found :overflow-dot (+ dot 1)}
          :else (recur (inc n) (bit-and (inc m) 3) (+ dot 2)))))))
