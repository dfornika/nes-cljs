(ns games.hopper
  "Star Hopper: a one-screen platform game, to exercise the engine.

  Run and jump round the girders collecting stars. Spiky things patrol
  some of them; touch one and it's over. Collect every star and they all
  come back.

  This file is the game's looks, its level and its numbers. The rules
  (which it shares with games.alienscape) are in games.platform-kit:
  that's where the code is.

  At the REPL: (play! (hopper/build)). Left and Right to run, A to jump."
  (:require [gamedev-toolkit.font :as font]
            [gamedev-toolkit.palette :as palette]
            [gamedev-toolkit.screen :as screen]
            [gamedev-toolkit.tileset :as tileset]
            [games.catch :as catch]
            [games.platform-kit :as kit]))

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

;; ---------------------------------------------------------------------------
;; Numbers to tune, and putting it together

(def in-level (kit/in-level level {:pickup \* :walker \S}))

(def game
  {:name "hopper.nes"
   :tiles tiles
   :palettes palettes
   :screens screens
   :start (:start in-level)
   :placed (:placed in-level)
   :player {:standing :standing :striding :striding :palette 0}
   :pickup {:frames [:star] :palette 1}
   :walker {:frames [:spike] :palette 2}
   :title-sprite {:x 120 :y 104}
   :score-at {:level [17 2] :game-over [17 14]}
   :run-speed 1.5          ; pixels a frame
   :jump-speed 4.5         ; upwards, at the start of a jump: enough to climb two blocks
   :gravity 0.25           ; added to the downward speed every frame
   :max-fall 4
   ;; the picture is 16 wide, but the hopper is drawn in the middle 10 of them
   :box {:left 3 :right 12 :top 1 :height 16}})

(defn build
  "Build the cartridge. Returns {:rom the .nes file's bytes, :name, ...}."
  []
  (kit/program game))
