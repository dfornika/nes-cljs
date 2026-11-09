(ns nes.sound
  "The sound on/off switch and volume, remembered between visits.

  Browsers won't start audio until the user clicks or presses a key on the
  page. So if sound was on last time, we turn it back on at the first
  click or key press."
  (:require [nes.audio :as audio]
            [nes.state :refer [db]]))

(def ^:private storage-key "nes-cljs.sound")

(defn- load-prefs []
  (try
    (some-> (.getItem js/localStorage storage-key) js/JSON.parse (js->clj :keywordize-keys true))
    (catch :default _ nil)))

(defn- save-prefs! []
  (try
    (.setItem js/localStorage storage-key
              (js/JSON.stringify (clj->js (select-keys (:sound @db) [:on? :volume]))))
    (catch :default _ nil)))

(defn volume [] (get-in @db [:sound :volume] 0.6))
(defn on? [] (boolean (get-in @db [:sound :on?])))

(defn turn-on!
  "Start sound. Must be called from a click or key press handler."
  []
  (-> (audio/enable! (volume))
      (.then (fn []
               (swap! db assoc-in [:sound :on?] true)
               (save-prefs!)))
      (.catch (fn [e]
                (js/console.warn "Couldn't start sound:" e)
                (swap! db assoc-in [:sound :on?] false)))))

(defn turn-off! []
  (audio/disable!)
  (swap! db assoc-in [:sound :on?] false)
  (save-prefs!))

(defn toggle! [] (if (on? ) (turn-off!) (turn-on!)))

(defn set-volume! [v]
  (swap! db assoc-in [:sound :volume] v)
  (audio/set-volume! v)
  (save-prefs!))

(defonce ^:private installed? (atom false))

(defn install!
  "Restore the saved settings; if sound was on, turn it on again at the
  first click or key press."
  []
  (when-not @installed?
    (reset! installed? true)
    (let [{:keys [on? volume]} (load-prefs)]
      (when volume (swap! db assoc-in [:sound :volume] volume))
      (when on?
        (let [handler (atom nil)]
          (reset! handler
                  (fn []
                    (.removeEventListener js/window "pointerdown" @handler)
                    (.removeEventListener js/window "keydown" @handler)
                    (when-not (audio/enabled?) (turn-on!))))
          (.addEventListener js/window "pointerdown" @handler)
          (.addEventListener js/window "keydown" @handler))))))
