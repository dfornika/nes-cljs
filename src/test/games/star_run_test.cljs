(ns games.star-run-test
  "Plays Star Run on the ClojureScript CPU by itself: a level wider than
  the screen, the camera, the columns drawn as they come into view, and
  positions past 255."
  (:require [cljs.test :refer [deftest is testing]]
            [games.star-run :as star-run]
            [gamedev-toolkit.screen :as screen]
            [games.tower :as tower]
            [nes.cpu.harness :as harness]))

(defn- addr [m name] (get (:symbols m) name))
(defn- byte-at [m a] (harness/peek-at m a))
(defn- word-at [m name] (+ (byte-at m (addr m name)) (* 256 (byte-at m (inc (addr m name))))))
(defn- player-x [m] (+ (byte-at m (inc (addr m :player/x))) (* 256 (byte-at m (+ 2 (addr m :player/x))))))
(defn- player-y [m] (byte-at m (inc (addr m :player/y))))
(defn- camera [m] (word-at m :camera/x))
(defn- state [m] ({0 :title 1 :playing 2 :game-over} (byte-at m (addr m :rt/state))))
(defn- put! [m x y]
  (harness/poke-at! m :player/x 0 (bit-and x 0xFF) (bit-shift-right x 8))
  (harness/poke-at! m :player/y 0 y)
  (harness/poke-at! m :player/vx 0 0 0 0)
  (harness/poke-at! m :player/climbing 0))
(defn- things [m]
  (for [slot (range 28)
        :let [t (byte-at m (+ (addr m :thing/type) slot))]
        :when (pos? t)]
    {:slot slot :type ({1 :star 2 :spike} t)
     :x (+ (byte-at m (+ (addr m :thing/x) slot)) (* 256 (byte-at m (+ (addr m :thing/x-hi) slot))))
     :y (byte-at m (+ (addr m :thing/y) slot))}))
(defn- remove-things! [m pred]
  (doseq [{:keys [slot] :as t} (things m) :when (pred t)]
    (harness/poke-at! m (+ (addr m :thing/type) slot) 0)))
(defn- score [m] (mapv #(byte-at m (+ (addr m :score) %)) (range 3)))

(defn- game
  ([] (game false))
  ([keep?]
   (let [m (harness/boot! (harness/console (star-run/build)))]
     (harness/frames! m 2)
     (harness/hold! m :start) (harness/frame! m)
     (harness/hold! m) (harness/frame! m)
     (when-not keep? (remove-things! m #(= :spike (:type %))))
     m)))

(deftest the-cartridge
  (let [{:keys [rom]} (star-run/build)]
    (is (= 1 (bit-and (nth rom 6) 1)) "wired for vertical mirroring: two screens side by side")
    (is (= 96 (:columns star-run/level)) "six screens"))
  (let [m (game true)]
    (is (= :playing (state m)))
    (is (= [32 208] [(player-x m) (player-y m)]))
    (is (= 25 (count (things m))) "every star and spike on the level, the far ones too")
    (is (some #(> (:x %) 1400) (things m)) "positions past 255")
    (is (= 0 (camera m)))))

(deftest the-camera-follows
  (let [m (game)]
    (harness/frames! m 2)
    (harness/hold! m :right)
    (harness/frames! m 80)
    (let [x (player-x m)]
      (is (> x 120))
      (is (= (- x 120) (camera m)) "the hopper stays 120 pixels from the screen's left edge")
      (is (= 120 (byte-at m 0x0203)) "its first sprite is drawn there"))
    (testing "and stops at the ends"
      (put! m 1480 208)
      (harness/frames! m 3)
      (is (= (* 16 (- 96 16)) (camera m)) "the last screen")
      (put! m 40 208)
      (harness/frames! m 3)
      (is (= 0 (camera m)) "the first"))))

(deftest columns-come-into-view
  (let [m (game)
        tables (screen/metatile-tables tower/metatiles)
        ;; the tiles a column should have, worked out here in Clojure
        expected (fn [col]
                   (let [ids (subvec (:ids star-run/level) (* 16 col) (+ 15 (* 16 col)))]
                     (vec (concat (mapcat (fn [id] [((:tl tables) id) ((:bl tables) id)]) ids)
                                  (mapcat (fn [id] [((:tr tables) id) ((:br tables) id)]) ids)))))
        prepared (atom #{})
        watch! (fn [frames]
                 (dotimes [_ frames]
                   (harness/frame! m)
                   (let [col (byte-at m (addr m :column/col))
                         buffer (mapv #(byte-at m (+ (addr m :column-tiles) %)) (range 60))]
                     (when (= buffer (expected col)) (swap! prepared conj col)))))
        left #(byte-at m (addr m :scroll/left))]
    (testing "running right, the columns ahead are drawn one by one"
      (harness/hold! m :right)
      (watch! 100)
      (harness/hold! m)
      (watch! 10)
      (is (= :playing (state m)))
      (is (= (max 0 (- (quot (camera m) 16) 8)) (left)) "the ring of drawn columns starts 8 left of the camera")
      (is (every? @prepared (range 32 (+ (left) 32))) "every column that came into view was prepared, and right")
      (is (zero? (byte-at m (addr m :column/pending))) "and copied to the PPU in the NMI"))
    (testing "a jump far along: the ring catches up, a column a frame"
      (put! m 1000 192)
      (watch! 60)
      (is (= (- 880 0) (camera m)))
      (is (= (- (quot 880 16) 8) (left)))
      (is (every? @prepared (range 32 (+ (left) 32)))))
    (testing "and back: columns on the left are drawn again"
      (reset! prepared #{})
      (put! m 600 192)
      (watch! 30)
      (is (= (- (quot 480 16) 8) (left)))
      (is (every? @prepared (range (left) (- 47 0)))))))

(deftest far-along-the-level
  (testing "standing on a plank past the first screen"
    (let [m (game)]
      (put! m 780 50)                                ; over the planks on the fourth screen (row 5, top at 80)
      (harness/frames! m 40)
      (is (= 64 (player-y m)) "it lands where the level's text says")
      (is (pos? (byte-at m (addr m :player/on-ground))))))
  (testing "collecting a star far along"
    (let [m (game true)
          star (first (filter #(and (= :star (:type %)) (> (:x %) 1000)) (things m)))]
      (remove-things! m #(= :spike (:type %)))
      (put! m (:x star) (:y star))
      (harness/frame! m)
      (is (= [0 0 1] (score m)))
      (is (not-any? #(= (:slot %) (:slot star)) (things m)) "it's gone")))
  (testing "a pit"
    (let [m (game)]
      (put! m 328 150)                               ; over the gap in the second screen's floor
      (harness/frames! m 40)
      (is (= :game-over (state m))))))

(deftest the-score-is-sprites
  (let [m (game)]
    (harness/frame! m)
    (let [ys (map #(byte-at m (+ 0x200 (* 4 %))) (range 64))
          on-line (count (filter #(= 7 %) ys))]
      (is (= 8 on-line) "SCORE and three digits on the top line: the most the PPU draws on a line")
      (is (= [88 96 104 112 120 136 144 152]
             (sort (keep #(when (= 7 (byte-at m (+ 0x200 (* 4 %)))) (byte-at m (+ 0x203 (* 4 %)))) (range 64))))
          "with a gap for the space"))))

(deftest last-star-starts-again
  (let [m (game)
        star (first (filter #(= :star (:type %)) (things m)))]
    (harness/poke-at! m :pickups-left 1)
    (put! m (:x star) (:y star))
    (harness/frames! m 3)
    (is (= :playing (state m)))
    (is (= [32 208] [(player-x m) (player-y m)]) "back to the start, the level drawn afresh")
    (is (= [0 0 1] (score m)) "keeping the score")))

(deftest a-busy-frame-fits
  (let [m (game true)]
    (harness/hold! m :right :a)
    (let [worst (apply max (for [_ (range 300)]
                             (let [{:keys [update nmi]} (harness/frame! m)]
                               (when (not= :playing (state m)) (put! m 32 208))
                               (+ update nmi 513))))]
      (is (< worst 29780) (str "the longest frame took " worst " of 29,780 cycles")))
    (testing "and the NMI handler, with a column to copy, fits in vertical blank"
      (let [nmis (for [_ (range 100)]
                   (do (harness/hold! m :right)
                       (:nmi (harness/frame! m))))]
        (is (< (+ 513 (apply max nmis)) 2270) (str "the longest NMI took " (apply max nmis) " cycles, and 513 more for the sprites"))))))
