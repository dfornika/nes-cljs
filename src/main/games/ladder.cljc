(ns games.ladder
  "A ladder of tiny programs, each one rung above the last, from a
  cartridge that does nothing at all up to something you can steer.

  Read it from the top. Each rung adds one idea about the NES, and brings
  in one more piece of gamedev-toolkit to say it with:

    nil     a valid cartridge that does nothing        the assembler, rom/nrom
    color   one change to the picture                  names for the hardware
    pulse   the smallest game loop: frames             generators (functions)
    pad     input                                      memory/allocate
    sprite  something to move                          tiles drawn as text

  After that come games.hello (subroutines, fixed point) and games.catch
  (the runtime, states, screens).

  At the REPL: (play! (ladder/build :nil)), then :color, and so on. Load
  one and open the CPU tab: these are small enough to step through from
  the first instruction to the last."
  (:require [gamedev-toolkit.chr :as chr]
            [gamedev-toolkit.gen :as gen]
            [gamedev-toolkit.hardware :as hw]
            [gamedev-toolkit.memory :as memory]
            [gamedev-toolkit.rom :as rom]))

;; ---------------------------------------------------------------------------
;; nil
;;
;; The CPU starts wherever the RESET vector points, so a cartridge needs
;; one instruction to point it at. This one jumps to itself, forever. The
;; whole program is three bytes: 4C 00 C0, 'JMP $C000'.
;;
;; The other two vectors (NMI and IRQ) have to hold something. Neither
;; interrupt ever happens here, so they point at the same place.

(def nil-code
  [[:label :reset]
   [:jmp :reset]])

(defn build-nil []
  (rom/nrom {:code nil-code
             :vectors {:reset :reset :nmi :reset :irq :reset}}))

;; ---------------------------------------------------------------------------
;; color
;;
;; The smallest visible effect: change the backdrop color, then stop.
;;
;; Everything the program does to the outside world is a write to a
;; hardware register, an address that's wired to a chip instead of memory.
;; hw/registers names them, and passing those names as :symbols lets the
;; code say :ppuaddr instead of $2006.
;;
;; Colors live in the picture chip's (PPU's) own memory at $3F00. To write
;; there: give PPUADDR the address, high byte then low, then write the
;; color to PPUDATA. But the PPU ignores writes until it has warmed up,
;; which takes about two frames, so first wait for two vertical blanks:
;; bit 7 of PPUSTATUS says when one starts.

(def sky-blue 0x21)

(def color-code
  [[:label :reset]
   [:label :wait-1]
   [:bit :ppustatus]                    ; bit 7 of PPUSTATUS -> the negative flag
   [:bpl :wait-1]                       ; not set yet ('plus'): look again
   [:label :wait-2]
   [:bit :ppustatus]
   [:bpl :wait-2]

   [:lda [:imm 0x3F]] [:sta :ppuaddr]   ; address $3F00: the backdrop color
   [:lda [:imm 0x00]] [:sta :ppuaddr]
   [:lda [:imm sky-blue]]
   [:sta :ppudata]

   ;; While the picture is off, the PPU shows the color PPUADDR is left
   ;; pointing at, if that's in the palette. Point it somewhere else.
   [:lda [:imm 0x00]] [:sta :ppuaddr] [:sta :ppuaddr]

   [:label :forever]
   [:jmp :forever]])

(defn build-color []
  (rom/nrom {:code color-code
             :symbols hw/registers
             :vectors {:reset :reset :nmi :forever :irq :forever}}))

;; ---------------------------------------------------------------------------
;; pulse
;;
;; The smallest game loop. A game does a little, 60 times a second, in
;; step with the picture. The PPU keeps time: with bit 7 of PPUCTRL set,
;; it interrupts the CPU at the start of every vertical blank (an 'NMI').
;; So there are two pieces of code now:
;;
;;   the main loop   decide what this frame looks like; then wait
;;   the NMI handler put it on screen (only safe during vertical blank),
;;                   and count the frame, which is what the main loop
;;                   is waiting for
;;
;; Here 'deciding' is choosing a color from the frame count, so the screen
;; steps through colors, a new one every 16 frames.
;;
;; The waiting loops and the PPUADDR dance from the last rung appear again,
;; so they become functions that return those instructions: generators.

(defn wait-for-vblank []
  (gen/do-while :positive
    [:bit :ppustatus]))

(defn video-address [addr]
  [[:lda [:imm (bit-shift-right addr 8)]] [:sta :ppuaddr]
   [:lda [:imm (bit-and addr 0xFF)]]      [:sta :ppuaddr]])

(defn show-backdrop
  "In the NMI handler: make `color` (an operand for LDA) the backdrop."
  [color]
  [[:bit :ppustatus]                    ; make PPUADDR expect a high byte
   (video-address 0x3F00)
   [:lda color]
   [:sta :ppudata]
   (video-address 0x0000)])

(defn wait-for-frame
  "In the main loop: wait until the NMI handler has counted a frame."
  []
  [[:lda :frame]
   [:label :wait-for-frame]
   [:cmp :frame]
   [:beq :wait-for-frame]])

(def pulse-code
  [[:label :reset]
   (wait-for-vblank)
   (wait-for-vblank)
   [:lda [:imm 0x80]] [:sta :ppuctrl]   ; NMI on

   [:label :main]
   [:lda :frame]
   [:lsr :a] [:lsr :a] [:lsr :a] [:lsr :a]   ; frame / 16
   [:and [:imm 0x07]]                   ; 0 to 7, round and round
   [:clc] [:adc [:imm 0x21]]            ; colors $21 to $28: blue round to yellow
   [:sta :color]
   (wait-for-frame)
   [:jmp :main]

   [:label :nmi]
   [:pha]                               ; the main loop was using A
   (show-backdrop :color)
   [:inc :frame]
   [:pla]
   [:rti]])

(defn build-pulse []
  (rom/nrom {:code pulse-code
             ;; two variables, at addresses picked by hand (for the last time)
             :symbols (merge hw/registers {:frame 0x00 :color 0x01})
             :vectors {:reset :reset :nmi :nmi :irq :nmi}}))

;; ---------------------------------------------------------------------------
;; pad
;;
;; Input. Left and Right on the D-pad step through a table of colors.
;;
;; The controller is read one button at a time: write 1 then 0 to JOY1 to
;; make it take a snapshot, then each read gives the next button in bit 0.
;; Eight reads, shifted into one byte, and the main loop knows what's held.
;; To act once per press rather than 60 times a second, it also keeps last
;; frame's byte: a button was just pressed if it's down now and wasn't then.
;;
;; That's five variables. Rather than pick five addresses, declare them
;; and let memory/allocate place them.

(def pad-memory
  (memory/allocate
   [[:frame    :u8]
    [:color    :u8 {:doc "The backdrop color, for the NMI handler to show"}]
    [:choice   :u8 {:doc "Which of the colors in the table"}]
    [:buttons  [:bits hw/button-bits]]
    [:previous [:bits hw/button-bits]]
    [:pressed  [:bits hw/button-bits]]]))

(def colors [0x21 0x2C 0x2A 0x28 0x27 0x16 0x14 0x12])

(defn read-controller []
  [(gen/move :previous :buttons)
   [:lda [:imm 1]]
   [:sta :joy1]
   [:sta :buttons]                      ; a 1 that falls out after 8 shifts
   [:lsr :a]
   [:sta :joy1]
   (gen/do-while :no-carry
     [:lda :joy1]
     [:lsr :a]                          ; the button -> carry
     [:rol :buttons])                   ; carry -> :buttons
   [:lda :previous]
   [:eor [:imm 0xFF]]
   [:and :buttons]
   [:sta :pressed]])

(defn when-button
  "Run `body` if `button` is set in the variable `in`."
  [in button & body]
  [[:lda in]
   [:and [:imm (hw/buttons button)]]
   (gen/when-flag :not-zero body)])

(def pad-code
  [[:label :reset]
   (wait-for-vblank)
   (wait-for-vblank)
   [:lda [:imm 0x80]] [:sta :ppuctrl]

   [:label :main]
   (read-controller)
   (when-button :pressed :right [:inc :choice])
   (when-button :pressed :left  [:dec :choice])
   [:lda :choice]
   [:and [:imm 7]]                      ; 8 colors: wrap round
   [:tax]
   [:lda :colors :x]                    ; look it up in the table
   [:sta :color]
   (wait-for-frame)
   [:jmp :main]

   [:label :nmi]
   [:pha]
   (show-backdrop :color)
   [:inc :frame]
   [:pla]
   [:rti]

   [:label :colors]
   [:db colors]])

(defn build-pad []
  (rom/nrom {:code pad-code
             :symbols hw/registers
             :memory pad-memory
             :vectors {:reset :reset :nmi :nmi :irq :nmi}}))

;; ---------------------------------------------------------------------------
;; sprite
;;
;; Something to move: a ball, steered with the D-pad.
;;
;; A sprite is an 8x8 tile the PPU draws wherever it's told. The PPU has
;; its own memory for 64 of them, four bytes each: Y, tile, attributes, X.
;; The program can't write there directly at any moment, so it keeps a
;; copy in ordinary memory (a page of it, at $0200) and asks for the whole
;; page to be copied every vertical blank, by writing the page number to
;; OAMDMA.
;;
;; A tile is graphics, the other chip on the cartridge (CHR ROM): drawn
;; here as text. And the picture has to be turned on (PPUMASK), which
;; means the NMI handler now also says where the picture starts (scroll).

(def ball
  ["..1111.."
   ".122221."
   "12322221"
   "12222221"
   "12222221"
   "12222221"
   ".122221."
   "..1111.."])

(def sprite-memory
  (memory/allocate
   [[:frame    :u8]
    [:buttons  [:bits hw/button-bits]]
    [:previous [:bits hw/button-bits]]
    [:pressed  [:bits hw/button-bits]]
    [:ball     {:x :u8 :y :u8}]
    [:oam      [:array 256 :u8] {:at 0x0200 :hide true}]]))

(def sprite-code
  [[:label :reset]
   (wait-for-vblank)
   (gen/fill :oam 256 [:imm 0xFF])      ; a Y of 255 is off screen: hide all 64
   (wait-for-vblank)

   ;; colors: the backdrop, then the first sprite palette's three
   (video-address 0x3F00) [:lda [:imm 0x0F]] [:sta :ppudata]
   (video-address 0x3F11)
   (for [color [0x0F 0x16 0x30]]        ; black, red, white
     [[:lda [:imm color]] [:sta :ppudata]])

   (gen/move :ball/x [:imm 124])
   (gen/move :ball/y [:imm 116])
   [:lda [:imm 0x80]] [:sta :ppuctrl]

   [:label :main]
   (read-controller)
   (when-button :buttons :right [:inc :ball/x])
   (when-button :buttons :left  [:dec :ball/x])
   (when-button :buttons :down  [:inc :ball/y])
   (when-button :buttons :up    [:dec :ball/y])
   ;; sprite 0, in the copy: Y, tile, attributes, X
   (gen/move :oam :ball/y)
   (gen/move [:+ :oam 1] [:imm 1])
   (gen/move [:+ :oam 2] [:imm 0])
   (gen/move [:+ :oam 3] :ball/x)
   (wait-for-frame)
   [:jmp :main]

   [:label :nmi]
   [:pha]
   [:lda [:imm 0]] [:sta :oamaddr]
   [:lda [:imm [:hi :oam]]] [:sta :oamdma]   ; copy the page of sprites
   [:lda [:imm 0x80]] [:sta :ppuctrl]
   [:lda [:imm 0]] [:sta :ppuscroll] [:sta :ppuscroll]
   [:lda [:imm 0x14]] [:sta :ppumask]   ; show sprites, right to the left edge
   [:inc :frame]
   [:pla]
   [:rti]])

(defn build-sprite []
  (rom/nrom {:code sprite-code
             :symbols hw/registers
             :memory sprite-memory
             :chr (chr/bank [chr/blank (chr/tile ball)])
             :vectors {:reset :reset :nmi :nmi :irq :nmi}}))

;; ---------------------------------------------------------------------------

(def rungs
  "The ladder, bottom rung first."
  [{:id :nil    :build build-nil
    :about "A valid cartridge that does nothing: one instruction, jumping to itself."}
   {:id :color  :build build-color
    :about "One change to the picture: wait for the PPU, set the backdrop color, stop."}
   {:id :pulse  :build build-pulse
    :about "The smallest game loop: a main loop and an NMI handler, in step. The color changes every 16 frames."}
   {:id :pad    :build build-pad
    :about "Input: Left and Right on the D-pad step through colors."}
   {:id :sprite :build build-sprite
    :about "Something to move: a ball you steer with the D-pad."}])

(defn build
  "Build a rung by its id (:nil :color :pulse :pad :sprite). Returns what
  rom/nrom returns, with :name."
  [id]
  (let [rung (or (some #(when (= id (:id %)) %) rungs)
                 (throw (ex-info (str "There's no rung called " (pr-str id) ". There are: " (mapv :id rungs)) {})))]
    (assoc ((:build rung)) :name (str (name id) ".nes"))))

(defn program-size
  "How many bytes of program a built cartridge has (not counting the
  three vectors, or the unused ROM around it)."
  [built]
  (reduce + (map #(count (:bytes %))
                 (remove #(#{:org :dw} (first (:form %))) (:listing built)))))
