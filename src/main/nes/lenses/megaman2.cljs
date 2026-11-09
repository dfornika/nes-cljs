(ns nes.lenses.megaman2
  "Lenses for Mega Man 2 (USA), Capcom, 1989.

  Most addresses come from the Data Crystal wiki's RAM map,
  https://datacrystal.tcrf.net/wiki/Mega_Man_2/RAM_map (content under the
  GNU Free Documentation License 1.2), checked against the game running
  in this emulator. Fields marked 'found here' aren't on that page; they
  were worked out by watching the game's memory with snapshots:

  - $00A9 is the weapon in use: it went 0 -> 1 when the next weapon down
    the pause menu was chosen.
  - $002A is the stage select cursor on the stage select screen, but
    inside a stage it's 2 in Wood Man's stage: the stage number, in the
    same order as the weapon bits at $009A (Heat Man 0 ... Crash Man 7).
  - The game keeps everything that moves in 32 'object slots', one byte
    per slot in each of several tables: $0400 (which sprite/animation),
    $0420 (flags: bit 7 = in use, bit 6 = facing right), $0440 (X screen),
    $0460 (X pixel), $04A0 (Y), and $06C0 (hit points). Slot 0 is Mega
    Man and slot 1 the boss; his weapons (and effects, like the spark
    when he's hit) appeared in slots 2-15, and enemies in 16-31. (Data Crystal's 'enemy HP at $06C2-$06E1' is the same table
    from slot 2 on.)
  - $001C goes up every frame, paused or not.

  Some entries on the wiki didn't match this version and are left out:
  facing ($0F12), position ($0F15/$0F16) and the gameplay frame counter
  ($0F39) all stayed 0 here. ($0F12 is really $0712, as RAM repeats every
  2 KB.)

  ROM lenses (\"Boss weaknesses\", \"Game rules\", \"Credits\") read the
  cartridge, at the file offsets of the wiki's ROM map,
  https://datacrystal.tcrf.net/wiki/Mega_Man_2/ROM_map. Every default
  value listed there that's used here was checked against the ROM. Change
  one in the Lens tab and the game changes: make the Mega Buster do 28
  damage to Heat Man, say.

  The text tables are the wiki's, https://datacrystal.tcrf.net/wiki/Mega_Man_2/TBL,
  plus digits ($30-$39) for the credits table, found here in the ending
  credits ('NO.009' for Metal Man). That table also turns out to be the
  one for the stage select screen."
  (:require [nes.lens :as lens]
            [nes.snapshot :as snapshot]
            [nes.text-table :as text-table]))

(def sha1
  "Mega Man 2 (USA), without its header. See nes.rom/rom-hash."
  "2ec08f9341003ded125458df8697ca5ef09d2209")

(defn- byte-at [s addr] (snapshot/cpu-byte s addr))

;; The eight Robot Masters, in the order the game numbers them: the order
;; of the weapon bits at $009A, and of stages inside the game.
(def robot-masters
  ["Heat Man" "Air Man" "Wood Man" "Bubble Man" "Quick Man" "Flash Man" "Metal Man" "Crash Man"])

(def weapons
  "The pause menu, top to bottom: the values of $00A9."
  ["Mega Buster" "Atomic Fire" "Air Shooter" "Leaf Shield" "Bubble Lead" "Quick Boomerang"
   "Time Stopper" "Metal Blade" "Crash Bomber" "Item-1" "Item-2" "Item-3"])

(def ^:private buttons
  ;; how the game stores a controller's buttons (bit 0 = A ... bit 7 = Right)
  {:a 0 :b 1 :select 2 :start 3 :up 4 :down 5 :left 6 :right 7})

(defn- pressed [s addr]
  (let [b (byte-at s addr)]
    (vec (for [[k bit] (sort-by val buttons) :when (bit-test b bit)] k))))

;; ---------------------------------------------------------------------------
;; Objects

(defn- slot-kind [slot]
  (cond (= slot 0) :mega-man
        (= slot 1) :boss
        (< slot 16) :weapon-or-effect
        :else :enemy))

(defn objects
  "Everything on screen that moves: the object slots in use."
  [s]
  (vec (for [slot (range 32)
             :let [flags (byte-at s (+ 0x0420 slot))]
             :when (bit-test flags 7)]
         {:slot slot
          :kind (slot-kind slot)
          :sprite (byte-at s (+ 0x0400 slot))
          :x (+ (* 256 (byte-at s (+ 0x0440 slot))) (byte-at s (+ 0x0460 slot)))
          :y (byte-at s (+ 0x04A0 slot))
          :facing (if (bit-test flags 6) :right :left)
          :hp (byte-at s (+ 0x06C0 slot))})))

;; ---------------------------------------------------------------------------

(def mega-man
  [[:hp [:u8 0x06C0]]
   [:lives [:u8 0x00A8]]
   [:energy-tanks [:u8 0x00A7]]
   [:weapon [:enum 0x00A9 (zipmap (range) weapons)]]   ; found here
   [:x [:fn #(+ (* 256 (byte-at % 0x0440)) (byte-at % 0x0460))]]
   [:y [:u8 0x04A0]]
   [:facing [:fn #(if (bit-test (byte-at % 0x0420) 6) :right :left)]] ; found here
   [:standing-on [:enum 0x0032 {0 "air" 1 "ground" 2 "ladder" 3 "spikes or lava"
                                4 "water" 5 "conveyor →" 6 "conveyor ←" 7 "ice"}]]
   [:shooting [:enum 0x003D {0 "no" 1 "yes" 3 "throwing"}]]
   [:arm-down-in [:u8 0x0036]]
   [:invincible-for [:u8 0x004B]]
   [:difficulty [:enum 0x00CB {0 "normal" 1 "difficult"}]]])

(def arsenal
  [[:weapons [:bits 0x009A (zipmap (map keyword ["atomic-fire" "air-shooter" "leaf-shield" "bubble-lead"
                                                 "quick-boomerang" "time-stopper" "metal-blade"
                                                 "crash-bomber"])
                                   (range))]]
   [:items [:bits 0x009B {:item-1 0 :item-2 1 :item-3 2}]]
   [:ammo [:struct 0x009C [:atomic-fire :u8 :air-shooter :u8 :leaf-shield :u8
                           :bubble-lead :u8 :quick-boomerang :u8 :time-stopper :u8
                           :metal-blade :u8 :crash-bomber :u8
                           :item-1 :u8 :item-2 :u8 :item-3 :u8]]]
   [:robot-masters-beaten [:fn (fn [s]
                                 (let [b (byte-at s 0x009A)]
                                   (vec (for [[i n] (map-indexed vector robot-masters)
                                              :when (bit-test b i)]
                                          n))))]]])

(def stage
  [[:stage-or-cursor [:u8 0x002A]]   ; see the namespace docs
   [:stage [:fn #(get robot-masters (byte-at % 0x002A) "Dr. Wily's castle, or the stage select")]]
   [:boss-hp [:u8 0x06C1]]
   [:camera [:enum 0x001B {0 "following" 1 "redrawing the nametable"
                           2 "frozen (before/after a screen change)" 0x80 "scrolling vertically"}]]
   [:camera-x [:fn #(+ (* 256 (byte-at % 0x0020)) (byte-at % 0x001F))]]
   [:camera-y [:u8 0x0022]]
   [:frame-counter [:u8 0x001C]]   ; found here
   [:palette-cycle [:struct 0x0354 [:pattern :u8 :frames-per-step :u8]]]])

(def moving-things
  [[:objects [:fn objects]]
   [:enemies [:fn #(count (filter (comp #{:enemy} :kind) (objects %)))]]])

(def input-and-sound
  [[:buttons-held [:fn #(pressed % 0x0023)]]
   [:buttons-new [:fn #(pressed % 0x0027)]]
   [:sound-effect-queued [:u8 0x0580]]
   [:sfx-strobe [:u8 0x0066]]
   [:music-strobe [:u8 0x0067]]])

;; ---------------------------------------------------------------------------
;; Text tables

(def title-table
  "Letters on the title screen (and other screens drawn with the same tiles)."
  (text-table/parse "A0=0\nA1=8\nA2=2\nA3=©\nA4=t\na5=9\nA6=m\na7=1\nC0= \nC1=A\nC2=B\nC3=C\nC4=D\nC5=E\nC6=F\nC7=G\nC8=H\nC9=I\nCA=J\nCB=K\nCC=L\nCD=M\nCE=N\nCF=O\nD0=P\nD1=Q\nD2=R\nD3=S\nD4=T\nD5=U\nD6=V\nD7=W\nD8=X\nD9=Y\nDA=Z\nDB=r\nDC=.\nDD=,\nDE='\nDF=!"))

(def in-game-table
  "The few letters the game uses during play: the pause menu."
  (text-table/parse "95=N\n96=E\n97=X\n98=T\n99=W\n9A=F\n9B=A\n9C=Q\n9D=B\n9E=M\n9F=H\nA0=C\nA1=→\nA2=>\nA3=●\nA4=0\nA5=1\nA6=2\nA7=3\nA8=4\nA9=5\nAA=6\nAB=7\nAC=8\nAD=9\nAE=:\nAF=P"))

(def credits-table
  "The ending credits, and the stage select screen."
  (merge (text-table/parse "01=A\n02=B\n03=C\n04=D\n05=E\n06=F\n07=G\n08=H\n09=I\n0A=J\n0B=K\n0C=L\n0D=M\n0E=N\n0F=O\n10=P\n11=Q\n12=R\n13=S\n14=T\n15=U\n16=V\n17=W\n18=X\n19=Y\n1A=Z\n1B=r\n1C=.\n1D=,\n1E='\n1F=!\n20= ")
         ;; found here: digits, as in "NO.009"
         (zipmap (range 0x30 0x3A) (map str (range 10)))))

(text-table/register! {:rom sha1 :name "Title screen" :table title-table})
(text-table/register! {:rom sha1 :name "In game (pause menu)" :table in-game-table})
(text-table/register! {:rom sha1 :name "Credits and stage select" :table credits-table})

;; ---------------------------------------------------------------------------
;; ROM lenses

(def bosses
  "The order of every damage table: the eight Robot Masters, then Dr.
  Wily's castle bosses."
  [:heat-man :air-man :wood-man :bubble-man :quick-man :flash-man :metal-man :crash-man
   :mecha-dragon :picopico-kun :guts-tank :boobeam-trap :wily-machine :alien])

(def damage-tables
  "Where each weapon's damage table starts (USA version). Each has one
  byte per boss, in the order of `bosses`. (The Time Stopper has none: it
  hurts only Flash Man, by other means.)"
  [[:mega-buster 0x2E952] [:atomic-fire 0x2E960] [:air-shooter 0x2E96E]
   [:leaf-shield 0x2E97C] [:bubble-lead 0x2E98A] [:quick-boomerang 0x2E998]
   [:crash-bomber 0x2E9A6] [:metal-blade 0x2E9B4]])

(defn- damage-meaning [d]
  (cond (>= d 0x80) "heals" (zero? d) "–" :else d))

(defn weak-to
  "For each boss, the weapons that hurt it most."
  [s]
  (vec (for [[i boss] (map-indexed vector bosses)
             :let [hits (for [[weapon at] damage-tables
                              :let [d (snapshot/rom-byte s (+ at i))]
                              :when (and d (< 0 d 0x80))]
                          [weapon d])
                   best (when (seq hits) (apply max (map second hits)))]]
         {:boss boss
          :weakest-to (if best
                        (->> hits (filter #(= best (second %))) (map first) (map name) (interpose ", ") (apply str))
                        "–")
          :damage (or best 0)
          :heals (->> damage-tables
                          (filter (fn [[_ at]] (>= (or (snapshot/rom-byte s (+ at i)) 0) 0x80)))
                          (map (comp name first)) (interpose ", ") (apply str))})))

(def boss-weaknesses
  (into [[:weak-to [:fn weak-to]]]
        (for [[weapon at] damage-tables]
          [weapon [:rom [:struct at (vec (interleave bosses (repeat :u8)))]]])))

(def game-rules
  [[:max-energy [:rom [:u8 0x038315]]]
   [:starting-energy [:rom [:u8 0x0380D5]]]
   [:max-energy-tanks [:rom [:u8 0x038382]]]
   [:max-lives [:rom [:u8 0x038390]]]
   [:energy-tank-refill [:rom [:u8 0x0352AC]]]
   [:large-energy-pellet [:rom [:u8 0x0382FD]]]
   [:small-energy-pellet [:rom [:u8 0x038301]]]
   [:jump-height [:rom [:u8 0x038A81]]]
   [:walk-speed-pixels [:rom [:u8 0x038921]]]
   [:walk-speed-256ths [:rom [:u8 0x03892C]]]
   [:buster-shot-speed [:rom [:u8 0x03D4A7]]]
   [:buster-shots-on-screen+1 [:rom [:u8 0x03DA83]]]
   [:buster-sound [:rom [:u8 0x03DA91]]]
   [:crash-bomb-fuse [:rom [:u8 0x03E09C]]]])

(defn- credits-list
  "The Robot Masters' designers, from the ending credits."
  [s]
  (let [text (text-table/decode credits-table (map #(snapshot/rom-byte s %) (range 0x24AF8 0x24BF2)) "")]
    ;; each entry is "NO." number, robot, designer, with no space between
    ;; the robot's name and the designer's (the game positions them apart)
    (vec (keep #(let [t (.trim %)]
                  (when (seq t) (str "No." (.replace t (js/RegExp. "MAN(?=[A-Z])") "MAN – "))))
               (.split text "NO.")))))

(def credits
  [[:thank-you [:rom [:text 0x24F87 24 credits-table]]]
   [:presented-by [:rom [:text 0x24F9F 12 credits-table]]]
   [:publisher [:rom [:text 0x24FAB 13 credits-table]]]
   [:stage-select-prompt [:rom [:text 0x2EE5A 12 credits-table]]]
   [:robot-master-designers [:fn credits-list]]])

(def screen-text
  [[:title-font [:screen-text title-table]]
   [:credits-font [:screen-text credits-table]]
   [:pause-menu-font [:screen-text in-game-table]]])

;; ---------------------------------------------------------------------------

(lens/register! {:rom sha1 :name "Mega Man" :fields mega-man
                 :doc "Health, lives, the weapon in use, where he is and what he's standing on."})
(lens/register! {:rom sha1 :name "Arsenal" :fields arsenal
                 :doc "Weapons and items collected, and their energy (28 is full)."})
(lens/register! {:rom sha1 :name "Stage" :fields stage
                 :doc "Which stage, the boss's health, and the camera."})
(lens/register! {:rom sha1 :name "Objects" :fields moving-things
                 :doc "The 32 object slots: Mega Man (0), the boss (1), his weapons and effects (2-15) and enemies (16-31)."})
(lens/register! {:rom sha1 :name "Input & sound" :fields input-and-sound
                 :doc "The controller as the game sees it, and the sound requests it makes."})
(lens/register! {:rom sha1 :name "Boss weaknesses" :fields boss-weaknesses
                 :doc "Damage each weapon does to each boss, from the cartridge. $80 and up heals instead. Click a number to change it in the game."})
(lens/register! {:rom sha1 :name "Game rules" :fields game-rules
                 :doc "Numbers the game's code is built around, from the cartridge. Change one and the game plays differently (the stage restarts with some)."})
(lens/register! {:rom sha1 :name "Credits" :fields credits
                 :doc "Text from the cartridge, through the credits text table. Edit it and see it in the ending (or on the stage select screen)."})
(lens/register! {:rom sha1 :name "Screen text" :fields screen-text
                 :doc "The background tiles on screen now, read as text through each of the game's text tables. Only the right table makes sense for each screen."})
