(ns nes.ppu.render-jsnes-test
  "Our whole-frame renderer (nes.ppu.render) against jsnes's pictures.

  Play one of our games in jsnes. At the start of a frame (line 0, after
  vertical blank, when the game has set everything up for it) take
  jsnes's video memory, palette, sprites and registers, and draw the frame
  with nes.ppu.render. Let jsnes draw the same frame, and compare the two
  pictures pixel by pixel.

  Our games change nothing while a frame is drawn (they do all of it in
  vertical blank), so a whole-frame renderer should draw exactly what
  jsnes does, except where jsnes differs from a real PPU. The comparison
  skips those pixels, and says why."
  (:require [cljs.test :refer [deftest is testing]]
            [games.alienscape :as alienscape]
            [games.hello :as hello]
            [games.hopper :as hopper]
            [games.star-run :as star-run]
            [games.tower :as tower]
            [nes.emulator :as emu]
            [nes.emulator.jsnes :as jsnes]
            [nes.bytes :as bytes]
            [nes.ppu.core :as core]
            [nes.ppu.memory :as memory]
            [nes.ppu.registers :as r]
            [nes.ppu.render :as render]))

(defn- console [game]
  (let [frame (atom nil)
        e (jsnes/create {:on-frame #(reset! frame (js/Uint32Array.from %))})]
    (emu/load-rom! e (js/Uint8Array.from (clj->js (:rom (game)))))
    ;; jsnes blacks out the edges a TV would hide (8 pixels all round);
    ;; a PPU draws them
    (set! (.. ^js (:nes e) -ppu -clipToTvSize) false)
    {:emu e :frame frame}))

(defn- inputs
  "What nes.ppu.render needs, from jsnes as it is now."
  [e]
  (let [{:keys [ctrl mask]} (emu/ppu-registers e)
        {:keys [t x]} (emu/ppu-scroll e)]
    {:memory (reify memory/VideoMemory
               (video-read [_ addr] (emu/ppu-peek e addr))
               (video-write! [_ _ _] nil))
     :palette (js/Uint8Array.from (clj->js (for [i (range 32)] (emu/ppu-peek e (+ 0x3F00 i)))))
     :oam (js/Uint8Array.from (emu/oam e))
     :ctrl ctrl :mask mask :t t :x x}))

(defn- frames!
  "Run `n` frames, holding `buttons`."
  [{:keys [emu]} n & buttons]
  (doseq [b buttons] (emu/button-down! emu 1 b))
  (dotimes [_ n] (emu/frame! emu))
  (doseq [b buttons] (emu/button-up! emu 1 b)))

(defn- dot-by-dot
  "The same frame drawn by nes.ppu.core, the PPU that goes dot by dot,
  from the same state. Returns its picture."
  [{:keys [memory palette oam ctrl mask t x]}]
  (let [ppu (core/create)]
    (bytes/copy-into! (:palette ppu) palette 0)
    (bytes/copy-into! (:oam ppu) oam 0)
    (doseq [[reg v] [[r/CTRL ctrl] [r/MASK mask] [r/T t] [r/X x]]] (r/set-reg! ppu reg v))
    (core/frame! ppu memory)))

(defn- compare-frame!
  "Draw the next frame both ways. Returns the pixels that differ, as
  [x y ours theirs] (at most 10), or [] if none. Also checks the PPU that
  goes dot by dot draws what the whole-frame renderer does."
  [{:keys [emu frame]}]
  ;; on to line 0 of the next frame
  (loop [] (when-not (zero? (:scanline (emu/ppu-state emu))) (emu/step-instruction! emu) (recur)))
  (let [in (inputs emu)
        whole (:pixels (render/render in))
        _ (is (= (vec whole) (vec (dot-by-dot in))) "nes.ppu.core draws the same")
        ours (render/colors! (js/Uint32Array. (* 256 240)) whole (emu/master-palette emu))]
    (loop [] (when-not (emu/step-instruction! emu) (recur)))
    (let [theirs @frame
          ;; jsnes blacks out the left 8 pixels when either layer is
          ;; hidden there; a PPU shows the other layer, or the backdrop
          left (if (= 0x06 (bit-and (:mask in) 0x06)) 0 8)
          ;; jsnes draws 32 tiles a line, starting fine X pixels to the
          ;; left, so the last fine X pixels are left as the backdrop. A
          ;; PPU draws a 33rd tile there.
          right (- 256 (:x in))]
      (vec (take 10 (for [i (range (* 256 240))
                          :when (< (dec left) (mod i 256) right)
                          :let [a (bit-and (aget ours i) 0xFFFFFF) b (bit-and (aget theirs i) 0xFFFFFF)]
                          :when (not= a b)]
                      [(mod i 256) (quot i 256) (.toString a 16) (.toString b 16)]))))))

(deftest hello-world
  (let [c (console hello/build)]
    (frames! c 30)
    (is (= [] (compare-frame! c)))))

(deftest star-hopper
  (let [c (console hopper/build)]
    (frames! c 30)
    (is (= [] (compare-frame! c)) "the title")
    (frames! c 5 :start)
    (frames! c 40 :right)
    (is (= [] (compare-frame! c)) "playing")
    (frames! c 3 :a)
    (frames! c 6 :left)
    (is (= [] (compare-frame! c)) "in the air")))

(deftest alienscape
  (let [c (console alienscape/build)]
    (frames! c 30)
    (frames! c 5 :start)
    (frames! c 60 :right)
    (is (= [] (compare-frame! c)))))

(deftest star-tower
  (let [c (console tower/build)]
    (frames! c 30)
    (frames! c 5 :start)
    (frames! c 30 :right)
    (is (= [] (compare-frame! c)))))

(deftest star-run
  (testing "scrolled partway: the camera's fine X, two nametables, sprites for the score"
    (let [c (console star-run/build)]
      (frames! c 30)
      (frames! c 5 :start)
      (frames! c 90 :right)
      (is (= [] (compare-frame! c)))
      (frames! c 7 :right)
      (is (= [] (compare-frame! c)))
      (frames! c 160 :right)
      (is (= [] (compare-frame! c))))))
