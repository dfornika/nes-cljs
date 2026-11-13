(ns games.tower
  "Star Tower: Star Hopper climbs. Planks you can jump up through and
  stand on, ladders between the floors, spikes that walk off the edges and
  fall, and a tune.

  Like Star Hopper and Alienscape, this file is looks, a level and
  numbers; the rules are games.platform-kit. What's new here is all
  switched on from the numbers at the bottom: ladders (:climb-speed),
  speeding up and slowing down (:accel, :friction), jumps as high as A is
  held (:short-jump), spikes that fall (:falls?) and music (:song).

  At the REPL: (play! (tower/build)). Left and Right to run, A to jump
  (hold it for higher), Up and Down on ladders."
  (:require [gamedev-toolkit.font :as font]
            [gamedev-toolkit.palette :as palette]
            [gamedev-toolkit.screen :as screen]
            [gamedev-toolkit.tileset :as tileset]
            [games.catch :as catch]
            [games.hopper :as hopper]
            [games.platform-kit :as kit]))

;; ---------------------------------------------------------------------------
;; Graphics: the hopper, stars, spikes and wall are Star Hopper's

(def plank
  "A plank: only the top of the block, so it looks like something you
  could jump up through."
  ["1111111111111111"
   "2333333333333332"
   "2222222222222222"
   "1111111111111111"
   ".1............1."
   "..1..........1.."
   "...1........1..."
   "................"
   "................"
   "................"
   "................"
   "................"
   "................"
   "................"
   "................"
   "................"])

(def ladder
  ["..1........1...."
   "..12......12...."
   "..1111111111...."
   "..12222222212..."
   "..1........1...."
   "..12......12...."
   "..1........1...."
   "..12......12...."
   "..1111111111...."
   "..12222222212..."
   "..1........1...."
   "..12......12...."
   "..1........1...."
   "..12......12...."
   "..1........1...."
   "..12......12...."])

(def tiles
  (tileset/tileset [[:font     font/tiles]
                    [:standing hopper/hopper-standing]
                    [:striding hopper/hopper-striding]
                    [:star     catch/star]
                    [:spike    catch/spike]
                    [:wall     catch/wall]
                    [:plank    plank]
                    [:ladder   ladder]
                    [:twinkle  catch/twinkle]]))

(def palettes
  (palette/palettes
   {:backdrop   :dark-indigo
    :background [[:white :light-gray :gray]            ; text, and the twinkles
                 [:dark-gray :gray :light-gray]        ; the wall
                 [:dark-orange :orange :light-yellow]  ; planks
                 [:light-yellow :dark-orange :pale-yellow]]  ; ladders
    :sprites    [[:dark-green :light-lime :white]      ; the hopper
                 [:light-orange :pale-yellow :white]   ; stars
                 [:black :red :white]]}))              ; spikes

;; ---------------------------------------------------------------------------
;; The level
;;
;; # is wall, = a plank (one-way), H a ladder. A ladder's top block, the
;; one level with the planks it reaches, can be stood on: the screen works
;; that out (see gamedev-toolkit.screen/kind-bytes). P is the start, * a
;; star, S a spike. The gaps in the planks are where spikes fall through.

(def level
  ["................"
   "................"
   "################"
   "#*.....S.....*.#"
   "#===H=====H==..#"
   "#...H.....H....#"
   "#...H..*..H..S.#"
   "#..===H====H===#"
   "#.....H....H...#"
   "#.*...H.S..H.*.#"
   "#===H=====H==..#"
   "#...H.....H....#"
   "#...H..*..H....#"
   "#.P.H.....H..*.#"
   "################"])

(def metatiles
  {:empty  {:tiles [0 0 0 0] :palette 0}
   :wall   {:tiles (tileset/block tiles :wall) :palette 1 :kind :solid}
   :plank  {:tiles (tileset/block tiles :plank) :palette 2 :kind :one-way}
   :ladder {:tiles (tileset/block tiles :ladder) :palette 3 :kind :ladder}})

(def legend
  {\. :empty, \# :wall, \= :plank, \H :ladder
   \P :empty, \* :empty, \S :empty})

(def sky
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
  {:title     (screen/screen (assoc sky :text [[11 9 "STAR TOWER"]
                                               [10 18 "PRESS START"]
                                               [4 22 "UP AND DOWN ON LADDERS"]]))
   :level     (screen/screen {:metatiles metatiles :legend legend :rows level
                              :text [[11 1 "SCORE 000"]]})
   :game-over (screen/screen (assoc sky :text [[11 10 "GAME OVER"]
                                               [11 14 "SCORE"]
                                               [10 20 "PRESS START"]]))})

;; ---------------------------------------------------------------------------
;; A tune
;;
;; [note frames]: 60 frames is a second. It's played on the triangle
;; channel, a soft, flute-like sound, and goes round and round.

(def theme
  (let [q 12, h 24]                    ; a quarter note and a half
    [[:c4 q] [:e4 q] [:g4 q] [:e4 q]
     [:f4 q] [:a4 q] [:g4 h]
     [:e4 q] [:g4 q] [:c5 q] [:g4 q]
     [:f4 q] [:d4 q] [:c4 h]
     [:a3 q] [:c4 q] [:e4 q] [:c4 q]
     [:d4 q] [:f4 q] [:e4 h]
     [:g3 q] [:b3 q] [:d4 q] [:f4 q]
     [:e4 q] [:d4 q] [:c4 h]
     [:rest h]]))

;; ---------------------------------------------------------------------------
;; Numbers to tune, and putting it together

(def in-level (kit/in-level level {:pickup \* :walker \S}))

(def game
  {:name "tower.nes"
   :tiles tiles
   :palettes palettes
   :screens screens
   :start (:start in-level)
   :placed (:placed in-level)
   :player {:standing :standing :striding :striding :palette 0}
   :pickup {:frames [:star] :palette 1}
   :walker {:frames [:spike] :palette 2 :falls? true}
   :title-sprite {:x 120 :y 104}
   :score-at {:level [17 1] :game-over [17 14]}
   :run-speed 1.75         ; the most, in pixels a frame...
   :accel 0.125            ; ...reached by adding this much a frame while held
   :friction 0.1875        ; and this much is taken off a frame when let go
   :jump-speed 4.5         ; a full jump: about 40 pixels, two and a half blocks
   :short-jump 1.5         ; let go of A early and the rise slows to this
   :gravity 0.25
   :max-fall 4
   :climb-speed 1
   :song theme
   :box {:left 3 :right 12 :top 1 :height 16}})

(defn build
  "Build the cartridge. Returns {:rom the .nes file's bytes, :name, ...}."
  []
  (kit/program game))
