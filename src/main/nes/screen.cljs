(ns nes.screen
  "The game screen: a 256x240 canvas we copy each emulated frame onto.")

(def width 256)
(def height 240)

;; The canvas's drawing context, and a reusable image the size of the
;; screen. Set when the canvas appears on the page. Not a Reagent atom:
;; these change every frame and nothing on the page should re-render
;; because of them.
(defonce ^:private target (atom nil))

(defn- attach!
  "React calls this with the canvas element when it appears, and with nil
  when it goes away."
  [^js canvas]
  (if canvas
    (let [ctx (.getContext canvas "2d")
          image (.createImageData ctx width height)]
      (reset! target {:ctx ctx
                      :image image
                      ;; The same pixels viewed as 32-bit numbers, one
                      ;; per pixel, so we can copy a pixel in one step
                      :pixels (js/Uint32Array. (.. image -data -buffer))}))
    (reset! target nil)))

;; A copy of the last finished frame (0x00BBGGRR pixels), to fill in the
;; part of the picture the PPU hasn't redrawn yet when stepping.
(defonce ^:private last-frame (atom nil))

(defn draw!
  "Copy an emulated frame onto the canvas.

  `frame` is a Uint32Array of 256x240 pixels, each 0x00BBGGRR. The canvas
  stores each pixel as the bytes R, G, B, A - which, read as a 32-bit
  number on a little-endian machine (nearly all of them), is 0xAABBGGRR.
  So the only thing missing is the alpha (opacity) byte."
  [^js frame]
  (if-let [^js keep @last-frame]
    (when-not (identical? keep frame) (.set keep frame))
    (reset! last-frame (js/Uint32Array.from frame)))
  (when-let [{:keys [^js ctx image ^js pixels]} @target]
    (dotimes [i (* width height)]
      (aset pixels i (bit-or 0xFF000000 (aget frame i))))
    (.putImageData ctx image 0 0)))

(defn redraw!
  "Show the last finished frame again (after `draw-partial!`)."
  []
  (when-let [^js frame @last-frame]
    (draw! frame)))

(defn draw-partial!
  "Show a picture the PPU is partway through drawing: lines above `line`
  from `frame` (the frame in progress), the rest from the last finished
  frame, dimmed, and a red line with a white dot where the PPU is drawing
  now (`line` and `dot`)."
  [^js frame line dot]
  (when-let [{:keys [^js ctx image ^js pixels]} @target]
    (let [^js out pixels
          ^js prev @last-frame
          split (* line width)]
      (dotimes [i split]
        (aset out i (bit-or 0xFF000000 (aget frame i))))
      (loop [i split]
        (when (< i (* width height))
          ;; half brightness: halve each of R, G and B
          (aset out i (bit-or 0xFF000000
                              (if prev
                                (bit-and (unsigned-bit-shift-right (aget prev i) 1) 0x7F7F7F)
                                0)))
          (recur (inc i))))
      ;; the line being drawn, and the dot
      (dotimes [x width]
        (aset out (+ split x) 0xFF3030E0))
      (when (< dot width)
        (doseq [dy [-1 0 1] dx [-1 0 1]
                :let [x (+ dot dx) y (+ line dy)]
                :when (and (< -1 x width) (< -1 y height))]
          (aset out (+ (* y width) x) 0xFFFFFFFF)))
      (.putImageData ctx image 0 0))))

(defn frame-pixels
  "The pixels currently on the screen, as a Uint32Array of 256x240
  0xAABBGGRR numbers, or nil if the screen isn't showing. Read only."
  []
  (:pixels @target))

(defn clear!
  "Fill the screen with black."
  []
  (when-let [{:keys [^js ctx]} @target]
    (set! (.-fillStyle ctx) "black")
    (.fillRect ctx 0 0 width height)))

(defn canvas
  "The screen. With `:scale`, each NES pixel is that many CSS pixels wide.
  With `:fill? true`, the screen grows to fill the box it's in, keeping
  its shape (with black bars at the sides or top and bottom if needed)."
  [{:keys [scale fill?] :or {scale 2}}]
  [:canvas {:ref attach!
            :width width
            :height height
            :style (merge {:display "block"
                           :background "black"
                           ;; Keep pixels sharp-edged instead of blurring
                           ;; them when the canvas is scaled up
                           :image-rendering "pixelated"}
                          (if fill?
                            {:width "100%" :height "100%" :object-fit "contain"}
                            {:width (* scale width) :height (* scale height)}))}])
