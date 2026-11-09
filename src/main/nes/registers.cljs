(ns nes.registers
  "Names and meanings of the NES's memory-mapped I/O registers.

  The CPU has no special instructions for talking to other chips. Instead,
  some addresses don't lead to memory at all: reading or writing them
  talks to the PPU, the sound hardware, or the controllers. These are
  'memory-mapped registers'.

  See https://www.nesdev.org/wiki/PPU_registers and
  https://www.nesdev.org/wiki/APU_registers")

(defn- bit? [v n] (bit-test v n))

(defn- hex [n width]
  (str "$" (.padStart (.toUpperCase (.toString n 16)) width "0")))

(def ^:private ppu-registers
  ;; Only the low 3 bits of the address matter: $2000-$2007 repeat to $3FFF.
  [{:name "PPUCTRL" :about "PPU control: NMI on/off, sprite size, which pattern tables to use"
    :decode-write
    (fn [v]
      (str "NMI " (if (bit? v 7) "on" "off")
           ", sprites 8×" (if (bit? v 5) 16 8)
           ", BG tiles " (if (bit? v 4) "$1000" "$0000")
           ", sprite tiles " (if (bit? v 3) "$1000" "$0000")
           ", PPUDATA step " (if (bit? v 2) 32 1)
           ", nametable " (bit-and v 3)))}
   {:name "PPUMASK" :about "PPU mask: show/hide background and sprites, color effects"
    :decode-write
    (fn [v]
      (str "BG " (if (bit? v 3) "shown" "hidden")
           ", sprites " (if (bit? v 4) "shown" "hidden")
           (when (bit? v 0) ", grayscale")
           (when (pos? (bit-and v 0xE0)) ", color emphasis")))}
   {:name "PPUSTATUS" :about "PPU status: in vertical blank? sprite 0 hit? Reading it also resets the PPUSCROLL/PPUADDR latch."
    :decode-read
    (fn [v]
      (str "vblank " (if (bit? v 7) "yes" "no")
           (when (bit? v 6) ", sprite 0 hit")
           (when (bit? v 5) ", sprite overflow")))}
   {:name "OAMADDR" :about "Which byte of sprite memory (OAM) the next OAMDATA access uses"}
   {:name "OAMDATA" :about "Read/write one byte of sprite memory (OAM)"}
   {:name "PPUSCROLL" :about "Scroll position: written twice, X then Y"}
   {:name "PPUADDR" :about "Address in PPU memory for PPUDATA: written twice, high byte then low byte"}
   {:name "PPUDATA" :about "Read/write PPU memory at the PPUADDR address, which then moves on by 1 or 32"}])

(def ^:private apu-io-registers
  (merge
   (into {} (for [[base channel] [[0x4000 "Pulse 1"] [0x4004 "Pulse 2"]]
                  [i what] (map-indexed vector ["duty/volume" "sweep" "timer low" "length/timer high"])]
              [(+ base i) {:name (str "SQ" (if (= base 0x4000) 1 2) "_" (nth ["VOL" "SWEEP" "LO" "HI"] i))
                           :about (str channel " channel: " what)}]))
   {0x4008 {:name "TRI_LINEAR" :about "Triangle channel: linear counter"}
    0x4009 {:name "APU_UNUSED" :about "Unused"}
    0x400A {:name "TRI_LO" :about "Triangle channel: timer low"}
    0x400B {:name "TRI_HI" :about "Triangle channel: length/timer high"}
    0x400C {:name "NOISE_VOL" :about "Noise channel: volume"}
    0x400D {:name "APU_UNUSED" :about "Unused"}
    0x400E {:name "NOISE_LO" :about "Noise channel: period"}
    0x400F {:name "NOISE_HI" :about "Noise channel: length"}
    0x4010 {:name "DMC_FREQ" :about "Sample channel: rate"}
    0x4011 {:name "DMC_RAW" :about "Sample channel: direct output level"}
    0x4012 {:name "DMC_START" :about "Sample channel: sample address"}
    0x4013 {:name "DMC_LEN" :about "Sample channel: sample length"}
    0x4014 {:name "OAMDMA" :about "Sprite DMA: copy 256 bytes from CPU page $XX00 into sprite memory (OAM)"
            :decode-write (fn [v] (str "copy " (hex (* v 256) 4) "–" (hex (+ 255 (* v 256)) 4) " → OAM"))}
    0x4015 {:name "SND_CHN" :about "Sound channels on/off (write) and status (read)"}
    0x4016 {:name "JOY1" :about "Controllers: write 1 then 0 to latch the buttons; read controller 1, one button per read"
            :decode-write (fn [v] (if (bit? v 0) "strobe on (latch buttons)" "strobe off"))
            :decode-read (fn [v] (str "button " (if (bit? v 0) "pressed" "not pressed")))}
    0x4017 {:name "JOY2" :about "Read controller 2; write: APU frame counter"
            :decode-read (fn [v] (str "button " (if (bit? v 0) "pressed" "not pressed")))}}))

(defn register-at
  "Info about the I/O register at CPU address `addr`, or nil if there isn't
  one: `:name`, `:about`, and maybe `:decode-read` / `:decode-write`
  functions from a value to a description."
  [addr]
  (cond
    (<= 0x2000 addr 0x3FFF) (nth ppu-registers (bit-and addr 7))
    (<= 0x4000 addr 0x4017) (apu-io-registers addr)
    (<= 0x8000 addr 0xFFFF) {:name "MAPPER"
                             :about "Writing to ROM does nothing to the ROM, but a mapper chip on the cartridge can watch for these writes and switch banks"}
    :else nil))

(defn write-only?
  "True for registers that can only be written. Reading one gets 'open
  bus': whatever value was last on the data bus, not anything from the
  register."
  [addr]
  (or (and (<= 0x2000 addr 0x3FFF) (contains? #{0 1 3 5 6} (bit-and addr 7)))
      (<= 0x4000 addr 0x4014)))

(defn describe
  "A one-line description of a value read from or written to `addr`."
  [addr value write?]
  (if (and (not write?) (write-only? addr))
    ;; Usually the 6502's 'dummy read': indexed stores like STA $4000,X
    ;; read the address once before writing to it.
    "write-only register: this read gets open bus (probably a 6502 dummy read)"
    (when-let [{:keys [decode-read decode-write]} (register-at addr)]
      (let [decode (if write? decode-write decode-read)]
        (when decode (decode value))))))
