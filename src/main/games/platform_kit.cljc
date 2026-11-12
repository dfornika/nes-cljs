(ns games.platform-kit
  "The rules of a small platform game, without the looks: run and jump
  round one screen collecting things ('pickups'), keep clear of the
  creatures that walk about it ('walkers'), and when the last pickup is
  collected they all come back.

  A game supplies the art, the level and the numbers, and `program` puts
  them together with these rules (and gamedev-toolkit.runtime) into a
  cartridge. games.hopper and games.alienscape are both made this way:
  the same code, looking and feeling quite different.

  What's here is ordinary game code built on the engine:

    gravity, jumping, landing    gamedev-toolkit.platformer
    pickups and walkers          gamedev-toolkit.objects: a pool of slots
    sound effects                gamedev-toolkit.sound
    a player that faces both
      ways and walks, and
      creatures that animate     sprites-flipped, rt/animated"
  (:require [gamedev-toolkit.asm :as asm]
            [gamedev-toolkit.font :as font]
            [gamedev-toolkit.gen :as gen]
            [gamedev-toolkit.objects :as objects]
            [gamedev-toolkit.platformer :as platformer]
            [gamedev-toolkit.runtime :as rt :refer [transition]]
            [gamedev-toolkit.sound :as sound]
            [gamedev-toolkit.sub :as sub :refer [call]]
            [gamedev-toolkit.tileset :as tileset]))

(def things
  "The pool of pickups and walkers. :dir is which way a walker is
  walking: 0 right, 1 left."
  (objects/pool :thing 8 [:pickup :walker] {:x :u8 :y :u8 :dir :u8}))

(def variables
  [[:player {:x :u8.8, :y :u8.8, :vx :s8.8, :vy :s8.8
             :on-ground :bool
             :facing    [:enum {0 :right 1 :left}]}]
   [:pickups-left :u8]
   [:score [:array 3 :u8] {:doc "Three decimal digits, hundreds first"}]
   (:variable things)])

(def default-sounds
  {:jump   {:channel :pulse1 :hz 220 :duty 2 :decay 3
            :sweep {:period 2 :shift 3 :up? true}}       ; a rising 'bwip'
   :pickup {:channel :pulse2 :hz 1320 :duty 1 :decay 2}  ; a high 'ting'
   :hit    {:channel :noise :pitch 12 :decay 6}})        ; a crunch

(defn in-level
  "Read a level drawn as text (15 rows of 16 characters, one per 16x16
  block): where the player starts (P), and the pickups (`pickup`, default
  *) and walkers (`walker`, default S). Returns {:start {:x :y} :placed
  [[type x y] ...]}, in pixels."
  ([rows] (in-level rows {}))
  ([rows {:keys [pickup walker] :or {pickup \* walker \S}}]
   (let [[[_ x y] :as starts] (objects/in-drawing rows {\P :player})]
     (when-not (= 1 (count starts))
       (throw (ex-info (str "A level needs one P, for where the player starts; this has " (count starts)) {})))
     {:start {:x x :y y}
      :placed (objects/in-drawing rows {pickup :pickup, walker :walker})})))

(defn- body-of
  "The player as gamedev-toolkit.platformer wants a body described."
  [{:keys [gravity max-fall box]}]
  (merge {:x :player/x :y :player/y :vx :player/vx :vy :player/vy
          :on-ground :player/on-ground
          :gravity gravity :max-fall max-fall}
         box))

(defn- frames
  "Sets of sprite pieces for the tileset pictures called `names`, facing
  as drawn or (`flipped?`) the other way."
  [tiles names palette flipped?]
  (mapv #(if flipped?
           (tileset/sprites-flipped tiles % palette)
           (tileset/sprites tiles % palette))
        names))

(defn program
  "Build a game from its looks, level and numbers:

    :name        the ROM's file name
    :tiles       a tileset (gamedev-toolkit.tileset) whose first entry is
                 :font, with the pictures named below
    :palettes    32 colors (gamedev-toolkit.palette)
    :screens     {:title s, :level s, :game-over s}; :level needs its
                 :solid map
    :start       {:x :y}: where the player begins, in pixels
    :placed      [[:pickup x y] [:walker x y] ...] (see `in-level`)
    :player      {:standing name, :striding name, :palette n}: 16x16
                 pictures, drawn facing right
    :pickup      {:frames [names], :palette n, :every frames}
    :walker      {:frames [names], :palette n, :every frames, and
                 :faces :left if the pictures are drawn facing left}
    :score-at    {:level [col row], :game-over [col row]}: where the
                 three digits go on those screens
    :run-speed :jump-speed :gravity :max-fall    in pixels a frame
    :box         {:left :right :top :height}: the player's body within its
                 16x16 picture (see platformer/move-body)
    :touching    how close, in pixels, counts as touching (default 12)
    :sounds      {:jump :pickup :hit} (default: `default-sounds`)

  Returns what gamedev-toolkit.runtime/program returns."
  [{:keys [name tiles palettes screens start placed player pickup walker score-at
           run-speed jump-speed touching sounds title-sprite]
    :or {touching 12 sounds default-sounds}
    :as game}]
  (let [body (body-of game)
        digit-tile (font/tile-of \0 (tileset/tile tiles :font))
        score-digit (fn [i] [[:lda [:+ :score i]] [:clc] [:adc [:imm digit-tile]]])
        field #(objects/field things %)

        start-level
        (sub/sub
         :start-level
         "Put the player at the start, and every pickup and walker where the level has them."
         [(gen/move16 :player/x [:imm (gen/fixed (:x start))])
          (gen/move16 :player/y [:imm (gen/fixed (:y start))])
          (gen/move16 :player/vx [:imm 0])
          (gen/move16 :player/vy [:imm 0])
          (gen/move :player/facing [:imm 0])
          (gen/move :pickups-left [:imm (count (filter #(= :pickup (first %)) placed))])
          (objects/clear things)
          (objects/spawn-all things :placed)])

        move-player
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
              (sound/play (:jump sounds))))
          (platformer/move-body body)])

        collect
        (sub/sub
         :collect
         "The pickup whose slot is in X has been touched."
         [(objects/remove-it things)
          (sound/play (:pickup sounds))
          (gen/inc-decimal :score 3)
          (let [[col row] (:level score-at)]
            (rt/queue (rt/tile-address col row) (score-digit 0) (score-digit 1) (score-digit 2)))
          [:dec :pickups-left]
          (gen/when-flag :zero               ; that was the last: they all come back
            (call start-level))])

        ;; Inside `each`, for a walker: turn it round (to face `new-dir`) if
        ;; there's a wall `ahead` pixels along from its left edge, or no
        ;; floor under that point.
        turn-if
        (fn [ahead new-dir]
          (let [turn (asm/genlabel "turn")
                done (asm/genlabel "walked")]
            [(rt/probe (field :x) ahead (field :y) 8)     ; a wall at chest height?
             [:bne turn]
             (objects/slot)
             (rt/probe (field :x) ahead (field :y) 16)    ; floor to walk onto?
             [:bne done]
             [:label turn]
             (objects/slot)
             (gen/move (field :dir) [:imm new-dir])
             [:label done]
             (objects/slot)]))

        walk
        (sub/sub
         :walk
         "The walker whose slot is in X takes a step, a pixel every other frame,
  and turns round at a wall or the end of what it's standing on."
         [[:lda :rt/frame]
          [:and [:imm 1]]
          (gen/when-flag :zero
            (gen/with-operand :lda (field :dir))
            (gen/if-flag :zero
              [(gen/add (field :x) [:imm 1])
               (turn-if 16 1)]
              [(gen/subtract (field :x) [:imm 1])
               (turn-if -1 0)]))])

        update-things
        (sub/sub
         :update-things
         "Every pickup and walker: move it if it moves, and see if the player is touching it."
         [(objects/each things
            (objects/by-type things
              {:walker [(call walk)
                        (objects/slot)]})
            (gen/when-close [(gen/whole :player/x) (gen/whole :player/y)]
                            [(field :x) (field :y)] touching
              (objects/by-type things
                {:pickup [(call collect)]
                 :walker [(sound/play (:hit sounds))
                          (transition :game-over)]})))])

        draw-player
        (fn [picture]
          [[:lda :player/facing]
           (gen/if-flag :zero
             (rt/metasprite (gen/whole :player/x) (gen/whole :player/y)
                            (tileset/sprites tiles picture (:palette player)))
             (rt/metasprite (gen/whole :player/x) (gen/whole :player/y)
                            (tileset/sprites-flipped tiles picture (:palette player))))])

        draw-everything
        (sub/sub
         :draw-everything
         "The player: striding on every other eighth of a second while running
  on the ground, otherwise standing. Then each pickup and walker."
         [[:lda :player/on-ground]
          (gen/when-flag :not-zero
            [:lda (gen/hi :player/vx)]
            [:ora (gen/lo :player/vx)]
            (gen/when-flag :not-zero
              [:lda :rt/frame]
              [:and [:imm 8]]))
          ;; zero here means: standing (in the air, or still, or the other half of the stride)
          (gen/if-flag :zero
            (draw-player (:standing player))
            (draw-player (:striding player)))
          (objects/each things
            (let [with-y {:with :y}
                  drawn-left? (= :left (:faces walker))
                  walker-frames (fn [flipped?] (frames tiles (:frames walker) (:palette walker) flipped?))]
              (objects/by-type things
                {:pickup (rt/animated (field :x) (field :y)
                                      (frames tiles (:frames pickup) (:palette pickup) false)
                                      (assoc with-y :every (:every pickup 8)))
                 :walker [(gen/with-operand :lda (field :dir))
                          ;; walking right: as drawn, unless it was drawn facing left
                          (gen/if-flag :zero
                            (rt/animated (field :x) (field :y) (walker-frames drawn-left?)
                                         (assoc with-y :every (:every walker 8)))
                            (rt/animated (field :x) (field :y) (walker-frames (not drawn-left?))
                                         (assoc with-y :every (:every walker 8))))]})))])

        solid (:solid (:level screens))]
    (rt/program
     {:name name
      :variables variables
      :tiles (:tiles tiles)
      :palettes palettes
      :screens screens
      :tables {:placed (objects/placements things placed)}
      :initial :title
      :states
      [[:title
        {:enter [(rt/draw-screen (rt/background :title))]
         :update [(when title-sprite
                    (rt/metasprite [:imm (:x title-sprite)] [:imm (:y title-sprite)]
                                   (tileset/sprites tiles (:standing player) (:palette player))))
                  (rt/when-pressed :start
                    (transition :playing))]}]
       [:playing
        {:enter [(rt/draw-screen (rt/background :level))
                 (gen/fill :score 3 [:imm 0])
                 (call start-level)]
         :update [(call move-player)
                  (call update-things)
                  (call draw-everything)]}]
       [:game-over
        {:enter [(rt/draw-screen
                   (rt/background :game-over)
                   ;; the score comes from memory, so its digits are written here
                   (let [[col row] (:game-over score-at)] (rt/at col row))
                   (for [i (range 3)]
                     [(score-digit i) [:sta :ppudata]]))]
         :update [(rt/when-pressed :start
                    (transition :playing))]}]]
      ;; For the debugger: what the game thinks is solid, and where it
      ;; thinks things are.
      :overlays
      [{:name "Physics"
        :doc "The solid blocks, the player's body (green when standing on something) and its speed, and each object's slot number."
        ;; (a struct with this many fields comes as one value per field: :player.x ...)
        :draw (fn [{state :rt.state xs :thing.x ys :thing.y types :thing.type
                    x :player.x y :player.y vx :player.vx vy :player.vy on-ground :player.on-ground}]
                (when (= state :playing)
                  (let [{:keys [left right top height]} body]
                    (concat
                     (for [i (range (count solid)) :when (pos? (nth solid i))]
                       [:rect {:x (* 16 (mod i 16)) :y (* 16 (quot i 16)) :w 16 :h 16 :color :gray}])
                     ;; (+ 1: the PPU draws a sprite one line below the Y it's given)
                     [[:rect {:x (+ x left) :y (+ y top 1) :w (inc (- right left)) :h (- height top)
                              :color (if on-ground :lime :yellow)}]
                      [:line {:x1 (+ x 8) :y1 (+ y 9) :x2 (+ x 8 (* 8 vx)) :y2 (+ y 9 (* 8 vy))
                              :color :yellow :arrow? true}]]
                     (for [[slot t] (map-indexed vector types)
                           :when (pos? t)]
                       [:text {:x (+ (nth xs slot) 6) :y (nth ys slot) :text slot :color :white}])))))}]})))
