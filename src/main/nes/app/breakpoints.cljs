(ns nes.app.breakpoints
  "The page's list of breakpoints (see nes.breakpoints for what one is).

  The list lives in `db` under :breakpoints, so the page shows it, and is
  handed to the running emulator whenever it changes. nes.app.console
  hands it to each new emulator too, and counts the hits."
  (:require [nes.app.current :as current]
            [nes.app.state :refer [db]]
            [nes.breakpoints :as bp]
            [nes.emulator :as emu]))

(defn breakpoints
  "The breakpoints now: a vector of maps (see nes.breakpoints)."
  []
  (:breakpoints @db []))

(defonce ^:private sync-watch
  (do (add-watch db ::breakpoints
                 (fn [_ _ old new]
                   (when-not (identical? (:breakpoints old) (:breakpoints new))
                     (when-let [e (current/emulator-instance)]
                       (emu/set-breakpoints! e (:breakpoints new []))))))
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

(defn update-breakpoint!
  "Change breakpoint `id` to (apply f breakpoint args)."
  [id f & args]
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

(defn count-hit!
  "A breakpoint went off (`hit`, as the emulator reports it): add one to
  its count of hits."
  [hit]
  (when-let [id (get-in hit [:breakpoint :id])]
    (update-breakpoint! id update :hits (fnil inc 0))))

(defn break-reason
  "Why the game last stopped at a breakpoint, in words, or nil."
  []
  (some-> (:break @db) bp/describe-hit))
