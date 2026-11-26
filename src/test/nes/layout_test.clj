(ns nes.layout-test
  "The rule in docs/code_layout.md: each group of namespaces may require
  the groups above it, never the ones below. (On the JVM only: it reads
  the source files. `npm run test:jvm` runs it.)"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- group
  "Which group a namespace is in, as a number: lower may not require higher."
  [ns-name]
  (condp re-find ns-name
    #"^nes\.(main|repl)$" 6
    #"^nes\.ui\." 5
    #"^nes\.app\." 4
    #"^nes\.inspect\." 3
    #"^nes\.emulator(\.|$)" 2
    #"^nes\." 1))

(defn- requires
  "The namespace a source file defines, and the nes.* namespaces in its
  :require."
  [file]
  (let [text (slurp file)
        ns-name (second (re-find #"\(ns ([\w.-]+)" text))
        req (re-find #"(?s)\(:require.*?(?:\n\n|\z)" text)]
    [ns-name (set (map second (re-seq #"\[(nes\.[\w.-]+)" (or req ""))))]))

(deftest groups-only-require-groups-above
  (let [files (filter #(re-find #"\.clj[cs]?$" (.getName %)) (file-seq (io/file "src/main/nes")))
        wrong (for [f files
                    :let [[ns-name reqs] (requires f)]
                    r reqs
                    :when (> (group r) (group ns-name))]
                (str ns-name " requires " r))]
    (is (seq files))
    (is (empty? wrong) (str "See docs/code_layout.md: " (vec wrong)))))
