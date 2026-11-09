(ns nes.rom
  "Reading NES ROM files.

  Most NES ROMs found today are in the iNES format (`.nes` files). An iNES
  file is a 16-byte header followed by the cartridge's contents:

    [header: 16 bytes] [trainer: 512 bytes, rare] [PRG ROM] [CHR ROM]

  - PRG ROM ('program') is the game's code and data, which the CPU reads.
    It comes in 16 KB banks.
  - CHR ROM ('character') is the graphics: 8x8 pixel tiles that the PPU
    draws to the screen. It comes in 8 KB banks. Some games have no CHR
    ROM and instead use CHR RAM, which the game fills in itself.

  The header says how big each part is, and which 'mapper' the cartridge
  uses. A mapper is extra hardware on the cartridge that lets a game hold
  more PRG/CHR than the NES can see at once, by swapping banks in and out.

  See https://www.nesdev.org/wiki/INES"
  (:require [nes.sha1 :refer [sha1]]))

(def ^:private magic
  "Every iNES file starts with these four bytes: 'N' 'E' 'S' and $1A."
  [0x4E 0x45 0x53 0x1A])

(def prg-bank-size 16384)
(def chr-bank-size 8192)

(defn ines?
  "True if `bytes` (a Uint8Array) starts with the iNES magic number."
  [^js bytes]
  (and (>= (.-length bytes) 16)
       (= magic (mapv #(aget bytes %) (range 4)))))

(defn- bit? [byte n]
  (bit-test byte n))

(defn parse-header
  "Decode the 16-byte iNES header of `bytes` (a Uint8Array) into a map.

  Returns nil if `bytes` isn't an iNES file."
  [^js bytes]
  (when (ines? bytes)
    (let [prg-banks (aget bytes 4)
          chr-banks (aget bytes 5)
          flags6    (aget bytes 6)
          flags7    (aget bytes 7)
          ;; The mapper number is split across two bytes: its low four
          ;; bits are the top half of flags 6, its high four bits are the
          ;; top half of flags 7.
          mapper    (bit-or (bit-shift-right flags6 4)
                            (bit-and flags7 0xF0))]
      {:prg-banks   prg-banks
       :prg-size    (* prg-banks prg-bank-size)
       :chr-banks   chr-banks
       :chr-size    (* chr-banks chr-bank-size)
       :chr-ram?    (zero? chr-banks)
       :mapper      mapper
       ;; How the PPU's two screens' worth of background ('nametables')
       ;; are laid out, which decides which way the game can scroll.
       :mirroring   (cond (bit? flags6 3) :four-screen
                          (bit? flags6 0) :vertical
                          :else           :horizontal)
       :battery?    (bit? flags6 1)
       :trainer?    (bit? flags6 2)
       :nes2?       (= 2 (bit-and (bit-shift-right flags7 2) 3))})))

(def mapper-names
  "Names of the most common mappers, by iNES mapper number."
  {0 "NROM" 1 "MMC1" 2 "UxROM" 3 "CNROM" 4 "MMC3" 7 "AxROM"
   9 "MMC2" 10 "MMC4" 11 "Color Dreams" 66 "GxROM"})

(defn rom-hash
  "The SHA-1 of the ROM's contents without its 16-byte header, as hex.
  This identifies a game however its file is named, and is the hash ROM
  databases like No-Intro list (as \"SHA-1 without header\")."
  [^js bytes]
  (sha1 (.subarray bytes 16)))
