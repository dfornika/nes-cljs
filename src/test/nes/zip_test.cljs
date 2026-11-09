(ns nes.zip-test
  (:require [cljs.test :refer [deftest is async]]
            [nes.zip :as zip]))

;; Made with Python's zipfile: README.txt (deflated), a macOS "._" file,
;; roms/Game (U).nes (deflated) and Other.NES (stored), plus a comment.
(def zip-b64 "UEsDBBQAAAAIAOBxaV2GphA2BwAAAAUAAAAKAAAAUkVBRE1FLnR4dMtIzcnJBwBQSwMEFAAAAAgA4HFpXTmc+wYGAAAABAAAABcAAABfX01BQ09TWC8uX0dhbWUgKFUpLm5lc8sqzcsGAFBLAwQUAAAACADgcWldOMFZnM0CAAAQYAAAEQAAAHJvbXMvR2FtZSAoVSkubmVz7c/nOhYAAAbQvpCVFJKVka1UWkZIZGTLykoZoSErZBWysonsLXvvPSq7QUZGVEahMopQ1M9uoafnPXdwVGU1mQiEbX+R7trLwikgJHJGTkXLwMTKxsnDJygyLjW7qLLhaXff8LuPX1d+EVHsYWDjETxxWvq8uu4l8xv2Lp7+odGJGXmlNc3tLwbGJueW1n7voKJjPsB/VFhSVllT/4rlLUf3+4ERsSlZhRX1T7p630zMfPn+czv57n2s3IeOi51VUNMxMrtud+eeX0hUQnpuSXVT2/P+0Q+ziz+2SHbSMnHwHTklcU7pgt5lC+vbbt4PwmOSHxeU17V2vhoan/78bYNARk2/n+vgMVEpeVVtQ9Nrts53fYMfxqflFFc1Put5PfL+08LqJjElDSM77+GT4jKKGheNr950cPUKCHuUlJlfVtvS8XLw7dT88jr++OOPP/74448//vjjjz/++OOPP/74448//vjjjz/++OOPP/74448//vjjjz/++OOPP/74448//vjjjz/++OOPP/74448//vjjjz/++P8Tf6Id5DupafYyMLNycPEKCB49fkpU/Iy0rIKSqoaWrr7RZdOrVjdu2d12dvXwvO/3IDgsMjo2ITktMzuvsKS8qrah+UlbZ8/LvoE3o+Pvpz7OfVn8trr+6/d2EjLKXXvo9jHtZ+fk4T905NhJkdOSZ8/JK6qoa+roGRqbmFtet7Z1cHJxv+ftGxAUGhEVE5+UmpGVW1BcVllT39T6rKP7RW//0Mjbd5Mzs58XllfWfm4RiEkpqHbT0jOysB3g5jt4WOiEsJiElIzceWW1C9oXDS5dMbO4dtPG3vGO210vH//AkPCHj+ISU9If5+QXlVZU1zW2PG3vev7q9eDw2MSH6U/zX5e+/9jYxB9//PHHH3/88ccff/zxxx9//PHHH3/88ccff/zxxx9//PHHH///4f8HUEsDBBQAAAAAAOBxaV3AYjhUUAAAAFAAAAAJAAAAT3RoZXIuTkVTTkVTGgEBAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABQSwECFAMUAAAACADgcWldhqYQNgcAAAAFAAAACgAAAAAAAAAAAAAAgAEAAAAAUkVBRE1FLnR4dFBLAQIUAxQAAAAIAOBxaV05nPsGBgAAAAQAAAAXAAAAAAAAAAAAAACAAS8AAABfX01BQ09TWC8uX0dhbWUgKFUpLm5lc1BLAQIUAxQAAAAIAOBxaV04wVmczQIAABBgAAARAAAAAAAAAAAAAACAAWoAAAByb21zL0dhbWUgKFUpLm5lc1BLAQIUAxQAAAAAAOBxaV3AYjhUUAAAAFAAAAAJAAAAAAAAAAAAAACAAWYDAABPdGhlci5ORVNQSwUGAAAAAAQABADzAAAA3QMAAA0AYSB6aXAgY29tbWVudA==")
(def gz-b64 "H4sIAL838moC/+3P5zoWAAAG0L6QlRSSlZGtVFpGSGRky8pKGaEhK2QVsrKJ7C177z0qu0FGRlRGoTKKUNTPbqGn5z13cFRlNZkIhG1/ke7ay8IpICRyRk5Fy8DEysbJwycoMi41u6iy4Wl33/C7j19XfhFR7GFg4xE8cVr6vLruJfMb9i6e/qHRiRl5pTXN7S8GxibnltZ+76CiYz7Af1RYUlZZU/+K5S1H9/uBEbEpWYUV9U+6et9MzHz5/nM7+e59rNyHjoudVVDTMTK7bnfnnl9IVEJ6bkl1U9vz/tEPs4s/tkh20jJx8B05JXFO6YLeZQvr227eD8Jjkh8XlNe1dr4aGp/+/G2DQEZNv5/r4DFRKXlVbUPTa7bOd32DH8an5RRXNT7reT3y/tPC6iYxJQ0jO+/hk+IyihoXja/edHD1Cgh7lJSZX1bb0vFy8O3U/PI6/vjjjz/++OOPP/74448//vjjjz/++OOPP/74448//vjjjz/++OOPP/74448//vjjjz/++OOPP/74448//vjjjz/++OOPP/74448//vj/E3+iHeQ7qWn2MjCzcnDxCggePX5KVPyMtKyCkqqGlq6+0WXTq1Y3btnddnb18Lzv9yA4LDI6NiE5LTM7r7CkvKq2oflJW2fPy76BN6Pj76c+zn1Z/La6/uv3dhIyyl176PYx7Wfn5OE/dOTYSZHTkmfPySuqqGvq6Bkam5hbXre2dXBycb/n7RsQFBoRFROflJqRlVtQXFZZU9/U+qyj+0Vv/9DI23eTM7OfF5ZX1n5uEYhJKah209IzsrAd4OY7eFjohLCYhJSM3HlltQvaFw0uXTGzuHbTxt7xjttdLx//wJDwh4/iElPSH+fkF5VWVNc1tjxt73r+6vXg8NjEh+lP81+Xvv/Y2MQff/zxxx9//PHHH3/88ccff/zxxx9//PHHH3/88ccff/zxxx///+H/BzjBWZwQYAAA")

(defn- bytes-of [b64] (js/Uint8Array.from (js/atob b64) #(.charCodeAt % 0)))

;; The ROM inside: an iNES header, then bytes counting up by 7, then by 3
(defn- expected-rom? [^js b]
  (and (= (+ 16 16384 8192) (.-length b))
       (= [0x4E 0x45 0x53 0x1A] (vec (.subarray b 0 4)))
       (= 7 (aget b 17)) (= 3 (aget b (+ 16 16384 1)))))

(deftest recognizing
  (is (zip/zip? (bytes-of zip-b64)))
  (is (zip/gzip? (bytes-of gz-b64)))
  (is (not (zip/zip? (js/Uint8Array. #js [0x4E 0x45 0x53 0x1A]))))
  (is (= "7-Zip" (zip/other-archive (js/Uint8Array. #js [0x37 0x7A 0xBC 0xAF 0x27 0x1C 0])))))

(deftest listing
  (let [es (zip/entries (bytes-of zip-b64))]
    (is (= ["README.txt" "__MACOSX/._Game (U).nes" "roms/Game (U).nes" "Other.NES"] (map :name es)))
    (is (= [8 8 8 0] (map :method es)))
    ;; .nes files only, case-insensitively, without the macOS copies
    (is (= ["roms/Game (U).nes" "Other.NES"] (map :name (zip/roms es))))))

(deftest extracting
  (async done
    (let [b (bytes-of zip-b64)
          [deflated stored] (zip/roms (zip/entries b))]
      (-> (js/Promise.all #js [(zip/extract b deflated) (zip/extract b stored) (zip/gunzip (bytes-of gz-b64))])
          (.then (fn [[d s g]]
                   (is (expected-rom? d) "deflated")
                   (is (= 80 (.-length s)) "stored")
                   (is (expected-rom? g) "gzip")))
          (.catch #(is false (str %)))
          (.finally done)))))
