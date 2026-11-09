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

  The wiki's ROM map describes data in the cartridge (damage tables,
  speeds), which lenses can't read yet: they read RAM."
  (:require [nes.lens :as lens]
            [nes.snapshot :as snapshot]))

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
