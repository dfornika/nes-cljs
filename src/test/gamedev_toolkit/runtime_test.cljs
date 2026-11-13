(ns gamedev-toolkit.runtime-test
  (:require [cljs.test :refer [deftest is testing]]
            [gamedev-toolkit.asm :as asm]
            [gamedev-toolkit.font :as font]
            [gamedev-toolkit.gen :as gen]
            [gamedev-toolkit.objects :as objects]
            [gamedev-toolkit.runtime :as rt]
            [gamedev-toolkit.screen :as screen]
            [gamedev-toolkit.sub :refer [call]]
            [nes.chr]
            [nes.cpu.harness :as harness]))

(deftest font
  (is (= (count font/characters) (count font/tiles)))
  (is (every? #(= 16 (count %)) font/tiles))
  (is (= (repeat 16 0) (first font/tiles)) "tile 0 is the space: blank")
  (is (= [8 5 12 12 15 0 23 15 18 12 4] (font/encode "Hello world")) "capitals for lower case")
  (is (= 27 (font/tile-of \0)))
  (is (= 127 (font/tile-of \0 100)))
  (is (thrown-with-msg? js/Error #"The font has no" (font/encode "~")))
  (testing "an A looks like an A"
    (is (= ["..111..." ".1...1.." ".1...1.." ".11111.." ".1...1.." ".1...1.." ".1...1.." "........"]
           (map (fn [row] (apply str (map #(if (zero? %) "." %) row)))
                (nes.chr/tile-pixels (js/Uint8Array.from (clj->js (font/tiles 1))) 0))))))

(deftest pieces
  (is (= 0x2051 (rt/tile-address 17 2)))
  (is (= [[10 0 0 1] [11 8 0 1] [12 0 8 1] [13 8 8 1]] (rt/picture 10 2 2 1)))
  (is (= [[:lda [:imm :state/playing]] [:sta :rt/next]] (rt/transition :playing)))
  (testing "text loads A once for a run of the same letter"
    (is (= [[:lda [:imm 1]] [:sta :ppudata] [:lda [:imm 12]] [:sta :ppudata] [:sta :ppudata]]
           (asm/flatten-forms (drop 1 (rt/text 0 0 "ALL")))))))

(defn- tiny [states]
  (rt/program {:name "tiny.nes" :initial (ffirst states) :states states
               :variables [[:count :u8] [:entered :u8] [:exited :u8]]
               :tiles font/tiles
               :palettes (repeat 32 0x0F)}))

(deftest states
  (let [m (harness/boot!
           (harness/console
            (tiny [[:one {:enter [[:inc :entered]]
                          :update [[:inc :count]
                                   (rt/when-pressed :a (rt/transition :two))]}]
                   [:two {:update [[:dec :count]
                                   (rt/when-held :b (rt/transition :one))]
                          :exit [[:inc :exited]]}]])))
        at #(harness/peek-at m %)]
    (harness/frames! m 3)
    (is (= [3 1 0] [(at :count) (at :entered) (at :rt/state)]) "enter once, update every frame")
    (harness/hold! m :a)
    (harness/frame! m)
    (is (= [4 0] [(at :count) (at :rt/state)]) "the change happens at the start of the next frame")
    (harness/frames! m 2)
    (is (= [2 1] [(at :count) (at :rt/state)]))
    (harness/hold! m :b)
    (harness/frames! m 2)
    (is (= [0 2] [(at :rt/state) (at :entered)]) "back in the first state, entered again")
    (is (= 1 (at :exited)) "and the second state's exit action ran, once")))

(deftest sprites-and-queue
  (let [m (harness/boot!
           (harness/console
            (tiny [[:only {:update [(rt/sprite {:x [:imm 10] :y [:imm 20] :tile 5 :attributes 1})
                                    (rt/metasprite :count [:imm 30] [[6 0 0] [7 8 0 0x40]])
                                    [:inc :count]
                                    (rt/queue (rt/tile-address 3 4) [:imm 9] :count)]}]])))
        sprite (fn [i] (mapv #(harness/peek-at m (+ 0x200 (* 4 i) %)) (range 4)))]
    (harness/frame! m)
    (is (= [[20 5 1 10] [30 6 0 0] [30 7 0x40 8] [0xFF 0xFF 0xFF 0xFF]] (map sprite (range 4))))
    (is (= 0x1E (harness/peek-at m 0x2001)))
    (is (= 1 (harness/peek-at m 0x2007)) "the queue's last tile (the count) reached PPUDATA")
    (is (= 0x83 (harness/peek-at m 0x2006)) "written at row 4, column 3: $2083")
    (harness/frame! m)
    (is (= [30 6 0 1] (sprite 1)) "sprites are drawn afresh each frame")))

(deftest unpacking-a-background
  ;; The runtime's unpacker, run by itself: PPUDATA is an ordinary byte of
  ;; memory here, so count the writes by making each one also bump a
  ;; counter... which it can't. Instead, unpack to a table: the routine
  ;; under test is the same loop with a store to memory.
  (let [bytes (vec (concat (repeat 300 9) [1 2 3] (repeat 40 0) [5]))
        packed (screen/pack bytes)
        m (harness/boot!
           (harness/console
            (rt/program {:name "t.nes" :initial :only :tiles font/tiles :palettes (repeat 32 0x0F)
                         :screens {:test {:tiles (take 960 (concat bytes (repeat 0))) :attributes (repeat 64 0)}}
                         :states [[:only {:enter [(rt/draw-screen (rt/background :test))]}]]})))]
    (harness/frame! m)
    (is (= 0 (harness/peek-at m 0x2007)) "the last byte written to PPUDATA was the last attribute")
    (is (= 0x80 (harness/peek-at m 0x2000)) "and the NMI is back on afterwards")
    (testing "the pointer ends on the packed data's final 0"
      (let [pointer (+ (harness/peek-at m :rt/pointer) (* 256 (harness/peek-at m (inc (get (:symbols m) :rt/pointer)))))
            start (get (:labels m) :screen/test)
            length (count (screen/pack (concat (take 960 (concat bytes (repeat 0))) (repeat 64 0))))]
        (is (= (+ start length -1) pointer))
        (is (= 0 (harness/peek-at m pointer)))))
    (is (pos? (count packed)))))

(deftest objects-in-a-pool
  (let [pool (objects/pool :thing 3 [:coin :bat] {:x :u8 :y :u8})
        symbols {:thing/type 0x300 :thing/x 0x303 :thing/y 0x306 :rt/slot 0x10 :rt/temp 0x11 :total 0x20}
        run (fn [forms] (harness/run-routine [forms [:rts]] {:symbols symbols}))
        types (fn [r] (mapv #((:peek r) (+ 0x300 %)) (range 3)))]
    (testing "spawning takes the first free slot, and sets its fields"
      (let [r (run [(objects/spawn pool :bat {:x [:imm 40] :y [:imm 50]})
                    (objects/spawn pool :coin {:x [:imm 60]})])]
        (is (= [2 1 0] (types r)))
        (is (= [40 60 50 0] [((:peek r) 0x303) ((:peek r) 0x304) ((:peek r) 0x306) ((:peek r) 0x307)]))))
    (testing "a full pool: nothing happens, and the carry flag says so"
      (let [r (run [(repeatedly 4 #(objects/spawn pool :coin {}))])]
        (is (= [1 1 1] (types r)))
        (is (:carry? r))))
    (testing "each visits the ones that exist, and can remove them"
      (let [r (run [(objects/spawn pool :coin {:x [:imm 5]})
                    (objects/spawn pool :bat {:x [:imm 7]})
                    (objects/spawn pool :coin {:x [:imm 9]})
                    ;; add up the coins' x, removing each; bats get x + 1
                    (objects/each pool
                      (objects/by-type pool
                        {:coin [(gen/add :total (objects/field pool :x))
                                [:ldx [:imm 99]]             ; the body may wreck X...
                                (objects/slot)               ; ...and get it back
                                (objects/remove-it pool)]
                         :bat [(gen/add (objects/field pool :x) [:imm 1])]}))])]
        (is (= 14 ((:peek r) 0x20)))
        (is (= [0 2 0] (types r)))
        (is (= 8 ((:peek r) 0x304)))))
    (testing "a level's objects, from a table"
      (let [r (harness/run-routine [(objects/clear pool)
                                    (objects/spawn-all pool :table)
                                    [:rts]
                                    [:label :table]
                                    [:db (objects/placements pool [[:bat 10 20] [:coin 30 40] [:coin 50 60] [:bat 70 80]])]]
                                   {:symbols symbols})]
        (is (= [2 1 1] (types r)) "three fit; the fourth is left out")
        (is (= [10 30 50 20 40 60] (mapv #((:peek r) (+ 0x303 %)) (range 6))))))))

(deftest drawing-in-turns
  (let [pool (objects/pool :thing 3 [:coin] {:x :u8})
        symbols {:thing/type 0x300 :thing/x 0x303 :rt/slot 0x10 :rt/turn 0x11 :log 0x20 :n 0x30}
        ;; visit every object, writing its x to a log
        visit (objects/each-rotating pool
                [:ldy :n]
                [:lda :thing/x :x]
                [:sta :log :y]
                [:inc :n])
        order (fn [turn]
                (let [r (harness/run-routine [visit [:rts]]
                                             {:symbols symbols
                                              :memory {0x300 [1 1 1] 0x303 [10 20 30] 0x11 turn}})]
                  (mapv #((:peek r) (+ 0x20 %)) (range 3))))]
    (is (= [20 30 10] (order 0)) "it moves on one first, then goes round")
    (is (= [30 10 20] (order 1)))
    (is (= [10 20 30] (order 2)) "and wraps")))

(deftest is-it-solid
  ;; a map 32 columns wide (two screens), a column at a time, 16 bytes each:
  ;; one solid block at column 3, row 2, and another at column 20, row 5
  (let [kinds (-> (vec (repeat 512 0))
                  (assoc (+ (* 3 16) 2) 1)
                  (assoc (+ (* 20 16) 5) 2))
        ask (fn [x y]
              (let [r (harness/run-routine [[:ldx [:imm 0x55]]
                                            (rt/probe (gen/pair :px-lo :px-hi) 0 :py 0)
                                            [:rts]
                                            [:label :map] [:db kinds]]
                                           {:symbols {:rt/map 0x10 :rt/px 0x12 :rt/cursor 0x14
                                                      :px-lo 0x20 :px-hi 0x21 :py 0x22}
                                            :memory {0x10 [0x00 0x00]   ; (set below)
                                                     0x20 [(bit-and x 0xFF) (bit-shift-right x 8) y]}})
                    ;; the map's address: after the code, so look it up
                    _ (identity r)]
                r))
        ;; the routine is at $8000; find where :map ended up, then ask again with it set
        where (:map (:labels (ask 0 0)))
        ask (fn [x y]
              (let [r (harness/run-routine [[:ldx [:imm 0x55]]
                                            (rt/probe (gen/pair :px-lo :px-hi) 0 :py 0)
                                            [:rts]
                                            [:label :map] [:db kinds]]
                                           {:symbols {:rt/map 0x10 :rt/px 0x12 :rt/cursor 0x14
                                                      :px-lo 0x20 :px-hi 0x21 :py 0x22}
                                            :memory {0x10 [(bit-and where 0xFF) (bit-shift-right where 8)]
                                                     0x20 [(bit-and x 0xFF) (bit-shift-right x 8) y]}})]
                [(:a r) (:x r)]))]
    (is (= [1 0x55] (ask 48 32)) "the block's top left pixel; X is left alone")
    (is (= [1 0x55] (ask 63 47)) "its bottom right")
    (is (= 0 (first (ask 47 32))) "one pixel to the left")
    (is (= 0 (first (ask 64 40))) "one to the right")
    (is (= 0 (first (ask 50 31))) "one above")
    (is (= 0 (first (ask 50 48))) "one below")
    (is (= [2 0x55] (ask 330 90)) "past the first 256 pixels: column 20 (x 320-335), row 5")
    (is (= 0 (first (ask 336 90))))
    (testing "a step to the left of a 16-bit position borrows"
      (let [r (harness/run-routine [(rt/probe (gen/pair :px-lo :px-hi) -1 :py 0) [:rts]]
                                   {:symbols {:rt/map 0x10 :rt/px 0x12 :rt/cursor 0x14
                                              :px-lo 0x20 :px-hi 0x21 :py 0x22}
                                    :memory {0x20 [0x00 0x01 0]}
                                    :max-cycles 1000})]
        (is (= [0xFF 0x00] [((:peek r) 0x12) ((:peek r) 0x13)]) "256 - 1 = 255")))))

(deftest one-byte-and-wide-positions
  (testing "an 8.8 speed added to a 16.8 position carries into the third byte, and borrows"
    (let [run (fn [pos speed]
                (let [r (harness/run-routine [(gen/add-wide :p :v) [:rts]]
                                             {:symbols {:p 0x10 :v 0x14}
                                              :memory {0x10 [0 (bit-and pos 0xFF) (bit-shift-right pos 8)]
                                                       0x14 [(bit-and speed 0xFF) (bit-and (bit-shift-right speed 8) 0xFF)]}})]
                  (+ ((:peek r) 0x11) (* 256 ((:peek r) 0x12)))))]
      (is (= 256 (run 255 (gen/fixed 1))))
      (is (= 255 (run 256 (gen/fixed -1))))
      (is (= 1000 (run 998 (gen/fixed 2))))))
  (testing "16-bit generators on a pair of table entries"
    (let [r (harness/run-routine [[:ldx [:imm 2]]
                                  (gen/inc16 (gen/pair (gen/indexed :lo :x) (gen/indexed :hi :x)))
                                  [:rts]]
                                 {:symbols {:lo 0x20 :hi 0x30}
                                  :memory {0x22 0xFF 0x32 0x01}})]
      (is (= [0x00 0x02] [((:peek r) 0x22) ((:peek r) 0x32)]) "$01FF + 1, the bytes in different tables")))
  (testing "close, across more than 256 pixels"
    (let [close? (fn [a b]
                   (let [r (harness/run-routine [(gen/when-close [(gen/pair :ax-lo :ax-hi) :ay]
                                                                 [(gen/pair :bx-lo :bx-hi) :by] 12
                                                   [:inc :hit])
                                                 [:rts]]
                                                {:symbols {:ax-lo 0x20 :ax-hi 0x21 :ay 0x22
                                                           :bx-lo 0x23 :bx-hi 0x24 :by 0x25 :hit 0x26}
                                                 :memory {0x20 [(bit-and a 0xFF) (bit-shift-right a 8) 100
                                                                (bit-and b 0xFF) (bit-shift-right b 8) 100]}})]
                     (pos? ((:peek r) 0x26))))]
      (is (close? 300 300))
      (is (close? 300 311))
      (is (not (close? 300 312)))
      (is (close? 311 300))
      (is (not (close? 300 44)) "300 and 44 have the same low byte's distance as 256 apart: not close")
      (is (close? 250 260) "either side of 256"))))

(deftest random-numbers
  ;; call `random` 600 times, collecting the bytes
  (let [r (harness/run-routine
           [(gen/times 600
              (gen/preserving [:x :y]
                (call rt/random)
                [:ldy [:imm 0]]
                [:sta [:ind :ptr] :y]
                (gen/inc16 :ptr)))
            [:rts]]
           {:symbols {:rt/seed 0x10 :ptr 0x20}
            :memory {0x10 [0xDE 0xC0] 0x20 [0x00 0x10]}
            :max-cycles 1000000})
        bytes (mapv #((:peek r) (+ 0x1000 %)) (range 600))]
    (is (< 200 (count (distinct bytes))) "plenty of different values")
    (is (< 100 (/ (reduce + bytes) 600) 155) "averaging somewhere near the middle")
    (is (not= (take 300 bytes) (drop 300 bytes)) "and not repeating soon")))

(deftest mistakes
  (is (thrown-with-msg? js/Error #"isn't one of the states"
                        (rt/program {:initial :nope :states [[:a {}]] :tiles [] :palettes (repeat 32 0)})))
  (is (thrown-with-msg? js/Error #"needs 32 colors"
                        (rt/program {:initial :a :states [[:a {}]] :tiles [] :palettes [1 2 3]}))))
