(ns nes.app.loading
  "Getting a game's bytes into the console: from a file the user picks or
  drops, from a URL (the sample ROMs), from inside a .zip or .gz, and a
  game's names file beside it. Each way ends in nes.app.console/load-bytes!."
  (:require [nes.app.console :as console]
            [nes.app.state :refer [db]]
            [nes.app.zip :as zip]
            [nes.inspect.debug-info :as debug-info]
            [nes.rom :as rom]))

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
                   (console/load-bytes! (last (.split (:name entry) "/")) rom)
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
        (.then #(console/load-bytes! (strip-gz file-name) %))
        (.catch #(fail! (str "Couldn't decompress " file-name ": " (ex-message %)))))

    (zip/other-archive bytes)
    (fail! (str file-name " is a " (zip/other-archive bytes) " archive, which can't be opened here. "
                "Extract the .nes file from it first (or use a .zip)."))

    :else (console/load-bytes! file-name bytes)))

(defn load-file!
  "Load a ROM from a browser File (from a file picker or drag-and-drop):
  a .nes file, or a .zip or .gz with one inside."
  [^js file]
  (-> (.arrayBuffer file)
      (.then #(load-any! (.-name file) (js/Uint8Array. %)))))

(defn- fetch!
  "A promise of what's at `url`: (.arrayBuffer response) or (.text response)."
  [url read]
  (-> (js/fetch url)
      (.then (fn [^js resp]
               (if (.-ok resp)
                 (read resp)
                 (throw (js/Error. (str "HTTP " (.-status resp) " for " url))))))))

(defn load-url!
  "Load a ROM from a URL (.nes, .zip or .gz). With `names-url`, also its
  names file (see nes.inspect.debug-info), so the inspectors can use the
  game's own names for its code and variables."
  ([url] (load-url! url nil))
  ([url names-url]
   (-> (js/Promise.all
        #js [(fetch! url #(.arrayBuffer ^js %))
             (if names-url (fetch! names-url #(.text ^js %)) (js/Promise.resolve nil))])
       (.then (fn [^js results]
                (let [bytes (js/Uint8Array. (aget results 0))]
                  ;; the names first, so they're there when the game's first shown
                  (when-let [text (aget results 1)]
                    (debug-info/install! (rom/rom-hash bytes) (debug-info/read-names text)))
                  (load-any! (last (.split url "/")) bytes))))
       (.catch #(swap! db assoc :error (str "Couldn't fetch " url ": " (ex-message %)))))))
