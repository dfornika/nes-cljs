(ns nes.text-table
  "Text tables: which byte stands for which letter, in a particular game.

  NES games don't store text as ASCII. A letter on screen is a background
  tile, and its tile number is whatever position the artist put that
  letter's picture in the pattern table. So 'A' might be $0A in one game,
  $41 in another, and $C1 on one screen of a third. ROM hackers write the
  mapping down as a 'table file' (.tbl), one entry per line:

    C1=A
    C2=B
    DC=.

  (See e.g. https://datacrystal.tcrf.net/wiki/Mega_Man_2/TBL.)

  With one, we can read the text in the game's memory or ROM (a lens field
  type, see nes.lens) and the text on screen right now (the nametables
  hold tile numbers, so they're text in the game's own encoding).

  Like lenses, tables are registered per game:
    (register! {:rom sha1 :name \"Title screen\" :table (parse tbl-text)})"
  (:require [clojure.string :as str]
            [nes.util :as util]))

(defn parse
  "A .tbl file's text as a map of byte -> string. Lines that aren't
  `XX=text` (comments, blank lines, multi-byte entries) are skipped. A
  value can be a space."
  [text]
  (into {}
        (keep (fn [line]
                (when-let [[_ hex value] (re-matches #"^\s*([0-9A-Fa-f]{2})=(.*?)\r?$" line)]
                  (when (seq value)
                    [(util/parse-int hex 16) value]))))
        (str/split (str text) #"\n")))

(defn decode
  "Bytes as text through `table`. Bytes not in the table come out as
  `missing` (default \"·\")."
  ([table bytes] (decode table bytes "·"))
  ([table bytes missing]
   (apply str (map #(get table % missing) bytes))))

(defn encode
  "Text as bytes through `table` (the reverse of `decode`), or nil if a
  character isn't in the table. Characters that more than one byte stands
  for use the lowest byte."
  [table text]
  (let [reverse-table (reduce (fn [m [b s]] (if (contains? m s) m (assoc m s b)))
                              {} (sort-by key table))]
    (loop [out [] s (str text)]
      (if (empty? s)
        out
        ;; longest match first, for entries like "r͘" that are 2 characters
        (if-let [[k b] (some (fn [n] (let [k (subs s 0 n)] (when-let [b (reverse-table k)] [k b])))
                             (range (min 4 (count s)) 0 -1))]
          (recur (conj out b) (subs s (count k)))
          nil)))))

(defn screen-lines
  "The 32×30 tiles of the nametable at `base` ($2000, $2400, ...) as 30
  lines of text through `table`, read with `peek` (a function of a PPU
  address). Tiles not in the table become spaces."
  [table peek base]
  (vec (for [row (range 30)]
         (apply str (for [col (range 32)]
                      (get table (peek (+ base (* 32 row) col)) " "))))))

(defn visible-lines
  "The text on the screen right now: the 32×30 tiles at scroll position
  (`x`, `y`) in the 2×2 square of nametables, through `table`. Lines with
  no text are blank."
  [table peek x y]
  (let [col0 (quot (mod x 512) 8)
        row0 (quot (mod y 480) 8)]
    (vec (for [r (range 30)
               :let [row (mod (+ row0 r) 60)]]
           (apply str
                  (for [c (range 32)
                        :let [col (mod (+ col0 c) 64)
                              base (+ 0x2000 (* 0x400 (+ (quot col 32) (* 2 (quot row 30)))))]]
                    (get table (peek (+ base (* 32 (mod row 30)) (mod col 32))) " ")))))))

;; ---------------------------------------------------------------------------
;; Which tables go with which game

(defonce registry (atom {}))

(defn register!
  "Add a text table: {:rom sha1-or-file-name :name ... :table {byte string}}.
  Returns its [rom name] key."
  [{:keys [rom name table] :as entry}]
  (assert (and rom name (map? table)) "A text table needs :rom, :name and :table (a map)")
  (swap! registry assoc [rom name] entry)
  [rom name])

(defn tables-for
  "The text tables registered for `rom` ({:sha1 ... :name ...})."
  [rom]
  (vec (sort-by :name (filter #(or (= (:rom %) (:sha1 rom)) (= (:rom %) (:name rom)))
                              (vals @registry)))))
