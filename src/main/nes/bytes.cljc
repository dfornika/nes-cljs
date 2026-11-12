(ns nes.bytes
  "Arrays of bytes, and of 32-bit numbers, that work the same in the
  browser and on the JVM.

  Emulators live on these: RAM, ROM and sprite memory are arrays of bytes,
  and a picture is an array of 32-bit pixels. The two platforms have
  different ones. In the browser they're a Uint8Array and a Uint32Array.
  On the JVM they're a byte[] and an int[], whose elements are *signed*:
  a JVM byte holding $FF reads back as -1. So this namespace reads them
  unsigned, and code that goes through it sees 0 to 255 either way.

  `at`, `put!`, `word-at` and `put-word!` are macros, so that in inner
  loops (drawing a picture, a pixel at a time) they cost exactly what the
  platform's own array access costs. That means they can't be passed
  around as functions: wrap them, as in #(bytes/at mem %)."
  #?(:cljs (:require-macros [nes.bytes])))

;; ---------------------------------------------------------------------------
;; Bytes

(defn make
  "A new array of `n` bytes, all zero."
  [n]
  #?(:clj (byte-array n)
     :cljs (js/Uint8Array. n)))

(defn from
  "A new array of bytes holding `values` (numbers 0 to 255)."
  [values]
  #?(:clj (byte-array (map unchecked-byte values))
     :cljs (js/Uint8Array.from (to-array values))))

(defn size
  "How many elements an array (of bytes, or of words) has."
  [array]
  #?(:clj (java.lang.reflect.Array/getLength array)
     :cljs (.-length array)))

#?(:clj
   (defmacro at
     "The byte at index `i` of `array`, as a number 0 to 255."
     [array i]
     (if (:ns &env)
       `(cljs.core/aget ~array ~i)
       `(bit-and (aget ~(vary-meta array assoc :tag 'bytes) (int ~i)) 0xFF))))

#?(:clj
   (defmacro put!
     "Store `value` (0 to 255) at index `i` of `array`."
     [array i value]
     (if (:ns &env)
       `(cljs.core/aset ~array ~i ~value)
       `(aset ~(vary-meta array assoc :tag 'bytes) (int ~i) (unchecked-byte ~value)))))

(defn slice
  "The bytes of `array` from index `from` up to (not including) `to`, or
  to the end. In the browser this is a view of the same memory, not a
  copy, so treat it as read-only."
  ([array from] (slice array from (size array)))
  ([array from to]
   #?(:clj (java.util.Arrays/copyOfRange ^bytes array (int from) (int to))
      :cljs (.subarray array from to))))

(defn copy-into!
  "Copy all of `source` (an array of bytes) into `target`, starting at
  index `offset` there. Returns `target`."
  [target source offset]
  #?(:clj (System/arraycopy source 0 target (int offset) (size source))
     :cljs (.set target source offset))
  target)

(defn fill!
  "Set every byte of `array` to `value`. Returns `array`."
  [array value]
  #?(:clj (java.util.Arrays/fill ^bytes array (unchecked-byte value))
     :cljs (.fill array value))
  array)

(defn ->vec
  "The bytes of `array` as a vector of numbers 0 to 255."
  [array]
  #?(:clj (mapv #(bit-and % 0xFF) array)
     :cljs (vec (array-seq array))))

;; ---------------------------------------------------------------------------
;; 32-bit words: pixels (0xAABBGGRR) and counters

(defn make-words
  "A new array of `n` 32-bit numbers, all zero."
  [n]
  #?(:clj (int-array n)
     :cljs (js/Uint32Array. n)))

#?(:clj
   (defmacro word-at
     "The 32-bit number at index `i` of `array`, as a number 0 to 4294967295."
     [array i]
     (if (:ns &env)
       `(cljs.core/aget ~array ~i)
       `(bit-and (aget ~(vary-meta array assoc :tag 'ints) (int ~i)) 0xFFFFFFFF))))

#?(:clj
   (defmacro put-word!
     "Store the 32-bit number `value` at index `i` of `array`."
     [array i value]
     (if (:ns &env)
       `(cljs.core/aset ~array ~i ~value)
       `(aset ~(vary-meta array assoc :tag 'ints) (int ~i) (unchecked-int ~value)))))

(defn words->vec
  "The numbers in an array of words, as a vector."
  [array]
  #?(:clj (mapv #(bit-and % 0xFFFFFFFF) array)
     :cljs (vec (array-seq array))))
