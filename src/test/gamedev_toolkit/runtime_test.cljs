(ns gamedev-toolkit.runtime-test
  (:require [cljs.test :refer [deftest is testing]]
            [gamedev-toolkit.asm :as asm]
            [gamedev-toolkit.font :as font]
            [gamedev-toolkit.gen :as gen]
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
