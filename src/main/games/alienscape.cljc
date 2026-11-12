(ns games.alienscape
  "Alienscape: a platform game in a room from Hexany's Alienscape, a
  CC0 (public domain) art pack by Hexany Ives, made to the NES's limits.
  https://hexany-ives.itch.io/hexanys-alienscape

  Collect the orbs, and keep clear of the biters. The rules are the ones
  Star Hopper has (games.platform-kit); the room, the orbs and the biters
  are the pack's.

  The pack is pictures (resources/assets/hexanys-alienscape). They become
  NES tiles in two steps:

    games.alienscape.import   a script, run when the art changes: reads the
                              PNGs and writes out
    games.alienscape.art      the tiles as text drawings, and which tile
                              goes where in the room

  so that building the game needs only data, and works in the browser.
  The room's picture says what it looks like but not what's solid, so
  that's drawn here by hand, over the same grid.

  At the REPL: (play! (alienscape/build)). Left and Right to run, A to jump."
  (:require [gamedev-toolkit.chr :as chr]
            [gamedev-toolkit.font :as font]
            [gamedev-toolkit.palette :as palette]
            [gamedev-toolkit.screen :as screen]
            [gamedev-toolkit.tileset :as tileset]
            [games.alienscape.art :as art]
            [games.hopper :as hopper]
            [games.platform-kit :as kit]))

;; ---------------------------------------------------------------------------
;; Tiles

(def creature-frames
  "The pack's animations, a name for each frame: [[:orb-0 drawing] ...]"
  (for [[creature {:keys [frames]}] art/sprites
        [i drawing] (map-indexed vector frames)]
    [(keyword (str (name creature) "-" i)) drawing]))

(defn- frame-names [creature]
  (mapv #(keyword (str (name creature) "-" %))
        (range (count (get-in art/sprites [creature :frames])))))

(def tiles
  (tileset/tileset (into [[:font     font/tiles]
                          [:room     (map chr/tile art/tiles)]   ; the pack's background tiles
                          [:standing hopper/hopper-standing]                     ; the player is Star Hopper's
                          [:striding hopper/hopper-striding]]
                         creature-frames)))

(def palettes
  (palette/palettes
   {:backdrop   art/backdrop
    ;; the pack's palettes, then one for text
    :background (conj art/background-palettes [:white :light-gray :gray])
    :sprites    [[:dark-cyan :light-cyan :white]              ; the player: the one cool color in the room
                 (get-in art/sprites [:orb :palette])
                 (get-in art/sprites [:biter :palette])]}))

(def text-palette (count art/background-palettes))

;; ---------------------------------------------------------------------------
;; The room
;;
;; What's solid, over the picture's grid of 16x16 blocks: # is solid. And
;; what's where: P the player, o an orb, B a biter.

(def layout
  ["................"
   "################"
   "##............##"
   "#..............#"
   "#.Bo........o..#"
   "####....o...####"
   "#.......#......#"
   "#......##......#"
   "#..............#"
   "#..............#"
   "#.P..o....o..B.#"
   "################"
   "################"
   "################"
   "################"])

(def blank-screen
  {:grid (vec (repeat 960 0))
   :block-palettes (vec (repeat 240 0))
   :solid (vec (repeat 15 "................"))
   :text-palette text-palette})

(def screens
  {:title     (screen/from-tiles (assoc blank-screen :text [[11 9 "ALIENSCAPE"]
                                                            [10 18 "PRESS START"]
                                                            [5 22 "ART: HEXANY IVES - CC0"]]))
   :level     (screen/from-tiles (assoc (:arena art/rooms)
                                        :tile-base (tileset/tile tiles :room)
                                        :solid layout
                                        :text [[11 1 "SCORE 000"]]
                                        :text-palette text-palette))
   :game-over (screen/from-tiles (assoc blank-screen :text [[11 10 "GAME OVER"]
                                                            [11 14 "SCORE"]
                                                            [10 20 "PRESS START"]]))})

(def in-level (kit/in-level layout {:pickup \o :walker \B}))

(def game
  {:name "alienscape.nes"
   :tiles tiles
   :palettes palettes
   :screens screens
   :start (:start in-level)
   :placed (:placed in-level)
   :player {:standing :standing :striding :striding :palette 0}
   :pickup {:frames (frame-names :orb) :palette 1 :every 8}
   :walker {:frames (frame-names :biter) :palette 2 :every 16 :faces :left}
   :title-sprite {:x 120 :y 104}
   :score-at {:level [17 1] :game-over [17 14]}
   :run-speed 1.5
   ;; The room's platforms are four blocks apart, so jumps are higher here
   ;; than in Star Hopper, and fall a little slower: an alien planet.
   :jump-speed 6
   :gravity 0.25
   :max-fall 4
   :box {:left 3 :right 12 :top 1 :height 16}})

(defn build
  "Build the cartridge. Returns {:rom the .nes file's bytes, :name, ...}."
  []
  (kit/program game))
