(ns nes.console
  "The virtual console: one emulator, the cartridge in it, and the loop
  that keeps it running.

  The emulator itself lives in a plain atom, outside the Reagent `db`, so
  that running it 60 times a second doesn't re-render the page. Only
  things the page shows (what ROM is loaded, whether it's running) go in
  `db`."
  (:require [reagent.core :as r]
            [nes.audio :as audio]
            [nes.breakpoints :as bp]
            [nes.emulator :as emu]
            [nes.emulator.jsnes :as jsnes]
            [nes.rom :as rom]
            [nes.screen :as screen]
            [nes.state :refer [db]]
            [nes.zip :as zip]))

(defonce ^:private emulator (atom nil))

;; Whether to watch the CPU's bus (for the schematic). Kept here so a newly
;; loaded emulator gets the same setting.
(defonce ^:private bus-monitor? (atom false))

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

;; When the bus monitor is on: what happened on the bus since the previous
;; refresh, and a rolling log of I/O register accesses.
(defonce bus-activity (r/atom nil))
(defonce bus-log (r/atom []))
(def ^:private bus-log-size 200)
(defonce ^:private last-sample-ms (atom nil))

(defn- sample-bus!
  "Collect the bus activity since the last sample. `kind` is :step if
  it covers exactly one step taken while paused, otherwise :tick."
  [kind]
  (when-let [e @emulator]
    (when-let [activity (emu/take-bus-activity! e)]
      (let [now (js/performance.now)
            elapsed (if-let [t @last-sample-ms] (- now t) 0)
            frame (:frame (emu/ppu-state e))]
        (reset! last-sample-ms now)
        (reset! bus-activity (assoc activity :kind kind :elapsed-ms elapsed))
        (when (seq (:events activity))
          (swap! bus-log (fn [log]
                           (let [log (into log (map #(assoc % :frame frame)) (:events activity))]
                             (if (> (count log) bus-log-size)
                               (subvec log (- (count log) bus-log-size))
                               log)))))))))

(defn monitor-bus!
  "Turn bus monitoring on or off, now and for ROMs loaded later."
  [on?]
  (reset! bus-monitor? on?)
  (reset! last-sample-ms nil)
  (when-let [e @emulator] (emu/monitor-bus! e on?)))

(defn refresh-inspectors!
  ([] (refresh-inspectors! :tick))
  ([kind]
   (when @bus-monitor? (sample-bus! kind))
   (swap! inspect-tick inc)))

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
;; nes.input (which depends on this namespace, so can't be called directly).
(defonce input-poller (atom nil))

(declare stop-at-breakpoint!)

(defn- tick [ts]
  (when (running?)
    (let [{:keys [last-ts behind-ms]} @clock
          s (speed)
          elapsed (* s (if last-ts (- ts last-ts) frame-ms))
          behind (min (+ behind-ms elapsed) (* max-frames-per-tick (max 1 s) frame-ms))
          frames (js/Math.floor (/ behind frame-ms))]
      (try
        (when-let [poll @input-poller] (poll))
        (if-let [hit (loop [n frames]
                       (when (pos? n)
                         (or (emu/frame! @emulator) (recur (dec n)))))]
          (stop-at-breakpoint! hit)
          (do
            (when (>= (- ts @last-inspect) inspect-interval-ms)
              (reset! last-inspect ts)
              (refresh-inspectors!))
            (swap! clock assoc :last-ts ts :behind-ms (- behind (* frames frame-ms)))
            (schedule!)))
        (catch :default e
          (js/console.error e)
          (swap! db assoc :status :crashed :error (ex-message e)))))))

;; ---------------------------------------------------------------------------
;; Controls

(defn pause! []
  (when-let [id (:raf-id @clock)] (js/cancelAnimationFrame id))
  (when (running?) (swap! db assoc :status :paused))
  (audio/clear!)
  (refresh-inspectors!))

(defn resume! []
  (when (and @emulator (#{:paused} (:status @db)))
    (swap! db assoc :status :running :break nil)
    (swap! clock assoc :last-ts nil :behind-ms 0)
    (schedule!)))

;; ---------------------------------------------------------------------------
;; Breakpoints
;;
;; The list lives in `db` (so the page shows it) under :breakpoints, and is
;; handed to the emulator whenever it changes. See nes.breakpoints.

(defn breakpoints [] (:breakpoints @db []))

(defn- sync-breakpoints! []
  (when-let [e @emulator]
    (emu/set-breakpoints! e (breakpoints))))

(defonce ^:private breakpoint-watch
  (do (add-watch db ::breakpoints
                 (fn [_ _ old new]
                   (when-not (identical? (:breakpoints old) (:breakpoints new))
                     (sync-breakpoints!))))
      true))

(defn add-breakpoint!
  "Add a breakpoint (a map with at least :kind and :addr, see
  nes.breakpoints). Returns its id."
  [b]
  (let [id (inc (:next-breakpoint-id @db 0))]
    (swap! db (fn [d]
                (-> d
                    (assoc :next-breakpoint-id id)
                    (update :breakpoints (fnil conj [])
                            (merge {:enabled? true :hits 0} b {:id id})))))
    id))

(defn update-breakpoint! [id f & args]
  (swap! db update :breakpoints
         (fn [bps] (mapv #(if (= id (:id %)) (apply f % args) %) bps))))

(defn remove-breakpoint! [id]
  (swap! db update :breakpoints (fn [bps] (filterv #(not= id (:id %)) bps))))

(defn clear-breakpoints! []
  (swap! db assoc :breakpoints []))

(defn toggle-exec-breakpoint!
  "Add an execute breakpoint at `addr`, or remove the one that's there."
  [addr]
  (if-let [b (some #(when (and (= :exec (:kind %)) (= addr (:addr %)) (nil? (:end %))) %)
                   (breakpoints))]
    (remove-breakpoint! (:id b))
    (add-breakpoint! {:kind :exec :addr addr})))

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
  (when-let [id (get-in hit [:breakpoint :id])]
    (update-breakpoint! id update :hits (fnil inc 0)))
  (swap! db assoc :status :paused :break hit)
  (audio/clear!)
  (show-picture-so-far! @emulator)
  (refresh-inspectors!))

(defn break-reason
  "Why the emulator last stopped at a breakpoint, in words, or nil."
  []
  (some-> (:break @db) bp/describe-hit))

(defn while-paused!
  "Run `f` on the emulator if it's paused, then refresh the inspectors.
  Returns true if `f` ran."
  [f]
  (when (and @emulator (= :paused (:status @db)))
    ;; Throw away activity from before this step, so the sample taken
    ;; afterwards covers exactly this step.
    (when @bus-monitor? (emu/take-bus-activity! @emulator))
    ;; whatever stopped us before is old news after a step
    (swap! db assoc :break nil)
    (try
      (f @emulator)
      (show-picture-so-far! @emulator)
      (catch :default e
        (js/console.error e)
        (swap! db assoc :status :crashed :error (ex-message e))))
    (refresh-inspectors! :step)
    true))

(defn step-frame!
  "Run until the end of the current frame (or a breakpoint), then stay
  paused."
  []
  (while-paused! (fn [e]
                   (when-let [hit (emu/frame! e)]
                     (when-let [id (get-in hit [:breakpoint :id])]
                       (update-breakpoint! id update :hits (fnil inc 0)))
                     (swap! db assoc :break hit)))))

(defn step-instruction!
  "Run exactly one CPU instruction, then stay paused."
  []
  (while-paused! emu/step-instruction!))

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
  (while-paused! step-scanline-on!))

(defn reset-console! []
  (when-let [e @emulator]
    (emu/press-reset! e)
    (refresh-inspectors!)
    (when (= :crashed (:status @db))
      (swap! db assoc :status :paused :error nil)
      (resume!))))

(defn write!
  "Apply `writes` (a seq of {:space :cpu/:rom :addr :value}, as from
  nes.lens/writes) to the running game, then refresh the inspectors.
  :cpu writes go to RAM (other addresses can't be poked and are skipped);
  :rom writes patch the cartridge. Returns the writes that worked."
  [writes]
  (when-let [e @emulator]
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

(defn download-rom!
  "Save the cartridge, with any changes made to it, as a .nes file."
  []
  (when-let [e @emulator]
    (let [name (-> (get-in @db [:rom :name] "game.nes") (.replace (js/RegExp. "\\.nes$" "i") ""))
          url (js/URL.createObjectURL (js/Blob. #js [(emu/rom-file e)]
                                                #js {:type "application/octet-stream"}))
          a (js/document.createElement "a")]
      (set! (.-href a) url)
      (set! (.-download a) (str name " (patched).nes"))
      (.click a)
      (js/setTimeout #(js/URL.revokeObjectURL url) 1000))))

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
        (emu/set-audio-sink! e on-audio-sample)
        (emu/load-rom! e bytes)
        (emu/monitor-bus! e @bus-monitor?)
        (emu/set-breakpoints! e (breakpoints))
        (reset! emulator e)
        (reset! bus-log [])
        (reset! bus-activity nil)
        (swap! db assoc
               :rom {:name rom-name :size (.-length bytes) :header header
                     :sha1 (rom/rom-hash bytes)}
               :status :paused
               :error nil)
        (resume!))
      (catch :default err
        (screen/clear!)
        (reset! emulator nil)
        (swap! db assoc :rom nil :status :empty
               :error (str "Couldn't load " rom-name ": " (ex-message err)))))
    (swap! db assoc :error (str rom-name " isn't an iNES (.nes) file."))))

(defn- strip-gz [name] (.replace name (js/RegExp. "\\.gz$" "i") ""))

;; The last zip file loaded, if it had more than one ROM in it, so the
;; others can be loaded from the Cartridge tab: {:name :bytes :roms}
(defonce ^:private archive (atom nil))

(defn- fail! [message]
  (swap! db assoc :error message))

(defn load-from-archive!
  "Load ROM `entry-name` from the zip loaded last."
  [entry-name]
  (when-let [{:keys [name bytes roms]} @archive]
    (when-let [entry (some #(when (= entry-name (:name %)) %) roms)]
      (-> (zip/extract bytes entry)
          (.then (fn [rom]
                   (load-bytes! (last (.split (:name entry) "/")) rom)
                   (when (:rom @db)
                     (swap! db update :rom assoc
                            :archive name
                            :archive-roms (mapv :name roms)
                            :archive-entry (:name entry)))))
          (.catch #(fail! (str "Couldn't unzip " entry-name ": " (ex-message %))))))))

(defn load-any!
  "Load a ROM from `bytes` (a Uint8Array) called `file-name`: a .nes file,
  or a .zip or .gz with one inside."
  [file-name ^js bytes]
  (cond
    (zip/zip? bytes)
    (try
      (let [roms (zip/roms (zip/entries bytes))]
        (if (empty? roms)
          (fail! (str "There's no .nes file in " file-name "."))
          (do (reset! archive {:name file-name :bytes bytes :roms roms})
              (load-from-archive! (:name (first roms))))))
      (catch :default e
        (fail! (str "Couldn't read " file-name ": " (ex-message e)))))

    (zip/gzip? bytes)
    (-> (zip/gunzip bytes)
        (.then #(load-bytes! (strip-gz file-name) %))
        (.catch #(fail! (str "Couldn't decompress " file-name ": " (ex-message %)))))

    (zip/other-archive bytes)
    (fail! (str file-name " is a " (zip/other-archive bytes) " archive, which can't be opened here. "
                "Extract the .nes file from it first (or use a .zip)."))

    :else (load-bytes! file-name bytes)))

(defn load-file!
  "Load a ROM from a browser File (from a file picker or drag-and-drop):
  a .nes file, or a .zip or .gz with one inside."
  [^js file]
  (-> (.arrayBuffer file)
      (.then #(load-any! (.-name file) (js/Uint8Array. %)))))

(defn load-url!
  "Load a ROM from a URL (.nes, .zip or .gz)."
  [url]
  (-> (js/fetch url)
      (.then (fn [^js resp]
               (if (.-ok resp)
                 (.arrayBuffer resp)
                 (throw (js/Error. (str "HTTP " (.-status resp)))))))
      (.then #(load-any! (last (.split url "/")) (js/Uint8Array. %)))
      (.catch #(swap! db assoc :error (str "Couldn't fetch " url ": " (ex-message %))))))
