(ns games.hopper-test
  "Plays Star Hopper on the ClojureScript CPU by itself (see
  nes.cpu.harness): the physics, the objects and the timing."
  (:require [cljs.test :refer [deftest is testing]]
            [games.hopper :as hopper]
            [gamedev-toolkit.objects :as objects]
            [gamedev-toolkit.sound :as sound]
            [nes.cpu.harness :as harness]))

(def ^:private states {0 :title 1 :playing 2 :game-over})

(defn- state [m] (states (harness/peek-at m :rt/state)))
(defn- addr [m name] (get (:symbols m) name))
(defn- whole [m name] (harness/peek-at m (inc (addr m name))))
(defn- where [m] [(whole m :player/x) (whole m :player/y)])
(defn- grounded? [m] (pos? (harness/peek-at m :player/on-ground)))
(defn- put! [m x y]
  (harness/poke-at! m :player/x 0 x)
  (harness/poke-at! m :player/y 0 y)
  (harness/poke-at! m :player/vy 0 0))
(defn- score [m] (mapv #(harness/peek-at m (+ (addr m :score) %)) (range 3)))
(defn- things
  "The objects that exist: [{:slot :type :x :y :dir} ...]"
  [m]
  (for [slot (range 8)
        :let [t (harness/peek-at m (+ (addr m :thing/type) slot))]
        :when (pos? t)]
    {:slot slot
     :type ({1 :star 2 :spike} t)
     :x (harness/peek-at m (+ (addr m :thing/x) slot))
     :y (harness/peek-at m (+ (addr m :thing/y) slot))
     :dir (harness/peek-at m (+ (addr m :thing/dir) slot))}))

(defn- tap! [m button]
  (harness/hold! m button) (harness/frame! m)
  (harness/hold! m) (harness/frame! m)
  m)

(defn- game
  "A game, started, with the spikes taken away unless `spikes?`."
  ([] (game false))
  ([spikes?]
   (let [m (harness/boot! (harness/console (hopper/build)))]
     (harness/frames! m 2)
     (tap! m :start)
     (when-not spikes?
       (doseq [{:keys [slot type]} (things m) :when (= type :spike)]
         (harness/poke-at! m (+ (addr m :thing/type) slot) 0)))
     m)))

(deftest the-level-is-as-drawn
  (let [m (game true)]
    (is (= :playing (state m)))
    (is (= [32 208] (where m)) "the player is where the P is")
    (is (= [[:star 32 64] [:star 208 64] [:star 112 112] [:star 32 144] [:spike 192 144] [:star 112 176] [:spike 208 208]]
           (map (juxt :type :x :y) (things m)))
        "and a star or a spike wherever there's a * or an S")
    (is (= 5 (harness/peek-at m :stars-left)))
    (is (= [0 0 0] (score m)))))

(deftest standing-and-running
  (let [m (game)]
    (harness/frames! m 3)
    (is (grounded? m))
    (is (= [32 208] (where m)) "gravity pulls, the floor holds")
    (harness/hold! m :right)
    (harness/frames! m 20)
    (is (= [62 208] (where m)) "a pixel and a half a frame")
    (is (= 0 (harness/peek-at m :player/facing)))
    (harness/hold! m :left)
    (harness/frames! m 4)
    (is (= [56 208] (where m)))
    (is (= 1 (harness/peek-at m :player/facing)))
    (testing "walls stop it"
      (harness/frames! m 100)
      (is (<= 13 (first (where m)) 15) "at the left wall, whose face is at x = 16 (the body starts 3 in)")
      (harness/hold! m :right)
      (harness/frames! m 300)
      (is (<= 226 (first (where m)) 228) "and the right wall, at x = 240 (the body ends 12 in)")
      (is (= 208 (second (where m)))))))

(deftest jumping
  (let [m (game)
        heights (atom [])]
    (harness/frames! m 2)
    (harness/hold! m :a)
    (harness/frame! m)
    (is (not (grounded? m)))
    (testing "the jump sound starts"
      (is (= (sound/registers hopper/jump-sound) (mapv #(harness/peek-at m (+ 0x4000 %)) (range 4)))))
    (harness/hold! m)
    (dotimes [_ 60]
      (harness/frame! m)
      (swap! heights conj (second (where m))))
    (let [top (apply min @heights)]
      (is (< (- 208 48) top (- 208 32)) (str "high enough to climb two blocks, not three: it reached " (- 208 top))))
    (is (= 208 (last @heights)) "and comes back down")
    (is (grounded? m))
    (testing "no jumping in mid air"
      (tap! m :a)
      (harness/frames! m 6)
      (let [y (second (where m))]
        (tap! m :a)
        (is (> (second (where m)) (- y 12)) "a second press on the way up does nothing")))))

(deftest girders-are-solid
  (testing "from above: you land on one"
    (let [m (game)]
      (put! m 112 150)                               ; over the girder whose top is at y = 192
      (harness/frames! m 30)
      (is (= [112 176] (where m)))
      (is (grounded? m))
      (testing "and walking off the end, you fall to the floor"
        (harness/hold! m :right)
        (harness/frames! m 80)
        (is (= 208 (second (where m)))))))
  (testing "from below: you bump your head"
    (let [m (game)
          lowest (atom 255)]
      (put! m 112 208)                               ; on the floor, under that girder
      (harness/frames! m 2)
      (harness/hold! m :a)
      (dotimes [_ 20]
        (harness/frame! m)
        (swap! lowest min (second (where m))))
      (is (>= @lowest 207) "it can't rise into the girder")))
  (testing "from the side: it stops you"
    (let [m (game)]
      (put! m 60 180)                                ; in the air, left of that girder (which starts at x = 96)
      (harness/hold! m :right)
      (harness/frames! m 12)
      (is (< (first (where m)) (- 96 12)) "still to the left of it"))))

(deftest collecting-stars
  (let [m (game)]
    (put! m 112 176)                                 ; on the star above the low girder
    (let [{:keys [update nmi]} (harness/frame! m)]
      (is (= [0 0 1] (score m)))
      (is (= 4 (harness/peek-at m :stars-left)))
      (is (= 4 (count (filter #(= :star (:type %)) (things m)))) "its slot is free again")
      (is (= (sound/registers hopper/star-sound) (mapv #(harness/peek-at m (+ 0x4004 %)) (range 4))))
      (is (< (+ update nmi 513) 29780) "a frame's work fits in a frame")
      (is (< (+ nmi 513) 2270) "and the NMI handler in vertical blank"))
    (testing "the last one brings them all back"
      (doseq [[_ x y] (filter #(= :star (first %)) hopper/placed)
              :when (not= [x y] [112 176])]
        (put! m x y)
        (harness/frame! m))
      (is (= [0 0 5] (score m)))
      (is (= 5 (harness/peek-at m :stars-left)))
      (is (= 5 (count (filter #(= :star (:type %)) (things m)))))
      (is (= [32 208] (where m)) "and the player is back at the start"))))

(deftest spikes-patrol
  (let [m (game true)
        slot (:slot (first (filter #(= [192 144] [(:x %) (:y %)]) (things m))))
        xs (atom [])]
    (dotimes [_ 500]
      (put! m 32 208)                                ; the player keeps out of it, in the corner
      (harness/poke-at! m (+ (addr m :thing/x) 6) 120) ; and so does the other spike
      (harness/frame! m)
      (swap! xs conj (harness/peek-at m (+ (addr m :thing/x) slot))))
    (is (= :playing (state m)))
    (is (<= 159 (apply min @xs)) "it doesn't walk off the left end of its girder (which starts at x = 160)")
    (is (>= 225 (apply max @xs)) "or into the wall (at x = 240; it's 16 wide)")
    (is (< (apply min @xs) 170) "but it does go all the way along")
    (is (> (apply max @xs) 215))
    (is (every? #(<= (abs %) 1) (map - (rest @xs) @xs)) "a step at a time")))

(deftest touching-a-spike
  (let [m (game true)]
    (put! m 200 208)                                 ; beside the spike on the floor
    (harness/frames! m 2)
    (is (= :game-over (state m)))
    (is (= (sound/registers hopper/hit-sound) (mapv #(harness/peek-at m (+ 0x400C %)) (range 4))))
    (tap! m :start)
    (is (= :playing (state m)))
    (is (= [32 208] (where m)))))

(deftest a-busy-frame-fits
  ;; every slot full, the player running and jumping
  (let [m (game true)]
    (harness/hold! m :right :a)
    (let [worst (apply max (for [_ (range 120)]
                             (let [{:keys [update nmi]} (harness/frame! m)]
                               (when (= :game-over (state m)) (put! m 32 208))
                               (+ update nmi 513))))]
      (is (< worst 29780) (str "the longest frame took " worst " of 29,780 cycles")))))
