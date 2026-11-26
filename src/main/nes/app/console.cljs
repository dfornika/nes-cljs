(ns nes.app.console
  "The console the page drives: putting a game in, the loop that keeps it
  running, and the controls (pause, run, step, reset, the buttons).

  The emulator it runs is in nes.app.current, where the views read it.
  The other things the page does with a running game have namespaces of
  their own, which this one calls at the right moments: the breakpoint
  list (nes.app.breakpoints), checking against jsnes (nes.app.checking)
  and watching the bus (nes.app.bus-monitor). Getting a game's bytes from
  a file or a URL is nes.app.loading.

  Only what the page shows (which ROM is loaded, whether it's running)
  goes in `db`."
  (:require [nes.app.audio :as audio]
            [nes.app.breakpoints :as breaks]
            [nes.app.bus-monitor :as bus]
            [nes.app.checking :as checking]
            [nes.app.current :as current]
            [nes.app.screen :as screen]
            [nes.app.state :refer [db]]
            [nes.emulator :as emu]
            [nes.emulator.jsnes :as jsnes]
            [nes.emulator.machine :as machine]
            [nes.rom :as rom]
            [reagent.core :as r]
            [zeropage.disasm :as disasm]))

;; ---------------------------------------------------------------------------
;; Settings that outlast a game: each new emulator is set up with them

;; Whether to record the code/data log (see nes.code-log)
(defonce ^:private code-logging? (atom false))

;; Which CPU runs the game: :jsnes or :cljs (see nes.emulator/CpuChoice)
(defonce ^:private cpu-choice (atom :jsnes))

;; Which emulator runs the game: :jsnes, or :nes-cljs (NES.cljs's own
;; console, nes.machine: our CPU and PPU)
(defonce ^:private machine-choice (atom :jsnes))

;; The ROM loaded last, as loaded, so switching emulators can load it again
(defonce ^:private last-rom (atom nil))

(defn log-code!
  "Start or stop recording the code/data log, now and for ROMs loaded later."
  [on?]
  (reset! code-logging? on?)
  (when-let [e (current/emulator-instance)] (emu/log-code! e on?))
  (swap! db assoc :code-logging? on?))

(defn use-cpu!
  "Run the game's CPU with :jsnes or :cljs (NES.cljs's own), now and for
  ROMs loaded later."
  [which]
  (reset! cpu-choice which)
  (when-let [e (current/emulator-instance)] (emu/use-cpu! e which))
  (swap! db assoc :cpu which))

(defn machine-in-use
  "Which emulator runs games: :jsnes or :nes-cljs."
  []
  @machine-choice)

(defn clear-code-log! []
  (when-let [e (current/emulator-instance)] (emu/clear-code-log! e)))

(defn code-log
  "The code/data log so far (see nes.emulator/code-log), or nil."
  []
  (some-> (current/emulator-instance) emu/code-log))

;; ---------------------------------------------------------------------------
;; Telling the views to look again (see nes.app.current/inspect-tick)

(def ^:private inspect-interval-ms 100)

(defonce ^:private last-inspect (atom 0))

(defn refresh-inspectors!
  "Tell the views the game has changed. `kind` is :step after one step
  taken while paused, otherwise :tick."
  ([] (refresh-inspectors! :tick))
  ([kind]
   (bus/sample! kind)
   (swap! current/inspect-tick inc)))

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

;; ---------------------------------------------------------------------------
;; Speed
;;
;; Slow motion and fast-forward: at speed 2 we run twice as many frames per
;; second of real time. Sound is muted at any speed but 1 (sped-up or
;; slowed-down sound would need resampling; for now it's just switched off).

(def speeds [0.25 0.5 1 2 4])

(defonce ^:private sound-live? (atom true))

(defn speed [] (or (:speed @db) 1))

(defn set-speed!
  "Run at `s` times normal speed (see `speeds`)."
  [s]
  (swap! db assoc :speed s)
  (reset! sound-live? (= s 1))
  (audio/clear!)
  s)

(defn- on-audio-sample [left right]
  (when @sound-live? (audio/on-sample! left right)))

;; Something to call before each batch of frames, to read gamepads. Set by
;; nes.app.input (which depends on this namespace, so can't be called directly).
(defonce input-poller (atom nil))

;; ---------------------------------------------------------------------------
;; Stopping

(defn- show-picture-so-far!
  "After stepping: if the PPU is partway through the picture, show it
  as far as it's drawn, with a marker where it's drawing. Otherwise show
  the last finished frame."
  [e]
  (if-let [{:keys [pixels line]} (emu/picture-so-far! e)]
    (screen/draw-partial! pixels line (:dot (emu/ppu-state e)))
    (screen/redraw!)))

(defn- stop-at-breakpoint!
  "A breakpoint went off while running: pause there and say why."
  [hit]
  (breaks/count-hit! hit)
  (checking/stop! "a breakpoint stopped the game partway through a frame")
  (swap! db assoc :status :paused :break hit)
  (audio/clear!)
  (show-picture-so-far! (current/emulator-instance))
  (refresh-inspectors!))

(defn- stop-at-difference!
  "Checking against jsnes found a difference: pause, and show the CPU tab,
  where the difference is."
  []
  (swap! db assoc :status :paused :inspector-tab :cpu)
  (audio/clear!)
  (refresh-inspectors!))

;; ---------------------------------------------------------------------------
;; The loop

(defn- tick [ts]
  (when (running?)
    (let [{:keys [last-ts behind-ms]} @clock
          s (speed)
          elapsed (* s (if last-ts (- ts last-ts) frame-ms))
          behind (min (+ behind-ms elapsed) (* max-frames-per-tick (max 1 s) frame-ms))
          frames (js/Math.floor (/ behind frame-ms))
          e (current/emulator-instance)]
      (try
        (when-let [poll @input-poller] (poll))
        (if-let [hit (loop [n frames]
                       (when (pos? n)
                         (or (emu/frame! e)
                             (when (checking/check-frame!) :differs)
                             (recur (dec n)))))]
          (if (= hit :differs) (stop-at-difference!) (stop-at-breakpoint! hit))
          (do
            (when (and (pos? frames) (seq (:overlays @db)))
              (swap! current/frame-tick inc))
            (when (>= (- ts @last-inspect) inspect-interval-ms)
              (reset! last-inspect ts)
              (refresh-inspectors!))
            (swap! clock assoc :last-ts ts :behind-ms (- behind (* frames frame-ms)))
            (schedule!)))
        (catch :default err
          (js/console.error err)
          (swap! db assoc :status :crashed :error (ex-message err)))))))

;; ---------------------------------------------------------------------------
;; Controls

(defn pause! []
  (when-let [id (:raf-id @clock)] (js/cancelAnimationFrame id))
  (when (running?) (swap! db assoc :status :paused))
  (audio/clear!)
  (refresh-inspectors!))

;; The last few instructions run with Step instruction (decoded, see
;; zeropage.disasm), oldest first, so the CPU tab can show where you've just
;; been. Anything else that runs the game (Run, Step frame...) empties it:
;; too much has happened since to call them 'the last few'.
(defonce recent-steps (r/atom []))

(def recent-steps-kept 3)

(defn resume! []
  (when (and (current/emulator-instance) (#{:paused} (:status @db)))
    (reset! recent-steps [])
    (swap! db assoc :status :running :break nil)
    (swap! clock assoc :last-ts nil :behind-ms 0)
    (schedule!)))

(defn while-paused!
  "Run `f` on the emulator if it's paused, then refresh the inspectors.
  Returns true if `f` ran."
  [f]
  (when-let [e (and (= :paused (:status @db)) (current/emulator-instance))]
    ;; so the bus sample taken afterwards covers exactly this step
    (bus/discard!)
    ;; whatever stopped us before is old news after a step
    (swap! db assoc :break nil)
    (reset! recent-steps [])
    (try
      (f e)
      (show-picture-so-far! e)
      (catch :default err
        (js/console.error err)
        (swap! db assoc :status :crashed :error (ex-message err))))
    (refresh-inspectors! :step)
    true))

(defn step-frame!
  "Run until the end of the current frame (or a breakpoint), then stay
  paused."
  []
  (while-paused! (fn [e]
                   (if-let [hit (emu/frame! e)]
                     (do (breaks/count-hit! hit)
                         (checking/stop! "a breakpoint stopped the game partway through a frame")
                         (swap! db assoc :break hit))
                     (when (checking/check-frame!) (stop-at-difference!))))))

(defn step-instruction!
  "Run exactly one CPU instruction, then stay paused. The instruction is
  remembered in `recent-steps`."
  []
  (checking/stop! "the game was stepped an instruction at a time")
  (let [before @recent-steps
        instr (when-let [e (current/emulator-instance)]
                (disasm/decode #(emu/cpu-peek e %) (:pc (emu/cpu-state e))))]
    (when (while-paused! emu/step-instruction!)
      (reset! recent-steps (vec (take-last recent-steps-kept (conj before instr)))))))

(defn step-scanline-on!
  "Run instructions until the PPU moves on to another scanline (at most a
  frame's worth, in case it never does)."
  [e]
  (let [start (:scanline (emu/ppu-state e))]
    (loop [i 0]
      (when (and (< i 40000)
                 (not (emu/step-instruction! e))
                 (= start (:scanline (emu/ppu-state e))))
        (recur (inc i))))))

(defn step-scanline!
  "Run until the PPU starts the next scanline (about 114 CPU cycles), then
  stay paused."
  []
  (checking/stop! "the game was stepped a line at a time")
  (while-paused! step-scanline-on!))

(defn reset-console! []
  (checking/stop! "Reset was pressed")
  (when-let [e (current/emulator-instance)]
    (emu/press-reset! e)
    (reset! recent-steps [])
    (refresh-inspectors!)
    (when (= :crashed (:status @db))
      (swap! db assoc :status :paused :error nil)
      (resume!))))

(defn button-down! [player button]
  (when-let [e (current/emulator-instance)] (emu/button-down! e player button))
  (checking/button-down! player button))

(defn button-up! [player button]
  (when-let [e (current/emulator-instance)] (emu/button-up! e player button))
  (checking/button-up! player button))

;; ---------------------------------------------------------------------------
;; Changing the game by hand

(defn write!
  "Apply `writes` (a seq of {:space :cpu/:rom :addr :value}, as from
  nes.inspect.lens/writes) to the running game, then refresh the inspectors.
  :cpu writes go to RAM (other addresses can't be poked and are skipped);
  :rom writes patch the cartridge. Returns the writes that worked."
  [writes]
  (checking/stop! "memory was changed by hand")
  (when-let [e (current/emulator-instance)]
    (let [done (filterv (fn [{:keys [space addr value]}]
                          (case space
                            :cpu (emu/cpu-poke! e addr value)
                            :rom (emu/rom-poke! e addr value)
                            false))
                        writes)
          rom-changes (count (filter #(= :rom (:space %)) done))]
      (when (pos? rom-changes)
        (swap! db update-in [:rom :patched] (fnil + 0) rom-changes))
      (refresh-inspectors! :step)
      done)))

(defn poke-tiles!
  "Change bytes of tile graphics in the running game: `writes` is a seq
  of [ppu-address value] (see nes.inspect.chr/pixel-writes). Returns where the
  changes went: :rom (into the cartridge too), :live (only the PPU's
  memory), or nil if nothing was written. See emu/tile-poke!."
  [writes]
  (checking/stop! "tiles were changed by hand")
  (when-let [e (current/emulator-instance)]
    (let [results (doall (keep (fn [[addr value]] (emu/tile-poke! e addr value)) writes))
          in-rom (count (filter #{:rom} results))]
      (when (pos? in-rom)
        (swap! db update-in [:rom :patched] (fnil + 0) in-rom))
      (refresh-inspectors! :step)
      (cond (pos? in-rom) :rom
            (seq results) :live))))

(defn download-rom!
  "Save the cartridge, with any changes made to it, as a .nes file."
  []
  (when-let [e (current/emulator-instance)]
    (let [name (-> (get-in @db [:rom :name] "game.nes") (.replace (js/RegExp. "\\.nes$" "i") ""))
          url (js/URL.createObjectURL (js/Blob. #js [(emu/rom-file e)]
                                                #js {:type "application/octet-stream"}))
          a (js/document.createElement "a")]
      (set! (.-href a) url)
      (set! (.-download a) (str name " (patched).nes"))
      (.click a)
      (js/setTimeout #(js/URL.revokeObjectURL url) 1000))))

;; ---------------------------------------------------------------------------
;; Putting a game in

(defn load-bytes!
  "Insert the ROM in `bytes` (a Uint8Array) and start it running.
  `rom-name` is just for display. (nes.app.loading gets the bytes from
  files, URLs and zips.)"
  [rom-name ^js bytes]
  (pause!)
  (checking/stop! "another game was loaded")
  (reset! last-rom {:name rom-name :bytes bytes})
  (if-let [header (rom/parse-header bytes)]
    (try
      (let [on-frame (fn [pixels]
                       (screen/draw! pixels)
                       (checking/keep-picture! pixels))
            e ((if (= @machine-choice :nes-cljs) machine/create jsnes/create) {:on-frame on-frame})]
        (emu/set-audio-sink! e on-audio-sample)
        (emu/load-rom! e bytes)
        (bus/attach! e)
        (emu/set-breakpoints! e (breaks/breakpoints))
        (emu/log-code! e @code-logging?)
        (emu/use-cpu! e @cpu-choice)
        (current/set-emulator! e)
        (reset! recent-steps [])
        (swap! db assoc
               :rom {:name rom-name :size (.-length bytes) :header header
                     :sha1 (rom/rom-hash bytes)}
               :status :paused
               :overlays #{}
               :error nil)
        (resume!))
      (catch :default err
        (screen/clear!)
        (current/set-emulator! nil)
        (swap! db assoc :rom nil :status :empty
               :error (str "Couldn't load " rom-name ": " (ex-message err)))))
    (swap! db assoc :error (str rom-name " isn't an iNES (.nes) file."))))

(defn use-machine!
  "Run games on :jsnes or :nes-cljs (NES.cljs's own console), now and for
  ROMs loaded later. The game loaded now starts again from power on."
  [which]
  (reset! machine-choice which)
  (swap! db assoc :machine which)
  (when-let [{:keys [name bytes]} @last-rom]
    (load-bytes! name bytes)))

(defn compare!
  "Start the loaded game again on our console, with jsnes beside it, fed
  the same buttons; after every frame, compare their RAM and pictures (see
  nes.app.checking), and pause at the first difference."
  []
  (when-let [{:keys [name bytes]} @last-rom]
    (reset! machine-choice :nes-cljs)
    (swap! db assoc :machine :nes-cljs)
    (load-bytes! name bytes)
    (when-let [ours (current/emulator-instance)]
      (checking/start! ours bytes))))
