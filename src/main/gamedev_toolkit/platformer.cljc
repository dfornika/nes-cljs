(ns gamedev-toolkit.platformer
  "The physics of a platform game: falling, landing, jumping, and walls.

  A *body* is something with a position and a speed, both 8.8 fixed point
  (see gamedev-toolkit.gen), that the background's solid blocks stop. Each
  frame:

    1. add its sideways speed to its x. If its leading side is now inside
       something solid, put it back where it was.
    2. add gravity to its downward speed (up to a limit: terminal velocity).
    3. add that to its y. If it was falling and its feet are now inside
       something solid, stand it on top (feet at the top of the block),
       stop its fall and note that it's on the ground. If it was rising
       and its head hit something, put it back and stop its rise.

  Doing sideways and up-and-down separately is what lets a body slide
  along a wall as it falls, and it means each check knows which way the
  body was going, so knows which way to push it back.

  'Inside something solid' is asked with gamedev-toolkit.runtime's
  `probe`, at two points along the side in question, because a body is
  nearly as wide as a block and could straddle two.

  The code is generated for one body at a time: (move-body {...}) returns
  the instructions for that body's variables. Needs
  gamedev-toolkit.runtime."
  (:require [gamedev-toolkit.gen :as gen]
            [gamedev-toolkit.runtime :as rt]))

(defn- either-solid
  "Probe two points; afterwards the zero flag is clear if either is solid."
  [x dx1 y dy1 dx2 dy2]
  [(rt/probe x dx1 y dy1)
   ;; the second only if the first found nothing; either way the flags
   ;; are left from the last probe made
   (gen/when-flag :zero
     (rt/probe x dx2 y dy2))])

(defn move-body
  "The instructions to move a body one frame (see the namespace
  docstring). Its variables:

    :x :y            its top left corner, 8.8 fixed point
    :vx :vy          its speed, signed 8.8 fixed point, in pixels a frame
    :on-ground       set to 1 when it's standing on something, else 0

  and its shape and the world:

    :left :right     how far in from its picture's left edge its sides
                     are (a picture is rarely as wide as its 16 pixels)
    :top :height     likewise its head, and how tall it is
    :gravity         pixels per frame, per frame, e.g. 0.25
    :max-fall        its fastest fall, in pixels a frame (less than 16, or
                     it could pass through a block in one frame)

  Uses A, X and Y, and :rt/old."
  [{:keys [x y vx vy on-ground left right top height gravity max-fall]}]
  (let [wx (gen/whole x)
        wy (gen/whole y)
        stop-vy (gen/move16 vy [:imm 0])]
    [;; --- sideways
     (gen/move16 :rt/old x)
     (gen/add16 x vx)
     [:lda (gen/hi vx)]
     [:ora (gen/lo vx)]
     (gen/when-flag :not-zero            ; it's moving sideways at all
       [:lda (gen/hi vx)]
       (gen/if-flag :negative
         (either-solid wx left wy (inc top) left (dec height))
         (either-solid wx right wy (inc top) right (dec height)))
       (gen/when-flag :not-zero
         (gen/move16 x :rt/old)))

     ;; --- gravity
     (gen/add16 vy [:imm (gen/fixed gravity)])
     [:lda (gen/hi vy)]
     (gen/when-flag :positive            ; falling...
       [:cmp [:imm max-fall]]
       (gen/when-flag :carry             ; ...too fast
         (gen/move16 vy [:imm (gen/fixed max-fall)])))

     ;; --- up and down
     (gen/move16 :rt/old y)
     (gen/add16 y vy)
     (gen/move on-ground [:imm 0])
     [:lda (gen/hi vy)]
     (gen/if-flag :negative
       ;; rising: has its head hit something?
       [(either-solid wx (inc left) wy top (dec right) top)
        (gen/when-flag :not-zero
          (gen/move16 y :rt/old)
          stop-vy)]
       ;; falling (or still): are its feet in something?
       [(either-solid wx (inc left) wy height (dec right) height)
        (gen/when-flag :not-zero
          ;; Stand on top of the block. Blocks are 16 high, so the top of
          ;; the one its feet are in is its feet's y with the low four
          ;; bits cleared.
          [:lda wy] [:clc] [:adc [:imm height]]
          [:and [:imm 0xF0]]
          [:sec] [:sbc [:imm height]]
          [:sta wy]
          (gen/move (gen/lo y) [:imm 0])
          (gen/move16 vy [:imm 0])
          (gen/move on-ground [:imm 1]))])]))

(defn jump
  "Start a jump, if the body is on the ground: set its upward speed to
  `speed` pixels a frame, then run `body` (a sound, say). How high it
  goes is about speed squared over twice the gravity. Uses A."
  [{:keys [vy on-ground]} speed & body]
  [[:lda on-ground]
   (gen/when-flag :not-zero
     (gen/move16 vy [:imm (gen/fixed (- speed))])
     body)])
