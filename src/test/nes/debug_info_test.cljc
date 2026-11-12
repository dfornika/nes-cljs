(ns nes.debug-info-test
  (:require [clojure.test :refer [deftest is testing]]
            [games.hello :as hello]
            [gamedev-toolkit.memory :as memory]
            [nes.cpu.disasm :as disasm]
            [nes.debug-info :as di]
            [nes.lens :as lens]))

(def ^:private mem
  (memory/allocate
   [[:frame :u8]
    [:score :u16]
    [:state [:enum {0 :title 1 :playing}]]
    [:player {:x :u8 :y :u16}]
    [:enemies [:soa 4 {:x :u8 :hp :u8}]]
    [:oam [:array 256 :u8] {:at 0x0200 :hide true}]]))

(deftest display-names
  (is (= "main" (di/display-name :main)))
  (is (= "player.x" (di/display-name :player/x)))
  (is (= "player.x" (di/display-name "player.x"))))

(deftest a-lens-from-the-variables
  (let [fields (di/lens-fields mem)]
    (is (= [[:frame [:u8 0]]
            [:score [:u16 1]]
            [:state [:enum 3 {0 :title 1 :playing}]]
            [:player [:struct 4 [:x :u8 :y :u16]]]
            [:enemies.x [:array 0x300 4 :u8]]
            [:enemies.hp [:array 0x304 4 :u8]]]
           fields)
        "in order; hidden variables left out")
    (testing "and the lens reads a snapshot's RAM"
      (let [ram (assoc (vec (repeat 0x800 0)) 0 7, 1 0x34, 2 0x12, 3 1, 4 100, 5 0x01, 6 0x02, 0x305 9)
            v (lens/interpret fields {:ram ram})]
        (is (= {:frame 7 :score 0x1234 :state :playing :player {:x 100 :y 0x0201}
                :enemies.x [0 0 0 0] :enemies.hp [0 9 0 0]}
               v))))))

(deftest fixed-point-variables
  (let [m (memory/allocate [[:speed :s8.8] [:player {:x :u8.8 :hp :u8}]])
        fields (di/lens-fields m)
        ram (assoc (vec (repeat 0x800 0)) 0 0x80, 1 0xFF, 2 0x40, 3 100, 4 7)
        t (di/symbol-table {:labels {} :memory m})]
    (is (= [[:speed [:s8.8 0]] [:player [:struct 2 [:x :u8.8 :hp :u8]]]] fields))
    (is (= {:speed -0.5 :player {:x 100.25 :hp 7}} (lens/interpret fields {:ram ram})))
    (is (= ["speed" "speed+1" "player.x" "player.x+1" "player.hp"] (map #(di/name-at t %) (range 5))))
    (testing "changing one writes both bytes"
      (is (= [{:space :cpu :addr 0 :value 0x80} {:space :cpu :addr 1 :value 0x01}]
             (lens/writes {:ram ram} [:s8.8 0] 1.5)))
      (is (= [{:space :cpu :addr 0 :value 0xC0} {:space :cpu :addr 1 :value 0xFF}]
             (lens/writes {:ram ram} [:s8.8 0] "-0.25")))
      (is (lens/editable? [:u8.8 0])))))

(deftest names-for-addresses
  (let [t (di/symbol-table {:labels {:main 0xC000 :nmi 0xC010} :memory mem})]
    (is (= "frame" (di/name-at t 0)))
    (is (= ["score" "score+1"] [(di/name-at t 1) (di/name-at t 2)]))
    (is (= ["player.x" "player.y" "player.y+1"] (map #(di/name-at t %) [4 5 6])))
    (is (= ["enemies.x" "enemies.x+3" "enemies.hp" "enemies.hp+1"] (map #(di/name-at t %) [0x300 0x303 0x304 0x305])))
    (is (= ["oam" "oam+255"] [(di/name-at t 0x200) (di/name-at t 0x2FF)]) "hidden from the lens, still named")
    (is (= "main" (di/name-at t 0xC000)))
    (is (= "main" (di/label-at t 0xC000)))
    (is (nil? (di/label-at t 0)) "a variable isn't a label")
    (is (nil? (di/name-at t 0x700)))
    (testing "looking a name up"
      (is (= 0xC010 (di/address t :nmi)))
      (is (= 5 (di/address t :player/y)))
      (is (= 5 (di/address t "player.y")))
      (is (= 0x304 (di/address t :enemies/hp)))
      (is (nil? (di/address t :nope)))
      (is (nil? (di/address nil :nmi))))))

(deftest hardware-names-need-no-table
  (is (= "PPUCTRL" (di/name-at nil 0x2000)))
  (is (= "PPUSTATUS" (di/name-at nil 0x2002)))
  (is (not= (di/name-at nil 0x4017 false) (di/name-at nil 0x4017 true)) "$4017 is two registers")
  (is (nil? (di/name-at nil 0x8000)) "ROM isn't a register")
  (is (nil? (di/name-at nil 0x0010))))

(deftest disassembly-with-names
  (let [{:keys [prg] :as built} (hello/build)
        t (di/symbol-table built)
        name-of (di/namer t)
        peek #(get prg (- % 0xC000))
        lines (fn [label n] (mapv #(disasm/text % name-of) (disasm/disassemble peek (di/address t label) n)))]
    (is (= ["SEI" "CLD" "LDX #$40" "STX APU_FRAME"] (lines :reset 4)) "a hardware register, as written")
    (is (= ["JSR read-controller" "JSR move-player" "JSR draw-player" "LDA frame"] (lines :main 4))
        "subroutines, and a variable")
    (is (= ["LDA buttons" "STA prev-buttons" "LDA #$01" "STA JOY1"] (lines :read-controller 4)))
    (is (= ["CMP frame" "BEQ wait-for-frame" "JMP main"] (lines :wait-for-frame 3)) "a branch to a label")
    (is (some #{"STA player.x+1"} (lines :move-player 12)) "a struct's field (its second byte)")
    (is (some #{"STA oam,X"} (lines :reset 40)))
    (testing "a subroutine's description comes along"
      (is (re-find #"^Move the player" (di/doc-at t (di/address t :move-player))))
      (is (= "Counts frames; the NMI handler adds 1" (di/doc-at t (di/address t :frame))))
      (is (nil? (di/doc-at t (di/address t :main)))))
    (testing "without names it's as before"
      (is (= "STX $4017" (nth (mapv disasm/text (disasm/disassemble peek 0xC000 4)) 3))))))
