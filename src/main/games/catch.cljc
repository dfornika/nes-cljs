(ns games.catch
  "Catch the Stars: a first real game, built on gamedev-toolkit.runtime.

  You're the face. Touch a star and you score a point, and another star
  appears somewhere else. A spiky ball bounces around the screen, a little
  faster after every star; touch that and the game is over.

  The whole game is a state machine with three states (title, playing,
  game over), each with an entry action and code that runs every frame,
  and transitions between them. The
  runtime does the rest: reading the controller, sending sprites to the
  PPU, and so on. At the REPL: (play! (catch/build))."
  (:require [gamedev-toolkit.chr :as chr]
            [gamedev-toolkit.font :as font]
            [gamedev-toolkit.gen :as gen]
            [gamedev-toolkit.runtime :as rt :refer [transition]]
            [gamedev-toolkit.sub :as sub :refer [call]]))

;; ---------------------------------------------------------------------------
;; Variables

(def variables
  [;; Positions are fixed point (a whole byte and a fraction byte), so
   ;; things can move by parts of a pixel. Speeds can be negative.
   [:player {:x :u8.8, :y :u8.8}]
   [:enemy  {:x :u8.8, :y :u8.8, :vx :s8.8, :vy :s8.8}]
   [:star   {:x :u8, :y :u8}]
   [:score  [:array 3 :u8] {:doc "Three decimal digits, hundreds first"}]])

;; ---------------------------------------------------------------------------
;; Graphics

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

(def star
  [".......11......."
   ".......11......."
   "......1221......"
   "......1221......"
   ".....122221....."
   "1111112222111111"
   ".12222222222221."
   "..122222222221.."
   "...1222222221..."
   "...1222222221..."
   "..122221122221.."
   "..12221..12221.."
   ".12221....12221."
   ".1221......1221."
   ".111........111."
   "................"])

(def spike
  [".......11......."
   "..1....11....1.."
   "..11..1221..11.."
   "...1112222111..."
   "...1222222221..."
   "..122222222221.."
   ".12223322332221."
   "1122231221322211"
   "1122222222222211"
   ".12222111122221."
   "..122122221221.."
   "...1222222221..."
   "...1112222111..."
   "..11..1221..11.."
   "..1....11....1.."
   ".......11......."])

(def tiles
  "The font first (so tile 0 is a blank space, and `rt/text` works), then
  the three pictures, four tiles each."
  (vec (concat font/tiles (chr/tiles face) (chr/tiles star) (chr/tiles spike))))

;; Each picture is 2 tiles by 2; these are its four sprites, and which
;; sprite palette they use.
(def first-picture (count font/tiles))
(def face-sprites  (rt/picture first-picture 2 2 0))
(def star-sprites  (rt/picture (+ first-picture 4) 2 2 1))
(def spike-sprites (rt/picture (+ first-picture 8) 2 2 2))

;; Colors are numbers from the NES's fixed set of 64 (see the Palettes tab).
(def palettes
  (let [bg 0x02]                           ; dark blue
    [bg 0x30 0x10 0x00,  0 0 0 0,  0 0 0 0,  0 0 0 0    ; background: white text
     bg 0x0F 0x28 0x30                                  ; the face: black, yellow, white
     0  0x27 0x38 0x30                                  ; the star: orange, pale yellow
     0  0x0F 0x16 0x30                                  ; the spike: black, red, white
     0 0 0 0]))

;; ---------------------------------------------------------------------------
;; Numbers to tune

(def player-speed [:imm (gen/fixed 1.5)])            ; pixels per frame
(def spike-start-speed {:vx 1.0 :vy 0.75})
(def spike-speed-up [:imm (gen/fixed 0.125)])        ; added per star caught

;; Where things may be: the screen is 256 x 240, pictures are 16 x 16, and
;; the top rows are kept for the score.
(def left 8) (def right 232) (def top 32) (def bottom 208)

(def touching 12)                                    ; pixels, centre to centre

(def score-column 17) (def score-row 2)

;; ---------------------------------------------------------------------------
;; Pieces of the game

(def place-star
  (sub/sub
   :place-star
   "Put the star somewhere random: 128 possible places each way, a power
  of two, so keeping the low 7 bits of a random byte is all it takes."
   [(call rt/random) [:and [:imm 0x7F]] [:clc] [:adc [:imm 56]] [:sta :star/x]
    (call rt/random) [:and [:imm 0x7F]] [:clc] [:adc [:imm 48]] [:sta :star/y]]))

(defn score-digit
  "Instructions that leave the tile for digit `i` of the score in A."
  [i]
  [[:lda [:+ :score i]]
   [:clc]
   [:adc [:imm (font/tile-of \0)]]])

(defn speed-up
  "Make a speed a little bigger, whichever way it's going."
  [speed]
  [[:lda (gen/hi speed)]                 ; its top bit is set if it's negative
   (gen/if-flag :negative
     (gen/sub16 speed spike-speed-up)
     (gen/add16 speed spike-speed-up))])

(def move-player
  (sub/sub
   :move-player
   "Move with the D-pad, and stay on the screen."
   [(rt/when-held :right (gen/add16 :player/x player-speed))
    (rt/when-held :left  (gen/sub16 :player/x player-speed))
    (rt/when-held :down  (gen/add16 :player/y player-speed))
    (rt/when-held :up    (gen/sub16 :player/y player-speed))
    (gen/clamp-fixed :player/x left right)
    (gen/clamp-fixed :player/y top bottom)]))

(def move-spike
  (sub/sub
   :move-spike
   "Move the spike by its speed. At an edge, put it back on the edge and
  turn that part of its speed around."
   [(gen/add16 :enemy/x :enemy/vx)
    (gen/clamp-fixed :enemy/x left right (gen/negate16 :enemy/vx))
    (gen/add16 :enemy/y :enemy/vy)
    (gen/clamp-fixed :enemy/y top bottom (gen/negate16 :enemy/vy))]))

(def check-touches
  (sub/sub
   :check-touches
   "Touching the star scores; touching the spike ends the game."
   [(gen/when-close [(gen/whole :player/x) (gen/whole :player/y)] [:star/x :star/y] touching
      (gen/inc-decimal :score 3)
      ;; the score on screen is background tiles: ask for them to be redrawn
      (rt/queue (rt/tile-address score-column score-row)
                (score-digit 0) (score-digit 1) (score-digit 2))
      (call place-star)
      (speed-up :enemy/vx)
      (speed-up :enemy/vy))
    (gen/when-close [(gen/whole :player/x) (gen/whole :player/y)]
                    [(gen/whole :enemy/x) (gen/whole :enemy/y)] touching
      (transition :game-over))]))

(def draw-everything
  (sub/sub
   :draw-everything
   [(rt/metasprite (gen/whole :player/x) (gen/whole :player/y) face-sprites)
    (rt/metasprite :star/x :star/y star-sprites)
    (rt/metasprite (gen/whole :enemy/x) (gen/whole :enemy/y) spike-sprites)]))

;; ---------------------------------------------------------------------------
;; The states

(def title
  {:enter [(rt/draw-screen
             (rt/clear-background)
             (rt/text 8 9 "CATCH THE STARS")
             (rt/text 10 18 "PRESS START")
             (rt/text 5 22 "MIND THE SPIKY THING!"))]
   :update [;; some decoration: the cast, standing still
            (rt/metasprite [:imm 88] [:imm 104] face-sprites)
            (rt/metasprite [:imm 120] [:imm 104] star-sprites)
            (rt/metasprite [:imm 152] [:imm 104] spike-sprites)
            (rt/when-pressed :start
              (rt/stir-random)
              (transition :playing))]})

(def playing
  {:enter [(rt/draw-screen
             (rt/clear-background)
             (rt/text 11 score-row "SCORE 000"))
           (gen/fill :score 3 [:imm 0])
           (gen/move16 :player/x [:imm (gen/fixed 120)])
           (gen/move16 :player/y [:imm (gen/fixed 112)])
           (gen/move16 :enemy/x [:imm (gen/fixed left)])
           (gen/move16 :enemy/y [:imm (gen/fixed top)])
           (gen/move16 :enemy/vx [:imm (gen/fixed (:vx spike-start-speed))])
           (gen/move16 :enemy/vy [:imm (gen/fixed (:vy spike-start-speed))])
           (call place-star)]
   :update [(call move-player)
            (call move-spike)
            (call check-touches)
            (call draw-everything)]})

(def game-over
  {:enter [(rt/draw-screen
             (rt/clear-background)
             (rt/text 11 10 "GAME OVER")
             (rt/text 11 14 "SCORE ")
             ;; the digits come from memory, so they're written one by one
             (for [i (range 3)]
               [(score-digit i) [:sta :ppudata]])
             (rt/text 10 20 "PRESS START"))]
   :update [(rt/when-pressed :start
              (transition :playing))]})

(defn build
  "Build the cartridge. Returns {:rom the .nes file's bytes, :name, ...}."
  []
  (rt/program
   {:name "catch.nes"
    :variables variables
    :tiles tiles
    :palettes palettes
    :initial :title
    :states [[:title title]
             [:playing playing]
             [:game-over game-over]]}))
