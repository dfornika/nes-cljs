(ns gamedev-toolkit.sub
  "Subroutines: code that's written once, lives in one place in the ROM,
  and is called from anywhere with JSR.

    (def read-controller
      (sub :read-controller
           \"Reads controller 1 into :buttons.\"
           [[:lda [:imm 1]] ...]))

    ;; somewhere in the program:
    (call read-controller)

  A subroutine is a value (a map). `call` puts the whole subroutine in the
  program, as a [:call sub] form, so nothing has to be registered anywhere:
  `link` finds every subroutine the program reaches (including ones only
  called by other subroutines), turns each call into a JSR, and puts each
  subroutine's code after the program, once, with an RTS on the end.
  Subroutines nothing calls take no room in the ROM.

  Because a subroutine carries its own code, the same value can also be
  written straight into the program with `inline`: faster (no JSR and RTS,
  12 cycles) but bigger if it's used in several places.

  Calling convention: there isn't one, beyond what the 6502 gives you.
  Arguments and results go in the registers A, X and Y, or in variables.
  gamedev-toolkit.analysis/clobbers says which registers a call changes."
  (:require [gamedev-toolkit.asm :as asm]))

(defn sub
  "A subroutine called `name` (a keyword, which becomes its label) with
  `body` as its code. Leave the final RTS off; `link` adds it.
  Define each subroutine once, with `def`."
  ([name body] (sub name nil body))
  ([name doc body]
   (when-not (and (keyword? name) (nil? (namespace name)))
     (throw (ex-info (str "A subroutine's name is a plain keyword, not " (pr-str name)) {:name name})))
   (cond-> {:name name :body body}
     doc (assoc :doc doc))))

(defn- sub? [x]
  (and (map? x) (keyword? (:name x)) (contains? x :body)))

(defn call
  "Call a subroutine: a form that `link` turns into a JSR."
  [s]
  (when-not (sub? s)
    (throw (ex-info (str "call takes a subroutine (made with sub), not " (pr-str s)) {})))
  [:call s])

(defn inline
  "A subroutine's code, to put straight into the program instead of
  calling it. Not for subroutines that return early with their own RTS:
  inline, that RTS would return from whatever the code was put in."
  [s]
  (when (some #(= :rts (first %)) (asm/flatten-forms (:body s)))
    (throw (ex-info (str "Can't inline " (:name s) ": it has an RTS of its own") {:name (:name s)})))
  (:body s))

(defn- calls
  "The subroutines called directly by `forms`."
  [forms]
  (for [[op s] (asm/flatten-forms forms) :when (= op :call)] s))

(defn- reachable
  "Every subroutine `forms` can reach, in the order first met."
  [forms]
  (loop [queue (vec (calls forms)), found [], by-name {}]
    (if-let [s (first queue)]
      (if-let [known (by-name (:name s))]
        (if (= known s)
          (recur (subvec queue 1) found by-name)
          (throw (ex-info (str "Two different subroutines are both called " (:name s)
                               ". (Define a subroutine once, with def, rather than in a function that's called twice.)")
                          {:name (:name s)})))
        (recur (into (subvec queue 1) (calls (:body s)))
               (conj found s)
               (assoc by-name (:name s) s)))
      found)))

(defn- lower
  "`forms` flattened, with each [:call sub] as a JSR to its label."
  [forms]
  (mapv (fn [[op s :as form]] (if (= op :call) [:jsr (:name s)] form))
        (asm/flatten-forms forms)))

(def ^:private never-falls-through #{:rts :rti :jmp})

(defn link
  "Make `forms` ready for the assembler: calls become JSRs, and the code
  of every subroutine reached is put after `forms`, each under a label
  with its name.

  Returns {:code the program, :subroutines [{:name :doc} ...]}."
  [forms]
  (let [subs (reachable forms)]
    {:code [(lower forms)
            (for [{:keys [name body]} subs
                  :let [code (lower body)]]
              [[:label name]
               code
               ;; no RTS after code that can't reach it anyway
               (when-not (never-falls-through (first (peek code)))
                 [:rts])])]
     :subroutines (mapv #(select-keys % [:name :doc]) subs)}))
