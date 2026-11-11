(ns games.hello
  "Hello, NES: the smallest program that feels like a game. A face on a
  colored screen; the D-pad moves it, and A changes the background color.

  The whole cartridge is built from the data in this file by
  gamedev-toolkit: `(build)` returns a .nes file's bytes. At the REPL,
  `(play! (hello/build))` runs it; change something here and run that
  again.

  How an NES program is organized:

  - RESET is where the CPU starts. It sets the hardware up, then falls
    into the main loop.
  - The main loop runs the game's logic once per frame: read the
    controller, move things, and write down where the sprites should be,
    in ordinary memory (a 'shadow' copy of sprite memory, at $0200).
    Then it waits for the next frame.
  - NMI is an interrupt that the picture chip (PPU) raises 60 times a
    second, when it has finished drawing a frame ('vertical blank'). Only
    then is it safe to change what's on screen, so the NMI handler copies
    the shadow sprites to the PPU and changes any colors, quickly, before
    drawing starts again.

  The code is written at three levels: single instructions
  ([:sta :ppuctrl]), code generators (functions that return instructions,
  from gamedev-toolkit.gen or defined here), and subroutines (code that
  lives in one place in the ROM and is called: gamedev-toolkit.sub)."
  (:require [gamedev-toolkit.chr :as chr]
            [gamedev-toolkit.gen :as gen]
            [gamedev-toolkit.hardware :as hw]
            [gamedev-toolkit.memory :as memory]
            [gamedev-toolkit.rom :as rom]
            [gamedev-toolkit.sub :as sub :refer [call]]))

;; ---------------------------------------------------------------------------
;; Memory: the program's variables. The allocator picks their addresses
;; (see gamedev-toolkit.memory); the code below only uses their names. The
;; same declarations become the game's lens in the Lens tab, and the names
;; in the CPU tab's disassembly.

(def memory
  (memory/allocate
   [[:frame        :u8 {:doc "Counts frames; the NMI handler adds 1"}]
    [:buttons      [:bits hw/button-bits] {:doc "Buttons held down now"}]
    [:prev-buttons [:bits hw/button-bits] {:doc "...and on the frame before"}]
    [:pressed      [:bits hw/button-bits] {:doc "Buttons that went down this frame"}]
    [:player       {:x :u8, :y :u8}]
    [:color        :u8 {:doc "Which of `backgrounds` to show"}]
    ;; the shadow copy of sprite memory: 64 sprites x 4 bytes. It has to be
    ;; a whole page, and $0200 is where everyone puts it.
    [:oam          [:array 256 :u8] {:at 0x0200 :hide true}]]))

;; ---------------------------------------------------------------------------
;; Graphics and colors

(def face
  ["....11111111...."
   "..112222222211.."
   ".12222222222221."
   ".12222222222221."
   "1222112222112221"
   "1222132222132221"
   "1222112222112221"
   "1222222222222221"
   "1222222222222221"
   "1221222222221221"
   "1222122222212221"
   ".12221111112221."
   ".12222222222221."
   "..112222222211.."
   "....11111111...."
   "................"])

(def tiles
  "Tile 0 is blank (the background is filled with it); 1-4 are the face."
  (into [chr/blank] (chr/tiles face)))

(def face-sprites
  "The face is 16x16, and a sprite is 8x8, so it takes four sprites that
  move together (a 'metasprite'): [tile x-offset y-offset]"
  [[1 0 0] [2 8 0]
   [3 0 8] [4 8 8]])

;; Colors are numbers from the NES's fixed set of 64
;; (https://www.nesdev.org/wiki/PPU_palettes; the Palettes tab shows them).
(def backgrounds
  "The background colors that A steps through. Eight, so that
  `color AND 7` is always a valid index."
  [0x21 0x2A 0x16 0x14 0x27 0x11 0x00 0x0F])

(def palettes
  "Four background palettes, then four sprite palettes, of four colors
  each. The first color of all is the background color; the first of each
  other palette is unused (it means 'transparent'). The first sprite
  palette's first entry is the same memory as the background color, so it
  gets the same value."
  (let [bg (first backgrounds)]
    [bg 0x0F 0x10 0x30,  0 0 0 0,  0 0 0 0,  0 0 0 0
     bg 0x0F 0x28 0x30,  0 0 0 0,  0 0 0 0,  0 0 0 0]))  ; the face: black, yellow, white

;; ---------------------------------------------------------------------------
;; Code generators: functions that return instructions

(defn wait-for-vblank
  "Wait until the PPU starts a vertical blank. BIT copies bit 7 of
  PPUSTATUS (the 'in vertical blank' bit) to the negative flag; keep doing
  that while it's clear ('positive')."
  []
  (gen/do-while :positive
    [:bit :ppustatus]))

(defn set-video-address
  "Point PPUADDR at a video memory address: high byte, then low."
  [addr]
  [[:lda [:imm [:hi addr]]] [:sta :ppuaddr]
   [:lda [:imm [:lo addr]]] [:sta :ppuaddr]])

(defn when-button
  "Run `body` if `button` (:a, :left...) is set in the variable `in`.
  AND leaves only that button's bit, so the result is zero if it's up."
  [in button & body]
  [[:lda in]
   [:and [:imm (hw/buttons button)]]
   (gen/when-flag :not-zero body)])

;; ---------------------------------------------------------------------------
;; The program

(def reset
  [[:label :reset]
   [:sei]                               ; no interrupts while setting up
   [:cld]                               ; decimal mode off (the NES has none)
   [:ldx [:imm 0x40]] [:stx :apu-frame] ; turn off the sound chip's interrupt
   [:ldx [:imm 0xFF]] [:txs]            ; the stack starts at $01FF
   [:inx]                               ; X = 0
   [:stx :ppuctrl]                      ; NMI off
   [:stx :ppumask]                      ; picture off
   [:stx :dmc-freq]                     ; sample interrupts off

   ;; The PPU needs about two frames to warm up before it will listen.
   (wait-for-vblank)

   ;; Meanwhile, clear all 2 KB of RAM: it holds garbage at power on.
   [:lda [:imm 0]]
   [:label :clear-ram]
   (for [page (range 0 0x800 0x100)]
     [:sta page :x])
   [:inx]
   [:bne :clear-ram]

   ;; Sprites can't be turned off one by one, but a sprite with a Y of
   ;; 240 or more is off the bottom of the screen. Hide all 64.
   (gen/fill :oam 256 [:imm 0xFF])

   (wait-for-vblank)

   ;; Fill the background (1024 bytes: 4 x 256) with tile 0.
   (set-video-address (:nametable-0 hw/video))
   [:lda [:imm 0]]
   (gen/times 4 {:with :y}
     (gen/times 256
       [:sta :ppudata]))

   ;; Copy the 32 palette colors to video memory.
   (set-video-address (:palettes hw/video))
   [:ldx [:imm 0]]
   [:label :load-palettes]
   [:lda :palettes :x]
   [:sta :ppudata]
   [:inx]
   [:cpx [:imm (count palettes)]]
   [:bne :load-palettes]

   ;; Start in the middle of the screen.
   (gen/move :player/x [:imm 120])
   (gen/move :player/y [:imm 112])

   ;; NMI on. The NMI handler turns the picture on.
   [:lda [:imm 0x80]] [:sta :ppuctrl]])

(def read-controller
  (sub/sub
   :read-controller
   "Read controller 1 into :buttons, and work out :pressed.

  Writing 1 then 0 to JOY1 makes the controller take a snapshot of its
  buttons. Each read of JOY1 then gives the next button in bit 0: A, B,
  Select, Start, Up, Down, Left, Right. LSR moves that bit into the carry
  flag, and ROL moves the carry into :buttons from the right.

  The loop needs no counter: :buttons starts as 1, and when that 1 has
  been pushed all the way out of the left side (after 8 ROLs), it lands in
  the carry flag and the loop stops."
   [(gen/move :prev-buttons :buttons)
    [:lda [:imm 1]]
    [:sta :joy1]
    [:sta :buttons]
    [:lsr :a]                           ; A = 0
    [:sta :joy1]
    (gen/do-while :no-carry
      [:lda :joy1]
      [:lsr :a]
      [:rol :buttons])
    ;; pressed = held now, and not held before
    [:lda :prev-buttons]
    [:eor [:imm 0xFF]]
    [:and :buttons]
    [:sta :pressed]]))

(def move-player
  (sub/sub
   :move-player
   "Move the player with the D-pad; A steps to the next background color."
   [(when-button :buttons :right [:inc :player/x])
    (when-button :buttons :left  [:dec :player/x])
    (when-button :buttons :down  [:inc :player/y])
    (when-button :buttons :up    [:dec :player/y])
    (when-button :pressed :a     [:inc :color])]))

(def draw-player
  (sub/sub
   :draw-player
   "Write the face's four sprites to the shadow sprite memory. A sprite is
  4 bytes: Y, tile number, attributes (palette, flipping), X.

  The `for` runs when the ROM is built, not on the NES: it writes out the
  same few instructions four times with different numbers."
   (for [[i [tile dx dy]] (map-indexed vector face-sprites)
         :let [sprite [:+ :oam (* 4 i)]]]
     [[:lda :player/y] [:clc] [:adc [:imm dy]] [:sta sprite]
      [:lda [:imm tile]]                       [:sta [:+ sprite 1]]
      [:lda [:imm 0]]                          [:sta [:+ sprite 2]]   ; sprite palette 0
      [:lda :player/x] [:clc] [:adc [:imm dx]] [:sta [:+ sprite 3]]])))

(def main
  [[:label :main]
   (call read-controller)
   (call move-player)
   (call draw-player)
   ;; Wait until the NMI handler has counted another frame.
   [:lda :frame]
   [:label :wait-for-frame]
   [:cmp :frame]
   [:beq :wait-for-frame]
   [:jmp :main]])

(def nmi
  "Runs at the start of every vertical blank, interrupting the main loop
  wherever it is. So it first saves the registers the main loop was
  using, on the stack, and puts them back at the end."
  [[:label :nmi]
   [:pha] [:txa] [:pha] [:tya] [:pha]

   ;; Copy the shadow sprites (the page at :oam) to the PPU's sprite memory.
   [:lda [:imm 0]] [:sta :oamaddr]
   [:lda [:imm [:hi :oam]]] [:sta :oamdma]

   ;; Set the background color.
   [:bit :ppustatus]                    ; make PPUADDR expect a high byte
   (set-video-address (:palettes hw/video))
   [:lda :color]
   [:and [:imm 7]]
   [:tax]
   [:lda :backgrounds :x]
   [:sta :ppudata]

   ;; Writing PPUADDR moves where the picture is drawn from, so put that
   ;; back: no scrolling, NMI still on, picture (background and sprites) on.
   [:lda [:imm 0x80]] [:sta :ppuctrl]
   [:lda [:imm 0]] [:sta :ppuscroll] [:sta :ppuscroll]
   [:lda [:imm 0x1E]] [:sta :ppumask]

   [:inc :frame]                        ; tell the main loop a frame has passed

   [:pla] [:tay] [:pla] [:tax] [:pla]
   [:rti]])

(def irq
  "Nothing here asks for IRQs, but the vector has to point somewhere."
  [[:label :irq]
   [:rti]])

(def data
  [[:label :palettes]    [:db palettes]
   [:label :backgrounds] [:db backgrounds]])

(defn build
  "Build the cartridge. Returns {:rom the .nes file's bytes, :name,
  :labels, :listing, :symbols, :memory, :subroutines}."
  []
  (assoc (rom/nrom {:code [reset main nmi irq data]
                    :vectors {:nmi :nmi :reset :reset :irq :irq}
                    :symbols hw/registers
                    :memory memory
                    :chr (chr/bank tiles)})
         :name "hello.nes"))
