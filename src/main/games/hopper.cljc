(ns games.hopper
  "Star Hopper: a one-screen platform game, to exercise the engine.

  Run and jump round the girders collecting stars. Spiky things patrol
  some of them; touch one and it's over. Collect every star and they all
  come back.

  What's new since games.catch:

    gravity, jumping, landing    gamedev-toolkit.platformer
    solid blocks in the screen   :solid? on a metatile (gamedev-toolkit.screen)
    stars and spikes as objects  gamedev-toolkit.objects: a pool of slots
    the level's objects          drawn in the level's text, with the blocks
    a character that faces both
      ways, and walks            sprites-flipped, and two frames
    sound effects                gamedev-toolkit.sound

  At the REPL: (play! (hopper/build)). Left and Right to run, A to jump."
  (:require [gamedev-toolkit.asm :as asm]
            [gamedev-toolkit.font :as font]
            [gamedev-toolkit.gen :as gen]
            [gamedev-toolkit.objects :as objects]
            [gamedev-toolkit.palette :as palette]
            [gamedev-toolkit.platformer :as platformer]
            [gamedev-toolkit.runtime :as rt :refer [transition]]
            [gamedev-toolkit.screen :as screen]
            [gamedev-toolkit.sound :as sound]
            [gamedev-toolkit.sub :as sub :refer [call]]
            [gamedev-toolkit.tileset :as tileset]
            [games.catch :as catch]))

;; ---------------------------------------------------------------------------
;; Graphics

(def hopper-standing
  ["................"
   ".....111111....."
   "....12222221...."
   "...1222222221..."
   "...1222313221..."
   "...1222313221..."
   "...1222222221..."
   "...1222222111..."
   "...1222222221..."
   "....12222221...."
   ".....111111....."
   "....11....11...."
   "....11....11...."
   "....11....11...."
   "...111...111...."
   "................"])

(def hopper-striding
  ["................"
   ".....111111....."
   "....12222221...."
   "...1222222221..."
   "...1222313221..."
   "...1222313221..."
   "...1222222221..."
   "...1222222111..."
   "...1222222221..."
   "....12222221...."
   ".....111111....."
   ".....11..11....."
   "....11....11...."
   "...11......11..."
   "..111......111.."
   "................"])

(def girder
  ["1111111111111111"
   "1222222222222221"
   "1233333333333321"
   "1222222222222221"
   "1111111111111111"
   ".1....1..1....1."
   "..1..1....1..1.."
   "...11......11..."
   "...11......11..."
   "..1..1....1..1.."
   ".1....1..1....1."
   "1111111111111111"
   "1222222222222221"
   "1222222222222221"
   "1222222222222221"
   "1111111111111111"])

(def tiles
  (tileset/tileset [[:font     font/tiles]
                    [:standing hopper-standing]
                    [:striding hopper-striding]
                    [:star     catch/star]        ; borrowed from Catch the Stars
                    [:spike    catch/spike]
                    [:wall     catch/wall]
                    [:girder   girder]
                    [:twinkle  catch/twinkle]]))

(def palettes
  (palette/palettes
   {:backdrop   :black
    :background [[:white :light-gray :gray]            ; text, and the twinkles
                 [:dark-gray :gray :light-gray]        ; the wall
                 [:dark-cyan :cyan :pale-cyan]]        ; girders
    :sprites    [[:dark-green :light-lime :white]      ; the hopper
                 [:light-orange :pale-yellow :white]   ; stars
                 [:black :red :white]]}))              ; spikes

;; ---------------------------------------------------------------------------
;; The level
;;
;; One character per 16x16 block. # and = are solid. P is where the player
;; starts, * a star and S a spike: those aren't blocks (the background
;; there is empty), they're where objects are put when the level starts.

(def level
  ["................"
   "................"
   "################"
   "#..............#"
   "#.*..........*.#"
   "#..............#"
   "#===........===#"
   "#......*.......#"
   "#....======....#"
   "#.*.........S..#"
   "#====.....=====#"
   "#......*.......#"
   "#.....====.....#"
   "#.P..........S.#"
   "################"])

(def metatiles
  {:empty  {:tiles [0 0 0 0] :palette 0}
   :wall   {:tiles (tileset/block tiles :wall) :palette 1 :solid? true}
   :girder {:tiles (tileset/block tiles :girder) :palette 2 :solid? true}})

(def legend
  {\. :empty, \# :wall, \= :girder
   \P :empty, \* :empty, \S :empty})

(def sky
  "A screen with nothing in it but stars, for the title and game over."
  (let [twinkle (tileset/tile tiles :twinkle)]
    {:metatiles (assoc metatiles :twinkle {:tiles [twinkle 0 0 0] :palette 0})
     :legend (assoc legend \+ :twinkle)
     :rows ["................"
            "..+.......+....."
            ".......+......+."
            ".+.............."
            "............+..."
            "................"
            "................"
            "..+..........+.."
            "................"
            "................"
            ".+............+."
            "................"
            ".....+....+....."
            "..+..........+.."
            "................"]}))

(def screens
  {:title     (screen/screen (assoc sky :text [[10 9 "STAR HOPPER"]
                                               [10 18 "PRESS START"]
                                               [5 22 "A JUMPS. MIND THE SPIKES"]]))
   :level     (screen/screen {:metatiles metatiles :legend legend :rows level
                              :text [[11 2 "SCORE 000"]]})
   :game-over (screen/screen (assoc sky :text [[11 10 "GAME OVER"]
                                               [11 14 "SCORE"]
                                               [10 20 "PRESS START"]]))})

(def score-column 17) (def score-row 2)

;; ---------------------------------------------------------------------------
;; Objects, and variables

(def things
  "Stars and spikes. :dir is which way a spike is walking: 0 right, 1 left."
  (objects/pool :thing 8 [:star :spike] {:x :u8 :y :u8 :dir :u8}))

(def placed
  "Where the level's text puts things: [[type x y] ...]"
  (objects/in-drawing level {\* :star, \S :spike}))

(def start
  "Where the P is, in pixels."
  (let [[[_ x y]] (objects/in-drawing level {\P :player})]
    {:x x :y y}))

(def variables
  [[:player {:x :u8.8, :y :u8.8, :vx :s8.8, :vy :s8.8
             :on-ground :bool
             :facing    [:enum {0 :right 1 :left}]}]
   [:stars-left :u8]
   [:score [:array 3 :u8] {:doc "Three decimal digits, hundreds first"}]
   (:variable things)])

;; ---------------------------------------------------------------------------
;; Numbers to tune

(def run-speed 1.5)          ; pixels a frame
(def jump-speed 4.5)         ; upwards, at the start of a jump: enough to climb two blocks
(def gravity 0.25)           ; added to the downward speed every frame
(def touching 12)            ; pixels: how close counts as touching

(def body
  "The player, as gamedev-toolkit.platformer wants a body described. The
  picture is 16 wide but the hopper is drawn in the middle 10 of them."
  {:x :player/x :y :player/y :vx :player/vx :vy :player/vy
   :on-ground :player/on-ground
   :left 3 :right 12 :top 1 :height 16
   :gravity gravity :max-fall 4})

;; ---------------------------------------------------------------------------
;; Sounds

(def jump-sound {:channel :pulse1 :hz 220 :duty 2 :decay 3
                 :sweep {:period 2 :shift 3 :up? true}})       ; a rising 'bwip'
(def star-sound {:channel :pulse2 :hz 1320 :duty 1 :decay 2})  ; a high 'ting'
(def hit-sound  {:channel :noise :pitch 12 :decay 6})          ; a crunch

;; ---------------------------------------------------------------------------
;; Pieces of the game

(def start-level
  (sub/sub
   :start-level
   "Put the player at the start and every star and spike where the level's
  text has them."
   [(gen/move16 :player/x [:imm (gen/fixed (:x start))])
    (gen/move16 :player/y [:imm (gen/fixed (:y start))])
    (gen/move16 :player/vx [:imm 0])
    (gen/move16 :player/vy [:imm 0])
    (gen/move :player/facing [:imm 0])
    (gen/move :stars-left [:imm (count (filter #(= :star (first %)) placed))])
    (objects/clear things)
    (objects/spawn-all things :placed)]))

(def move-player
  (sub/sub
   :move-player
   "Left and Right set the sideways speed (there's no skidding: letting go
  stops at once), A jumps, and platformer/move-body does the rest."
   [(gen/move16 :player/vx [:imm 0])
    (rt/when-held :right
      (gen/move16 :player/vx [:imm (gen/fixed run-speed)])
      (gen/move :player/facing [:imm 0]))
    (rt/when-held :left
      (gen/move16 :player/vx [:imm (gen/fixed (- run-speed))])
      (gen/move :player/facing [:imm 1]))
    (rt/when-pressed :a
      (platformer/jump body jump-speed
        (sound/play jump-sound)))
    (platformer/move-body body)]))

(defn score-digit
  "Instructions that leave the tile for digit `i` of the score in A."
  [i]
  [[:lda [:+ :score i]]
   [:clc]
   [:adc [:imm (font/tile-of \0 (tileset/tile tiles :font))]]])

(def collect-star
  (sub/sub
   :collect-star
   "The star whose slot is in X has been touched."
   [(objects/remove-it things)
    (sound/play star-sound)
    (gen/inc-decimal :score 3)
    (rt/queue (rt/tile-address score-column score-row)
              (score-digit 0) (score-digit 1) (score-digit 2))
    [:dec :stars-left]
    (gen/when-flag :zero                 ; that was the last: they all come back
      (call start-level))]))

(defn- turn-if
  "Inside `each`, for a spike: turn it round (to face `new-dir`) if
  there's a wall `ahead` pixels along from its left edge, or no floor
  under that point."
  [ahead new-dir]
  (let [x (objects/field things :x)
        y (objects/field things :y)
        turn (asm/genlabel "turn")
        done (asm/genlabel "walked")]
    [(rt/probe x ahead y 8)              ; a wall at chest height?
     [:bne turn]
     (objects/slot)
     (rt/probe x ahead y 16)             ; floor to walk onto?
     [:bne done]
     [:label turn]
     (objects/slot)
     (gen/move (objects/field things :dir) [:imm new-dir])
     [:label done]
     (objects/slot)]))

(def walk-spike
  (sub/sub
   :walk-spike
   "The spike whose slot is in X takes a step, a pixel every other frame,
  and turns round at a wall or the end of its girder."
   [[:lda :rt/frame]
    [:and [:imm 1]]
    (gen/when-flag :zero
      (gen/with-operand :lda (objects/field things :dir))
      (gen/if-flag :zero
        [(gen/add (objects/field things :x) [:imm 1])
         (turn-if 16 1)]
        [(gen/subtract (objects/field things :x) [:imm 1])
         (turn-if -1 0)]))]))

(def update-things
  (sub/sub
   :update-things
   "Every star and spike: move it if it moves, and see if the player is touching it."
   [(objects/each things
      (objects/by-type things
        {:spike [(call walk-spike)
                 (objects/slot)]})
      (gen/when-close [(gen/whole :player/x) (gen/whole :player/y)]
                      [(objects/field things :x) (objects/field things :y)] touching
        (objects/by-type things
          {:star  [(call collect-star)]
           :spike [(sound/play hit-sound)
                   (transition :game-over)]})))]))

(defn- draw-hopper
  "The player, with the picture called `frame`, facing whichever way."
  [frame]
  [[:lda :player/facing]
   (gen/if-flag :zero
     (rt/metasprite (gen/whole :player/x) (gen/whole :player/y) (tileset/sprites tiles frame 0))
     (rt/metasprite (gen/whole :player/x) (gen/whole :player/y) (tileset/sprites-flipped tiles frame 0)))])

(def draw-everything
  (sub/sub
   :draw-everything
   "The player: striding on every other eighth of a second while running
  on the ground, otherwise standing. Then each star and spike."
   [[:lda :player/on-ground]
    (gen/when-flag :not-zero
      [:lda (gen/hi :player/vx)]
      [:ora (gen/lo :player/vx)]
      (gen/when-flag :not-zero
        [:lda :rt/frame]
        [:and [:imm 8]]))
    ;; zero here means: standing (in the air, or still, or the other half of the stride)
    (gen/if-flag :zero
      (draw-hopper :standing)
      (draw-hopper :striding))
    (objects/each things
      (let [x (objects/field things :x)
            y (objects/field things :y)]
        (objects/by-type things
          {:star  (rt/metasprite x y (tileset/sprites tiles :star 1) {:with :y})
           :spike (rt/metasprite x y (tileset/sprites tiles :spike 2) {:with :y})})))]))

;; ---------------------------------------------------------------------------
;; The states

(def title
  {:enter [(rt/draw-screen
             (rt/background :title))]
   :update [(rt/metasprite [:imm 120] [:imm 104] (tileset/sprites tiles :standing 0))
            (rt/when-pressed :start
              (transition :playing))]})

(def playing
  {:enter [(rt/draw-screen
             (rt/background :level))
           (gen/fill :score 3 [:imm 0])
           (call start-level)]
   :update [(call move-player)
            (call update-things)
            (call draw-everything)]})

(def game-over
  {:enter [(rt/draw-screen
             (rt/background :game-over)
             (rt/at score-column 14)
             (for [i (range 3)]
               [(score-digit i) [:sta :ppudata]]))]
   :update [(rt/when-pressed :start
              (transition :playing))]})

;; ---------------------------------------------------------------------------
;; An overlay, for the debugger: what the game thinks is solid, and where
;; it thinks things are.

(def overlays
  [{:name "Physics"
    :doc "The solid blocks, the player's body (green when standing on something) and its speed, and each object's slot number."
    ;; (a struct with this many fields comes as one value per field: :player.x ...)
    :draw (fn [{state :rt.state xs :thing.x ys :thing.y types :thing.type
                x :player.x y :player.y vx :player.vx vy :player.vy on-ground :player.on-ground}]
            (when (= state :playing)
              (let [{:keys [left right top height]} body]
                (concat
                 (for [[row line] (map-indexed vector level)
                       [col ch] (map-indexed vector line)
                       :when (:solid? (metatiles (legend ch)))]
                   [:rect {:x (* 16 col) :y (* 16 row) :w 16 :h 16 :color :gray}])
                 ;; (+ 1: the PPU draws a sprite one line below the Y it's given)
                 [[:rect {:x (+ x left) :y (+ y top 1) :w (inc (- right left)) :h (- height top)
                          :color (if on-ground :lime :yellow)}]
                  [:line {:x1 (+ x 8) :y1 (+ y 9) :x2 (+ x 8 (* 8 vx)) :y2 (+ y 9 (* 8 vy))
                          :color :yellow :arrow? true}]]
                 (for [[slot t] (map-indexed vector types)
                       :when (pos? t)]
                   [:text {:x (+ (nth xs slot) 6) :y (nth ys slot) :text slot :color :white}])))))}])

(defn build
  "Build the cartridge. Returns {:rom the .nes file's bytes, :name, ...}."
  []
  (rt/program
   {:name "hopper.nes"
    :variables variables
    :tiles (:tiles tiles)
    :palettes palettes
    :screens screens
    :tables {:placed (objects/placements things placed)}
    :overlays overlays
    :initial :title
    :states [[:title title]
             [:playing playing]
             [:game-over game-over]]}))
