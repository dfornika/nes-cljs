(ns nes.zip
  "Reading ROMs out of .zip (and .gz) files.

  ROMs are often passed around zipped. A zip file is a series of entries,
  each a small header followed by the file's bytes (usually compressed
  with 'deflate'), and at the end a 'central directory' listing every
  entry and where it starts. So to read one we find the directory at the
  end, look up the file we want, and inflate its bytes. The browser can
  do the inflating (DecompressionStream), so this namespace only has to
  find things.

  See https://en.wikipedia.org/wiki/ZIP_(file_format) and the spec,
  https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT")

(defn- u16 [^js b i] (bit-or (aget b i) (bit-shift-left (aget b (inc i)) 8)))
(defn- u32 [^js b i] (+ (u16 b i) (* 65536 (u16 b (+ i 2)))))

(defn- starts-with? [^js b sig]
  (and (>= (.-length b) (count sig))
       (every? true? (map-indexed #(= %2 (aget b %1)) sig))))

(defn zip? "Is `bytes` (a Uint8Array) a zip file?" [bytes] (starts-with? bytes [0x50 0x4B 0x03 0x04]))
(defn gzip? "Is `bytes` gzipped?" [bytes] (starts-with? bytes [0x1F 0x8B]))

(defn other-archive
  "If `bytes` is an archive we can't open, what kind, else nil."
  [bytes]
  (cond (starts-with? bytes [0x37 0x7A 0xBC 0xAF 0x27 0x1C]) "7-Zip"
        (starts-with? bytes [0x52 0x61 0x72 0x21]) "RAR"))

(defn- end-of-directory
  "Where the 'end of central directory' record starts: the last 22+ bytes
  (more if the zip has a comment)."
  [^js b]
  (let [n (.-length b)]
    (loop [i (- n 22)]
      (cond (or (neg? i) (< i (- n 22 65535))) nil
            (= 0x06054B50 (u32 b i)) i
            :else (recur (dec i))))))

(defn entries
  "The files in zip `bytes`, from its central directory: a vector of
  {:name :method :size :compressed-size :header-offset :encrypted?}.
  `:method` is 0 (stored) or 8 (deflated). Throws if it isn't a zip."
  [^js b]
  (let [end (or (end-of-directory b) (throw (ex-info "Not a zip file (no central directory)" {})))
        n (u16 b (+ end 10))
        decoder (js/TextDecoder.)]
    (loop [i (u32 b (+ end 16)), k 0, out []]
      (if (= k n)
        out
        (do
          (when-not (= 0x02014B50 (u32 b i))
            (throw (ex-info "Damaged zip file (bad central directory)" {:at i})))
          (let [name-len (u16 b (+ i 28))
                extra-len (u16 b (+ i 30))
                comment-len (u16 b (+ i 32))
                name (.decode decoder (.subarray b (+ i 46) (+ i 46 name-len)))]
            (recur (+ i 46 name-len extra-len comment-len)
                   (inc k)
                   (conj out {:name name
                              :encrypted? (bit-test (u16 b (+ i 8)) 0)
                              :method (u16 b (+ i 10))
                              :compressed-size (u32 b (+ i 20))
                              :size (u32 b (+ i 24))
                              :header-offset (u32 b (+ i 42))}))))))))

(defn roms
  "The entries that look like NES ROMs (*.nes), skipping folders and the
  hidden copies macOS adds."
  [entries]
  (filterv (fn [{:keys [name]}]
             (and (re-find #"(?i)\.nes$" name)
                  (not (re-find #"(^|/)__MACOSX/|(^|/)\._" name))))
           entries))

(defn- inflate
  "Decompress `data` with the browser's DecompressionStream. `format` is
  \"deflate-raw\" (zip) or \"gzip\". Returns a promise of a Uint8Array."
  [^js data format]
  (-> (js/Response. (.pipeThrough (.stream (js/Blob. #js [data]))
                                  (js/DecompressionStream. format)))
      (.arrayBuffer)
      (.then #(js/Uint8Array. %))))

(defn extract
  "The contents of `entry` (from `entries`) in zip `bytes`, as a promise
  of a Uint8Array."
  [^js b {:keys [name method compressed-size header-offset encrypted?]}]
  (cond
    encrypted? (js/Promise.reject (js/Error. (str name " is password-protected")))
    (not= 0x04034B50 (u32 b header-offset))
    (js/Promise.reject (js/Error. "Damaged zip file (bad entry header)"))
    :else
    ;; the entry's own header has its own name and extra-field lengths
    (let [start (+ header-offset 30 (u16 b (+ header-offset 26)) (u16 b (+ header-offset 28)))
          data (.subarray b start (+ start compressed-size))]
      (case method
        0 (js/Promise.resolve (js/Uint8Array.from data))
        8 (inflate data "deflate-raw")
        (js/Promise.reject (js/Error. (str name " uses a compression method we can't read (" method ")")))))))

(defn gunzip
  "Decompress gzipped `bytes`: a promise of a Uint8Array."
  [bytes]
  (inflate bytes "gzip"))
