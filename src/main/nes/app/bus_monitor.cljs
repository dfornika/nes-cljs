(ns nes.app.bus-monitor
  "Watching the CPU's bus, for the Schematic tab: what happened on it since
  the last look, and a rolling log of the I/O registers it read and wrote.
  Off unless the Schematic tab turns it on, since it slows the game down.

  The setting is kept for games loaded later: nes.app.console calls
  `attach!` on each new emulator."
  (:require [reagent.core :as r]
            [nes.app.current :as current]
            [nes.emulator :as emu]))

(defonce ^:private on (atom false))

;; What happened on the bus since the previous sample, and the log.
(defonce bus-activity (r/atom nil))
(defonce bus-log (r/atom []))
(def ^:private bus-log-size 200)
(defonce ^:private last-sample-ms (atom nil))

(defn on?
  "Is the bus being watched?"
  []
  @on)

(defn monitor-bus!
  "Turn bus monitoring on or off, now and for games loaded later."
  [on?]
  (reset! on on?)
  (reset! last-sample-ms nil)
  (when-let [e (current/emulator-instance)] (emu/monitor-bus! e on?)))

(defn attach!
  "A new emulator `e` has just been made: watch its bus if monitoring is
  on, and start the log again."
  [e]
  (emu/monitor-bus! e @on)
  (reset! bus-log [])
  (reset! bus-activity nil))

(defn discard!
  "Throw away what's happened on the bus so far, so the next sample covers
  only what happens from now on (one step taken while paused)."
  []
  (when-let [e (and @on (current/emulator-instance))]
    (emu/take-bus-activity! e)))

(defn sample!
  "If monitoring is on, collect the bus activity since the last sample.
  `kind` is :step if it covers exactly one step taken while paused,
  otherwise :tick."
  [kind]
  (when-let [e (and @on (current/emulator-instance))]
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
