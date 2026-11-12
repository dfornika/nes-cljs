(ns nes.workbench
  "Where games built here meet the console: put a freshly built cartridge
  in, and tell the inspectors what the game calls things."
  (:require [gamedev-toolkit.rom-edn :as rom-edn]
            [nes.console :as console]
            [nes.emulator :as emu]
            [nes.debug-info :as debug-info]
            [nes.lens :as lens]
            [nes.overlay :as overlay]
            [nes.rom :as rom]))

;; What `build` returned for the games played here, by ROM hash, so their
;; source can be saved later. {sha1 built}
(defonce ^:private built-games (atom {}))

(defn install!
  "Register what's known about a game built with gamedev-toolkit (what its
  `build` returns), under its ROM's hash: a lens of its variables (shown
  in the Lens tab), its overlays (see nes.overlay), and its symbol table (names in the disassembly, at the
  REPL and for breakpoints). Returns the ROM as a Uint8Array."
  [built]
  (let [bytes (js/Uint8Array.from (clj->js (:rom built)))
        sha1 (rom/rom-hash bytes)
        fields (debug-info/lens-fields (:memory built))]
    (debug-info/register! sha1 (debug-info/symbol-table built))
    (when (:source built) (swap! built-games assoc sha1 built))
    ;; the game's own overlays read its variables
    (doseq [o (:overlays built)]
      (overlay/register! (merge {:fields fields} o {:rom sha1})))
    (when (seq fields)
      (lens/register! {:rom sha1
                       :name "Variables"
                       :doc "The game's variables, as declared in its source (with gamedev-toolkit.memory)."
                       :fields fields}))
    bytes))

(defn play!
  "Build-and-run's second half: put `built` (a map with :rom, the .nes
  file's bytes, and maybe :name, :labels and :memory; or just the bytes)
  into the console and start it. Returns the ROM's size in bytes."
  [built]
  (let [built (if (map? built) built {:rom built})]
    (console/load-bytes! (or (:name built) "built.nes") (install! built))
    (count (:rom built))))

;; ---------------------------------------------------------------------------
;; Saving cartridges as files

(defn- download!
  "Have the browser save `content` (text, or a Uint8Array) as `file-name`."
  [file-name content]
  (let [url (js/URL.createObjectURL
             (js/Blob. #js [content]
                       #js {:type (if (string? content) "text/plain" "application/octet-stream")}))
        a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) file-name)
    (.click a)
    (js/setTimeout #(js/URL.revokeObjectURL url) 1000)
    file-name))

(defn- base-name [rom-name]
  (.replace (or rom-name "game.nes") (js/RegExp. "\\.nes$" "i") ""))

(defn built-game
  "What `build` returned for the game with this ROM hash, if it was built
  and played here."
  [sha1]
  (get @built-games sha1))

(defn edn-text
  "The loaded cartridge as EDN text (see gamedev-toolkit.rom-edn). `kind`
  is :source (its program as instructions and its tiles as drawings: only
  for games built here) or :container (its bytes as hex: any ROM)."
  [{:keys [sha1]} kind]
  (case kind
    ;; with the tiles as they are now, in case they've been painted on in
    ;; the Tiles tab since the game was built
    :source (when-let [built (built-game sha1)]
              (let [file (some-> (console/emulator-instance) emu/rom-file array-seq vec)
                    chr-size (count (get-in built [:source :chr]))
                    built (cond-> built
                            (and file (pos? chr-size))
                            (assoc-in [:source :chr] (subvec file (- (count file) chr-size))))]
                (rom-edn/write (rom-edn/source built))))
    :container (some-> (console/emulator-instance) emu/rom-file array-seq rom-edn/container rom-edn/write)))

(defn save-edn!
  "Download the loaded cartridge (`rom` is its entry in the app db) as an
  .edn file. Returns the file's name, or nil if there's nothing to save."
  [rom kind]
  (when-let [text (edn-text rom kind)]
    (download! (str (base-name (:name rom)) (when (= kind :container) ".rom") ".edn") text)))

(defn save-nes!
  "Download a built game (what its `build` returned) as a .nes file."
  [built]
  (download! (or (:name built) "built.nes") (js/Uint8Array.from (clj->js (:rom built)))))
