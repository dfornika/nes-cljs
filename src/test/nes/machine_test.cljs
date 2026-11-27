(ns nes.machine-test
  "Our games on NES.cljs's own console (nes.machine: our CPU and our PPU),
  side by side with jsnes: the same buttons pressed on the same frames,
  then the same RAM and the same picture after every frame, from power on.

  This is the CPU tab's \"Check against jsnes\" (nes.app.console/compare!),
  with the same rules (nes.inspect.compare): what's set up first so the two keep
  in step, and which pixels are left out because jsnes draws them
  differently from a real PPU."
  (:require [cljs.test :refer [deftest is testing]]
            [nes.sample-roms :as sample]
            [nes.inspect.compare :as compare]
            [nes.emulator :as emu]
            [nes.emulator.jsnes :as jsnes]
            [nes.emulator.machine :as machine]
            [zeropage.asm :as asm]))

(defn- pair
  "jsnes and our console, each with `game` (a sample game's name, or a
  .nes file's bytes) loaded, keeping their last
  pictures, set up to keep in step (as nes.app.console/compare! does)."
  [game]
  (let [rom (if (string? game) (sample/rom game) game)
        make (fn [create]
               (let [pic (atom nil)
                     e (create {:on-frame #(reset! pic (js/Uint32Array.from %))})]
                 (emu/load-rom! e rom)
                 {:emu e :pic pic}))
        j (make jsnes/create)
        o (make machine/create)]
    (set! (.. ^js (:nes (:emu j)) -ppu -clipToTvSize) false)
    (machine/match-jsnes! (:emu o))
    (dotimes [a 0x800] (emu/cpu-poke! (:emu o) a (emu/cpu-peek (:emu j) a)))
    {:jsnes j :ours o}))

(defn- play!
  "Run both for `n` frames holding `buttons`. Returns the first frame where
  they differ, as {:frame :ram :pixels}, or nil."
  [{:keys [jsnes ours]} n & buttons]
  (doseq [{e :emu} [jsnes ours] b buttons] (emu/button-down! e 1 b))
  (let [result (loop [k 0]
                 (when (< k n)
                   (emu/frame! (:emu jsnes))
                   (emu/frame! (:emu ours))
                   (let [j (:emu jsnes), o (:emu ours)
                         ram (compare/ram-diffs #(emu/cpu-peek o %) #(emu/cpu-peek j %))
                         px (compare/picture-diffs @(:pic ours) @(:pic jsnes)
                                                   (:mask (emu/ppu-registers j))
                                                   (bit-and (:x (:scroll (emu/ppu-state j))) 7)
                                                   (machine/lines-drawn o))]
                     (if (or (seq ram) (seq px))
                       {:frame k :ram (vec (take 5 ram))
                        :pixels (mapv (fn [i] [(mod i 256) (quot i 256)]) (take 5 px))}
                       (recur (inc k))))))]
    (doseq [{e :emu} [jsnes ours] b buttons] (emu/button-up! e 1 b))
    result))

(defn- play-ram!
  "As `play!`, but comparing only RAM: the first frame where it differs,
  or nil."
  [{:keys [jsnes ours]} n & buttons]
  (doseq [{e :emu} [jsnes ours] b buttons] (emu/button-down! e 1 b))
  (let [result (loop [k 0]
                 (when (< k n)
                   (emu/frame! (:emu jsnes))
                   (emu/frame! (:emu ours))
                   (let [ram (compare/ram-diffs #(emu/cpu-peek (:emu ours) %) #(emu/cpu-peek (:emu jsnes) %))]
                     (if (seq ram)
                       {:frame k :ram (vec (take 5 ram))}
                       (recur (inc k))))))]
    (doseq [{e :emu} [jsnes ours] b buttons] (emu/button-up! e 1 b))
    result))

(deftest hello-world
  (let [p (pair "hello")]
    (is (nil? (play! p 60)))))

(deftest star-hopper
  (let [p (pair "hopper")]
    (is (nil? (play! p 30)) "the title")
    (is (nil? (play! p 5 :start)))
    (is (nil? (play! p 60 :right)) "running")
    (is (nil? (play! p 20 :a :left)) "jumping")
    (is (nil? (play! p 120)) "and on")))

(deftest alienscape
  (let [p (pair "alienscape")]
    (is (nil? (play! p 30)))
    (is (nil? (play! p 5 :start)))
    (is (nil? (play! p 90 :right)))
    (is (nil? (play! p 30 :b)))))

(deftest star-tower
  (let [p (pair "tower")]
    (is (nil? (play! p 30)))
    (is (nil? (play! p 5 :start)))
    (is (nil? (play! p 60 :right)))
    (is (nil? (play! p 40 :up)))))

(deftest star-run
  (testing "scrolling, with columns streamed in during vertical blank"
    (let [p (pair "star-run")]
      (is (nil? (play! p 30)))
      (is (nil? (play! p 5 :start)))
      (is (nil? (play! p 300 :right)))
      (is (nil? (play! p 30 :a :right))))))

(def ^:private fs (js/require "fs"))

(deftest the-240p-test-suite
  ;; Mapper 2 (UxROM): 64 KB of program in four 16 KB banks, switched as
  ;; it moves between its menu and its tests. RAM is compared every frame,
  ;; which is what the mapper decides: the wrong bank would run the wrong
  ;; code. The pictures aren't compared here, because jsnes draws two
  ;; things in this ROM differently from our PPU, and which of them is
  ;; right is a separate question (TODO.md, under step 7): line 0, which
  ;; jsnes leaves black, and the 8x16 sprites from tile RAM that make up
  ;; the title screen's character, which jsnes draws scrambled.
  (let [p (pair (js/Uint8Array. (.readFileSync fs "resources/public/roms/240p/240pee.nes")))
        ^js m (.-m ^js (.-state (:emu (:ours p))))
        banks (atom #{})
        same-ram? (fn [n & buttons]
                    (let [d (apply play-ram! p n buttons)]
                      (swap! banks conj (.-bank ^js (:cart m)))
                      (nil? d)))]
    (is (same-ram? 90) "the title and the menu")
    (doseq [[n test] [[1 "the first test"] [2 "the second"] [4 "the fourth"]]]
      (dotimes [_ n] (is (same-ram? 4 :down)) (is (same-ram? 4)))
      (is (same-ram? 4 :a))
      (is (same-ram? 120) test)
      (is (same-ram? 4 :b) "and back to the menu")
      (is (same-ram? 30)))
    (is (< 1 (count @banks)) (str "it switched banks: " (sort @banks)))))

(defn- cnrom-test-rom
  "A tiny mapper 3 (CNROM) program, as an iNES file: it fills the screen
  with all 256 tiles, then every 16 frames switches to the next of four
  8 KB tile banks. Each bank's tiles are a different made-up pattern, so
  each switch changes the whole picture.

  Except the top row of tiles, which is tile 0, blank in every bank: jsnes
  leaves line 0 black where our PPU draws it, and which is right is an
  open question (TODO.md, under step 7)."
  []
  (let [ppu {:ctrl 0x2000 :mask 0x2001 :status 0x2002 :scroll 0x2005 :addr 0x2006 :data 0x2007
             :frames 0x00}
        set-addr (fn [a] [[:lda [:imm (bit-shift-right a 8)]] [:sta :addr]
                          [:lda [:imm (bit-and a 0xFF)]] [:sta :addr]])
        program [[:label :reset]
                 [:sei] [:cld] [:ldx [:imm 0xFF]] [:txs]
                 [:lda [:imm 0]] [:sta :frames]
                 ;; wait for the PPU to warm up: two vblanks
                 [:bit :status]
                 [:label :vblank-1] [:bit :status] [:bpl :vblank-1]
                 [:label :vblank-2] [:bit :status] [:bpl :vblank-2]
                 ;; all 32 palette entries
                 (set-addr 0x3F00)
                 [:ldx [:imm 0]]
                 [:label :palette] [:lda :colours :x] [:sta :data] [:inx] [:cpx [:imm 32]] [:bne :palette]
                 ;; nametable 0 and its attributes (1 KB): a row of tile 0,
                 ;; then 0, 1, ... 255 over and over
                 (set-addr 0x2000)
                 [:lda [:imm 0]] [:ldx [:imm 32]]
                 [:label :top-row] [:sta :data] [:dex] [:bne :top-row]
                 [:ldy [:imm 3]]
                 [:label :page] [:ldx [:imm 0]]
                 [:label :tile] [:txa] [:sta :data] [:inx] [:bne :tile]
                 [:dey] [:bne :page]
                 [:label :last-part] [:txa] [:sta :data] [:inx] [:cpx [:imm 224]] [:bne :last-part]
                 ;; turn the screen on in vblank, as games do
                 [:label :vblank-3] [:bit :status] [:bpl :vblank-3]
                 [:lda [:imm 0]] [:sta :scroll] [:sta :scroll]
                 [:lda [:imm 0x80]] [:sta :ctrl]          ; NMI on
                 [:lda [:imm 0x0A]] [:sta :mask]          ; background on
                 [:label :forever] [:jmp :forever]
                 [:label :nmi]
                 [:inc :frames]
                 [:lda :frames] [:lsr :a] [:lsr :a] [:lsr :a] [:lsr :a] [:and [:imm 3]]
                 ;; write the bank number over a byte that holds it already
                 ;; (on a real board, ROM and mapper both answer the write)
                 [:tax] [:lda :banks :x] [:sta :banks :x]
                 [:rti]
                 [:label :banks] [:db 0 1 2 3]
                 [:label :colours] [:db (repeat 2 [0x0F 0x16 0x2A 0x12 0x0F 0x27 0x1A 0x02
                                                   0x0F 0x30 0x21 0x14 0x0F 0x19 0x28 0x06])]
                 [:org 0xFFFA] [:dw :nmi :reset :nmi]]
        prg (:bytes (asm/assemble program {:org 0xC000 :symbols ppu}))
        ;; 8 KB a bank: 512 tiles (the screen uses the first 256)
        chr (for [b (range 4), t (range 512), plane (range 2), row (range 8)]
              (if (zero? t)
                0
                (bit-and 0xFF (if (zero? plane)
                              (+ (* t 7) (* row 13) (* b 61))
                              (bit-xor (+ (* t 3) (* row 5) (* b 101)) 0x55)))))]
    (assert (= 0x4000 (count prg)))
    (assert (= (* 4 0x2000) (count chr)))
    (js/Uint8Array.from (clj->js (concat [0x4E 0x45 0x53 0x1A 1 4 0x30 0 0 0 0 0 0 0 0 0] prg chr)))))

(deftest cnrom
  ;; Mapper 3 (CNROM): the tiles switch, so this compares the pictures
  ;; (and RAM) with jsnes every frame, through all four tile banks and
  ;; back to the first. Each look is taken between two switches (a
  ;; picture shows the bank from before the frame's NMI).
  (let [p (pair (cnrom-test-rom))
        ^js m (.-m ^js (.-state (:emu (:ours p))))
        seen (atom {})]                         ; bank -> the picture's hash
    (is (nil? (play! p 8)) "the first 8 frames")
    (dotimes [i 5]
      (is (nil? (play! p 16)) (str "16 more frames, " (inc i) " switches in"))
      (swap! seen assoc (.-bank ^js (:cart m)) (hash (vec @(:pic (:ours p))))))
    (is (= #{0 1 2 3} (set (keys @seen))) "it used all four tile banks")
    (is (= 4 (count (set (vals @seen)))) "and each looked different")))
