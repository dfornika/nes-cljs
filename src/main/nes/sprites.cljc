(ns nes.sprites
  "Decoding sprites from the PPU's sprite memory (OAM).

  OAM ('object attribute memory') is 256 bytes inside the PPU: 64 sprites
  of 4 bytes each.

    byte 0  Y position, minus 1 (the sprite's top is on line Y+1)
    byte 1  tile number
    byte 2  attributes:  76543210
                         ||||||++- palette (4 + this: sprites use palettes 4-7)
                         |||+++--- unused
                         ||+------ priority (1 = behind the background)
                         |+------- flip horizontally
                         +-------- flip vertically
    byte 3  X position of the left edge

  A Y of $EF or more puts the sprite below the bottom of the screen, which
  is how games hide sprites they're not using.

  Sprites are 8x8 or (if PPUCTRL says so) 8x16. 8x16 sprites take two
  tiles, and choose their pattern table from bit 0 of the tile number.

  See https://www.nesdev.org/wiki/PPU_OAM"
  (:require [nes.bytes :as bytes]
            [nes.chr :as chr]))

(def sprite-count 64)

(defn sprite
  "Sprite number `i` (0-63) decoded from `oam` (a 256-byte byte array)."
  [oam i]
  (let [base (* 4 i)
        y (bytes/at oam base)
        attr (bytes/at oam (+ base 2))]
    {:index i
     :y y
     :tile (bytes/at oam (+ base 1))
     :attr attr
     :x (bytes/at oam (+ base 3))
     :palette (+ 4 (bit-and attr 3))
     :behind-bg? (bit-test attr 5)
     :flip-h? (bit-test attr 6)
     :flip-v? (bit-test attr 7)
     :hidden? (>= y 0xEF)}))

(defn sprites [oam]
  (mapv #(sprite oam %) (range sprite-count)))

(defn tile-addresses
  "PPU addresses of the tile(s) a sprite is drawn from: one for 8x8
  sprites, two (top then bottom) for 8x16."
  [{:keys [tile]} height sprite-table]
  (if (= height 16)
    (let [table (bit-and tile 1)
          top (bit-and tile 0xFE)]
      [(chr/tile-address table top) (chr/tile-address table (inc top))])
    [(chr/tile-address sprite-table tile)]))

(defn draw-sprite!
  "Draw sprite `s` into `pixels` (an array of 32-bit pixels for an image `width`
  pixels wide) with its top-left corner at (`ox`, `oy`). Applies the
  sprite's flips. Color 0 is transparent, so those pixels are left alone.
  `colors` is the sprite's 4 colors as 0x00BBGGRR; `clip-h` is the image
  height, so sprites hanging off the bottom don't write past the end."
  [pixels width clip-h mem s height sprite-table colors ox oy]
  (let [{:keys [flip-h? flip-v?]} s
        addrs (tile-addresses s height sprite-table)]
    (dotimes [row height]
      (let [src-row (if flip-v? (- height 1 row) row)
            addr (nth addrs (quot src-row 8))
            ty (mod src-row 8)
            py (+ oy row)]
        (when (< -1 py clip-h)
          (dotimes [col 8]
            (let [px (+ ox col)
                  v (chr/pixel mem addr (if flip-h? (- 7 col) col) ty)]
              (when (and (pos? v) (< -1 px width))
                (bytes/put-word! pixels (+ px (* py width))
                      (bit-or 0xFF000000 (nth colors v)))))))))))
