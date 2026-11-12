(ns gamedev-toolkit.rom
  "Building NES ROM files (.nes, the iNES format) from machine code and
  graphics.

  A cartridge has two ROM chips. PRG ROM holds the program, which the CPU
  sees at the top of its address space. CHR ROM holds the graphics (see
  gamedev-toolkit.chr), which the PPU reads directly. An iNES file is a
  16-byte header describing the cartridge, then the two chips' contents.

  The simplest cartridge, 'NROM' (mapper 0), has no bank switching: 16 KB
  or 32 KB of PRG ROM and 8 KB of CHR ROM, all visible all the time. With
  16 KB the program sits at $C000-$FFFF (and appears again at $8000).

  The CPU finds the program through three 'vectors', addresses stored in
  the last six bytes of memory:

    $FFFA  NMI    where to go at the start of each vertical blank
    $FFFC  RESET  where to start at power on and reset
    $FFFE  IRQ    where to go on an interrupt request"
  (:require [gamedev-toolkit.asm :as asm]
            [gamedev-toolkit.sub :as sub]))

(def prg-bank-size 16384)
(def chr-bank-size 8192)

(defn ines
  "An iNES file, as a vector of bytes.

    :prg        PRG ROM bytes: a multiple of 16 KB
    :chr        CHR ROM bytes: a multiple of 8 KB (empty for CHR RAM)
    :mapper     the iNES mapper number (default 0, NROM)
    :mirroring  :horizontal (default) or :vertical: how the two screens of
                background memory are arranged, which decides which way a
                game can scroll smoothly"
  [{:keys [prg chr mapper mirroring] :or {mapper 0 mirroring :horizontal chr []}}]
  (when (or (empty? prg) (pos? (mod (count prg) prg-bank-size)))
    (throw (ex-info (str "PRG ROM must be a multiple of 16384 bytes, not " (count prg)) {})))
  (when (pos? (mod (count chr) chr-bank-size))
    (throw (ex-info (str "CHR ROM must be a multiple of 8192 bytes, not " (count chr)) {})))
  (when-not (#{:horizontal :vertical} mirroring)
    (throw (ex-info (str "Mirroring is :horizontal or :vertical, not " (pr-str mirroring)) {})))
  (let [header [0x4E 0x45 0x53 0x1A                      ; "NES" and $1A
                (quot (count prg) prg-bank-size)
                (quot (count chr) chr-bank-size)
                ;; the mapper number is split across two bytes
                (bit-or (bit-shift-left (bit-and mapper 0x0F) 4)
                        (if (= mirroring :vertical) 1 0))
                (bit-and mapper 0xF0)
                0 0 0 0 0 0 0 0]]
    (-> header (into prg) (into chr))))

(defn nrom
  "Assemble a program and pack it into an NROM cartridge.

    :code       the program (see gamedev-toolkit.asm). Subroutines it calls
                (see gamedev-toolkit.sub) are put after it
    :vectors    {:nmi label :reset label :irq label}
    :symbols    names for addresses, as for asm/assemble
    :memory     the game's variables, from gamedev-toolkit.memory/allocate:
                their names are added to the symbols
    :chr        8 KB of CHR ROM (see gamedev-toolkit.chr/bank)
    :prg-size   16384 (default) or 32768
    :mirroring  as for `ines`

  Returns {:rom the .nes file's bytes, :prg just the program,
  :labels, :listing (from the assembler), :symbols, :memory,
  :subroutines [{:name :doc} ...], and :source: everything the assembler
  was given ({:org :symbols :code}), for gamedev-toolkit.rom-edn}."
  [{:keys [code vectors symbols memory chr prg-size mirroring]
    :or {prg-size prg-bank-size symbols {} mirroring :horizontal}}]
  (when-not (#{16384 32768} prg-size)
    (throw (ex-info "An NROM cartridge has 16384 or 32768 bytes of PRG ROM" {:prg-size prg-size})))
  (doseq [v [:nmi :reset :irq]]
    (when-not (get vectors v)
      (throw (ex-info (str "No " v " in :vectors. The CPU needs all three: :nmi, :reset and :irq.") {}))))
  (when-let [clash (some #(when (contains? symbols %) %) (keys (:symbols memory)))]
    (throw (ex-info (str clash " is both a variable and one of the :symbols") {:name clash})))
  (let [symbols (merge symbols (:symbols memory))
        {:keys [code subroutines]} (sub/link code)
        org (- 0x10000 prg-size)
        code (asm/flatten-forms [code
                                 [:org 0xFFFA]
                                 [:dw (:nmi vectors) (:reset vectors) (:irq vectors)]])
        {:keys [bytes labels listing]} (asm/assemble code {:org org :symbols symbols})]
    {:rom (ines {:prg bytes :chr chr :mapper 0 :mirroring mirroring})
     :prg bytes
     :labels labels
     :listing listing
     :symbols symbols
     :memory memory
     :subroutines subroutines
     :source {:org org :symbols symbols :code code
              :cartridge {:mapper 0 :mirroring mirroring}
              :chr chr}}))
