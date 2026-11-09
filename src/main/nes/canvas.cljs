(ns nes.canvas
  "A Reagent component for small pixel-art canvases, like the tile viewer."
  (:require [reagent.core :as r]))

(defn- repaint! [^js this]
  (let [{:keys [paint]} (r/props this)
        ^js state (.-pixelState this)]
    (when (and paint state)
      (paint (.-pixels state))
      (.putImageData (.-ctx state) (.-image state) 0 0))))

(defn pixel-canvas
  "A `width` x `height` canvas, scaled up by `scale` with sharp pixels.

  `paint` is called with a Uint32Array (one 0xAABBGGRR number per pixel)
  to fill in; it's called again every time this component re-renders, so
  pass something in the props that changes when the picture should
  (like the inspector tick).

  Other props (`:on-mouse-move`, `:style`...) go on the canvas element."
  [_props]
  (r/create-class
   {:display-name "pixel-canvas"
    :component-did-mount
    (fn [^js this]
      (let [{:keys [width height]} (r/props this)
            ^js canvas (.-canvasEl this)
            ctx (.getContext canvas "2d")
            image (.createImageData ctx width height)]
        (set! (.-pixelState this)
              #js {:ctx ctx
                   :image image
                   :pixels (js/Uint32Array. (.. image -data -buffer))})
        (repaint! this)))
    :component-did-update repaint!
    :reagent-render
    (fn [{:keys [width height scale style] :or {scale 1} :as props}]
      (let [^js this (r/current-component)]
        [:canvas (merge (dissoc props :paint :scale :tick)
                        {:ref #(set! (.-canvasEl this) %)
                         :width width
                         :height height
                         :style (merge {:width (* scale width)
                                        :height (* scale height)
                                        :display "block"
                                        :image-rendering "pixelated"}
                                       style)})]))}))
