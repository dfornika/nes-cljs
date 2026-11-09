(ns nes.sha1
  "SHA-1 of a byte array, as a lowercase hex string.

  Used to recognize ROMs: two copies of a game have the same hash whatever
  the files are called. (The browser's crypto.subtle can do this too, but
  only asynchronously and only on https or localhost pages; this works
  anywhere, including in the Node tests, and takes a few milliseconds for
  even a large ROM.)

  SHA-1 isn't safe against someone deliberately making two files with
  the same hash, but that's not a worry for telling games apart, and it's
  what ROM databases such as No-Intro list.")

(defn- rotl [x n]
  (bit-or (bit-shift-left x n) (unsigned-bit-shift-right x (- 32 n))))

(defn- hex8 [n]
  (.padStart (.toString (unsigned-bit-shift-right n 0) 16) 8 "0"))

(defn sha1
  "The SHA-1 hash of `bytes` (a Uint8Array) as 40 hex digits."
  [^js bytes]
  (let [len (.-length bytes)
        ;; message + 0x80 + zeros + 64-bit length, padded to 64-byte blocks
        total (* 64 (js/Math.ceil (/ (+ len 9) 64)))
        msg (js/Uint8Array. total)
        view (js/DataView. (.-buffer msg))
        w (js/Int32Array. 80)
        h (js/Int32Array. #js [0x67452301 0xEFCDAB89 0x98BADCFE 0x10325476 0xC3D2E1F0])]
    (.set msg bytes)
    (aset msg len 0x80)
    ;; length in bits, big-endian (split, as it can be over 32 bits)
    (.setUint32 view (- total 8) (js/Math.floor (/ len 0x20000000)))
    (.setUint32 view (- total 4) (bit-or (bit-shift-left len 3) 0))
    (loop [block 0]
      (when (< block total)
        (dotimes [i 16]
          (aset w i (.getInt32 view (+ block (* 4 i)))))
        (loop [i 16]
          (when (< i 80)
            (aset w i (rotl (bit-xor (aget w (- i 3)) (aget w (- i 8))
                                     (aget w (- i 14)) (aget w (- i 16)))
                            1))
            (recur (inc i))))
        (loop [i 0
               a (aget h 0) b (aget h 1) c (aget h 2) d (aget h 3) e (aget h 4)]
          (if (< i 80)
            (let [f (cond
                      (< i 20) (bit-or (bit-and b c) (bit-and (bit-not b) d))
                      (< i 40) (bit-xor b c d)
                      (< i 60) (bit-or (bit-and b c) (bit-and b d) (bit-and c d))
                      :else (bit-xor b c d))
                  k (cond (< i 20) 0x5A827999 (< i 40) 0x6ED9EBA1
                          (< i 60) 0x8F1BBCDC :else 0xCA62C1D6)
                  t (bit-or (+ (rotl a 5) f e k (aget w i)) 0)]
              (recur (inc i) t a (rotl b 30) c d))
            (do (aset h 0 (+ (aget h 0) a))
                (aset h 1 (+ (aget h 1) b))
                (aset h 2 (+ (aget h 2) c))
                (aset h 3 (+ (aget h 3) d))
                (aset h 4 (+ (aget h 4) e)))))
        (recur (+ block 64))))
    (apply str (map hex8 (array-seq h)))))
