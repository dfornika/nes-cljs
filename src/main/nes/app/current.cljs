(ns nes.app.current
  "The emulator the page is running now, and the counters that tell the
  views when to look at it again.

  Most views only read the game: memory, tiles, sprites, the PPU's
  registers. They need these and nothing else, so they don't depend on the
  run loop (nes.app.console), which is what sets them.

  The emulator lives in a plain atom, outside the Reagent `db`, so that
  running it 60 times a second doesn't re-render the page."
  (:require [reagent.core :as r]))

(defonce ^:private emulator (atom nil))

(defn emulator-instance
  "The emulator running now (see nes.emulator), or nil if no game is
  loaded."
  []
  @emulator)

(defn set-emulator!
  "Make `e` the emulator the page runs and shows (nil for none). For
  nes.app.console, which loads games."
  [e]
  (reset! emulator e))

;; The views that show the emulator's memory deref this, and redraw when
;; it changes. nes.app.console bumps it about 10 times a second while the
;; game runs, and whenever something happens while it's paused (a step,
;; a reset, a load), rather than every frame.
(defonce inspect-tick (r/atom 0))

;; Bumped after every frame, but only while something needs to keep up
;; with every frame (an overlay is showing: see nes.ui.overlay).
(defonce frame-tick (r/atom 0))
