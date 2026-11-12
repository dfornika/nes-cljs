(ns nes.memory-map
  "What lives where in the CPU's and PPU's address spaces.

  The CPU and PPU each have their own 'address space': the range of
  numbers they can put on their address bus. Which chip answers depends
  on the address. These tables are the 'memory map' for each.")

(def memory-maps
  {:cpu
   {:size 0x10000
    :regions
    [{:start 0x0000 :end 0x00FF :name "Zero page" :color "#ddf4ff"
      :about "The first 256 bytes of RAM. Instructions can reach it with 1-byte addresses, so it's faster: games keep their most-used variables here."}
     {:start 0x0100 :end 0x01FF :name "Stack" :color "#fbefff"
      :about "Return addresses for JSR/RTS and interrupts, and values saved with PHA/PHP. Grows downward from $01FF."}
     {:start 0x0200 :end 0x07FF :name "RAM" :color "#dafbe1"
      :about "The rest of the NES's 2 KB of RAM. Many games keep the sprite list ('OAM buffer') at $0200-$02FF."}
     {:start 0x0800 :end 0x1FFF :name "RAM mirrors" :color "#eef1f4"
      :about "The same 2 KB of RAM, repeated three more times. The chip only looks at the low 11 address bits."}
     {:start 0x2000 :end 0x2007 :name "PPU registers" :color "#ffebe9" :io? true
      :about "The CPU's window into the PPU: control, status, scroll, and the address/data ports for writing PPU memory."}
     {:start 0x2008 :end 0x3FFF :name "PPU register mirrors" :color "#eef1f4" :io? true
      :about "The 8 PPU registers repeated every 8 bytes."}
     {:start 0x4000 :end 0x4017 :name "APU & I/O registers" :color "#fff1e5" :io? true
      :about "Sound channels, sprite DMA ($4014), and the controller ports ($4016-$4017)."}
     {:start 0x4018 :end 0x401F :name "Test registers" :color "#eef1f4" :io? true
      :about "CPU test functions, normally disabled."}
     {:start 0x4020 :end 0x5FFF :name "Expansion" :color "#eef1f4" :io? true
      :about "Available to the cartridge; most don't use it."}
     {:start 0x6000 :end 0x7FFF :name "Cartridge RAM" :color "#fff8c5"
      :about "Extra RAM on some cartridges, often battery-backed for saved games."}
     {:start 0x8000 :end 0xFFF9 :name "PRG ROM" :color "#f6f8fa"
      :about "The game's program, from the cartridge. Mappers can swap which banks appear here."}
     {:start 0xFFFA :end 0xFFFF :name "Interrupt vectors" :color "#ffd8b5"
      :about "Three addresses the CPU jumps to: NMI ($FFFA), RESET ($FFFC) and IRQ/BRK ($FFFE)."}]}
   :ppu
   {:size 0x4000
    :regions
    (into
     [{:start 0x0000 :end 0x0FFF :name "Pattern table 0" :color "#ddf4ff"
       :about "256 tiles of graphics, 16 bytes each (see the Tiles tab)."}
      {:start 0x1000 :end 0x1FFF :name "Pattern table 1" :color "#fbefff"
       :about "Another 256 tiles. Usually one table is for the background, the other for sprites."}]
     (concat
      (for [n (range 4)
            :let [base (+ 0x2000 (* n 0x400))]]
        [{:start base :end (+ base 0x3BF) :name (str "Nametable " n) :color "#dafbe1"
          :about "The background layout: 32 x 30 tile numbers, one byte per 8x8 square of the screen."}
         {:start (+ base 0x3C0) :end (+ base 0x3FF) :name (str "Attribute table " n) :color "#fff8c5"
          :about "Which palette each 16x16 pixel area of the background uses, 2 bits each."}])
      [[{:start 0x3000 :end 0x3EFF :name "Nametable mirrors" :color "#eef1f4"
         :about "A repeat of $2000-$2EFF."}
        {:start 0x3F00 :end 0x3F0F :name "Background palettes" :color "#ffebe9"
         :about "4 palettes of 4 colors. Each byte is a color number from the NES's 64-color master palette."}
        {:start 0x3F10 :end 0x3F1F :name "Sprite palettes" :color "#fff1e5"
         :about "4 more palettes, for sprites. Color 0 of each is transparent."}
        {:start 0x3F20 :end 0x3FFF :name "Palette mirrors" :color "#eef1f4"
         :about "The 32 palette bytes repeated."}]]))}})

;; Flatten the nested PPU region lists above.
(def ^:private regions-by-space
  {:cpu (get-in memory-maps [:cpu :regions])
   :ppu (vec (flatten (get-in memory-maps [:ppu :regions])))})

(defn region-at
  "The region of address space `space` (:cpu or :ppu) containing `addr`."
  [space addr]
  (some #(when (<= (:start %) addr (:end %)) %) (regions-by-space space)))
