(ns gamedev-toolkit.platformer
  "The physics of a platform game: falling, landing, jumping, walls,
  one-way platforms and ladders.

  A *body* is something with a position and a speed, both 8.8 fixed point
  (see gamedev-toolkit.gen), that the background's blocks stop. Each frame:

    1. add its sideways speed to its x. If its leading side is now inside
       a solid block, put it back where it was.
    2. add gravity to its downward speed (up to a limit: terminal velocity).
    3. add that to its y. If it was falling and its feet are now in
       something it can land on, stand it on top (feet at the top of the
       block), stop its fall and note that it's on the ground. If it was
       rising and its head hit something solid, put it back and stop its
       rise.

  Doing sideways and up-and-down separately is what lets a body slide
  along a wall as it falls, and it means each check knows which way the
  body was going, so knows which way to push it back.

  What a block is like depends on its kind (see gamedev-toolkit.screen):

    :solid       stops it every way
    :one-way     only stops it falling, and only if its feet were above
                 the platform before it moved: so it can jump up through
                 one, and land on it
    :ladder      doesn't stop it at all, but it can climb (see `ladders`)
    :ladder-top  both: the top of a ladder is a one-way platform

  'Is it in a block' is asked with gamedev-toolkit.runtime's `probe`, at
  two points along the side in question, because a body is nearly as wide
  as a block and could straddle two.

  The code is generated for one body at a time: (move-body {...}) returns
  the instructions for that body's variables. Its x can be 8.8 fixed point
  (a position on a screen) or, with :wide? true, 16.8 (:u16.8: a position
  on a level up to 65,535 pixels wide). Needs gamedev-toolkit.runtime."
  (:require [gamedev-toolkit.asm :as asm]
            [gamedev-toolkit.gen :as gen]
            [gamedev-toolkit.runtime :as rt]))

(defn- x-whole
  "The body's x in whole pixels: one byte, or a 16-bit pair if it's wide."
  [{:keys [x wide?]}]
  (if wide? (gen/whole16 x) (gen/whole x)))

(defn- save-x [{:keys [x wide?]}]
  [(gen/move16 :rt/old x)
   (when wide? (gen/move [:+ :rt/old 2] [:+ x 2]))])

(defn- restore-x [{:keys [x wide?]}]
  [(gen/move16 x :rt/old)
   (when wide? (gen/move [:+ x 2] [:+ :rt/old 2]))])

(defn- check
  "Probe each [dx dy] of `points` from x, y; as soon as one's kind passes
  `test`, run `then`; if none does, `else`. `test` is a function of a
  label, returning instructions that look at the kind in A and branch to
  the label if it counts."
  [x y points test then else]
  (let [hit (asm/genlabel "hit")
        done (asm/genlabel "checked")]
    [(for [[dx dy] points]
       [(rt/probe x dx y dy) (test hit)])
     else
     [:jmp done]
     [:label hit]
     then
     [:label done]]))

(defn- solid [hit]
  [[:cmp [:imm 1]] [:beq hit]])

(defn- lands
  "A test (for `check`) for feet: solid always counts; a one-way platform
  or a ladder's top counts if the feet were at or above the platform's
  top before this frame's move, and the body isn't climbing."
  [wy height climbing]
  (fn [hit]
    (let [maybe (asm/genlabel "one-way")
          no (asm/genlabel "no")]
      [[:cmp [:imm 1]] [:beq hit]
       [:cmp [:imm 2]] [:beq maybe]
       [:cmp [:imm 4]] [:bne no]
       [:label maybe]
       (when climbing [[:ldx climbing] [:bne no]])
       ;; the block's top: the feet's y with the low four bits cleared
       [:lda wy] [:clc] [:adc [:imm height]]
       [:and [:imm 0xF0]]
       [:sta :rt/temp2]
       ;; the feet before the move (:rt/old holds y as it was)
       [:lda (gen/whole :rt/old)] [:clc] [:adc [:imm height]]
       [:cmp :rt/temp2]
       [:beq hit]
       [:bcc hit]
       [:label no]])))

(defn move-body
  "The instructions to move a body one frame (see the namespace
  docstring). Its variables:

    :x :y            its top left corner, 8.8 fixed point (x 16.8 if :wide?)
    :vx :vy          its speed, signed 8.8 fixed point, in pixels a frame
    :on-ground       set to 1 when it's standing on something, else 0
    :climbing        (optional) 1 while it's on a ladder: no gravity, and
                     it passes through one-way platforms (see `ladders`)

  and its shape and the world:

    :left :right     how far in from its picture's left edge its sides
                     are (a picture is rarely as wide as its 16 pixels)
    :top :height     likewise its head, and how tall it is
    :gravity         pixels per frame, per frame, e.g. 0.25
    :max-fall        its fastest fall, in pixels a frame (less than 16, or
                     it could pass through a block in one frame)

  Uses A, X and Y, :rt/old and :rt/temp2."
  [{:keys [x y vx vy on-ground climbing left right top height gravity max-fall wide?] :as body}]
  (let [wx (x-whole body)
        wy (gen/whole y)
        side (fn [dx] [[dx (inc top)] [dx (dec height)]])
        head [[(inc left) top] [(dec right) top]]
        feet [[(inc left) height] [(dec right) height]]
        gravity-code [(gen/add16 vy [:imm (gen/fixed gravity)])
                      [:lda (gen/hi vy)]
                      (gen/when-flag :positive            ; falling...
                        [:cmp [:imm max-fall]]
                        (gen/when-flag :carry             ; ...too fast
                          (gen/move16 vy [:imm (gen/fixed max-fall)])))]]
    [;; --- sideways
     (save-x body)
     (if wide? (gen/add-wide x vx) (gen/add16 x vx))
     [:lda (gen/hi vx)]
     [:ora (gen/lo vx)]
     (gen/when-flag :not-zero            ; it's moving sideways at all
       [:lda (gen/hi vx)]
       (gen/if-flag :negative
         (check wx wy (side left) solid (restore-x body) nil)
         (check wx wy (side right) solid (restore-x body) nil)))

     ;; --- gravity (not on a ladder)
     (if climbing
       [[:lda climbing] (gen/when-flag :zero gravity-code)]
       gravity-code)

     ;; --- up and down
     ;; (:on-ground is written once, when the answer is known, so that
     ;; anything looking at it part-way through a frame, like a debugger's
     ;; overlay, never sees a wrong value)
     (gen/move16 :rt/old y)
     (gen/add16 y vy)
     [:lda (gen/hi vy)]
     (gen/if-flag :negative
       ;; rising: has its head hit something solid?
       [(gen/move on-ground [:imm 0])
        (check wx wy head solid
               [(gen/move16 y :rt/old)
                (gen/move16 vy [:imm 0])]
               nil)]
       ;; falling (or still): are its feet in something it lands on?
       (check wx wy feet (lands wy height climbing)
              ;; Stand on top of the block. Blocks are 16 high, so the top
              ;; of the one its feet are in is its feet's y with the low
              ;; four bits cleared.
              [[:lda wy] [:clc] [:adc [:imm height]]
               [:and [:imm 0xF0]]
               [:sec] [:sbc [:imm height]]
               [:sta wy]
               (gen/move (gen/lo y) [:imm 0])
               (gen/move16 vy [:imm 0])
               (gen/move on-ground [:imm 1])]
              (gen/move on-ground [:imm 0])))]))

(defn jump
  "Start a jump, if the body is on the ground (or on a ladder): set its
  upward speed to `speed` pixels a frame, then run `body` (a sound, say).
  How high it goes is about speed squared over twice the gravity. Uses A."
  [{:keys [vy on-ground climbing]} speed & body]
  [[:lda on-ground]
   (when climbing [:ora climbing])
   (gen/when-flag :not-zero
     (when climbing (gen/move climbing [:imm 0]))
     (gen/move16 vy [:imm (gen/fixed (- speed))])
     body)])

(defn cut-jump
  "For jumps whose height depends on how long the button is held: if the
  body is still rising faster than `speed` pixels a frame, slow it to
  that. Run this on frames when the jump button isn't held. Uses A."
  [{:keys [vy]} speed]
  [(gen/compare16-signed vy [:imm (gen/fixed (- speed))])
   (gen/when-flag :negative             ; vy < -speed: rising faster
     (gen/move16 vy [:imm (gen/fixed (- speed))]))])

;; ---------------------------------------------------------------------------
;; Speeding up and slowing down

(defn speed-up
  "Add `amount` (pixels a frame, + or -) to the speed `v`, but not past
  `limit` (pixels a frame, the same sign). Uses A."
  [v amount limit]
  [(gen/add16 v [:imm (gen/fixed amount)])
   (gen/compare16-signed v [:imm (gen/fixed limit)])
   (if (pos? amount)
     (gen/when-flag :positive (gen/move16 v [:imm (gen/fixed limit)]))   ; v >= limit
     (gen/when-flag :negative (gen/move16 v [:imm (gen/fixed limit)])))]) ; v < limit

(defn slow-down
  "Take `amount` (pixels a frame) off the speed `v`, towards 0, and stop
  there: friction. Uses A."
  [v amount]
  [[:lda (gen/hi v)]
   (gen/if-flag :negative
     [(gen/add16 v [:imm (gen/fixed amount)])
      [:lda (gen/hi v)]
      (gen/when-flag :positive (gen/move16 v [:imm 0]))]
     [(gen/sub16 v [:imm (gen/fixed amount)])
      [:lda (gen/hi v)]
      (gen/when-flag :negative (gen/move16 v [:imm 0]))])])

;; ---------------------------------------------------------------------------
;; Ladders

(defn- start-climbing
  "Get on the ladder: line up with it, and keep to whole pixels (climbing
  is a pixel at a time, so it reaches the ladder's top exactly)."
  [{:keys [x y vx vy climbing] :as body}]
  (let [wx (gen/wide (x-whole body))]
    [(gen/move climbing [:imm 1])
     ;; x = (x + 8) with the low four bits cleared: the ladder's column
     (gen/with-operand :lda (gen/lo wx)) [:clc] [:adc [:imm 8]] [:and [:imm 0xF0]]
     (gen/with-operand :sta (gen/lo wx))
     (when (:wide? body)
       [(gen/with-operand :lda (gen/hi wx)) [:adc [:imm 0]]  ; (AND leaves the carry alone)
        (gen/with-operand :sta (gen/hi wx))])
     (gen/move (gen/lo x) [:imm 0])
   (gen/move (gen/lo y) [:imm 0])
     (gen/move16 vx [:imm 0])
     (gen/move16 vy [:imm 0])]))

(defn ladders
  "Climbing, with Up and Down. Run it after the code that sets the body's
  sideways speed from the D-pad, and before `move-body`. The body needs a
  :climbing variable.

  - Up, with the middle of the body on a ladder: get on it.
  - Down, standing on a ladder's top: get on it and start down.
  - On it: Up and Down climb `speed` pixels a frame (a whole number);
    nothing sideways. Jump (`jump`) to get off, or walk off at the bottom.
  - Climb past the top, or off the bottom of a ladder that ends in mid
    air, and it's off it.

  Uses A, X and Y."
  [{:keys [x y vx vy on-ground climbing height] :as body} speed]
  (let [wx (x-whole body)
        wy (gen/whole y)
        climbable [:ladder :ladder-top]]
    [[:lda climbing]
     (gen/if-flag :zero
       ;; --- not on a ladder: get on one?
       [(rt/when-held :up
          (rt/probe wx 8 wy 8)                        ; the middle of the body
          (rt/on-kinds climbable (start-climbing body)))
        (rt/when-held :down
          [:lda on-ground]
          (gen/when-flag :not-zero
            (rt/probe wx 8 wy height)                 ; what it's standing on
            (rt/on-kinds [:ladder-top]
              [(start-climbing body)
               [:inc wy] [:inc wy]])))]                ; into the ladder's top
       ;; --- on a ladder
       [(gen/move16 vx [:imm 0])
        (gen/move16 vy [:imm 0])
        (rt/when-held :up (gen/move16 vy [:imm (gen/fixed (- speed))]))
        (rt/when-held :down (gen/move16 vy [:imm (gen/fixed speed)]))
        ;; still on it? Its bottom row of pixels must be
        (rt/probe wx 8 wy (dec height))
        (rt/on-kinds climbable
          nil
          (gen/move climbing [:imm 0]))
        ;; at the bottom, standing on the floor: Left or Right walks off
        [:lda on-ground]
        (gen/when-flag :not-zero
          [:lda :pad/held]
          [:and [:imm 0x03]]                          ; left or right
          (gen/when-flag :not-zero
            (gen/move climbing [:imm 0])))])]))
