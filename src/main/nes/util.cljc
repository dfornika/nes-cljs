(ns nes.util
  "Small things that Clojure (on the JVM) and ClojureScript (in the
  browser) each do in their own way: formatting and reading numbers,
  mostly. Code that uses these instead of the platform's own functions
  runs in both places.")

(defn hex-digits
  "`n` as uppercase hex digits, padded with zeros to `width`: 255 -> \"FF\"."
  ([n] (hex-digits n 1))
  ([n width]
   (let [s #?(:clj (Long/toHexString (long n))
              :cljs (.toString n 16))
         s (.toUpperCase ^String s)]
     (str (apply str (repeat (- width (count s)) "0")) s))))

(defn hex
  "`n` as hex with a $ in front, the way 6502 programmers write it,
  padded to `width` digits: (hex 255 4) -> \"$00FF\"."
  [n width]
  (str "$" (hex-digits n width)))

(defn parse-int
  "The whole number written in `s`, in base `radix` (default 10), or nil
  if it isn't one."
  ([s] (parse-int s 10))
  ([s radix]
   (let [s (some-> s str .trim)]
     (when (seq s)
       #?(:clj (try (Long/parseLong s radix) (catch NumberFormatException _ nil))
          :cljs (let [digits (subs "0123456789abcdefghijklmnopqrstuvwxyz" 0 radix)
                      body (if (#{"-" "+"} (subs s 0 1)) (subs s 1) s)]
                  ;; (js/parseInt alone would read "12abc" as 12)
                  (when (and (seq body) (every? #(not (neg? (.indexOf digits %))) (.toLowerCase body)))
                    (js/parseInt s radix))))))))

(defn parse-number
  "The number written in `s` (which may have a decimal point), or nil."
  [s]
  (let [s (some-> s str .trim)]
    (when (and (seq s) (re-matches #"[-+]?(\d+\.?\d*|\.\d+)" s))
      #?(:clj (Double/parseDouble s)
         :cljs (js/parseFloat s)))))

(defn round
  "`x` rounded to the nearest whole number."
  [x]
  #?(:clj (Math/round (double x))
     :cljs (js/Math.round x)))
