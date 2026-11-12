(ns games.alienscape-test
  "Plays Alienscape on the ClojureScript CPU by itself: the same rules as
  Star Hopper (tested in games.hopper-test), so this checks what's
  different: the room, the higher jump, the art fitting in."
  (:require [cljs.test :refer [deftest is testing]]
            [games.alienscape :as alienscape]
            [games.alienscape.art :as art]
            [nes.cpu.harness :as harness]))

(defn- addr [m name] (get (:symbols m) name))
(defn- whole [m name] (harness/peek-at m (inc (addr m name))))
(defn- where [m] [(whole m :player/x) (whole m :player/y)])
(defn- grounded? [m] (pos? (harness/peek-at m :player/on-ground)))
(defn- put! [m x y]
  (harness/poke-at! m :player/x 0 x)
  (harness/poke-at! m :player/y 0 y)
  (harness/poke-at! m :player/vy 0 0))
(defn- things [m]
  (for [slot (range 8)
        :let [t (harness/peek-at m (+ (addr m :thing/type) slot))]
        :when (pos? t)]
    {:slot slot :type ({1 :orb 2 :biter} t)
     :x (harness/peek-at m (+ (addr m :thing/x) slot))
     :y (harness/peek-at m (+ (addr m :thing/y) slot))}))

(defn- game []
  (let [m (harness/boot! (harness/console (alienscape/build)))]
    (harness/frames! m 2)
    (harness/hold! m :start) (harness/frame! m)
    (harness/hold! m) (harness/frame! m)
    m))

(defn- without-biters [m]
  (doseq [{:keys [slot type]} (things m) :when (= type :biter)]
    (harness/poke-at! m (+ (addr m :thing/type) slot) 0))
  m)

(deftest the-art-fits
  (is (<= (count (:tiles alienscape/tiles)) 256) "one pattern table's worth of tiles")
  (is (<= (count art/background-palettes) 3) "room for a text palette beside the pack's")
  (is (every? #(< % (count art/tiles)) (:grid (:arena art/rooms))))
  (is (every? #(= 16 (count %)) (mapcat :frames (vals art/sprites))) "creatures are 16 rows tall")
  (let [{:keys [tiles attributes solid]} (:level alienscape/screens)]
    (is (= [960 64 240] [(count tiles) (count attributes) (count solid)]))
    (is (every? #(< % (count (:tiles alienscape/tiles))) tiles))))

(deftest the-room
  (let [m (game)]
    (is (= 1 (harness/peek-at m :rt/state)) "playing")
    (is (= [32 160] (where m)))
    (is (= [[:biter 32 64] [:orb 48 64] [:orb 192 64] [:orb 128 80] [:orb 80 160] [:orb 160 160] [:biter 208 160]]
           (map (juxt :type :x :y) (things m))))
    (harness/frames! m 3)
    (is (grounded? m) "standing on the floor")
    (is (= [32 160] (where m)))))

(deftest jumping-is-higher-here
  (let [m (without-biters (game))
        heights (atom [])]
    (put! m 64 160)
    (harness/frames! m 2)
    (harness/hold! m :a) (harness/frame! m) (harness/hold! m)
    (dotimes [_ 80]
      (harness/frame! m)
      (swap! heights conj (second (where m))))
    (let [rise (- 160 (apply min @heights))]
      (is (< 64 rise 80) (str "enough to climb four blocks, not five: it rose " rise)))
    (is (= 160 (last @heights)))
    (testing "which is what it takes to reach the platform in the middle, four blocks up"
      (put! m 112 60)                               ; dropped from above it
      (harness/frames! m 40)
      (is (grounded? m))
      (is (= [112 96] (where m)) "it's solid where the picture shows it"))))

(deftest biters-keep-to-their-ledges
  (let [m (game)
        slot (:slot (first (filter #(= [32 64] [(:x %) (:y %)]) (things m))))
        xs (atom [])]
    (dotimes [_ 300]
      (put! m 120 160)                              ; the player waits in the middle of the floor...
      (harness/poke-at! m (+ (addr m :thing/x) 6) 208) ; ...and the floor's biter stays put
      (harness/frame! m)
      (swap! xs conj (harness/peek-at m (+ (addr m :thing/x) slot))))
    (is (= 1 (harness/peek-at m :rt/state)))
    (is (<= 15 (apply min @xs)) "not into the wall")
    (is (<= (apply max @xs) 49) "nor off the end of the ledge (which is four blocks: x up to 63, and it's 16 wide)")
    (is (< 20 (- (apply max @xs) (apply min @xs))) "but it does walk")))

(deftest collecting-an-orb
  (let [m (without-biters (game))]
    (put! m 80 160)
    (let [{:keys [update nmi]} (harness/frame! m)]
      (is (= [0 0 1] (mapv #(harness/peek-at m (+ (addr m :score) %)) (range 3))))
      (is (= 4 (harness/peek-at m :pickups-left)))
      (is (< (+ update nmi 513) 29780))
      (is (< (+ nmi 513) 2270)))))

(deftest a-busy-frame-fits
  (let [m (game)]
    (harness/hold! m :right :a)
    (let [worst (apply max (for [_ (range 120)]
                             (let [{:keys [update nmi]} (harness/frame! m)]
                               (when (not= 1 (harness/peek-at m :rt/state)) (put! m 32 160))
                               (+ update nmi 513))))]
      (is (< worst 29780) (str "the longest frame took " worst " of 29,780 cycles")))))
