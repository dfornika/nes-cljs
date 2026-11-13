(ns gamedev-toolkit.runtime
  "The part of an NES game that's the same in every game, so a game only
  has to say what's different about it.

  A game is described as a *finite state machine*: a set of states (title
  screen, playing, game over...), of which it's in exactly one at a time,
  and transitions between them. Each state has actions, which are code:

    :enter   the entry action: run once, on arriving in the state
    :update  run every frame while in the state
    :exit    the exit action: run once, on leaving it

  (all optional), and the code asks for a transition with
  (transition :playing).

    (program
     {:name \"mygame.nes\"
      :variables [[:score :u8] ...]          ; as for gamedev-toolkit.memory
      :tiles     [...]                       ; the graphics
      :palettes  [...]                       ; 32 colors
      :initial   :title                      ; the initial state
      :states    [[:title   {:enter [...] :update [...]}]
                  [:playing {:enter [...] :update [...] :exit [...]}]]})

  `program` wraps that in everything else an NES program needs and
  returns a built cartridge (as gamedev-toolkit.rom/nrom does).

  What the runtime does each frame:

    main loop                          NMI (at every vertical blank)
    ---------                          ---
    read the controller                if the main loop finished its frame:
    if a transition was asked for,       copy the sprites to the PPU
      run :exit, then the new :enter     write queued changes to the background
    run the state's :update
    hide the sprites not drawn         set scroll, turn the picture on
    say 'frame finished', and wait     count the frame

  The split matters because the PPU's memory can only be written while
  it isn't drawing: during vertical blank, which lasts about 2,270 CPU
  cycles. So game code never writes to the PPU. It draws sprites into a
  copy in ordinary memory (`sprite`, `metasprite`), and asks for
  background changes by adding them to a queue (`queue`); the NMI handler
  passes both on at the right moment.

  The exception is drawing a whole screen, which is far too much for one
  vertical blank. `draw-screen` turns the picture off while it does that
  (the screen goes blank for a moment, as it does in real games between
  screens).

  What a game's code can use:

    :pad/held :pad/pressed    the controller: buttons down now, and ones
                              that went down this frame (see `when-held`)
    :rt/frame                 counts frames
    :rt/state :rt/next        the current state, and the one asked for
    (transition :playing)     ask for a transition to another state
    (call random)             a random byte in A
    (sprite ...) (metasprite ...) (queue ...) (draw-screen ...)
    (background :title)       inside draw-screen: a whole screen from :screens
    (probe x dx y dy)         what kind of block is there? (see `solid-at`)
    (on-kinds [:solid] ...)   ...and if it's one of these, do something
    (play-song :theme)        music, from :songs; (stop-music)

  More of an engine is built on this: gamedev-toolkit.objects (a pool of
  things that come and go), gamedev-toolkit.platformer (gravity, jumping,
  standing on things) and gamedev-toolkit.sound (sound effects)."
  (:require [gamedev-toolkit.asm :as asm]
            [gamedev-toolkit.chr :as chr]
            [gamedev-toolkit.font :as font]
            [gamedev-toolkit.gen :as gen]
            [gamedev-toolkit.hardware :as hw]
            [gamedev-toolkit.memory :as memory]
            [gamedev-toolkit.rom :as rom]
            [gamedev-toolkit.screen :as screen]
            [gamedev-toolkit.sound :as sound]
            [gamedev-toolkit.sub :as sub :refer [call]]))

;; ---------------------------------------------------------------------------
;; The controller

(def read-pad
  (sub/sub
   :read-pad
   "Read controller 1 into :pad/held, and work out :pad/pressed (the
  buttons that weren't down last frame).

  Writing 1 then 0 to JOY1 makes the controller take a snapshot of its
  buttons. Each read of JOY1 then gives the next one in bit 0: A, B,
  Select, Start, Up, Down, Left, Right. LSR moves that bit into the carry
  flag, and ROL moves the carry into :pad/held from the right. The 1 that
  :pad/held starts with falls out of the left after 8 ROLs, into the
  carry, and stops the loop."
   [(gen/move :pad/previous :pad/held)
    [:lda [:imm 1]]
    [:sta :joy1]
    [:sta :pad/held]
    [:lsr :a]
    [:sta :joy1]
    (gen/do-while :no-carry
      [:lda :joy1]
      [:lsr :a]
      [:rol :pad/held])
    [:lda :pad/previous]
    [:eor [:imm 0xFF]]
    [:and :pad/held]
    [:sta :pad/pressed]]))

(defn when-held
  "Run `body` while `button` (:a :b :select :start :up :down :left
  :right) is down."
  [button & body]
  [[:lda :pad/held]
   [:and [:imm (hw/buttons button)]]
   (gen/when-flag :not-zero body)])

(defn when-pressed
  "Run `body` on the frame `button` goes down (once per press)."
  [button & body]
  [[:lda :pad/pressed]
   [:and [:imm (hw/buttons button)]]
   (gen/when-flag :not-zero body)])

;; ---------------------------------------------------------------------------
;; States

(defn restart
  "Arrive in the current state afresh: its entry action runs again at the
  start of the next frame. (A transition to the state it's already in
  does nothing.) Its exit action doesn't run. Uses A."
  []
  [(gen/move :rt/state [:imm 0xFF])])

(defn transition
  "Ask for a transition to another state. It happens at the start of the
  next frame: the current state's :exit action runs, then the new state's
  :enter action, and its :update from then on. (Until then the rest of
  this frame's :update carries on as usual.)"
  [state]
  [[:lda [:imm (keyword "state" (name state))]]
   [:sta :rt/next]])

;; ---------------------------------------------------------------------------
;; Sprites
;;
;; The PPU draws up to 64 sprites, each described by 4 bytes: Y, tile,
;; attributes (which palette, flipping), X. The game keeps its own copy of
;; those 256 bytes at :oam, and the NMI handler sends the copy to the PPU
;; every frame. Each frame the game draws its sprites into the copy from
;; the start; :rt/oam-next is where the next one goes.

(defn metasprite
  "Draw a character made of several sprites that move together. `x` and
  `y` are where its top left is (`src` operands, one byte each), and
  `pieces` are [tile dx dy] or [tile dx dy attributes]: which tile goes
  how far right and down of there. Attributes are the sprite palette (0
  to 3), plus 0x40 to flip it left-right and 0x80 upside down.

  The loop over the pieces runs when the ROM is built: the NES gets the
  instructions for each piece written out. Uses A and X; or with
  {:with :y}, A and Y, leaving X alone, for when `x` and `y` are entries
  in a table of objects that X is the index into (see gen/indexed)."
  ([x y pieces] (metasprite x y pieces {}))
  ([x y pieces {:keys [with] :or {with :x}}]
   (let [[load transfer] (case with :x [:ldx :txa] :y [:ldy :tya])]
     [[load :rt/oam-next]
      (for [[i [tile dx dy attributes]] (map-indexed vector pieces)
            :let [at (fn [field] [:+ :oam (+ (* 4 i) field)])]]
        [(gen/with-operand :lda y) (when-not (zero? dy) [[:clc] [:adc [:imm dy]]]) [:sta (at 0) with]
         [:lda [:imm tile]]                                                        [:sta (at 1) with]
         [:lda [:imm (or attributes 0)]]                                           [:sta (at 2) with]
         (gen/with-operand :lda x) (when-not (zero? dx) [[:clc] [:adc [:imm dx]]]) [:sta (at 3) with]])
      [transfer]
      [:clc]
      [:adc [:imm (* 4 (count pieces))]]
      [:sta :rt/oam-next]])))

(defn animated
  "Draw one of several pictures, taking turns: `frames` is a vector of
  1, 2 or 4 sets of pieces (as for `metasprite`), each shown for `:every`
  frames (a power of two; default 8) before the next. They all change in
  step, with the frame count. Other options are `metasprite`'s."
  ([x y frames] (animated x y frames {}))
  ([x y frames {:keys [every] :or {every 8} :as options}]
   (let [n (count frames)
         shifts {1 0, 2 1, 4 2, 8 3, 16 4, 32 5, 64 6}
         done (asm/genlabel "drawn")]
     (when-not (and (#{1 2 4} n) (shifts every))
       (throw (ex-info "animated takes 1, 2 or 4 frames, and :every a power of two up to 64" {:frames n :every every})))
     (if (= 1 n)
       (metasprite x y (first frames) options)
       [[:lda :rt/frame]
        (repeat (shifts every) [:lsr :a])
        [:and [:imm (dec n)]]             ; which picture: 0 to n-1
        (for [[i pieces] (map-indexed vector frames)]
          [[:cmp [:imm i]]
           (gen/when-flag :equal
             (metasprite x y pieces options)
             [:jmp done])])
        [:label done]]))))

(defn sprite
  "Draw one 8x8 sprite: (sprite {:x :ball/x :y :ball/y :tile 5})."
  [{:keys [x y tile attributes]}]
  (metasprite x y [[tile 0 0 attributes]]))

(defn picture
  "The pieces (for `metasprite`) of a picture that's `width` tiles wide,
  whose tiles are in the cartridge in reading order from `first-tile`
  (as gamedev-toolkit.chr/tiles cuts a picture up)."
  ([first-tile width height] (picture first-tile width height 0))
  ([first-tile width height attributes]
   (vec (for [row (range height), col (range width)]
          [(+ first-tile (* row width) col) (* 8 col) (* 8 row) attributes]))))

(def ^:private hide-unused-sprites
  (sub/sub
   :hide-unused-sprites
   "Move every sprite after the ones drawn this frame off the bottom of
  the screen (a Y of 240 or more), which is the only way to hide one."
   [[:ldx :rt/oam-next]
    [:lda [:imm 0xFF]]
    (gen/do-while :not-zero              ; until X wraps round to 0
      [:sta :oam :x]
      [:inx] [:inx] [:inx] [:inx])]))

;; ---------------------------------------------------------------------------
;; The background

(defn tile-address
  "The video memory address of the background tile at column `col` (0 to
  31) and row `row` (0 to 29)."
  [col row]
  (+ (:nametable-0 hw/video) (* 32 row) col))

(defn- set-video-address [addr]
  [[:lda [:imm (bit-shift-right addr 8)]] [:sta :ppuaddr]
   [:lda [:imm (bit-and addr 0xFF)]]      [:sta :ppuaddr]])

(defn queue
  "Ask for some tiles to be written to the background at the next
  vertical blank, starting at `address` (see `tile-address`) and going
  right. Each of `tiles` is a `src` operand ([:imm 5], :some-variable),
  or a sequence of instructions that leaves the tile number in A without
  changing X:

    (queue (tile-address 17 2)
           [[:lda :score] [:clc] [:adc [:imm zero-tile]]])

  The queue holds 64 bytes a frame (3 per call, plus 1 per tile), and
  nothing checks that it isn't overfilled. Uses A and X."
  [address & tiles]
  (let [put (fn [load] [load [:sta :video-queue :x] [:inx]])]
    [[:ldx :rt/queue-end]
     (put [:lda [:imm (bit-shift-right address 8)]])
     (put [:lda [:imm (bit-and address 0xFF)]])
     (put [:lda [:imm (count tiles)]])
     (for [tile tiles]
       ;; a sequence of instructions starts with an instruction (a vector)
       (put (if (and (sequential? tile) (sequential? (first tile)))
              tile
              [:lda tile])))
     [:stx :rt/queue-end]]))

(defn at
  "Inside `draw-screen`: move to column `col`, row `row`. Tiles written
  to :ppudata from then on go there, left to right. Uses A."
  [col row]
  (set-video-address (tile-address col row)))

(defn text
  "Inside `draw-screen`: write `string` at column `col`, row `row`, with
  gamedev-toolkit.font (whose tiles must be the cartridge's first ones).
  Uses A."
  [col row string]
  [(at col row)
   ;; consecutive equal characters need A loaded only once
   (for [run (partition-by identity (font/encode string))]
     [[:lda [:imm (first run)]]
      (repeat (count run) [:sta :ppudata])])])

(defn clear-background
  "Inside `draw-screen`: fill the whole background with tile 0, in its
  first palette. Uses A, X and Y."
  []
  [(set-video-address (:nametable-0 hw/video))
   [:lda [:imm 0]]
   ;; 1024 bytes: 960 tiles, then the 64 bytes that choose palettes
   (gen/times 1024 [:sta :ppudata])])

(def ^:private unpack-to-video
  (sub/sub
   :unpack-to-video
   "Write packed bytes (see gamedev-toolkit.screen/pack) to video memory,
  from wherever PPUADDR points. :rt/pointer points at the packed bytes:
  pairs of a count and a value, ending with a count of 0."
   (let [done (asm/genlabel "unpacked")]
     [(gen/forever
        [:ldy [:imm 0]]
        [:lda [:ind :rt/pointer] :y]         ; how many
        [:beq done]                          ; none: that's the end
        [:tax]
        [:iny]
        [:lda [:ind :rt/pointer] :y]         ; of what
        (gen/do-while :not-zero
          [:sta :ppudata]
          [:dex])
        (gen/add16 :rt/pointer [:imm 2]))
      [:label done]])))

(defn background
  "Inside `draw-screen`: draw the whole background from the screen called
  `name` (one of the game's :screens), tiles and palettes both. Uses A, X
  and Y."
  [name]
  [(set-video-address (:nametable-0 hw/video))
   (gen/move16 :rt/pointer [:imm (keyword "screen" (clojure.core/name name))])
   (call unpack-to-video)
   ;; a screen doesn't scroll
   (gen/move16 :camera/x [:imm 0])
   (gen/move :scroll/on [:imm 0])
   ;; and from now on, this is the screen `solid-at` asks about
   (gen/move16 :rt/map [:imm (keyword "solid" (clojure.core/name name))])])

;; ---------------------------------------------------------------------------
;; Bumping into the background
;;
;; Each screen and level comes with a map of what kind of block is where
;; (gamedev-toolkit.screen/kinds): 16 bytes for each column of blocks, top
;; to bottom (the 15 rows of the screen, then one that's solid). So the byte
;; for the pixel at x, y is at (x / 16) * 16 + y / 16, and (x / 16) * 16 is
;; x with its low four bits cleared: no multiplying, even when x is a
;; position on a level thousands of pixels wide.

(def solid-at
  (sub/sub
   :solid-at
   "What kind of block is at a pixel? Give its x (16 bits: a position on
  the level) in :rt/px, and its y in Y. Afterwards A is the kind (see
  gamedev-toolkit.screen/kinds), and the zero flag is set if it's empty.
  Changes A and Y, and leaves X alone, so it can be asked about an object
  while X is the object's slot."
   [[:lda :rt/px]
    [:and [:imm 0xF0]]                  ; (x / 16) * 16
    [:clc]
    [:adc :rt/map]
    [:sta :rt/cursor]
    [:lda [:+ :rt/px 1]]
    [:adc [:+ :rt/map 1]]
    [:sta [:+ :rt/cursor 1]]
    [:tya]
    [:lsr :a] [:lsr :a] [:lsr :a] [:lsr :a]   ; y / 16
    [:tay]
    [:lda [:ind :rt/cursor] :y]]))

(defn on-kinds
  "After `probe` (or `solid-at`), with the kind of block in A: run `then`
  if it's one of `kinds` (:empty :solid :one-way :ladder :ladder-top: see
  gamedev-toolkit.screen/kinds), otherwise `else`."
  ([kinds then] (on-kinds kinds then nil))
  ([kinds then else]
   (let [yes (asm/genlabel "kind")
         done (asm/genlabel "kinded")]
     [(for [k kinds]
        [[:cmp [:imm (or (screen/kinds k)
                         (throw (ex-info (str "Unknown kind of block " (pr-str k)) {:kind k})))]]
         [:beq yes]])
      else
      [:jmp done]
      [:label yes]
      then
      [:label done]])))

(def landable
  "The kinds of block that something falling lands on."
  [:solid :one-way :ladder-top])

(defn probe
  "Ask `solid-at` about the pixel `dx` right and `dy` down from the point
  x, y. x is a one-byte operand (a position on a screen) or a 16-bit one
  made with gen/pair or gen/wide (a position on a level); y is one byte.
  The kind is left in A, as for `solid-at`. Uses A and Y, not X."
  [x dx y dy]
  [(gen/with-operand :lda (gen/lo x)) [:clc] [:adc [:imm (bit-and dx 0xFF)]] [:sta :rt/px]
   (if (gen/pair? x)
     (gen/with-operand :lda (gen/hi x))
     [:lda [:imm 0]])
   [:adc [:imm (if (neg? dx) 0xFF 0)]]  ; (a step to the left borrows from the high byte)
   [:sta [:+ :rt/px 1]]
   (gen/with-operand :lda y) [:clc] [:adc [:imm (bit-and dy 0xFF)]] [:tay]
   (call solid-at)])

(defn draw-screen
  "Run `body` with the picture and the NMI turned off, so it can write as
  much to the background as it likes (with `clear-background`, `text`,
  `at` and writes to :ppudata). The picture comes back on at the next
  vertical blank. For :enter code; the screen is blank meanwhile."
  [& body]
  [[:lda [:imm 0]]
   [:sta :rt/ctrl]                      ; (what the runtime writes to PPUCTRL)
   [:sta :ppuctrl]                      ; NMI off: it would use PPUADDR too
   [:sta :ppumask]                      ; picture off
   [:sta :rt/queue-end]                 ; anything queued was for the old screen
   [:bit :ppustatus]                    ; make PPUADDR expect a high byte
   body
   [:lda [:imm 0x80]]
   [:sta :rt/ctrl]
   [:sta :ppuctrl]])                    ; NMI back on; it turns the picture on


;; ---------------------------------------------------------------------------
;; Levels wider than the screen
;;
;; The PPU has room for two screens' worth of background, side by side (with
;; the cartridge wired for 'vertical mirroring'): 64 tiles across, or 32
;; blocks. The picture can start anywhere in that, and wraps round from the
;; right edge to the left. So a level wider than that is drawn as a ring:
;; the 32 columns of blocks around the camera are in the PPU, and as the
;; camera moves right, the column that's fallen furthest behind is redrawn
;; with the next one ahead (and the other way going left). The columns
;; being changed are well off screen, 8 blocks either side of it.
;;
;; A column takes too long to work out in vertical blank, so the main loop
;; prepares it in a buffer (`prepare-column`: its 30 tiles, and the 8
;; palette bytes for it and its neighbour), and the NMI handler copies the
;; buffer into the PPU (`flush-column`). One column a frame is plenty: the
;; camera would have to move 16 pixels a frame to need more.
;;
;; The level's data is worked out when the ROM is built
;; (gamedev-toolkit.screen/level); the game names it with `level` and
;; moves the camera with `follow`.

(def ^:private prepare-column
  (sub/sub
   :prepare-column
   "Work out the column of blocks :column/col into the column buffer: its
  tiles (from the level's metatile numbers and the metatile tables), its
  palette bytes, and where in the PPU they go. Uses A, X and Y."
   [;; where the column's metatile numbers are: ids + col * 16 (the high
    ;; byte first: shifting right would lose the carry from adding the low)
    [:lda :column/col] [:lsr :a] [:lsr :a] [:lsr :a] [:lsr :a] [:sta :rt/temp]
    [:lda :column/col] [:asl :a] [:asl :a] [:asl :a] [:asl :a]
    [:clc] [:adc :scroll/ids] [:sta :rt/pointer]
    [:lda :rt/temp] [:adc [:+ :scroll/ids 1]] [:sta [:+ :rt/pointer 1]]
    ;; each block's four tiles: two in the left tile column, two in the
    ;; right. Written out row by row when the ROM is built, so no counting.
    (for [row (range 15)]
      [[:ldy [:imm row]] [:lda [:ind :rt/pointer] :y] [:tax]
       [:lda :metatiles/tl :x] [:sta [:+ :column-tiles (* 2 row)]]
       [:lda :metatiles/bl :x] [:sta [:+ :column-tiles (inc (* 2 row))]]
       [:lda :metatiles/tr :x] [:sta [:+ :column-tiles (+ 30 (* 2 row))]]
       [:lda :metatiles/br :x] [:sta [:+ :column-tiles (+ 31 (* 2 row))]]])
    ;; the palette bytes: 8 for each pair of columns, so at
    ;; attrs + (col / 2) * 8 = attrs + (col & $FE) * 4
    [:lda :column/col] [:lsr :a] [:lsr :a] [:lsr :a] [:lsr :a] [:lsr :a] [:lsr :a] [:sta :rt/temp]
    [:lda :column/col] [:and [:imm 0xFE]] [:asl :a] [:asl :a]
    [:clc] [:adc :scroll/attrs] [:sta :rt/pointer]
    [:lda :rt/temp] [:adc [:+ :scroll/attrs 1]] [:sta [:+ :rt/pointer 1]]
    (for [i (range 8)]
      [[:ldy [:imm i]] [:lda [:ind :rt/pointer] :y] [:sta [:+ :column-attrs i]]])
    ;; where it goes: the first nametable ($2000) or the second ($2400),
    ;; by the column's fifth bit; two tiles across for each block
    [:lda :column/col] [:and [:imm 15]] [:asl :a] [:sta :column/ppu]
    [:lda :column/col] [:and [:imm 16]] [:lsr :a] [:lsr :a] [:ora [:imm 0x20]] [:sta [:+ :column/ppu 1]]
    ;; and its palette bytes: $23C0 (or $27C0) + the block column / 2
    [:lda :column/col] [:and [:imm 15]] [:lsr :a] [:ora [:imm 0xC0]] [:sta :column/attr]
    (gen/move :column/pending [:imm 1])]))

(def ^:private flush-column
  (sub/sub
   :flush-column
   "Copy the column buffer into the PPU: in vertical blank (the NMI
  handler does it), or with the picture off. Uses A and X."
   [;; PPUCTRL bit 2: each write to PPUDATA steps down a row, not across
    [:lda :rt/ctrl] [:ora [:imm 0x04]] [:sta :ppuctrl]
    (for [half [0 1]]
      [[:lda [:+ :column/ppu 1]] [:sta :ppuaddr]
       [:lda :column/ppu] (when (= half 1) [[:clc] [:adc [:imm 1]]]) [:sta :ppuaddr]
       [:ldx [:imm (* 30 half)]]
       (gen/do-while :not-equal
         [:lda :column-tiles :x] [:sta :ppudata]
         [:inx]
         [:cpx [:imm (+ 30 (* 30 half))]])])
    [:lda :rt/ctrl] [:sta :ppuctrl]
    ;; the palette bytes, one for each 32-pixel row
    (for [ar (range 8)]
      [[:lda [:+ :column/ppu 1]] [:clc] [:adc [:imm 3]] [:sta :ppuaddr]
       [:lda :column/attr] [:clc] [:adc [:imm (* 8 ar)]] [:sta :ppuaddr]
       [:lda [:+ :column-attrs ar]] [:sta :ppudata]])
    (gen/move :column/pending [:imm 0])]))

(def ^:private ring-start
  (sub/sub
   :ring-start
   "Where the ring of drawn columns should start, for where the camera is:
  8 columns left of it, but not before the level's start or so far that
  the ring would run off its end. Leaves it in A."
   [[:lda :camera/x] [:lsr :a] [:lsr :a] [:lsr :a] [:lsr :a] [:sta :rt/temp]
    [:lda [:+ :camera/x 1]] [:asl :a] [:asl :a] [:asl :a] [:asl :a] [:ora :rt/temp]   ; camera / 16
    [:sec] [:sbc [:imm 8]]
    (gen/when-flag :no-carry [:lda [:imm 0]])
    [:cmp :scroll/last]
    (gen/when-flag :carry [:lda :scroll/last])]))

(def ^:private scroll-step
  (sub/sub
   :scroll-step
   "Keep the ring of drawn columns around the camera: if it should have
  moved, prepare the column that comes into it. Uses A, X and Y."
   (let [done (asm/genlabel "scrolled")
         back (asm/genlabel "back")]
     [[:lda :scroll/on]
      [:beq done]
      (call ring-start)
      [:cmp :scroll/left]
      [:beq done]
      [:bcc back]
      ;; forward: the column 32 ahead of the ring's start replaces it
      [:lda :scroll/left] [:clc] [:adc [:imm 32]] [:sta :column/col]
      [:inc :scroll/left]
      (call prepare-column)
      [:jmp done]
      [:label back]
      [:dec :scroll/left]
      [:lda :scroll/left] [:sta :column/col]
      (call prepare-column)
      [:label done]])))

(defn level
  "Inside `draw-screen`: start the level called `name` (one of the game's
  :levels): draw the 32 columns around where the camera is (so set the
  camera first, with `follow`), and from then on keep drawing as it moves.
  Uses A, X and Y."
  [name]
  (let [n (clojure.core/name name)
        again (asm/genlabel "draw-column")]
    [(gen/move16 :scroll/ids [:imm (keyword "level-ids" n)])
     (gen/move16 :scroll/attrs [:imm (keyword "level-attrs" n)])
     (gen/move16 :rt/map [:imm (keyword "level-kinds" n)])
     (gen/move :scroll/last [:imm (keyword "level-last" n)])
     (call ring-start)
     [:sta :scroll/left]
     (gen/move :scroll/i [:imm 0])
     [:label again]
     [:lda :scroll/left] [:clc] [:adc :scroll/i] [:sta :column/col]
     (call prepare-column)
     (call flush-column)
     [:inc :scroll/i]
     [:lda :scroll/i]
     [:cmp [:imm 32]]
     [:bne again]
     (gen/move :scroll/on [:imm 1])]))

(defn follow
  "Point the camera at the level position `x` (16 bits: gen/pair or
  gen/wide), less `offset` pixels: so whatever's at x appears `offset`
  pixels from the screen's left edge. It stops at the ends of the level
  called `level-name`. Uses A."
  [x offset level-name]
  (let [most [:imm (keyword "camera-max" (clojure.core/name level-name))]]
    [[:sec]
     (gen/with-operand :lda (gen/lo x)) [:sbc [:imm offset]] [:sta :camera/x]
     (gen/with-operand :lda (gen/hi x)) [:sbc [:imm 0]] [:sta [:+ :camera/x 1]]
     (gen/when-flag :no-carry                ; before the level's start
       (gen/move16 :camera/x [:imm 0]))
     (gen/compare16 :camera/x most)
     (gen/when-flag :carry                   ; past its end
       (gen/move16 :camera/x most))]))

(defn on-screen
  "Run `body` if something at level position `x` (16 bits: gen/pair or
  gen/wide) is on the screen, with :rt/sx set to where across the screen
  it is. For drawing: (on-screen x (metasprite :rt/sx y pieces)). Things
  within 16 pixels of the right edge count as off it (their right half
  would wrap round to the left). Uses A."
  [x & body]
  [[:sec]
   (gen/with-operand :lda (gen/lo x)) [:sbc :camera/x] [:sta :rt/sx]
   (gen/with-operand :lda (gen/hi x)) [:sbc [:+ :camera/x 1]]
   ;; (when-flag, not plain branches: the body can be too long to branch over)
   (gen/when-flag :zero                  ; less than 256 pixels right of the camera
     [:lda :rt/sx] [:cmp [:imm 241]]
     (gen/when-flag :no-carry body))])

(defn sprite-text
  "Draw a row of tiles as sprites, at a fixed place on the screen: for
  words and numbers that stay put while the background scrolls. Each of
  `tiles` is a tile number, or instructions that leave one in A (and
  leave X alone). Uses A and X."
  [x y tiles palette]
  [[:ldx :rt/oam-next]
   (for [[i t] (map-indexed vector tiles)
         :let [at (fn [field] [:+ :oam (+ (* 4 i) field)])]]
     [[:lda [:imm y]] [:sta (at 0) :x]
      (if (number? t) [:lda [:imm t]] t) [:sta (at 1) :x]
      [:lda [:imm palette]] [:sta (at 2) :x]
      [:lda [:imm (+ x (* 8 i))]] [:sta (at 3) :x]])
   [:txa]
   [:clc]
   [:adc [:imm (* 4 (count tiles))]]
   [:sta :rt/oam-next]])

;; ---------------------------------------------------------------------------
;; Random numbers

(def random
  (sub/sub
   :random
   "A random byte, in A (and the flags are set from it). Uses Y.

  It's a 'linear feedback shift register': 16 bits (:rt/seed) shifted
  left 8 times, with some bits flipped whenever a 1 falls out. The
  numbers repeat after 65,535 calls, in an order with no obvious pattern.
  The seed must never be 0 (it would stay 0)."
   [[:ldy [:imm 8]]
    [:lda :rt/seed]
    (gen/do-while :not-zero
      [:asl :a]
      [:rol [:+ :rt/seed 1]]
      (gen/when-flag :carry
        [:eor [:imm 0x39]])
      [:dey])
    [:sta :rt/seed]
    [:cmp [:imm 0]]]))

(defn stir-random
  "Mix the frame count into the random numbers. Do it when the player
  presses something: nobody presses Start on exactly the same frame
  twice, so each game gets different numbers. Uses A."
  []
  [[:lda :rt/frame]
   [:eor :rt/seed]
   [:ora [:imm 1]]                      ; never 0
   [:sta :rt/seed]])

;; ---------------------------------------------------------------------------
;; The program around the game

(defn- runtime-variables [state-names]
  [[:rt {:frame     :u8
         :ready     :bool                ; the main loop has finished a frame
         :mask      :u8                  ; what the NMI writes to PPUMASK
         :state     [:enum (assoc (zipmap (range) state-names) 0xFF :none)]
         :next      [:enum (zipmap (range) state-names)]
         :oam-next  :u8
         :queue-end :u8
         :seed      :u16
         :pointer   :u16                 ; for reading tables in the ROM
         :map       :u16                 ; the current screen's map of what's solid
         :ctrl      :u8                  ; what the runtime writes to PPUCTRL
         :px        :u16                 ; the pixel solid-at asks about
         :cursor    :u16                 ; where in the map solid-at looks
         :sx        :u8                  ; a position on the screen (see on-screen)
         :temp      :u8                  ; scratch, for the runtime's own routines
         :slot      :u8                  ; the object being looked at (see gamedev-toolkit.objects)
         :turn      :u8                  ; which object is drawn first this frame (see objects/each-rotating)
         :temp2     :u8                  ; more scratch
         :old       :u16.8}]             ; a position to go back to (see gamedev-toolkit.platformer)
   [:camera {:x :u16}]                   ; the level's x at the screen's left edge
   [:scroll {:on :bool :left :u8 :last :u8 :i :u8 :ids :u16 :attrs :u16}]   ; see `level`
   [:column {:pending :bool :col :u8 :ppu :u16 :attr :u8}]
   [:column-tiles [:array 60 :u8] {:hide true}]
   [:column-attrs [:array 8 :u8] {:hide true}]
   [:pad {:held     [:bits hw/button-bits]
          :pressed  [:bits hw/button-bits]
          :previous [:bits hw/button-bits]}]
   ;; the copy of sprite memory: a whole page, and $0200 by convention
   [:oam [:array 256 :u8] {:at 0x0200 :hide true}]
   [:video-queue [:array 64 :u8] {:hide true}]])

(defn- wait-for-vblank []
  (gen/do-while :positive
    [:bit :ppustatus]))

(defn- reset-code [initial]
  [[:label :reset]
   [:sei]                               ; no interrupts while setting up
   [:cld]                               ; decimal mode off (the NES has none)
   [:ldx [:imm 0x40]] [:stx :apu-frame] ; turn off the sound chip's interrupt
   [:ldx [:imm 0xFF]] [:txs]            ; the stack starts at $01FF
   [:inx]
   [:stx :ppuctrl]                      ; NMI off
   [:stx :ppumask]                      ; picture off
   [:stx :dmc-freq]                     ; sample interrupts off
   [:lda [:imm 0x0F]] [:sta :snd-chn]   ; the four sound channels on

   ;; The PPU needs about two frames to warm up. Meanwhile, clear all
   ;; 2 KB of RAM (it holds garbage at power on) and hide the sprites.
   (wait-for-vblank)
   (gen/fill 0x0000 0x800 [:imm 0])
   (gen/fill :oam 256 [:imm 0xFF])
   (wait-for-vblank)

   ;; Copy the 32 palette colors to video memory.
   (set-video-address (:palettes hw/video))
   [:ldx [:imm 0]]
   (gen/do-while :not-equal
     [:lda :palettes :x]
     [:sta :ppudata]
     [:inx]
     [:cpx [:imm 32]])

   (gen/move :rt/mask [:imm 0x1E])      ; show background and sprites
   (gen/move :rt/ctrl [:imm 0x80])      ; NMI on, tiles from pattern table 0
   ;; In no state yet, with a transition to the initial state waiting: so
   ;; the first frame begins by running its entry action.
   (gen/move :rt/state [:imm 0xFF])
   (gen/move :rt/next [:imm (keyword "state" (name initial))])
   (gen/move16 :rt/seed [:imm 0xC0DE])

   [:lda [:imm 0x80]]
   [:sta :ppuctrl]])                    ; NMI on

(defn- dispatch
  "Call the subroutine in `subs` (in state order) for the state in
  :rt/state. With a handful of states, comparing against each in turn is
  as good as anything cleverer."
  [subs]
  (let [done (asm/genlabel "dispatched")]
    [[:lda :rt/state]
     (for [[i s] (map-indexed vector subs)]
       [[:cmp [:imm i]]
        (gen/when-flag :equal
          (call s)
          [:jmp done])])
     [:label done]]))

;; ---------------------------------------------------------------------------
;; Music
;;
;; A song (gamedev-toolkit.sound/song) is a table of notes in the ROM. Each
;; frame the player counts down the current note's frames; at 0 it reads
;; the next note and gives it to the triangle channel, which then plays it
;; by itself until told otherwise.

(def ^:private music-step
  (sub/sub
   :music-step
   "Play the song, if there is one: count down this note, and at its end
  start the next. Uses A and Y."
   (let [done (asm/genlabel "played")
         again (asm/genlabel "next-note")
         rest (asm/genlabel "rest")]
     [[:lda :music/on]
      [:beq done]
      [:dec :music/wait]
      [:bne done]
      [:label again]
      [:ldy [:imm 0]]
      [:lda [:ind :music/at] :y]           ; how long
      (gen/when-flag :zero                 ; the end: back to the start
        (gen/move16 :music/at :music/start)
        [:jmp again])
      [:sta :music/wait]
      [:iny]
      [:lda [:ind :music/at] :y]           ; the timer's low byte
      [:sta :rt/temp]
      [:iny]
      [:lda [:ind :music/at] :y]           ; its high byte, or $FF for a rest
      [:cmp [:imm 0xFF]]
      [:beq rest]
      [:tay]
      ;; $4008: keep sounding until told otherwise (bit 7, and the most
      ;; it can count); then the pitch. Writing $400B starts the note.
      [:lda [:imm 0xFF]] [:sta :triangle]
      [:lda :rt/temp] [:sta [:+ :triangle 2]]
      [:sty [:+ :triangle 3]]
      (gen/add16 :music/at [:imm 3])
      [:jmp done]
      [:label rest]
      ;; silence: a count of 0, which takes effect on the write to $400B
      [:lda [:imm 0x80]] [:sta :triangle]
      [:lda [:imm 0]] [:sta [:+ :triangle 3]]
      (gen/add16 :music/at [:imm 3])
      [:label done]])))

(defn play-song
  "Start the song called `name` (one of the game's :songs), from its
  beginning. It repeats until `stop-music`. Uses A."
  [name]
  (let [label (keyword "song" (clojure.core/name name))]
    [(gen/move16 :music/at [:imm label])
     (gen/move16 :music/start [:imm label])
     (gen/move :music/wait [:imm 1])
     (gen/move :music/on [:imm 1])]))

(defn stop-music
  "Stop the song, and silence the triangle channel. Uses A."
  []
  [(gen/move :music/on [:imm 0])
   [:lda [:imm 0x80]] [:sta :triangle]
   [:lda [:imm 0]] [:sta [:+ :triangle 3]]])

(defn- main-loop [exits enters updates music? levels?]
  [[:label :main]
   (call read-pad)
   (when music? (call music-step))

   ;; a transition was asked for: leave one state, arrive in the other
   [:lda :rt/next]
   [:cmp :rt/state]
   (gen/when-flag :not-equal
     (when exits (dispatch exits))
     (gen/move :rt/state :rt/next)
     (dispatch enters))

   (gen/move :rt/oam-next [:imm 0])
   (dispatch updates)
   ;; (after the update, which moves the camera)
   (when levels? (call scroll-step))
   (call hide-unused-sprites)

   ;; Tell the NMI handler this frame's sprites and queue are complete,
   ;; then wait for it to count the frame.
   (gen/move :rt/ready [:imm 1])
   [:lda :rt/frame]
   [:label :wait-for-frame]
   [:cmp :rt/frame]
   [:beq :wait-for-frame]
   [:jmp :main]])

(defn- nmi [levels?]
  [[:label :nmi]
   [:pha] [:txa] [:pha] [:tya] [:pha]   ; the main loop was using these

   [:lda :rt/ready]
   (gen/when-flag :not-zero
     ;; sprites: copy the page at :oam to the PPU's sprite memory
     [:lda [:imm 0]] [:sta :oamaddr]
     [:lda [:imm [:hi :oam]]] [:sta :oamdma]

     ;; a column of a level coming into view (see `level`)
     (when levels?
       [[:lda :column/pending]
        (gen/when-flag :not-zero (call flush-column))])

     ;; the queue: entries of address (high, low), count, then the tiles
     [:bit :ppustatus]
     [:ldx [:imm 0]]
     [:label :flush-queue]
     [:cpx :rt/queue-end]
     (gen/when-flag :no-carry           ; X < the end: there's another entry
       [:lda :video-queue :x] [:sta :ppuaddr] [:inx]
       [:lda :video-queue :x] [:sta :ppuaddr] [:inx]
       [:ldy :video-queue :x] [:inx]
       (gen/do-while :not-zero
         [:lda :video-queue :x] [:sta :ppudata] [:inx]
         [:dey])
       [:jmp :flush-queue])
     [:lda [:imm 0]]
     [:sta :rt/queue-end]
     [:sta :rt/ready])

   ;; Writing PPUADDR moves where the picture is drawn from: put it back,
   ;; to where the camera is. Its x's low byte is the scroll, and its next
   ;; bit picks which of the two screens' worth of background (nametables,
   ;; side by side: see `level`) the picture starts in.
   [:bit :ppustatus]
   [:lda [:+ :camera/x 1]]
   [:and [:imm 1]]
   [:ora :rt/ctrl]
   [:sta :ppuctrl]
   [:lda :camera/x] [:sta :ppuscroll]
   [:lda [:imm 0]] [:sta :ppuscroll]
   [:lda :rt/mask] [:sta :ppumask]

   [:inc :rt/frame]

   [:pla] [:tay] [:pla] [:tax] [:pla]
   [:rti]])

(def ^:private irq
  [[:label :irq]
   [:rti]])

(defn program
  "Build a game (see the namespace docstring) into a cartridge.

    :name       the ROM's file name
    :states     [[name {:enter forms :update forms :exit forms}] ...];
                all three actions are optional
    :initial    the initial state
    :variables  the game's variables, as for memory/allocate
    :tiles      the graphics, as 16-byte tiles (see gamedev-toolkit.chr),
                from tile 0. To use `text`, put gamedev-toolkit.font/tiles first
    :palettes   32 colors: four background palettes, then four for sprites
                (see gamedev-toolkit.palette)
    :screens    {name screen}: backgrounds (see gamedev-toolkit.screen), drawn
                with (background name) inside draw-screen
    :levels     {name level}: levels wider than the screen (see
                gamedev-toolkit.screen/level), started with (level name) inside
                draw-screen. They need :metatiles too: the map the levels
                were made from. The cartridge is then wired for vertical
                mirroring, so the background can scroll sideways
    :tables     {label bytes}: other data to put in the ROM, each under its label
    :songs      {name notes}: tunes (see gamedev-toolkit.sound/song), started
                with (play-song name) and stopped with (stop-music)
    :overlays   [{:name :doc :draw (fn [variables] shapes)} ...]: things for a
                debugger to draw over the picture, from the game's variables
                (NES.cljs: see nes.overlay). Passed through as they are

  Returns what rom/nrom returns, with :name."
  [{:keys [name states initial variables tiles palettes screens levels metatiles tables songs overlays]}]
  (let [states (vec states)
        state-names (mapv first states)
        _ (when-not (some #{initial} state-names)
            (throw (ex-info (str ":initial is " (pr-str initial) ", which isn't one of the states " state-names) {})))
        _ (when-not (= 32 (count palettes))
            (throw (ex-info (str ":palettes needs 32 colors, not " (count palettes)) {})))
        state-sub (fn [phase [state-name state]]
                    (sub/sub (keyword (str (clojure.core/name state-name) "-" (clojure.core/name phase)))
                             (get state phase [])))
        levels? (boolean (seq levels))
        _ (when (and levels? (not metatiles))
            (throw (ex-info ":levels needs :metatiles too: the map the levels were made from" {})))]
    (assoc (rom/nrom
            {:code [(reset-code initial)
                    (main-loop (when (some #(:exit (second %)) states)   ; most games have none
                                 (map #(state-sub :exit %) states))
                               (map #(state-sub :enter %) states)
                               (map #(state-sub :update %) states)
                               (seq songs)
                               levels?)
                    (nmi levels?)
                    irq
                    [:label :palettes]
                    [:db palettes]
                    (for [[screen-name s] screens]
                      [[:label (keyword "screen" (clojure.core/name screen-name))]
                       [:db (screen/pack (screen/nametable s))]
                       ;; what's solid, a column at a time (see `solid-at`)
                       [:label (keyword "solid" (clojure.core/name screen-name))]
                       [:db (screen/kind-columns (:solid s))]])
                    (when levels?
                      (let [{:keys [tl tr bl br]} (screen/metatile-tables metatiles)]
                        [[:label :metatiles/tl] [:db tl]
                         [:label :metatiles/tr] [:db tr]
                         [:label :metatiles/bl] [:db bl]
                         [:label :metatiles/br] [:db br]]))
                    (for [[level-name l] levels
                          :let [n (clojure.core/name level-name)]]
                      [[:label (keyword "level-ids" n)] [:db (:ids l)]
                       [:label (keyword "level-kinds" n)] [:db (:kinds l)]
                       [:label (keyword "level-attrs" n)] [:db (:attributes l)]])
                    (for [[label bytes] tables]
                      [[:label label] [:db bytes]])
                    (for [[song-name notes] songs]
                      [[:label (keyword "song" (clojure.core/name song-name))]
                       [:db (sound/song notes)]])]
             :vectors {:nmi :nmi :reset :reset :irq :irq}
             :symbols (merge hw/registers
                             (into {} (map-indexed (fn [i n] [(keyword "state" (clojure.core/name n)) i])
                                                   state-names))
                             ;; each level's last ring start, and how far the camera can go
                             (into {} (for [[level-name {:keys [columns]}] levels
                                            :let [n (clojure.core/name level-name)]]
                                        {(keyword "level-last" n) (- columns 32)
                                         (keyword "camera-max" n) (* 16 (- columns 16))})))
             :mirroring (if levels? :vertical :horizontal)
             :memory (memory/allocate (concat (runtime-variables state-names)
                                               (when (seq songs)
                                                 [[:music {:at :u16 :start :u16 :wait :u8 :on :bool}]])
                                               variables))
             :chr (chr/bank tiles)})
           :name name
           :overlays overlays)))
