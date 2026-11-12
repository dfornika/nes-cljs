(ns games.catch-test
  "Plays Catch the Stars on the ClojureScript CPU by itself (see
  nes.cpu.harness): no PPU, so this checks the game's logic and timing,
  and the browser smoke test checks that it draws."
  (:require [cljs.test :refer [deftest is testing]]
            [games.catch :as catch]
            [gamedev-toolkit.font :as font]
            [gamedev-toolkit.gen :as gen]
            [nes.cpu.harness :as harness]))

(def ^:private states {0 :title 1 :playing 2 :game-over})

(defn- game [] (harness/boot! (harness/console (catch/build))))

(defn- state [m] (states (harness/peek-at m :rt/state)))

(defn- whole [m name] (harness/peek-at m (inc (get (:symbols m) name))))

(defn- put-fixed! [m name pixels]
  (harness/poke-at! m name 0 pixels))

(defn- press!
  "Tap a button: down for a frame, then up."
  [m button]
  (harness/hold! m button)
  (harness/frame! m)
  (harness/hold! m)
  (harness/frame! m)
  m)

(defn- start-playing [m]
  (harness/frames! m 2)
  (press! m :start)
  m)

(defn- sprites-shown [m]
  (count (remove #(>= % 0xEF) (map #(harness/peek-at m (+ 0x200 (* 4 %))) (range 64)))))

(deftest title-screen
  (let [m (harness/frames! (game) 3)]
    (is (= :title (state m)))
    (is (= 12 (sprites-shown m)) "the cast: three pictures of four sprites")
    (is (= 0x1E (harness/peek-at m 0x2001)) "the picture is on")
    (testing "nothing happens until Start"
      (harness/hold! m :a :right)
      (harness/frames! m 3)
      (is (= :title (state m))))))

(deftest playing
  (let [m (start-playing (game))]
    (is (= :playing (state m)))
    (is (= [120 112] [(whole m :player/x) (whole m :player/y)]))
    (is (= [0 0 0] (mapv #(harness/peek-at m (+ (get (:symbols m) :score) %)) (range 3))))
    (is (= 12 (sprites-shown m)))
    (testing "the star is somewhere on the screen"
      (is (<= 56 (harness/peek-at m :star/x) 183))
      (is (<= 64 (harness/peek-at m :star/y) 191)))
    (testing "the D-pad moves the face a pixel and a half a frame"
      (harness/hold! m :left :up)
      (harness/frames! m 2)
      (is (= [117 109] [(whole m :player/x) (whole m :player/y)])))
    (testing "...and it can't leave the screen"
      (let [park-spike! (fn [x y]                     ; standing still, out of the way
                          (harness/poke-at! m :enemy/vx 0 0 0 0)
                          (put-fixed! m :enemy/x x)
                          (put-fixed! m :enemy/y y)
                          (harness/poke-at! m :star/x 250))]
        (park-spike! catch/right catch/bottom)
        (harness/frames! m 200)
        (is (= [catch/left catch/top] [(whole m :player/x) (whole m :player/y)]))
        (put-fixed! m :player/x 120)
        (put-fixed! m :player/y 112)
        (park-spike! catch/left catch/top)
        (harness/hold! m :right :down)
        (harness/frames! m 300)
        (is (= [catch/right catch/bottom] [(whole m :player/x) (whole m :player/y)]))
        (is (= :playing (state m)))))))

(deftest the-spike-bounces
  (let [m (start-playing (game))]
    (dotimes [_ 600]
      ;; keep the player out of its way: in whichever corner is furthest
      (put-fixed! m :player/x (if (> (whole m :enemy/x) 120) catch/left catch/right))
      (put-fixed! m :player/y (if (> (whole m :enemy/y) 128) catch/top catch/bottom))
      (harness/poke-at! m :star/x 250)
      (harness/frame! m)
      (is (<= catch/left (whole m :enemy/x) catch/right))
      (is (<= catch/top (whole m :enemy/y) catch/bottom)))
    (is (= :playing (state m)))
    (testing "at the right edge its sideways speed turns around"
      (put-fixed! m :enemy/x catch/right)
      (harness/poke-at! m :enemy/vx 0 1)              ; +1.0
      (harness/frame! m)
      (is (= [0 0xFF] [(harness/peek-at m :enemy/vx) (harness/peek-at m (inc (get (:symbols m) :enemy/vx)))])
          "-1.0")
      (is (= catch/right (whole m :enemy/x))))))

(deftest catching-a-star
  (let [m (start-playing (game))
        score (fn [] (mapv #(harness/peek-at m (+ (get (:symbols m) :score) %)) (range 3)))
        speed (fn [] (+ (harness/peek-at m :enemy/vx) (* 256 (harness/peek-at m (inc (get (:symbols m) :enemy/vx))))))
        catch! (fn []
                 (put-fixed! m :enemy/x 200)          ; the spike, out of the way
                 (put-fixed! m :enemy/y 200)
                 (put-fixed! m :player/x 60)
                 (put-fixed! m :player/y 70)
                 (harness/poke-at! m :star/x 64)
                 (harness/poke-at! m :star/y 68)
                 (harness/frame! m))]
    (harness/poke-at! m :enemy/vx (bit-and (gen/fixed 1.0) 0xFF) 1)
    (let [{:keys [update nmi]} (catch!)]
      (is (= [0 0 1] (score)))
      (is (not= [64 68] [(harness/peek-at m :star/x) (harness/peek-at m :star/y)]) "a new star")
      (is (= (gen/fixed 1.125) (speed)) "the spike speeds up")
      (testing "the score on screen was sent to the PPU in the NMI"
        (is (= (font/tile-of \1) (harness/peek-at m 0x2007)) "the last tile written: the ones digit")
        (is (= 0 (harness/peek-at m :rt/queue-end)) "and the queue is empty again"))
      (testing "it all fits"
        (is (< (+ update nmi 513) 29780) "a frame's work in a frame")
        (is (< (+ nmi 513) 2270) "the NMI handler, with the sprite copy, in vertical blank")))
    (testing "ten make a ten"
      (dotimes [_ 9] (catch!))
      (is (= [0 1 0] (score))))
    (testing "a speed going the other way gets more negative"
      (harness/poke-at! m :enemy/vx 0 0xFF)           ; -1.0
      (catch!)
      (is (= (gen/fixed -1.125) (speed))))))

(deftest touching-the-spike
  (let [m (start-playing (game))]
    (harness/poke-at! m :star/x 250)
    (put-fixed! m :enemy/x 124)
    (put-fixed! m :enemy/y 110)
    (harness/poke-at! m (get (:symbols m) :score) 0 4 2)
    (harness/frames! m 2)
    (is (= :game-over (state m)))
    (is (= 0 (sprites-shown m)) "nothing but text on the game over screen")
    (testing "Start plays again, from a score of 0"
      (press! m :start)
      (is (= :playing (state m)))
      (is (= [0 0 0] (mapv #(harness/peek-at m (+ (get (:symbols m) :score) %)) (range 3))))
      (is (= [120 112] [(whole m :player/x) (whole m :player/y)])))))

(deftest a-new-star-is-never-on-the-player
  (let [m (start-playing (game))]
    (dotimes [_ 60]
      ;; the spike stays parked in a corner (each star speeds it up again)
      (harness/poke-at! m :enemy/vx 0 0 0 0)
      (put-fixed! m :enemy/x catch/right)
      (put-fixed! m :enemy/y catch/bottom)
      ;; walk onto the star, wherever it is, and see where the next one goes
      (put-fixed! m :player/x (harness/peek-at m :star/x))
      (put-fixed! m :player/y (harness/peek-at m :star/y))
      (harness/frame! m)
      (is (or (>= (abs (- (harness/peek-at m :star/x) (whole m :player/x))) catch/too-close)
              (>= (abs (- (harness/peek-at m :star/y) (whole m :player/y))) catch/too-close))))
    (is (= [0 6 0] (mapv #(harness/peek-at m (+ (get (:symbols m) :score) %)) (range 3))) "sixty stars")))

(deftest different-games-get-different-stars
  (let [first-star (fn [frames-before-start]
                     (let [m (harness/frames! (game) frames-before-start)]
                       (press! m :start)
                       [(harness/peek-at m :star/x) (harness/peek-at m :star/y)]))]
    (is (< 1 (count (distinct (map first-star [1 2 3 5 8 13 21]))))
        "pressing Start at different moments gives different first stars")))
