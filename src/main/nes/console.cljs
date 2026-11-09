(ns nes.console
  "The virtual console: one emulator, the cartridge in it, and the loop
  that keeps it running.

  The emulator itself lives in a plain atom, outside the Reagent `db`, so
  that running it 60 times a second doesn't re-render the page. Only
  things the page shows (what ROM is loaded, whether it's running) go in
  `db`."
  (:require [reagent.core :as r]
            [nes.emulator :as emu]
            [nes.emulator.jsnes :as jsnes]
            [nes.rom :as rom]
            [nes.screen :as screen]
            [nes.state :refer [db]]))

(defonce ^:private emulator (atom nil))

(defn emulator-instance
  "The current emulator, for poking at from the REPL."
  []
  @emulator)

;; Inspector panels (tile viewer, memory watch...) show the emulator's
;; memory, which changes constantly. Rather than redraw them every frame,
;; they deref this, and we bump it about 10 times a second while running,
;; and whenever something happens while paused (step, reset, load).
(defonce inspect-tick (r/atom 0))

(def ^:private inspect-interval-ms 100)

(defonce ^:private last-inspect (atom 0))

(defn refresh-inspectors! []
  (swap! inspect-tick inc))

;; ---------------------------------------------------------------------------
;; Timing
;;
;; The NES draws about 60.1 frames per second. The browser asks us to draw
;; whenever the monitor refreshes, which might be 60, 120 or 144 times a
;; second. So on each refresh we work out how much time has passed and run
;; however many NES frames fit in it.

(def frame-ms (/ 1000 60.0988))

;; If the tab was in the background (where the browser stops calling us)
;; we don't want to run hundreds of frames to catch up when it comes back.
(def ^:private max-frames-per-tick 4)

(defonce ^:private clock (atom {:last-ts nil :behind-ms 0 :raf-id nil}))

(declare tick)

(defn- schedule! []
  ;; Call `tick` through its name (not as a captured value) so hot
  ;; reloading a new version of `tick` takes effect on the next frame.
  (swap! clock assoc :raf-id (js/requestAnimationFrame (fn [ts] (tick ts)))))

(defn running? []
  (= :running (:status @db)))

(defn- tick [ts]
  (when (running?)
    (let [{:keys [last-ts behind-ms]} @clock
          elapsed (if last-ts (- ts last-ts) frame-ms)
          behind (min (+ behind-ms elapsed) (* max-frames-per-tick frame-ms))
          frames (js/Math.floor (/ behind frame-ms))]
      (try
        (dotimes [_ frames] (emu/frame! @emulator))
        (when (>= (- ts @last-inspect) inspect-interval-ms)
          (reset! last-inspect ts)
          (refresh-inspectors!))
        (swap! clock assoc :last-ts ts :behind-ms (- behind (* frames frame-ms)))
        (schedule!)
        (catch :default e
          (js/console.error e)
          (swap! db assoc :status :crashed :error (ex-message e)))))))

;; ---------------------------------------------------------------------------
;; Controls

(defn pause! []
  (when-let [id (:raf-id @clock)] (js/cancelAnimationFrame id))
  (when (running?) (swap! db assoc :status :paused))
  (refresh-inspectors!))

(defn resume! []
  (when (and @emulator (#{:paused} (:status @db)))
    (swap! db assoc :status :running)
    (swap! clock assoc :last-ts nil :behind-ms 0)
    (schedule!)))

(defn step-frame!
  "Run exactly one frame, then stay paused."
  []
  (when (and @emulator (= :paused (:status @db)))
    (emu/frame! @emulator)
    (refresh-inspectors!)))

(defn reset-console! []
  (when-let [e @emulator]
    (emu/press-reset! e)
    (refresh-inspectors!)
    (when (= :crashed (:status @db))
      (swap! db assoc :status :paused :error nil)
      (resume!))))

(defn button-down! [player button]
  (when-let [e @emulator] (emu/button-down! e player button)))

(defn button-up! [player button]
  (when-let [e @emulator] (emu/button-up! e player button)))

;; ---------------------------------------------------------------------------
;; Loading cartridges

(defn load-bytes!
  "Insert the ROM in `bytes` (a Uint8Array) and start it running.
  `rom-name` is just for display."
  [rom-name ^js bytes]
  (pause!)
  (if-let [header (rom/parse-header bytes)]
    (try
      (let [e (jsnes/create {:on-frame screen/draw!})]
        (emu/load-rom! e bytes)
        (reset! emulator e)
        (swap! db assoc
               :rom {:name rom-name :size (.-length bytes) :header header}
               :status :paused
               :error nil)
        (resume!))
      (catch :default err
        (screen/clear!)
        (reset! emulator nil)
        (swap! db assoc :rom nil :status :empty
               :error (str "Couldn't load " rom-name ": " (ex-message err)))))
    (swap! db assoc :error (str rom-name " isn't an iNES (.nes) file."))))

(defn load-file!
  "Load a ROM from a browser File (from a file picker or drag-and-drop)."
  [^js file]
  (-> (.arrayBuffer file)
      (.then #(load-bytes! (.-name file) (js/Uint8Array. %)))))

(defn load-url!
  "Load a ROM from a URL."
  [url]
  (-> (js/fetch url)
      (.then (fn [^js resp]
               (if (.-ok resp)
                 (.arrayBuffer resp)
                 (throw (js/Error. (str "HTTP " (.-status resp)))))))
      (.then #(load-bytes! (last (.split url "/")) (js/Uint8Array. %)))
      (.catch #(swap! db assoc :error (str "Couldn't fetch " url ": " (ex-message %))))))
