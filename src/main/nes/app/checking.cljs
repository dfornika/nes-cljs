(ns nes.app.checking
  "Checking our emulator against jsnes, two ways (the CPU tab's buttons).

  As you play (`start!`): jsnes runs beside our console as a 'shadow'. It
  gets the same buttons, runs a frame after each of ours, and after every
  frame the two are compared (see nes.inspect.compare). nes.app.console
  calls `check-frame!` after each frame, and stops when they differ.
  Anything that changes one game and not the other (stepping, a reset, a
  poke) stops the checking, with `stop!`.

  The two CPUs (`compare-cpus!`): the loaded game run twice in jsnes from
  power on, once on jsnes's CPU and once on ours (nes.cpu.core).

  What the CPU tab shows goes in `db`: under :compare for the first,
  :cpu-compare for the second."
  (:require [nes.app.current :as current]
            [nes.app.state :refer [db]]
            [nes.emulator :as emu]
            [nes.emulator.jsnes :as jsnes]
            [nes.emulator.machine :as machine]
            [nes.inspect.compare :as compare]))

;; ---------------------------------------------------------------------------
;; As you play

;; While checking: {:emu jsnes, :ours and :theirs (atoms of their last
;; pictures), :frames (an atom: how many frames were the same)}
(defonce ^:private shadow (atom nil))

(defn compared-frames
  "How many frames have been the same so far, while checking; else nil."
  []
  (some-> @shadow :frames deref))

(defn stop!
  "Stop checking, saying `why` (in words). Does nothing if not checking."
  [why]
  (when-let [{:keys [frames]} @shadow]
    (reset! shadow nil)
    (swap! db assoc :compare {:stopped why :frames @frames})))

(defn start!
  "Start checking `ours` (our console, just loaded with `bytes`, the .nes
  file) against jsnes, from power on."
  [ours ^js bytes]
  (let [theirs (atom nil)
        them (jsnes/create {:on-frame #(reset! theirs (js/Uint32Array.from %))})]
    (emu/load-rom! them bytes)
    ;; set the two up to keep in step (see nes.inspect.compare)
    (set! (.. ^js (:nes them) -ppu -clipToTvSize) false)
    (machine/match-jsnes! ours)
    (dotimes [a 0x800] (emu/cpu-poke! ours a (emu/cpu-peek them a)))
    (reset! shadow {:emu them :ours (atom nil) :theirs theirs :frames (atom 0)})
    (swap! db assoc :compare {:running? true})))

(defn keep-picture!
  "Our console has drawn `pixels`: while checking, keep a copy to compare."
  [pixels]
  (when-let [{:keys [ours]} @shadow]
    (reset! ours (js/Uint32Array.from pixels))))

(defn button-down! [player button]
  (when-let [{e :emu} @shadow] (emu/button-down! e player button)))

(defn button-up! [player button]
  (when-let [{e :emu} @shadow] (emu/button-up! e player button)))

(defn check-frame!
  "Our console has just finished a frame: run jsnes's, and compare. Returns
  true (and stops checking, with the difference in `db`) if they differ."
  []
  (when-let [{:keys [emu ours theirs frames]} @shadow]
    (emu/frame! emu)
    (let [e (current/emulator-instance)
          mask (:mask (emu/ppu-registers emu))
          fine-x (bit-and (:x (:scroll (emu/ppu-state emu))) 7)
          drawn (machine/lines-drawn e)
          pixels (compare/picture-diffs @ours @theirs mask fine-x drawn)
          ram (compare/ram-diffs #(emu/cpu-peek e %) #(emu/cpu-peek emu %))]
      (if (and (empty? pixels) (empty? ram))
        (do (swap! frames inc) false)
        (do (reset! shadow nil)
            (swap! db assoc :compare
                   {:result {:frame (inc @frames)
                             :pixels (count pixels)
                             :ram ram
                             :ours @ours
                             :theirs @theirs
                             :diff (compare/difference-picture! (js/Uint32Array. (* 256 240))
                                                               @ours pixels mask fine-x drawn)}})
            true)))))

;; ---------------------------------------------------------------------------
;; The two CPUs

(defn- frame-hash
  "A quick fingerprint of a frame and the CPU's RAM, for comparing."
  [^js pixels ^js ram]
  (let [h (volatile! 17)]
    (dotimes [i (.-length pixels)] (vreset! h (bit-or (+ (* @h 31) (aget pixels i)) 0)))
    (dotimes [i 0x800] (vreset! h (bit-or (+ (* @h 31) (aget ram i)) 0)))
    @h))

(defn compare-cpus!
  "Run the loaded game twice from power on, once with each CPU, for
  `frames` frames with no buttons pressed, comparing the picture and RAM
  after every frame. Progress and the result go in `db` under
  :cpu-compare. Runs in small chunks, so the page stays responsive."
  [frames]
  (when-let [e (current/emulator-instance)]
    (let [bytes (emu/rom-file e)
          make (fn [which]
                 (let [last-frame (atom nil)
                       x (jsnes/create {:on-frame #(reset! last-frame (js/Uint32Array.from %))})]
                   (emu/load-rom! x bytes)
                   (emu/use-cpu! x which)
                   {:emu x :frame last-frame :ms 0}))
          runs (atom {:jsnes (make :jsnes) :cljs (make :cljs)})
          run-frame! (fn [k]
                       (let [{:keys [emu frame]} (@runs k)
                             t0 (js/performance.now)]
                         (emu/frame! emu)
                         (swap! runs update-in [k :ms] + (- (js/performance.now) t0))
                         (frame-hash @frame (.. ^js (:nes emu) -cpu -mem))))]
      (swap! db assoc :cpu-compare {:running? true :frame 0 :of frames})
      (letfn [(chunk [n]
                (let [result (loop [i 0 n n]
                               (cond
                                 (= n frames) {:same-for n}
                                 (= i 20) {:continue n}
                                 :else (let [a (run-frame! :jsnes) b (run-frame! :cljs)]
                                         (if (= a b) (recur (inc i) (inc n)) {:differs-at (inc n)}))))]
                  (if-let [n (:continue result)]
                    (do (swap! db assoc-in [:cpu-compare :frame] n)
                        (js/setTimeout #(chunk n) 0))
                    (let [{:keys [jsnes cljs]} @runs]
                      (swap! db assoc :cpu-compare
                             (merge result {:running? false :of frames
                                            :jsnes-ms (:ms jsnes) :cljs-ms (:ms cljs)
                                            :stats (emu/cpu-stats (:emu cljs))}))))))]
        (chunk 0)))))
