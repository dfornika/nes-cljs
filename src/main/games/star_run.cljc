(ns games.star-run
  "Star Run: Star Tower's hopper, on a level six screens wide. Run right,
  collect the stars, mind the spikes and the pits.

  What's new is the level: wider than the screen, so the background
  scrolls. It's still drawn as text, one character per 16x16 block, just
  longer rows (gamedev-toolkit.screen/level works out the rest). The
  camera follows the hopper, and the PPU is given each column of blocks
  as it comes into view (gamedev-toolkit.runtime/level). Positions are 16
  bits now, and the score is drawn with sprites, since the background
  moves.

  At the REPL: (play! (star-run/build)). Left and Right to run, A to jump
  (hold it for higher), Up and Down on ladders."
  (:require [clojure.string :as str]
            [gamedev-toolkit.palette :as palette]
            [gamedev-toolkit.screen :as screen]
            [games.platform-kit :as kit]
            [games.tower :as tower]))

;; ---------------------------------------------------------------------------
;; The level, a screen at a time (16 blocks across each), joined up below
;;
;; # wall, = plank (jump up through it, stand on it), H ladder, * star,
;; S spike, P where the hopper starts. A gap in the floor is a pit.

(def screens-of-level
  [["................"
    "................"
    "################"
    "#..............."
    "#..............."
    "#..............."
    "#.........*....."
    "#........=====.."
    "#..............."
    "#....*.........."
    "#...=====......."
    "#..............."
    "#..............."
    "#.P..........S.."
    "################"]

   ["................"
    "................"
    "################"
    "................"
    "...*.......*...."
    "..=====..=====.."
    "................"
    "......==H===...."
    "........H......."
    "........H......."
    "........H......."
    "........H......."
    "..*.....H......."
    "........H..S...."
    "####..##########"]

   ["................"
    "................"
    "################"
    "................"
    "................"
    "..........*....."
    ".........###...."
    "................"
    "......###......."
    "................"
    "...###.........."
    "................"
    "###............."
    "...........S...."
    "################"]

   ["................"
    "................"
    "################"
    "................"
    "..*........*...."
    "==H=====H======."
    "..H.....H......."
    "..H.....H......."
    "..H..S..H....*.."
    "======H====.===="
    "......H........."
    "......H........."
    "..*...H........."
    "......H......S.."
    "################"]

   ["................"
    "................"
    "################"
    "................"
    "................"
    "................"
    "......*........."
    ".....===....*..."
    "...........===.."
    "..*............."
    ".===............"
    "................"
    "........*......."
    "....S..........."
    "#####...########"]

   ["................"
    "................"
    "################"
    "...............#"
    "...............#"
    "..*............#"
    ".====..........#"
    "...............#"
    "........*......#"
    ".......====....#"
    "...............#"
    "............*..#"
    "..........====.#"
    "..S......*.....#"
    "################"]])

(def rows
  "The whole level: each row of the screens above, joined end to end."
  (apply mapv str screens-of-level))

;; Things that aren't blocks (P, *, S) are empty background.
(def legend (assoc tower/legend \P :empty \* :empty \S :empty))

(def level (screen/level {:metatiles tower/metatiles :legend legend :rows rows}))

(def in-level (kit/in-level rows {:pickup \* :walker \S}))

;; ---------------------------------------------------------------------------
;; Looks: Star Tower's, and a fourth sprite palette for the score, which is
;; drawn with sprites here

(def palettes
  (palette/palettes
   {:backdrop   :dark-indigo
    :background [[:white :light-gray :gray]
                 [:dark-gray :gray :light-gray]
                 [:dark-orange :orange :light-yellow]
                 [:light-yellow :dark-orange :pale-yellow]]
    :sprites    [[:dark-green :light-lime :white]      ; the hopper
                 [:light-orange :pale-yellow :white]   ; stars
                 [:black :red :white]                  ; spikes
                 [:white :white :white]]}))            ; the score

(def screens
  {:title     (screen/screen (assoc tower/sky :text [[11 9 "STAR RUN"]
                                                     [10 18 "PRESS START"]
                                                     [7 22 "RUN RIGHT. MIND"]
                                                     [7 23 "THE PITS"]]))
   :game-over (screen/screen (assoc tower/sky :text [[11 10 "GAME OVER"]
                                                     [11 14 "SCORE"]
                                                     [10 20 "PRESS START"]]))})

(def game
  (merge (dissoc tower/game :screens :song)
         {:name "star-run.nes"
          :palettes palettes
          :screens screens
          :level level
          :metatiles tower/metatiles
          :text-palette 3
          :pool-size 28            ; every star and spike on the level (25), and a few spare
          :start (:start in-level)
          :placed (:placed in-level)
          :score-at {:level [11 1] :game-over [17 14]}
          :song tower/theme}))

(defn build
  "Build the cartridge. Returns {:rom the .nes file's bytes, :name, ...}."
  []
  (kit/program game))

(comment
  ;; the level, as text, to check it reads right
  (println (str/join "\n" rows)))
