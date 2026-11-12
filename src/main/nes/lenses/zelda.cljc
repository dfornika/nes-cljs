(ns nes.lenses.zelda
  "Lenses for The Legend of Zelda (USA), Nintendo, 1987.

  From the Data Crystal wiki (content under the GNU Free Documentation
  License 1.2):
  - RAM map: https://datacrystal.tcrf.net/wiki/The_Legend_of_Zelda/RAM_map
  - ROM map: https://datacrystal.tcrf.net/wiki/The_Legend_of_Zelda/ROM_map
    (its offsets don't count the 16-byte iNES header; the ones here do,
    so they're 0x10 more)
  - Item codes: https://datacrystal.tcrf.net/wiki/The_Legend_of_Zelda/Item_codes
  - Text table: https://datacrystal.tcrf.net/wiki/The_Legend_of_Zelda/TBL

  Checked against the game running in this emulator: Link's position,
  direction, hearts, the map location (77 on the first screen), game mode,
  enemy slots (four enemies on the screen west of the start) and the
  save file name ('ABC' registered was 0A 0B 0C). Every ROM default the
  ROM map lists that's used here matched this ROM (24 values, plus the
  warp whistle screens, shop items and prices, and the second quest name).

  Found here, not on the wiki:
  - The buttons held right now are at $00FA; $00F8 stayed 0 while
    holding buttons.
  - Game mode ($0012) $10 while walking into a cave, $0B inside one.
  - The cave text pointer table (ROM 0x4010) holds CPU addresses in the
    $8000 bank, so text at $804C is at file offset 0x405C.

  Cave text: each letter is in the low 6 bits of a byte; the top two bits
  say where the next letter goes: $80 the second line, $40 the third,
  $C0 the end. The text table below spells those as | / and ⏹ after a
  letter, so the text can be read and edited here. Keep the ⏹ at the end
  when editing, or the game will carry on into the next message."
  (:require [clojure.string :as str]
            [nes.lens :as lens]
            [nes.snapshot :as snapshot]
            [nes.text-table :as text-table]))

(def sha1
  "The Legend of Zelda (USA), without its header. See nes.rom/rom-hash."
  "be2f5dc8c5ba8ec1a344a71f9fb204750af24fe7")

(defn- byte-at [s addr] (snapshot/cpu-byte s addr))

;; ---------------------------------------------------------------------------
;; Text tables

(def letters
  "The game's font: the same byte for a letter in text and its tile on
  screen. (Data Crystal writes letters in lowercase; the game only has
  capitals.)"
  (text-table/parse
   (str "00=0\n01=1\n02=2\n03=3\n04=4\n05=5\n06=6\n07=7\n08=8\n09=9\n"
        "0A=A\n0B=B\n0C=C\n0D=D\n0E=E\n0F=F\n10=G\n11=H\n12=I\n13=J\n14=K\n15=L\n16=M\n"
        "17=N\n18=O\n19=P\n1A=Q\n1B=R\n1C=S\n1D=T\n1E=U\n1F=V\n20=W\n21=X\n22=Y\n23=Z\n"
        "24= \n25= \n28=,\n29=!\n2A='\n2B=&\n2C=.\n2D=\"\n2E=?\n2F=-")))

(def screen-table
  "The letters plus the pieces of the title story's wreath."
  (merge letters {0xE2 "(" 0xE3 ")" 0xE4 "<" 0xE5 ">" 0xE6 "#" 0xF8 "\""}))

(def npc-text-table
  "Cave text: a letter in the low 6 bits, and where the next one goes in
  the top 2 (see the namespace docs)."
  (into {} (for [b (range 256)
                 :let [c (letters (bit-and b 0x3F))]
                 :when c]
             [b (str c (case (bit-and b 0xC0) 0 "" 0x80 "|" 0x40 "/" 0xC0 "⏹"))])))

(text-table/register! {:rom sha1 :name "Letters" :table screen-table})

;; ---------------------------------------------------------------------------
;; Names for numbers

(def item-names
  "Item codes, as used by shops, caves and the ROM's item tables."
  {0x00 "bombs" 0x01 "wooden sword" 0x02 "white sword" 0x03 "magical sword" 0x04 "bait"
   0x05 "recorder" 0x06 "blue candle" 0x07 "red candle" 0x08 "arrow" 0x09 "silver arrow"
   0x0A "bow" 0x0B "magical key" 0x0C "raft" 0x0D "stepladder" 0x0E "Ganon's triforce"
   0x0F "5 rupees" 0x10 "magical rod" 0x11 "book of magic" 0x12 "blue ring" 0x13 "red ring"
   0x14 "power bracelet" 0x15 "letter" 0x16 "compass" 0x17 "map" 0x18 "rupee" 0x19 "key"
   0x1A "heart container" 0x1B "triforce piece" 0x1C "magical shield" 0x1D "boomerang"
   0x1E "magical boomerang" 0x1F "blue potion" 0x20 "red potion" 0x21 "clock"
   0x22 "heart" 0x23 "fairy" 0x3F "nothing"})

(def directions {8 "north" 4 "south" 1 "east" 2 "west"})

(defn- direction [d]
  (or (directions d)
      ;; diagonals are the sum of two
      (let [parts (keep (fn [[bit n]] (when (bit-test d bit) n))
                        [[3 "north"] [2 "south"] [0 "east"] [1 "west"]])]
        (if (seq parts) (apply str (interpose "-" parts)) "–"))))

(defn- map-location
  "$00EB: the overworld is 16 × 8 screens; high nibble = row, low = column."
  [v]
  {:column (bit-and v 0xF) :row (bit-shift-right v 4) :id v})

(defn- hearts
  "Hearts: $066F's high nibble is the containers - 1, its low nibble the
  full hearts - 1 (with the part-full heart at $0670)."
  [s]
  (let [h (byte-at s 0x066F)
        partial (byte-at s 0x0670)]
    {:containers (inc (bit-shift-right h 4))
     :filled (+ (bit-and h 0xF)
                (cond (zero? partial) 0 (< partial 0x80) 0.5 :else 1))}))

;; ---------------------------------------------------------------------------
;; RAM lenses

(def link
  [[:hearts [:fn hearts]]
   [:rupees [:u8 0x066D]]
   [:keys [:u8 0x066E]]
   [:bombs [:u8 0x0658]]
   [:bomb-bag [:u8 0x067C]]
   [:sword [:enum 0x0657 {0 "none" 1 "wooden sword" 2 "white sword" 3 "magical sword"}]]
   [:x [:u8 0x0070]]
   [:y [:u8 0x0084]]
   [:facing [:fn #(direction (byte-at % 0x0098))]]
   [:screen [:fn #(map-location (byte-at % 0x00EB))]]
   [:level [:enum 0x0010 {0 "overworld"}]]
   [:invincible-for [:u8 0x04F0]]
   [:sword-disabled [:bool 0x052E]]
   [:kills-without-a-hit [:u8 0x0050]]
   [:heart-byte [:u8 0x066F]]
   [:part-heart [:u8 0x0670]]])

(def inventory
  [[:sword [:enum 0x0657 {0 "none" 1 "wooden sword" 2 "white sword" 3 "magical sword"}]]
   [:arrows [:enum 0x0659 {0 "none" 1 "arrows" 2 "silver arrows"}]]
   [:bow [:bool 0x065A]]
   [:candle [:enum 0x065B {0 "none" 1 "blue" 2 "red"}]]
   [:recorder [:bool 0x065C]]
   [:bait [:bool 0x065D]]
   [:potion [:enum 0x065E {0 "none" 1 "blue (1 use)" 2 "red (2 uses)"}]]
   [:magical-rod [:bool 0x065F]]
   [:raft [:bool 0x0660]]
   [:book-of-magic [:bool 0x0661]]
   [:ring [:enum 0x0662 {0 "none" 1 "blue" 2 "red"}]]
   [:stepladder [:bool 0x0663]]
   [:magical-key [:bool 0x0664]]
   [:power-bracelet [:bool 0x0665]]
   [:letter [:enum 0x0666 {0 "no" 1 "yes" 2 "shown to the old woman"}]]
   [:boomerang [:bool 0x0674]]
   [:magical-boomerang [:bool 0x0675]]
   [:magical-shield [:bool 0x0676]]
   [:clock [:bool 0x066C]]
   [:triforce [:bits 0x0671 (zipmap (map #(keyword (str "level-" %)) (range 1 9)) (range 8))]]
   [:compasses [:bits 0x0667 (zipmap (map #(keyword (str "level-" %)) (range 1 9)) (range 8))]]
   [:maps [:bits 0x0668 (zipmap (map #(keyword (str "level-" %)) (range 1 9)) (range 8))]]
   [:level-9-compass [:bool 0x0669]]
   [:level-9-map [:bool 0x066A]]])

(defn enemies
  "The six enemy slots in use."
  [s]
  (vec (for [i (range 1 7)
             :let [type (byte-at s (+ 0x034F i))]
             :when (pos? type)]
         {:slot i
          :type type
          :x (byte-at s (+ 0x0070 i))
          :y (byte-at s (+ 0x0084 i))
          :facing (direction (byte-at s (+ 0x0098 i)))
          :invincible-for (byte-at s (+ 0x04F0 i))})))

(def screen
  [[:game-mode [:enum 0x0012 {0 "title / changing" 1 "file select" 4 "finishing scroll"
                              5 "playing" 6 "getting ready to scroll" 7 "scrolling"
                              0x0E "registering a name" 0x0F "elimination mode"
                              ;; found here
                              0x10 "going into a cave" 0x0B "in a cave"}]]
   [:screen [:fn #(map-location (byte-at % 0x00EB))]]
   [:enemies [:fn enemies]]
   [:killed-here [:u8 0x0627]]
   [:paused [:bool 0x00E0]]
   [:recently-visited [:array 0x0621 5 :u8]]
   [:buttons-held [:bits 0x00FA {:right 0 :left 1 :down 2 :up 3 :start 4 :select 5 :b 6 :a 7}]]
   [:frame-counter [:u8 0x0015]]
   [:text-on-screen [:screen-text screen-table]]])

(def save-files
  (vec (for [i (range 3)
             field [[:name [:text (+ 0x0638 (* 8 i)) 8 letters]]
                    [:quest [:enum (+ 0x062D i) {0 "first" 1 "second"}]]
                    [:deaths [:u8 (+ 0x0630 i)]]]]
         [(keyword (str "file-" (inc i) "-" (name (first field)))) (second field)])))

;; ---------------------------------------------------------------------------
;; ROM lenses (file offsets: the ROM map's + 0x10)

(def weapons
  [[:wooden-sword [:rom [:u8 0x075A6]]]
   [:white-sword [:rom [:u8 0x075A7]]]
   [:magical-sword [:rom [:u8 0x075A8]]]
   [:wooden-sword-beam [:rom [:u8 0x07549]]]
   [:white-sword-beam [:rom [:u8 0x07543]]]
   [:magical-sword-beam [:rom [:u8 0x0753A]]]
   [:magical-rod [:rom [:u8 0x075ED]]]
   [:magical-rod-beam [:rom [:u8 0x07531]]]
   [:arrow [:rom [:u8 0x075F9]]]
   [:bomb [:rom [:u8 0x0757A]]]
   [:flame [:rom [:u8 0x07563]]]
   [:boomerang [:rom [:u8 0x0748F]]]])

(def ^:private shops
  ;; [name, items offset, prices offset] (file offsets)
  [["potion shop" 0x1862E 0x1866A] ["shop A" 0x18637 0x18673] ["shop B" 0x1863A 0x18676]
   ["shop C" 0x1863D 0x18679] ["shop D" 0x18640 0x1867C]])

(defn- shop-list
  "What each shop sells, and for how much. The item bytes keep flags in
  their top 2 bits; the item code is the low 6."
  [s]
  (vec (for [[shop items prices] shops]
         {:shop shop
          :items (apply str (interpose ", " (for [i (range 3)
                                                   :let [code (bit-and 0x3F (snapshot/rom-byte s (+ items i)))]
                                                   :when (not= code 0x3F)]
                                               (str (item-names code (str "item " code))
                                                    " (" (snapshot/rom-byte s (+ prices i)) ")"))))})))

(def shop-prices
  (into [[:shops [:fn shop-list]]]
        (concat
         (for [[shop _ prices] shops]
           [(keyword (str (str/replace-first shop " " "-") "-prices")) [:rom [:array prices 3 :u8]]])
         [[:moblin-gift-1 [:rom [:u8 0x18680]]]
          [:moblin-gift-2 [:rom [:u8 0x18683]]]
          [:moblin-gift-3 [:rom [:u8 0x18686]]]])))

(def world
  [[:starting-screen [:rom [:u8 0x1933F]]]
   [:starting-y [:rom [:u8 0x19338]]]
   [:warp-whistle-screens [:rom [:array 0x06020 8 :u8]]]
   [:take-any-road-screens [:rom [:array 0x19344 4 :u8]]]
   [:raft-screen-1 [:rom [:u8 0x14954]]]
   [:raft-screen-2 [:rom [:u8 0x14958]]]
   [:statue-item [:rom [:enum 0x10D05 item-names]]]
   [:tunic-colors [:rom [:struct 0x0A297 [:green :u8 :blue :u8 :red :u8]]]]
   [:second-quest-name [:rom [:text 0x09EFB 5 letters]]]])

(def cave-text
  [
   [:its-dangerous-to [:rom [:text 0x0405C 43 npc-text-table]]]
   [:master-using-it [:rom [:text 0x04087 42 npc-text-table]]]
   [:take-any-road [:rom [:text 0x040B1 24 npc-text-table]]]
   [:secret-is-in [:rom [:text 0x040C9 42 npc-text-table]]]
   [:lets-play-money [:rom [:text 0x040F3 34 npc-text-table]]]
   [:pay-me-for [:rom [:text 0x04115 40 npc-text-table]]]
   [:show-this-to [:rom [:text 0x0413D 37 npc-text-table]]]
   [:meet-the-old [:rom [:text 0x04162 39 npc-text-table]]]
   [:buy-medicine-before [:rom [:text 0x04189 30 npc-text-table]]]
   [:pay-me-and [:rom [:text 0x041A7 23 npc-text-table]]]
   [:this-aint-enough [:rom [:text 0x041BE 36 npc-text-table]]]
   [:go-up-up [:rom [:text 0x041E2 38 npc-text-table]]]
   [:go-north-west [:rom [:text 0x04208 52 npc-text-table]]]
   [:boy-youre-rich [:rom [:text 0x0423C 21 npc-text-table]]]
   [:buy-somethin-will [:rom [:text 0x04251 23 npc-text-table]]]
   [:boy-this-is [:rom [:text 0x04268 37 npc-text-table]]]
   [:take-any-one [:rom [:text 0x0428D 23 npc-text-table]]]
   [:its-a-secret [:rom [:text 0x042A4 36 npc-text-table]]]
   [:grumble-grumble [:rom [:text 0x042C8 21 npc-text-table]]]
   [:eastmost-penninsula-is [:rom [:text 0x042DD 40 npc-text-table]]]
   [:dodongo-dislikes-smoke [:rom [:text 0x04305 24 npc-text-table]]]
   [:did-you-get [:rom [:text 0x0431D 64 npc-text-table]]]
   [:walk-into-the [:rom [:text 0x0435D 35 npc-text-table]]]
   [:secret-power-is [:rom [:text 0x04380 44 npc-text-table]]]
   [:digdogger-hates-certain [:rom [:text 0x043AC 42 npc-text-table]]]
   [:i-bet-youd [:rom [:text 0x043D6 42 npc-text-table]]]
   [:if-you-go [:rom [:text 0x04400 44 npc-text-table]]]
   [:leave-your-life [:rom [:text 0x0442C 35 npc-text-table]]]
   [:there-are-secrets [:rom [:text 0x0444F 46 npc-text-table]]]
   [:aim-at-the [:rom [:text 0x0447D 35 npc-text-table]]]
   [:south-of-arrow [:rom [:text 0x044A0 40 npc-text-table]]]
   [:theres-a-secret [:rom [:text 0x044C8 43 npc-text-table]]]
   [:spectacle-rock-is [:rom [:text 0x044F3 43 npc-text-table]]]
   [:10th-enemy-has [:rom [:text 0x0451E 24 npc-text-table]]]
   [:ones-who-does [:rom [:text 0x04536 45 npc-text-table]]]
   [:patra-has-the [:rom [:text 0x04563 21 npc-text-table]]]
   [:go-to-the [:rom [:text 0x04578 22 npc-text-table]]]
   [:eyes-of-skull [:rom [:text 0x0458E 36 npc-text-table]]]])

(def ending-text
  [[:thanks [:rom [:text 0x0A969 38 npc-text-table]]]
   ;; plain letters, ended by $FF (left out here, so editing keeps it)
   [:the-end [:rom [:text 0x0AB17 52 letters]]]])

;; ---------------------------------------------------------------------------

(lens/register! {:rom sha1 :name "Link" :fields link
                 :doc "Hearts, money, where Link is and which way he's facing."})
(lens/register! {:rom sha1 :name "Inventory" :fields inventory
                 :doc "Everything Link has collected. Tick a box to give him something."})
(lens/register! {:rom sha1 :name "Screen" :fields screen
                 :doc "This screen: the enemies on it, and its text."})
(lens/register! {:rom sha1 :name "Save files" :fields save-files
                 :doc "The three save files as the file select screen shows them. Names use the game's font."})
(lens/register! {:rom sha1 :name "Weapon power" :fields weapons
                 :doc "Damage each weapon does, from the cartridge (16 = one heart's worth on a basic enemy). Change one and the game changes."})
(lens/register! {:rom sha1 :name "Shops" :fields shop-prices
                 :doc "What the shops sell and the prices, from the cartridge. Edit a price and go shopping."})
(lens/register! {:rom sha1 :name "World" :fields world
                 :doc "Where things are, from the cartridge: change the starting screen, or the name that skips to the second quest."})
(lens/register! {:rom sha1 :name "Cave text" :fields cave-text
                 :doc "What the old men and women say, from the cartridge. | and / start the second and third lines; ⏹ ends it (keep it!)."})
(lens/register! {:rom sha1 :name "Ending text" :fields ending-text
                 :doc "The ending's messages, from the cartridge."})
