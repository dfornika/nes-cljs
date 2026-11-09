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

(defn draw!
  "Copy an emulated frame onto the canvas.

  `frame` is a Uint32Array of 256x240 pixels, each 0x00BBGGRR. The canvas
  stores each pixel as the bytes R, G, B, A - which, read as a 32-bit
  number on a little-endian machine (nearly all of them), is 0xAABBGGRR.
  So the only thing missing is the alpha (opacity) byte."
  [^js frame]
  (when-let [{:keys [^js ctx image ^js pixels]} @target]
    (dotimes [i (* width height)]
      (aset pixels i (bit-or 0xFF000000 (aget frame i))))
    (.putImageData ctx image 0 0)))

(defn clear!
  "Fill the screen with black."
  []
  (when-let [{:keys [^js ctx]} @target]
    (set! (.-fillStyle ctx) "black")
    (.fillRect ctx 0 0 width height)))

(defn canvas
  "The screen. `:scale` is how many CSS pixels wide each NES pixel is."
  [{:keys [scale] :or {scale 2}}]
  [:canvas {:ref attach!
            :width width
            :height height
            :style {:width (* scale width)
                    :height (* scale height)
                    :display "block"
                    :background "black"
                    ;; Keep pixels sharp-edged instead of blurring them
                    ;; when the canvas is scaled up
                    :image-rendering "pixelated"}}])
