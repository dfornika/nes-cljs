(ns games.tower-test
  "Plays Star Tower on the ClojureScript CPU by itself: one-way planks,
  ladders, speeding up and slowing down, jumps as high as A is held,
  spikes that fall, music and flicker."
  (:require [cljs.test :refer [deftest is testing]]
            [games.tower :as tower]
            [gamedev-toolkit.gen :as gen]
            [gamedev-toolkit.sound :as sound]
            [nes.cpu.harness :as harness]))

(defn- addr [m name] (get (:symbols m) name))
(defn- whole [m name] (harness/peek-at m (inc (addr m name))))
(defn- word [m name] (let [v (+ (harness/peek-at m name) (* 256 (harness/peek-at m (inc (addr m name)))))]
                       (if (>= v 0x8000) (- v 0x10000) v)))
(defn- where [m] [(whole m :player/x) (whole m :player/y)])
(defn- grounded? [m] (pos? (harness/peek-at m :player/on-ground)))
(defn- climbing? [m] (pos? (harness/peek-at m :player/climbing)))
(defn- state [m] ({0 :title 1 :playing 2 :game-over} (harness/peek-at m :rt/state)))
(defn- put! [m x y]
  (harness/poke-at! m :player/x 0 x)
  (harness/poke-at! m :player/y 0 y)
  (harness/poke-at! m :player/vx 0 0 0 0)
  (harness/poke-at! m :player/climbing 0))
(defn- things [m]
  (for [slot (range 12)
        :let [t (harness/peek-at m (+ (addr m :thing/type) slot))]
        :when (pos? t)]
    {:slot slot :type ({1 :star 2 :spike} t)
     :x (harness/peek-at m (+ (addr m :thing/x) slot))
     :y (harness/peek-at m (+ (addr m :thing/y) slot))}))
(defn- remove-things! [m pred]
  (doseq [{:keys [slot] :as t} (things m) :when (pred t)]
    (harness/poke-at! m (+ (addr m :thing/type) slot) 0)))

(defn- game
  "A game, started, with nothing in it but the player (unless `keep?`)."
  ([] (game false))
  ([keep?]
   (let [m (harness/boot! (harness/console (tower/build)))]
     (harness/frames! m 2)
     (harness/hold! m :start) (harness/frame! m)
     (harness/hold! m) (harness/frame! m)
     (when-not keep? (remove-things! m (constantly true)))
     m)))

(defn- peak
  "Hold `buttons` for `frames` frames, then nothing for 60; the highest
  the player got (the smallest y)."
  [m buttons frames]
  (let [ys (atom [])]
    (apply harness/hold! m buttons)
    (dotimes [_ frames] (harness/frame! m) (swap! ys conj (second (where m))))
    (harness/hold! m)
    (dotimes [_ 60] (harness/frame! m) (swap! ys conj (second (where m))))
    (apply min @ys)))

(deftest the-level
  (let [m (game true)]
    (is (= :playing (state m)))
    (is (= [32 208] (where m)))
    (is (= 7 (count (filter #(= :star (:type %)) (things m)))))
    (is (= 3 (count (filter #(= :spike (:type %)) (things m)))))
    (harness/frames! m 3)
    (is (grounded? m))))

(deftest speeding-up-and-slowing-down
  (let [m (game)
        vx #(word m :player/vx)]
    (harness/hold! m :right)
    (harness/frame! m)
    (is (= (gen/fixed 0.125) (vx)) "a little faster each frame...")
    (harness/frames! m 20)
    (is (= (gen/fixed 1.75) (vx)) "...up to the most")
    (harness/hold! m)
    (harness/frame! m)
    (is (= (gen/fixed 1.5625) (vx)) "let go: it slows")
    (harness/frames! m 10)
    (is (= 0 (vx)) "and stops, and stays stopped")
    (testing "turning round skids through 0"
      (harness/hold! m :right) (harness/frames! m 20)
      (harness/hold! m :left) (harness/frame! m)
      (is (pos? (vx)) "still going right")
      (harness/frames! m 30)
      (is (= (- (gen/fixed 1.75)) (vx)) "then full speed left"))))

(deftest jumps-as-high-as-a-is-held
  (let [m (game)]
    (harness/frames! m 2)
    (let [tap (- 208 (peak m [:a] 1))
          _ (harness/frames! m 10)
          full (- 208 (peak m [:a] 30))]
      (is (< 30 full 48) (str "held: a full jump, " full " pixels"))
      (is (< tap 15) (str "tapped: a hop, " tap " pixels"))
      (is (grounded? m)))))

(deftest one-way-planks
  (testing "jump up through one, and land on it"
    (let [m (game)]
      ;; under the planks of the second floor (their top is at y = 112),
      ;; rising fast
      (put! m 112 130)
      (harness/poke-at! m :player/vy 0 (bit-and (- 5) 0xFF))   ; -5.0
      (harness/hold! m :a)                                      ; (so the rise isn't cut short)
      (let [ys (atom [])]
        (dotimes [_ 50] (harness/frame! m) (swap! ys conj (second (where m))))
        (is (< (apply min @ys) 96) "its head went through the plank")
        (is (= [112 96] (where m)) "and it came down on top")
        (is (grounded? m)))))
  (testing "falling onto one: it holds"
    (let [m (game)]
      (put! m 112 60)
      (harness/frames! m 40)
      (is (= [112 96] (where m)))))
  (testing "walking along, and off the end of the row into a gap"
    (let [m (game)]
      (put! m 176 48)                                          ; on the top floor, near its gap
      (harness/frames! m 5)
      (is (= 48 (second (where m))))
      (harness/hold! m :right)
      (harness/frames! m 60)
      (is (> (second (where m)) 48) "it fell through the gap"))))

(deftest ladders
  (let [m (game)]
    (put! m 64 208)                                            ; at the foot of a ladder
    (harness/frames! m 2)
    (testing "Up gets on, and climbs a pixel a frame"
      (harness/hold! m :up)
      (harness/frame! m)
      (is (climbing? m))
      (harness/frames! m 10)
      (is (= [64 198] (where m))))
    (testing "Left and Right don't move it while it climbs"
      (harness/hold! m :up :right)
      (harness/frames! m 10)
      (is (= 64 (first (where m)))))
    (testing "at the top it steps off onto the ladder's top, and stands"
      (harness/hold! m :up)
      (harness/frames! m 60)
      (is (not (climbing? m)))
      (is (= [64 144] (where m)) "feet on the top of the ladder's top block, y 160")
      (is (grounded? m))
      (harness/frames! m 10)
      (is (= [64 144] (where m)) "Up held at the top doesn't start climbing again"))
    (testing "Down, standing on top, goes back down"
      (harness/hold! m :down)
      (harness/frames! m 80)
      (is (= [64 208] (where m)) "to the floor")
      (is (climbing? m) "still on the ladder")
      (harness/hold! m :right)
      (harness/frames! m 10)
      (is (not (climbing? m)) "and Right walks off it")
      (is (> (first (where m)) 64))))
  (testing "A jumps off a ladder"
    (let [m (game)]
      (put! m 64 208)
      (harness/frames! m 2)
      (harness/hold! m :up) (harness/frames! m 20)
      (is (climbing? m))
      (harness/hold! m :a) (harness/frame! m)
      (is (not (climbing? m)))
      (is (neg? (word m :player/vy)) "rising")))
  (testing "Up in the middle of nowhere does nothing"
    (let [m (game)]
      (harness/frames! m 2)
      (harness/hold! m :up) (harness/frames! m 10)
      (is (not (climbing? m)))
      (is (= [32 208] (where m))))))

(deftest spikes-fall
  (let [m (game true)
        slot (:slot (first (filter #(= [:spike 112 48] ((juxt :type :x :y) %)) (things m))))
        top-spike (fn [] (first (filter #(= slot (:slot %)) (things m))))]
    ;; keep only the spike on the top floor
    (remove-things! m #(not= slot (:slot %)))
    (is (some? slot))
    (let [ys (atom [])]
      (dotimes [_ 400]
        (put! m 32 208)                                        ; out of the way
        (harness/frame! m)
        (swap! ys conj (:y (top-spike))))
      (is (= :playing (state m)))
      (is (some #{96} @ys) "it walked off the gap and landed on the floor below (planks at y 112)")
      (is (every? even? @ys) "falling 2 pixels at a time, from a block's top")
      ;; standing (or walking) its y stays the same for a while; falling, it
      ;; changes every frame
      (is (every? #{48 96 144 208} (map first (filter #(> (count %) 1) (partition-by identity @ys))))
          "it only ever stood on a floor"))))

(deftest music
  (let [m (game)
        notes (sound/song tower/theme)
        ;; the triangle channel's registers: $4008 (sounding?), then $400A
        ;; and $400B (the pitch; $4009 isn't used)
        triangle (fn [m] (mapv #(harness/peek-at m %) [0x4008 0x400A 0x400B]))]
    ;; (the song starts with the state's entry action, after that frame's
    ;; music has already been done: its first note plays on the next frame)
    (harness/frame! m)
    (testing "the first note is playing"
      (is (= [0xFF (nth notes 1) (nth notes 2)] (triangle m))
          "sounding, at the first note's pitch"))
    (testing "12 frames later, the next"
      (harness/frames! m 12)
      (is (= (nth notes 4) (nth (triangle m) 1))))
    (testing "and round again at the end"
      (let [length (reduce + (map second tower/theme))]
        (harness/frames! m (- length 12))
        (is (= (nth notes 1) (nth (triangle m) 1)) "the first note again")))
    (testing "game over stops it"
      (let [m (game true)]
        (put! m 104 48)                                        ; into the top floor's spike
        (harness/frames! m 3)
        (is (= :game-over (state m)))
        (is (= 0x80 (first (triangle m))) "silenced")
        (is (zero? (harness/peek-at m :music/on)))))))

(deftest crowded-lines-flicker
  (let [m (game true)
        first-object-x #(harness/peek-at m (+ 0x200 (* 4 4) 3))]   ; the first sprite after the player's four
    (let [turns (atom []) xs (atom [])]
      (dotimes [_ 24]
        (put! m 32 208)
        (harness/frame! m)
        (swap! turns conj (harness/peek-at m :rt/turn))
        (swap! xs conj (first-object-x)))
      (is (= (take 12 (drop (.indexOf @turns 0) @turns)) (range 12)) "the first object drawn goes round all 12 slots")
      (is (< 5 (count (distinct @xs))) "so a different object comes first each frame"))))

(deftest a-busy-frame-fits
  (let [m (game true)]
    (harness/hold! m :right :a)
    (let [worst (apply max (for [_ (range 150)]
                             (let [{:keys [update nmi]} (harness/frame! m)]
                               (when (not= :playing (state m)) (put! m 32 208))
                               (+ update nmi 513))))]
      (is (< worst 29780) (str "the longest frame took " worst " of 29,780 cycles")))))
